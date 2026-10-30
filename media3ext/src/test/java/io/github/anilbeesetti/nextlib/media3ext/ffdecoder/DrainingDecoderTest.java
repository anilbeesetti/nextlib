package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class DrainingDecoderTest {
    @Test
    public void drainsAllDelayedFramesBeforeEosWithOneOutputBuffer() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        try {
            queueClip(decoder, 0);
            for (int i = 0; i < 3; i++) {
                VideoDecoderOutputBuffer output = output(decoder);
                assertFalse(output.isEndOfStream());
                assertEquals(i * 100L, output.timeUs);
                assertEquals(0, output.skippedOutputBufferCount);
                output.release();
            }
            VideoDecoderOutputBuffer eos = output(decoder);
            assertTrue(eos.isEndOfStream());
            eos.release();
        } finally {
            decoder.release();
        }
    }

    @Test
    public void seekFiltersDecodedPtsRatherThanCurrentInputOrEosTimestamp() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        try {
            decoder.setOutputStartTimeUs(100);
            queueClip(decoder, 0);
            for (int i = 1; i < 3; i++) {
                VideoDecoderOutputBuffer output = output(decoder);
                assertEquals(i * 100L, output.timeUs);
                assertEquals(i == 1 ? 1 : 0, output.skippedOutputBufferCount);
                output.release();
            }
            VideoDecoderOutputBuffer eos = output(decoder);
            assertTrue(eos.isEndOfStream());
            eos.release();
        } finally {
            decoder.release();
        }
    }

    @Test
    public void flushCancelsPendingEosDrainWhileRendererHoldsOutput() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        try {
            queueClip(decoder, 0);
            VideoDecoderOutputBuffer held = output(decoder);
            decoder.flush();
            held.release();
            queueClip(decoder, 1000);
            for (int i = 0; i < 3; i++) {
                VideoDecoderOutputBuffer output = output(decoder);
                assertEquals(1000 + i * 100L, output.timeUs);
                output.release();
            }
            VideoDecoderOutputBuffer eos = output(decoder);
            assertTrue(eos.isEndOfStream());
            eos.release();
        } finally {
            decoder.release();
        }
    }

    @Test
    public void flushDuringDecodeDiscardsOldOutputAndResetsBeforeNewInput() throws Exception {
        FakeDecoder decoder = new FakeDecoder();
        decoder.entered = new CountDownLatch(1);
        decoder.resume = new CountDownLatch(1);
        try {
            queueClip(decoder, 0);
            assertTrue(decoder.entered.await(5, TimeUnit.SECONDS));
            decoder.flush();
            decoder.resume.countDown();
            queueClip(decoder, 1000);
            VideoDecoderOutputBuffer output = output(decoder);
            assertEquals(1000, output.timeUs);
            output.release();
        } finally {
            decoder.resume.countDown();
            decoder.release();
        }
    }

    private static void queueClip(FakeDecoder decoder, long start) throws Exception {
        for (int i = 0; i < 4; i++) {
            DecoderInputBuffer input = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (input == null && System.nanoTime() < deadline) {
                input = decoder.dequeueInputBuffer();
                if (input == null) Thread.sleep(1);
            }
            assertNotNull("Input pool stalled", input);
            if (i == 3) input.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
            else input.timeUs = start + i * 100;
            decoder.queueInputBuffer(input);
        }
    }

    private static VideoDecoderOutputBuffer output(FakeDecoder decoder) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            VideoDecoderOutputBuffer output = decoder.dequeueOutputBuffer();
            if (output != null) return output;
            Thread.sleep(1);
        }
        throw new AssertionError("Output pool stalled");
    }

    private static final class FakeDecoder extends DrainingDecoder<DecoderInputBuffer,
            VideoDecoderOutputBuffer, FfmpegDecoderException> {
        private final ArrayDeque<Long> delayed = new ArrayDeque<>();
        volatile CountDownLatch entered;
        volatile CountDownLatch resume;

        FakeDecoder() {
            super(new DecoderInputBuffer[4], new VideoDecoderOutputBuffer[1]);
        }

        @Override public String getName() { return "delayed-test-decoder"; }
        @Override protected DecoderInputBuffer createInputBuffer() {
            return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
        }
        @Override protected VideoDecoderOutputBuffer createOutputBuffer() {
            return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
        }
        @Override protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
            return new FfmpegDecoderException("Test decoder failed", error);
        }
        @Override protected boolean decode(DecoderInputBuffer input,
                VideoDecoderOutputBuffer output, boolean reset) {
            if (entered != null) {
                entered.countDown();
                try {
                    assertTrue(resume.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                entered = null;
            }
            if (reset) delayed.clear();
            if (!input.isEndOfStream()) {
                delayed.add(input.timeUs);
                if (delayed.size() <= 2) return false;
            }
            if (delayed.isEmpty()) output.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
            else output.timeUs = delayed.removeFirst();
            return true;
        }
    }
}
