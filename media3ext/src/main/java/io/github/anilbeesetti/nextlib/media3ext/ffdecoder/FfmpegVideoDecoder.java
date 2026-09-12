package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Ffmpeg Video decoder.
 */
@UnstableApi
final class FfmpegVideoDecoder implements
        Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

    private static final int SUCCESS = 0;
    private static final int AGAIN = 1;
    private static final int END_OF_STREAM = 2;
    private static final int INVALID_DATA = -1;

    private final Object lock = new Object();
    private final ArrayDeque<DecoderInputBuffer> availableInputs = new ArrayDeque<>();
    private final ArrayDeque<DecoderInputBuffer> queuedInputs = new ArrayDeque<>();
    private final ArrayDeque<VideoDecoderOutputBuffer> availableOutputs = new ArrayDeque<>();
    private final ArrayDeque<VideoDecoderOutputBuffer> queuedOutputs = new ArrayDeque<>();
    // Includes renderer-held outputs, which must also be reclaimed on decoder replacement.
    private final VideoDecoderOutputBuffer[] outputBuffers;
    private final Thread decodeThread;
    private final String codecName;
    private final Format format;
    private long nativeContext;
    @Nullable private DecoderInputBuffer dequeuedInput;
    @Nullable private FfmpegDecoderException exception;
    @Nullable private Callback callback;
    @Nullable private Executor executor;
    private long outputStartTimeUs = C.TIME_UNSET;
    private int generation;
    private boolean resetPending;
    private boolean released;

    @C.VideoOutputMode
    private volatile int outputMode;

    /**
     * Creates a Ffmpeg video Decoder.
     *
     * @param numInputBuffers        Number of input buffers.
     * @param numOutputBuffers       Number of output buffers.
     * @param initialInputBufferSize The initial size of each input buffer, in bytes.
     * @param threads                Number of threads FFmpeg will use to decode.
     * @throws FfmpegDecoderException Thrown if an exception occurs when initializing the
     *                                decoder.
     */
    public FfmpegVideoDecoder(int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads, Format format) throws FfmpegDecoderException {
        Assertions.checkArgument(numInputBuffers > 0 && numOutputBuffers > 0);

        if (!FfmpegLibrary.isAvailable()) {
            throw new FfmpegDecoderException("Failed to load decoder native library.");
        }
        assert format.sampleMimeType != null;
        codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
        byte[] extraData = getExtraData(format.sampleMimeType, format.initializationData);
        this.format = format;
        nativeContext = ffmpegInitialize(codecName, extraData, threads);
        if (nativeContext == 0) {
            throw new FfmpegDecoderException("Failed to initialize decoder.");
        }
        for (int i = 0; i < numInputBuffers; i++) {
            DecoderInputBuffer input = new DecoderInputBuffer(
                    DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
                    FfmpegLibrary.getInputBufferPaddingSize());
            input.ensureSpaceForWrite(initialInputBufferSize);
            availableInputs.add(input);
        }
        outputBuffers = new VideoDecoderOutputBuffer[numOutputBuffers];
        for (int i = 0; i < numOutputBuffers; i++) {
            outputBuffers[i] = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
            availableOutputs.add(outputBuffers[i]);
        }
        decodeThread = new Thread(this::run, "NextLib:FfmpegVideoDecoder");
        decodeThread.start();
    }

    /**
     * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
     * not required.
     */
    @Nullable
    private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
        if (initializationData.isEmpty()) return null;
        switch (mimeType) {
            case MimeTypes.VIDEO_H264 -> {
                byte[] sps = initializationData.get(0);
                byte[] pps = initializationData.get(1);
                byte[] extraData = new byte[sps.length + pps.length];
                System.arraycopy(sps, 0, extraData, 0, sps.length);
                System.arraycopy(pps, 0, extraData, sps.length, pps.length);
                return extraData;
            }
            case MimeTypes.VIDEO_H265 -> {
                return initializationData.get(0);
            }
            default -> {
                // Other codecs do not require extra data.
                return null;
            }
        }
    }

    @Override
    public String getName() {
        return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
    }

    /**
     * Sets the output mode for frames rendered by the decoder.
     *
     * @param outputMode The output mode.
     */
    public void setOutputMode(@C.VideoOutputMode int outputMode) {
        this.outputMode = outputMode;
    }

    @Override
    public void setOutputStartTimeUs(long timeUs) {
        synchronized (lock) {
            outputStartTimeUs = timeUs;
        }
    }

    @Override
    public void setCallback(Callback callback, Executor executor) {
        synchronized (lock) {
            this.callback = callback;
            this.executor = executor;
        }
    }

    @Nullable
    @Override
    public DecoderInputBuffer dequeueInputBuffer() throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            Assertions.checkState(dequeuedInput == null && !released);
            dequeuedInput = availableInputs.pollFirst();
            return dequeuedInput;
        }
    }

    @Override
    public void queueInputBuffer(DecoderInputBuffer input) throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            Assertions.checkArgument(input == dequeuedInput);
            queuedInputs.addLast(input);
            dequeuedInput = null;
            lock.notifyAll();
        }
    }

    @Nullable
    @Override
    public VideoDecoderOutputBuffer dequeueOutputBuffer() throws FfmpegDecoderException {
        synchronized (lock) {
            maybeThrowException();
            return queuedOutputs.pollFirst();
        }
    }

    private void maybeThrowException() throws FfmpegDecoderException {
        if (exception != null) throw exception;
    }

    private void releaseInputBuffer(DecoderInputBuffer input) {
        input.clear();
        availableInputs.addLast(input);
    }

    private void releaseOutputBuffer(VideoDecoderOutputBuffer output) {
        synchronized (lock) {
            releaseNativeFrame(output);
            output.clear();
            if (!released) availableOutputs.addLast(output);
            lock.notifyAll();
        }
    }

    private void releaseNativeFrame(VideoDecoderOutputBuffer output) {
        if (output.decoderPrivate != 0) {
            ffmpegReleaseFrame(output.decoderPrivate);
            output.decoderPrivate = 0;
        }
    }

    @Override
    public void flush() {
        synchronized (lock) {
            generation++;
            resetPending = true;
            if (dequeuedInput != null) {
                releaseInputBuffer(dequeuedInput);
                dequeuedInput = null;
            }
            while (!queuedInputs.isEmpty()) releaseInputBuffer(queuedInputs.removeFirst());
            while (!queuedOutputs.isEmpty()) queuedOutputs.removeFirst().release();
            // The worker retains its in-flight input until JNI returns. Its output is
            // discarded by the generation check before native flush and any new input.
            lock.notifyAll();
        }
    }

    private void run() {
        try {
            decodeLoop();
        } catch (FfmpegDecoderException | RuntimeException | OutOfMemoryError error) {
            synchronized (lock) {
                exception = error instanceof FfmpegDecoderException
                        ? (FfmpegDecoderException) error
                        : new FfmpegDecoderException("Unexpected decode error", error);
                notifyCallback(false);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            synchronized (lock) {
                exception = new FfmpegDecoderException("Decode thread interrupted", error);
                notifyCallback(false);
            }
        }
    }

    private void decodeLoop() throws FfmpegDecoderException, InterruptedException {
        DecoderInputBuffer input = null;
        boolean receive = false;
        boolean draining = false;
        boolean ended = false;
        int skipped = 0;
        int emptyRetries = 0;
        while (true) {
            VideoDecoderOutputBuffer output;
            boolean reset;
            int currentGeneration;
            synchronized (lock) {
                while (!released && !resetPending && (ended || availableOutputs.isEmpty()
                        || (!receive && input == null && queuedInputs.isEmpty()))) {
                    lock.wait();
                }
                if (released) return;
                currentGeneration = generation;
                reset = resetPending;
                resetPending = false;
                if (reset) {
                    if (input != null) {
                        releaseInputBuffer(input);
                        notifyCallback(true);
                    }
                    input = null;
                    receive = draining = ended = false;
                    skipped = emptyRetries = 0;
                }
                if (!reset && !receive && input == null) input = queuedInputs.removeFirst();
                output = reset ? null : availableOutputs.removeFirst();
            }

            // JNI runs outside the pool lock so decoding never blocks the playback
            // thread from queueing, releasing buffers or requesting a seek.
            if (reset) {
                if (ffmpegReset(nativeContext) == 0) {
                    throw new FfmpegDecoderException("Error resetting decoder (see logcat).");
                }
                continue;
            }
            int result;
            if (receive) {
                result = ffmpegReceiveFrame(nativeContext, outputMode, output, format.frameRate);
            } else {
                Assertions.checkNotNull(input);
                ByteBuffer data = input.isEndOfStream() ? null : Util.castNonNull(input.data);
                result = ffmpegSendPacket(nativeContext, data,
                        data == null ? 0 : data.limit(), input.timeUs);
            }

            synchronized (lock) {
                if (released || currentGeneration != generation) {
                    output.release();
                    continue;
                }
                if (receive) {
                    if (result == SUCCESS) {
                        emptyRetries = 0;
                        if (outputStartTimeUs != C.TIME_UNSET && output.timeUs < outputStartTimeUs) {
                            skipped++;
                            output.release();
                        } else {
                            output.format = format;
                            output.skippedOutputBufferCount = skipped;
                            skipped = 0;
                            queuedOutputs.addLast(output);
                            notifyCallback(false);
                        }
                        // Keep receiving, even with no more Java input queued.
                    } else if (result == END_OF_STREAM && draining) {
                        output.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
                        output.skippedOutputBufferCount = skipped;
                        queuedOutputs.addLast(output);
                        ended = true;
                        notifyCallback(false);
                    } else {
                        output.release();
                        if (result != AGAIN || draining) {
                            throw new FfmpegDecoderException("Error receiving video frame (see logcat).");
                        }
                        // VP9's bitstream filter can consume hidden frames here after
                        // send-EAGAIN. Retry the retained packet, but fail a stuck codec.
                        // VP9 superframes contain at most eight frames; allow two
                        // superframes worth of empty receives before reporting failure.
                        if (input != null && ++emptyRetries > 16) {
                            throw new FfmpegDecoderException("Video decoder made no progress.");
                        }
                        receive = false;
                    }
                } else {
                    output.release();
                    if (result == SUCCESS || result == INVALID_DATA) {
                        draining = input.isEndOfStream();
                        if (result == INVALID_DATA) {
                            if (draining) throw new FfmpegDecoderException("Error draining video decoder.");
                            skipped++;
                        }
                        releaseInputBuffer(input);
                        input = null;
                        emptyRetries = 0;
                        notifyCallback(true);
                    } else if (result != AGAIN) {
                        throw new FfmpegDecoderException("Error sending video packet (see logcat).");
                    }
                    // On EAGAIN input remains owned by this worker, including EOS.
                    receive = true;
                }
            }
        }
    }

    private void notifyCallback(boolean inputAvailable) {
        Callback current = callback;
        if (current != null && executor != null) {
            executor.execute(inputAvailable ? current::onInputBufferAvailable : current::onOutputBufferAvailable);
        }
    }

    @Override
    public void release() {
        synchronized (lock) {
            released = true;
            lock.notifyAll();
        }
        boolean interrupted = false;
        while (decodeThread.isAlive()) {
            try {
                decodeThread.join();
            } catch (InterruptedException error) {
                interrupted = true;
            }
        }
        synchronized (lock) {
            for (VideoDecoderOutputBuffer output : outputBuffers) releaseNativeFrame(output);
            ffmpegRelease(nativeContext);
            nativeContext = 0;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Renders output buffer to the given surface. Must only be called when in {@link
     * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
     *
     * @param outputBuffer Output buffer.
     * @param surface      Output surface.
     * @throws FfmpegDecoderException Thrown if called with invalid output mode or frame
     *                                rendering fails.
     */
    public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws FfmpegDecoderException {
        if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
            throw new FfmpegDecoderException("Invalid output mode.");
        }
        if (ffmpegRenderFrame(
                nativeContext, surface,
                outputBuffer, outputBuffer.width, outputBuffer.height) != SUCCESS) {
            throw new FfmpegDecoderException("Buffer render error: ");
        }
    }

    private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads);

    private native long ffmpegReset(long context);

    private native void ffmpegRelease(long context);

    private native void ffmpegReleaseFrame(long frame);

    private native int ffmpegRenderFrame(
            long context, Surface surface, VideoDecoderOutputBuffer outputBuffer,
            int displayedWidth,
            int displayedHeight);

    // Null data sends the drain packet. AGAIN means this input is still unaccepted.
    private native int ffmpegSendPacket(long context, @Nullable ByteBuffer encodedData, int length,
                                       long inputTimeUs);

    // SUCCESS produces one output; AGAIN requests input; END_OF_STREAM completes draining.
    private native int ffmpegReceiveFrame(long context, int outputMode,
                                         VideoDecoderOutputBuffer outputBuffer, float frameRate);
}
