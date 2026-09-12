package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.*;

import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.extractor.*;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.Test;

/** Runs in Next Player's existing instrumentation host; see run_video_initialization_test.sh. */
public class FfmpegVideoInitializationTest {
    @Test public void h264EmptySingleAndMultipleHeaders() throws Exception {
        Clip clip = read("h264.mp4");
        List<byte[]> headers = clip.format.initializationData;
        assertEquals(2, headers.size());
        byte[] combined = FfmpegVideoDecoder.getExtraData(clip.format.sampleMimeType, headers);
        checkDecode(clip, Collections.singletonList(combined), false, 320, 180);
        checkDecode(clip, Arrays.asList(headers.get(0), new byte[0], headers.get(1), headers.get(1)), false, 320, 180);
        checkDecode(clip, Collections.emptyList(), true, Format.NO_VALUE, Format.NO_VALUE);
        checkDecode(clip, headers, false, 1, 1); // Bitstream dimensions override container hints.
        checkDecode(clip, headers, false, 0, -10);
    }

    @Test public void hevcAv1AndMpegContainerConfiguration() throws Exception {
        for (String name : Arrays.asList("hevc.mp4", "av1.mp4", "mpeg2.mp4")) {
            Clip clip = read(name);
            assertEquals(1, clip.format.initializationData.size());
            checkDecode(clip, clip.format.initializationData, false, 320, 180);
        }
    }

    @Test public void malformedInitializationFailsSafely() throws Exception {
        Clip clip = read("h264.mp4");
        Format nullEntry = clip.format.buildUpon().setInitializationData(Collections.singletonList(null)).build();
        assertThrows(FfmpegDecoderException.class, () -> new FfmpegVideoDecoder(2, 2, 4096, 1, nullEntry));
        // Truncated codec headers are parsed by FFmpeg, which may reject at open or on input.
        Format malformed = clip.format.buildUpon().setInitializationData(Collections.singletonList(new byte[]{0, 0, 1, 0x67})).build();
        FfmpegVideoDecoder decoder;
        try {
            decoder = new FfmpegVideoDecoder(2, 2, 4096, 1, malformed);
        } catch (FfmpegDecoderException expected) {
            return;
        }
        try {
            DecoderInputBuffer input = decoder.createInputBuffer();
            input.ensureSpaceForWrite(clip.samples.get(0).length);
            input.data.put(clip.samples.get(0)).flip();
            VideoDecoderOutputBuffer output = decoder.createOutputBuffer();
            FfmpegDecoderException error = decoder.decode(input, output, false);
            assertTrue(error != null || output.shouldBeSkipped);
        } finally {
            decoder.release();
        }
    }

    private static void checkDecode(Clip clip, List<byte[]> headers, boolean inBand, int width, int height) throws Exception {
        Format format = clip.format.buildUpon().setInitializationData(headers).setWidth(width).setHeight(height).build();
        FfmpegVideoDecoder decoder = new FfmpegVideoDecoder(2, 2, 4096, 1, format);
        decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_YUV);
        byte[] prefix = inBand ? FfmpegVideoDecoder.getExtraData(clip.format.sampleMimeType, clip.format.initializationData) : new byte[0];
        try {
            // Initial decode and three flush/replays exercise the same Java/JNI reset path as seeking.
            for (int pass = 0; pass < 4; pass++) {
                DecoderInputBuffer input = decoder.createInputBuffer();
                VideoDecoderOutputBuffer output = decoder.createOutputBuffer();
                int frames = 0;
                for (int i = 0; i < clip.samples.size(); i++) {
                    byte[] sample = clip.samples.get(i);
                    input.clear();
                    output.clear();
                    input.ensureSpaceForWrite(sample.length + (i == 0 ? prefix.length : 0));
                    if (i == 0) input.data.put(prefix);
                    input.data.put(sample);
                    input.flip();
                    input.timeUs = clip.times.get(i);
                    input.format = format;
                    assertNull(decoder.decode(input, output, pass > 0 && i == 0));
                    if (!output.shouldBeSkipped) {
                        assertEquals(320, output.width);
                        assertEquals(180, output.height);
                        assertNotNull(output.data);
                        frames++;
                    }
                }
                assertEquals(clip.samples.size(), frames);
                Log.i("NextLibInitTest", clip.format.sampleMimeType + " headers=" + headers.size()
                        + " dimensions=" + width + "x" + height + " pass=" + pass + " frames=" + frames);
            }
        } finally {
            decoder.release();
        }
    }

    private static Clip read(String name) throws Exception {
        byte[] bytes;
        try (InputStream stream = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) out.write(buffer, 0, count);
            bytes = out.toByteArray();
        }
        Clip clip = new Clip();
        Mp4Extractor extractor = new Mp4Extractor();
        extractor.init(new ExtractorOutput() {
            @Override public TrackOutput track(int id, int type) { assertEquals(C.TRACK_TYPE_VIDEO, type); return clip; }
            @Override public void endTracks() {}
            @Override public void seekMap(SeekMap map) {}
        });
        PositionHolder position = new PositionHolder();
        int result = Extractor.RESULT_SEEK;
        ExtractorInput input = null;
        try {
            while (result != Extractor.RESULT_END_OF_INPUT) {
                if (result == Extractor.RESULT_SEEK) {
                    ByteArrayInputStream stream = new ByteArrayInputStream(bytes, (int) position.position, bytes.length - (int) position.position);
                    input = new DefaultExtractorInput(stream::read, position.position, bytes.length);
                }
                result = extractor.read(input, position);
            }
        } finally {
            extractor.release();
        }
        assertNotNull(clip.format);
        assertEquals(320, clip.format.width);
        assertEquals(180, clip.format.height);
        assertFalse(clip.samples.isEmpty());
        return clip;
    }

    private static final class Clip implements TrackOutput {
        Format format;
        final List<byte[]> samples = new ArrayList<>();
        final List<Long> times = new ArrayList<>();
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        @Override public void format(Format format) { this.format = format; }
        @Override public int sampleData(DataReader input, int length, boolean allowEnd, int part) throws IOException {
            byte[] data = new byte[length];
            int count = input.read(data, 0, length);
            if (count > 0) pending.write(data, 0, count);
            else if (!allowEnd) throw new EOFException();
            return count;
        }
        @Override public void sampleData(ParsableByteArray data, int length, int part) {
            pending.write(data.getData(), data.getPosition(), length);
            data.skipBytes(length);
        }
        @Override public void sampleMetadata(long time, int flags, int size, int offset, CryptoData crypto) {
            assertEquals(0, offset);
            assertEquals(size, pending.size());
            samples.add(pending.toByteArray());
            times.add(time);
            pending.reset();
        }
    }
}
