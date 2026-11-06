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
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Ffmpeg Video decoder.
 */
@UnstableApi
final class FfmpegVideoDecoder extends
        DrainingDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

    // LINT.IfChange
    private static final int VIDEO_DECODER_SUCCESS = 0;
    private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
    private static final int VIDEO_DECODER_ERROR_OTHER = -2;
    private static final int VIDEO_DECODER_TRY_AGAIN = -3;
    private static final int VIDEO_DECODER_END_OF_STREAM = -4;
    // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

    private final String codecName;
    private long nativeContext;
    private boolean draining;
    private boolean firstSamplePending;
    private final Format format;
    // Initialized by createOutputBuffer() during the DrainingDecoder constructor.
    // Keep every buffer, including outputs held by a renderer during decoder reinitialization.
    private List<VideoDecoderOutputBuffer> outputBuffers;
    @Nullable
    private final byte[] extraData;
    private final int rotationDegrees;

    @C.VideoOutputMode
    private volatile int outputMode;

    /**
     * Creates a Ffmpeg video Decoder.
     *
     * @param numInputBuffers        Number of input buffers.
     * @param numOutputBuffers       Number of output buffers.
     * @param initialInputBufferSize The initial size of each input buffer, in bytes.
     * @param threads                Number of FFmpeg decoder worker threads.
     * @throws FfmpegDecoderException Thrown if an exception occurs when initializing the
     *                                decoder.
     */
    public FfmpegVideoDecoder(int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads, Format format) throws FfmpegDecoderException {
        super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);

        if (!FfmpegLibrary.isAvailable()) {
            throw new FfmpegDecoderException("Failed to load decoder native library.");
        }
        assert format.sampleMimeType != null;
        this.format = format;
        codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
        extraData = getExtraData(format.sampleMimeType, format.initializationData);
        // Format uses clockwise degrees. Ignore unsupported non-right-angle rotations.
        int rotation = format.rotationDegrees % 360;
        rotationDegrees = rotation % 90 == 0 ? (rotation + 360) % 360 : 0;
        nativeContext = ffmpegInitialize(codecName, extraData, threads);
        if (nativeContext == 0) {
            throw new FfmpegDecoderException("Failed to initialize decoder.");
        }
        setInitialInputBufferSize(initialInputBufferSize);
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
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, FfmpegLibrary.getInputBufferPaddingSize());
    }

    @Override
    protected VideoDecoderOutputBuffer createOutputBuffer() {
        if (outputBuffers == null) outputBuffers = new ArrayList<>();
        VideoDecoderOutputBuffer outputBuffer = new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
        outputBuffers.add(outputBuffer);
        return outputBuffer;
    }

    @Override
    protected void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
        synchronized (outputBuffers) {
            releaseNativeFrame(outputBuffer);
        }
        super.releaseOutputBuffer(outputBuffer);
    }

    private void releaseNativeFrame(VideoDecoderOutputBuffer outputBuffer) {
        if (outputBuffer.decoderPrivate != 0) {
            ffmpegReleaseFrame(outputBuffer.decoderPrivate);
            outputBuffer.decoderPrivate = 0;
        }
    }

    @Override
    protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
        return new FfmpegDecoderException("Unexpected decode error", error);
    }

    @Override
    protected boolean decode(DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset)
            throws FfmpegDecoderException {
        if (reset) {
            nativeContext = ffmpegReset(nativeContext);
            draining = false;
            firstSamplePending = false;
            if (nativeContext == 0) {
                throw new FfmpegDecoderException("Error resetting (see logcat).");
            }
        }
        firstSamplePending |= inputBuffer.isFirstSample();
        if (!draining) {
            ByteBuffer inputData = inputBuffer.isEndOfStream() ? null : Util.castNonNull(inputBuffer.data);
            int inputSize = inputData == null ? 0 : inputData.limit();
            int result = ffmpegSendPacket(nativeContext, inputData, inputSize, inputBuffer.timeUs);
            if (result == VIDEO_DECODER_ERROR_INVALID_DATA) return false;
            if (result != VIDEO_DECODER_SUCCESS) {
                throw new FfmpegDecoderException("ffmpegSendPacket error (see logcat).");
            }
            draining = inputBuffer.isEndOfStream();
        }

        int result = ffmpegReceiveFrame(nativeContext, outputMode, outputBuffer, rotationDegrees);
        if (result == VIDEO_DECODER_TRY_AGAIN) {
            if (draining) throw new FfmpegDecoderException("Decoder requested input after EOS.");
            return false;
        }
        if (result == VIDEO_DECODER_END_OF_STREAM) {
            if (!draining) throw new FfmpegDecoderException("Unexpected decoder EOF.");
            outputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        if (result != VIDEO_DECODER_SUCCESS) {
            throw new FfmpegDecoderException("ffmpegReceiveFrame error (see logcat).");
        }
        // A delayed frame belongs to an earlier sample. Filter using its decoded PTS,
        // not the timestamp (or EOS marker) of the input which made it available.
        outputBuffer.format = format;
        if (firstSamplePending) {
            outputBuffer.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE);
            firstSamplePending = false;
        }
        return true;
    }

    @Override
    public void release() {
        super.release();
        // The decode thread has stopped. Reclaim queued and renderer-held frames together;
        // clearing decoderPrivate also makes a later outputBuffer.release() harmless.
        synchronized (outputBuffers) {
            for (VideoDecoderOutputBuffer outputBuffer : outputBuffers) {
                releaseNativeFrame(outputBuffer);
            }
            ffmpegRelease(nativeContext);
            nativeContext = 0;
        }
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
                outputBuffer, outputBuffer.width, outputBuffer.height,
                rotationDegrees) == VIDEO_DECODER_ERROR_OTHER) {
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
            int displayedHeight, int rotationDegrees);

    /**
     * Decodes the encoded data passed.
     *
     * @param context     Decoder context.
     * @param encodedData Encoded data.
     * @param length      Length of the data buffer.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_OTHER} if an
     * error occurred.
     */
    private native int ffmpegSendPacket(long context, @Nullable ByteBuffer encodedData, int length,
                                        long inputTime);

    /**
     * Gets the decoded frame.
     *
     * @param context      Decoder context.
     * @param outputBuffer Output buffer for the decoded frame.
     * @return Success, more-input-needed, end-of-stream, or a fatal error status.
     */
    private native int ffmpegReceiveFrame(
            long context, int outputMode, VideoDecoderOutputBuffer outputBuffer,
            int rotationDegrees);

}
