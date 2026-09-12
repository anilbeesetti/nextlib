package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.*;

import android.content.res.AssetManager;
import android.os.SystemClock;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Real pooled Java decoder + packaged JNI; EOS is always queued through Decoder.queueInputBuffer. */
public final class FfmpegVideoDecoderTest {
    private static final String TAG = "FfmpegDrainTest";

    @Test
    public void delayedFramesReachEosWithReferencePixelsAndTimestamps() throws Exception {
        for (String codec : new String[] {"h264", "hevc", "mpeg2"}) {
            for (int frames : new int[] {3, 48}) {
                Fixture fixture = new Fixture(codec + "-" + frames);
                for (int mode : new int[] {C.VIDEO_OUTPUT_MODE_YUV, C.VIDEO_OUTPUT_MODE_SURFACE_YUV}) {
                    FfmpegVideoDecoder decoder = decoder(fixture, 1, mode);
                    try {
                        assertFrames(fixture, decode(decoder, fixture, 0, 0, false), 0, 0, false);
                    } finally {
                        decoder.release();
                    }
                }
            }
        }
    }

    @Test
    public void multipleVisibleFramesAndHiddenVp9FramesDoNotLosePackets() throws Exception {
        for (String name : new String[] {"vp9", "vp9-multiple", "vp9-altref", "av1"}) {
            Fixture fixture = new Fixture(name);
            FfmpegVideoDecoder decoder = decoder(fixture, 1, C.VIDEO_OUTPUT_MODE_YUV);
            AtomicInteger inputCallbacks = new AtomicInteger();
            AtomicInteger outputCallbacks = new AtomicInteger();
            decoder.setCallback(new androidx.media3.decoder.Decoder.Callback() {
                @Override public void onInputBufferAvailable() { inputCallbacks.incrementAndGet(); }
                @Override public void onOutputBufferAvailable() { outputCallbacks.incrementAndGet(); }
            }, Runnable::run);
            try {
                assertFrames(fixture, decode(decoder, fixture, 0, 0, false), 0, 0, false);
                assertTrue(inputCallbacks.get() > 0);
                assertTrue(outputCallbacks.get() >= fixture.times.size() + 1);
            } finally {
                decoder.release();
            }
        }
    }

    @Test
    public void missingPtsUsesFrameCadenceAndResetsItOnSeek() throws Exception {
        Fixture fixture = new Fixture("h264-48");
        FfmpegVideoDecoder decoder = decoder(fixture, 2, C.VIDEO_OUTPUT_MODE_YUV);
        try {
            for (long start : new long[] {0, 2_000_000, 0}) {
                decoder.flush();
                decoder.setOutputStartTimeUs(start);
                assertFrames(fixture, decode(decoder, fixture, start, 0, true), start, 0, true);
            }
        } finally {
            decoder.release();
        }
    }

    @Test
    public void repeatedSeeksClassifyDecodedFramesAndClearQueuedAndInFlightOutput() throws Exception {
        for (String name : new String[] {"h264-48", "hevc-48", "mpeg2-48", "vp9-altref", "av1"}) {
            Fixture fixture = new Fixture(name);
            FfmpegVideoDecoder decoder = decoder(fixture, 2, C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
            try {
                // Fill the output pool and retain a renderer-owned reference across flush.
                queueUntilBlocked(decoder, fixture);
                VideoDecoderOutputBuffer held = awaitOutput(decoder);
                assertNotEquals(0, held.decoderPrivate);
                for (long start : new long[] {500_000, 0, 750_000, 250_000, 0}) {
                    decoder.flush();
                    long offset = 10_000_000;
                    decoder.setOutputStartTimeUs(start + offset);
                    assertFrames(fixture, decode(decoder, fixture, start, offset, false), start, offset, false);
                }
                assertNotEquals("Flush leaves dequeued output owned by its caller", 0, held.decoderPrivate);
                held.release();
                assertEquals(0, held.decoderPrivate);
                decoder.flush();
                decoder.setOutputStartTimeUs(0);
                queueUntilBlocked(decoder, fixture);
                held = awaitOutput(decoder);
                decoder.release();
                assertEquals(0, held.decoderPrivate);
                held.release();
            } finally {
                decoder.release();
            }
        }
    }

    @Test
    public void flushBeforeFirstPacketAndImmediatelyAfterEosProducesOnlyNewStream() throws Exception {
        Fixture fixture = new Fixture("h264-3");
        FfmpegVideoDecoder decoder = decoder(fixture, 2, C.VIDEO_OUTPUT_MODE_YUV);
        try {
            for (int i = 0; i < 20; i++) {
                decoder.flush();
                decoder.setOutputStartTimeUs(0);
                DecoderInputBuffer eos = awaitInput(decoder);
                eos.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                decoder.queueInputBuffer(eos);
                decoder.flush();
                assertFrames(fixture, decode(decoder, fixture, 0, 0, false), 0, 0, false);
            }
        } finally {
            decoder.release();
        }
    }

    private static FfmpegVideoDecoder decoder(Fixture fixture, int outputs, int mode) throws Exception {
        FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(4, outputs, 8192, 4, fixture.format);
        decoder.setOutputMode(mode);
        return decoder;
    }

    private static List<Frame> decode(FfmpegVideoDecoder decoder, Fixture fixture,
            long start, long offset, boolean missingPts) throws Exception {
        List<Frame> frames = new ArrayList<>();
        int sent = 0;
        boolean eosQueued = false;
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!eosQueued) {
                DecoderInputBuffer input = decoder.dequeueInputBuffer();
                if (input != null) {
                    if (sent == fixture.packets.size()) {
                        input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    } else {
                        fill(input, fixture, sent++, offset, missingPts);
                    }
                    decoder.queueInputBuffer(input);
                }
            }
            VideoDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
            if (output == null) {
                SystemClock.sleep(1);
                continue;
            }
            try {
                if (output.isEndOfStream()) {
                    assertTrue(eosQueued);
                    assertNull("EOS must be the last output", decoder.dequeueOutputBuffer());
                    Log.i(TAG, fixture.name + " start=" + start + " frames=" + frames.size()
                            + " first=" + (frames.isEmpty() ? -1 : frames.get(0).time)
                            + " last=" + (frames.isEmpty() ? -1 : frames.get(frames.size() - 1).time)
                            + " eos=true");
                    return frames;
                }
                assertEquals(fixture.format, output.format);
                frames.add(new Frame(output.timeUs,
                        output.mode == C.VIDEO_OUTPUT_MODE_YUV ? hash(output) : null));
                assertTrue("Unbounded/repeated output", frames.size() <= fixture.times.size());
            } finally {
                output.release();
            }
        }
        throw new AssertionError(fixture.name + " timed out before EOS; sent=" + sent + " frames=" + frames.size());
    }

    private static void fill(DecoderInputBuffer input, Fixture fixture, int index,
            long offset, boolean missingPts) {
        byte[] packet = fixture.packets.get(index);
        input.ensureSpaceForWrite(packet.length);
        input.data.put(packet);
        input.timeUs = missingPts ? C.TIME_UNSET : fixture.packetTimes.get(index) + offset;
        input.format = fixture.format;
        input.flip();
    }

    private static void assertFrames(Fixture fixture, List<Frame> frames, long start,
            long offset, boolean missingPts) {
        int first = 0;
        double duration = 1_000_000d / fixture.format.frameRate;
        while (first < fixture.times.size()
                && (missingPts ? Math.round(first * duration) : fixture.times.get(first)) < start) first++;
        assertEquals(fixture.name + " final frame count", fixture.times.size() - first, frames.size());
        for (int i = 0; i < frames.size(); i++) {
            Frame frame = frames.get(i);
            long expected = missingPts ? Math.round((i + first) * duration) : fixture.times.get(i + first);
            assertEquals(fixture.name + " frame " + (i + first), expected + offset, frame.time);
            if (frame.hash != null) assertEquals(fixture.hashes.get(i + first), frame.hash);
        }
    }

    private static void queueUntilBlocked(FfmpegVideoDecoder decoder, Fixture fixture) throws Exception {
        for (int i = 0; i < fixture.packets.size(); i++) {
            DecoderInputBuffer input = decoder.dequeueInputBuffer();
            if (input == null) break;
            fill(input, fixture, i, 0, false);
            decoder.queueInputBuffer(input);
            SystemClock.sleep(2);
        }
    }

    private static DecoderInputBuffer awaitInput(FfmpegVideoDecoder decoder) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        DecoderInputBuffer input;
        while ((input = decoder.dequeueInputBuffer()) == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1);
        }
        assertNotNull(input);
        return input;
    }

    private static VideoDecoderOutputBuffer awaitOutput(FfmpegVideoDecoder decoder) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        VideoDecoderOutputBuffer output;
        while ((output = decoder.dequeueOutputBuffer()) == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1);
        }
        assertNotNull(output);
        return output;
    }

    private static String hash(VideoDecoderOutputBuffer output) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int plane = 0; plane < 3; plane++) {
            int width = plane == 0 ? output.width : (output.width + 1) / 2;
            int height = plane == 0 ? output.height : (output.height + 1) / 2;
            ByteBuffer data = output.yuvPlanes[plane].duplicate();
            for (int row = 0; row < height; row++) {
                data.limit(data.capacity());
                data.position(row * output.yuvStrides[plane]);
                data.limit(data.position() + width);
                digest.update(data);
            }
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static final class Frame {
        final long time;
        final String hash;
        Frame(long time, String hash) { this.time = time; this.hash = hash; }
    }

    private static final class Fixture {
        final String name;
        final Format format;
        final List<byte[]> packets = new ArrayList<>();
        final List<Long> packetTimes = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        final List<String> hashes = new ArrayList<>();
        Fixture(String name) throws Exception {
            this.name = name;
            String mime = name.startsWith("h264") ? MimeTypes.VIDEO_H264
                    : name.startsWith("hevc") ? MimeTypes.VIDEO_H265
                    : name.startsWith("mpeg2") ? MimeTypes.VIDEO_MPEG2
                    : name.startsWith("av1") ? MimeTypes.VIDEO_AV1 : MimeTypes.VIDEO_VP9;
            format = new Format.Builder().setSampleMimeType(mime)
                    .setFrameRate(name.startsWith("vp9") ? 24 : 12).build();
            AssetManager assets = InstrumentationRegistry.getInstrumentation().getContext().getAssets();
            try (DataInputStream input = new DataInputStream(assets.open(name + ".packets"))) {
                int count = input.readInt();
                for (int i = 0; i < count; i++) {
                    packetTimes.add(input.readLong());
                    byte[] packet = new byte[input.readInt()];
                    input.readFully(packet);
                    packets.add(packet);
                }
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(assets.open(name + ".frames")))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(" ");
                    times.add(Long.parseLong(parts[0]));
                    hashes.add(parts[1]);
                }
            }
        }
    }
}
