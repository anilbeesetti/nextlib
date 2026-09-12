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
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Ffmpeg Video decoder.
 */
@UnstableApi
final class FfmpegVideoDecoder extends
        SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

    // LINT.IfChange
    private static final int VIDEO_DECODER_SUCCESS = 0;
    private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
    private static final int VIDEO_DECODER_ERROR_OTHER = -2;
    // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

    private final String codecName;
    private long nativeContext;
    // Initialized by createOutputBuffer() during the SimpleDecoder constructor.
    // Keep every buffer, including outputs held by a renderer during decoder reinitialization.
    private List<VideoDecoderOutputBuffer> outputBuffers;
    @Nullable
    private final byte[] extraData;
    private Format format;

    @C.VideoOutputMode
    private volatile int outputMode;

    /**
     * Creates a Ffmpeg video Decoder.
     *
     * @param numInputBuffers        Number of input buffers.
     * @param numOutputBuffers       Number of output buffers.
     * @param initialInputBufferSize The initial size of each input buffer, in bytes.
     * @param threads                Number of threads libgav1 will use to decode.
     * @throws FfmpegDecoderException Thrown if an exception occurs when initializing the
     *                                decoder.
     */
    public FfmpegVideoDecoder(int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads, Format format) throws FfmpegDecoderException {
        super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);

        if (!FfmpegLibrary.isAvailable()) {
            throw new FfmpegDecoderException("Failed to load decoder native library.");
        }
        assert format.sampleMimeType != null;
        codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
        try {
            extraData = getExtraData(format.sampleMimeType, format.initializationData);
        } catch (FfmpegDecoderException e) {
            super.release();
            throw e;
        }
        this.format = format;
        nativeContext = ffmpegInitialize(codecName, extraData, threads, format.width, format.height);
        if (nativeContext == 0) {
            super.release();
            throw new FfmpegDecoderException("Failed to initialize decoder.");
        }
        setInitialInputBufferSize(initialInputBufferSize);
    }

    /**
     * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
     * not required.
     */
    @Nullable
    /* package */ static byte[] getExtraData(String mimeType, List<byte[]> initializationData)
            throws FfmpegDecoderException {
        if (initializationData.isEmpty()) return null;
        switch (mimeType) {
            case MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265,
                    MimeTypes.VIDEO_MPEG, MimeTypes.VIDEO_MPEG2 -> {
                // Media3 supplies start-code-prefixed NAL units or MPEG sequence headers.
                int size = 0;
                for (byte[] data : initializationData) {
                    if (data == null || data.length > Integer.MAX_VALUE - size) {
                        throw new FfmpegDecoderException("Invalid video initialization data.");
                    }
                    size += data.length;
                }
                byte[] extraData = new byte[size];
                int offset = 0;
                for (byte[] data : initializationData) {
                    System.arraycopy(data, 0, extraData, offset, data.length);
                    offset += data.length;
                }
                return extraData;
            }
            case MimeTypes.VIDEO_AV1 -> {
                // libdav1d accepts Media3's single av1C record, including its four-byte header.
                if (initializationData.size() != 1 || initializationData.get(0) == null) {
                    throw new FfmpegDecoderException("Invalid AV1 initialization data.");
                }
                return initializationData.get(0);
            }
            default -> {
                // VP8/VP9 do not need extradata; their container metadata is not a byte stream.
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

    @Nullable
    @Override
    protected FfmpegDecoderException decode(DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
        if (reset) {
            nativeContext = ffmpegReset(nativeContext);
            if (nativeContext == 0) {
                return new FfmpegDecoderException("Error resetting (see logcat).");
            }
        }

        // send packet
        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        int inputSize = inputData.limit();
        // enqueue origin data
        int sendPacketResult = ffmpegSendPacket(nativeContext, inputData, inputSize, inputBuffer.timeUs);
        if (sendPacketResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
            return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
        }

        // receive frame
        boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
        // We need to dequeue the decoded frame from the decoder even when the input data is
        // decode-only.
        int getFrameResult = ffmpegReceiveFrame(nativeContext, outputMode, outputBuffer, decodeOnly);
        if (getFrameResult == VIDEO_DECODER_ERROR_OTHER) {
            return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
        }

        if (getFrameResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
            outputBuffer.shouldBeSkipped = true;
        }

        if (!decodeOnly) {
            outputBuffer.format = inputBuffer.format;
        }

        return null;
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
                outputBuffer, outputBuffer.width, outputBuffer.height) == VIDEO_DECODER_ERROR_OTHER) {
            throw new FfmpegDecoderException("Buffer render error: ");
        }
    }

    private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads,
                                         int width, int height);

    private native long ffmpegReset(long context);

    private native void ffmpegRelease(long context);

    private native void ffmpegReleaseFrame(long frame);

    private native int ffmpegRenderFrame(
            long context, Surface surface, VideoDecoderOutputBuffer outputBuffer,
            int displayedWidth,
            int displayedHeight);

    /**
     * Decodes the encoded data passed.
     *
     * @param context     Decoder context.
     * @param encodedData Encoded data.
     * @param length      Length of the data buffer.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_OTHER} if an
     * error occurred.
     */
    private native int ffmpegSendPacket(long context, ByteBuffer encodedData, int length,
                                        long inputTime);

    /**
     * Gets the decoded frame.
     *
     * @param context      Decoder context.
     * @param outputBuffer Output buffer for the decoded frame.
     * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_INVALID_DATA}
     * if successful but the frame is decode-only, {@link #VIDEO_DECODER_ERROR_OTHER} if an error
     * occurred.
     */
    private native int ffmpegReceiveFrame(
            long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

}
