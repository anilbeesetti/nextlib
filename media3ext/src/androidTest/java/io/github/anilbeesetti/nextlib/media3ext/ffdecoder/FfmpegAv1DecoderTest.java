package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.*;

import android.os.SystemClock;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.DataInputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

@UnstableApi
public class FfmpegAv1DecoderTest {
    private static final Format FORMAT = new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_AV1).setWidth(64).setHeight(64).build();

    @Test
    public void delayedFramesDrainBeforeEosWithOneOutputBufferAndSurviveFlush() throws Exception {
        for (int depth : new int[] {8, 10}) {
            List<byte[]> packets = packets(depth);
            FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(2, 1, 4096, 4, FORMAT);
            decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
            try {
                // Clips shorter than the frame delay must still display before EOS.
                assertEquals(1, decode(decoder, packets.subList(0, 1), 0));
                decoder.flush();
                assertEquals(2, decode(decoder, packets.subList(0, 2), 0));
                decoder.flush();
                assertEquals(24, decode(decoder, packets, 0));
                decoder.flush();
                decoder.setOutputStartTimeUs(8 * 41667L);
                // Decoded PTS controls seek filtering, including frames emitted during EOS.
                assertEquals(16, decode(decoder, packets, 8));
                decoder.flush();
                decoder.setOutputStartTimeUs(0);
                decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
                assertEquals(24, decode(decoder, packets, 0));
            } finally {
                decoder.release();
            }
        }
    }

    private static int decode(FfmpegVideoDecoder decoder, List<byte[]> packets, int firstFrame)
            throws Exception {
        int queued = 0;
        int frames = 0;
        long deadline = SystemClock.elapsedRealtime() + 90000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (queued <= packets.size()) {
                DecoderInputBuffer input = decoder.dequeueInputBuffer();
                if (input != null) {
                    if (queued == packets.size()) {
                        input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        byte[] packet = packets.get(queued);
                        input.ensureSpaceForWrite(packet.length);
                        input.data.put(packet);
                        input.timeUs = queued * 41667L;
                        input.format = FORMAT;
                        if (queued == 0) input.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);
                        input.flip();
                    }
                    decoder.queueInputBuffer(input);
                    queued++;
                }
            }
            VideoDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
            if (output == null) {
                SystemClock.sleep(1);
                continue;
            }
            try {
                if (output.isEndOfStream()) {
                    assertEquals(packets.size() + 1, queued);
                    return frames;
                }
                assertEquals((firstFrame + frames) * 41667L, output.timeUs);
                assertSame(FORMAT, output.format);
                assertEquals(64, output.width);
                assertEquals(64, output.height);
                if (output.mode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
                    assertNotEquals(0, output.decoderPrivate);
                } else {
                    assertEquals(0, output.decoderPrivate);
                    assertNotNull(output.yuvPlanes);
                }
                assertEquals(frames == 0 ? firstFrame : 0, output.skippedOutputBufferCount);
                frames++;
            } finally {
                output.release();
                assertEquals(0, output.decoderPrivate);
            }
        }
        throw new AssertionError("Decoder stalled: queued=" + queued + " frames=" + frames);
    }

    private static List<byte[]> packets(int depth) throws Exception {
        List<byte[]> packets = new ArrayList<>();
        try (DataInputStream input = new DataInputStream(InstrumentationRegistry
                .getInstrumentation().getContext().getAssets().open("av1-" + depth + "bit.ivf"))) {
            byte[] header = new byte[32];
            input.readFully(header);
            assertEquals(24, Integer.reverseBytes(java.nio.ByteBuffer.wrap(header, 24, 4).getInt()));
            for (int i = 0; i < 24; i++) {
                int size = Integer.reverseBytes(input.readInt());
                input.readLong(); // IVF timestamp; use deterministic microsecond timestamps above.
                byte[] packet = new byte[size];
                input.readFully(packet);
                packets.add(packet);
            }
        }
        return packets;
    }
}
