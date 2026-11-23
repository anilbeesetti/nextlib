package io.github.anilbeesetti.nextlib.media3ext.ffdecoder;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

/** FFmpeg video output with field metadata from the decoded frame before rotation. */
@UnstableApi
public final class FfmpegVideoDecoderOutputBuffer extends VideoDecoderOutputBuffer {
    private boolean interlaced;
    private boolean topFieldFirst;

    FfmpegVideoDecoderOutputBuffer(Owner<VideoDecoderOutputBuffer> owner) {
        super(owner);
    }

    /** Returns whether the decoded frame is interlaced. */
    public boolean isInterlaced() {
        return interlaced;
    }

    /** Returns the decoded frame's field order. Only meaningful for interlaced frames. */
    public boolean isTopFieldFirst() {
        return topFieldFirst;
    }

    @Override
    public void clear() {
        super.clear();
        interlaced = false;
        topFieldFirst = false;
    }

    private void initFromFfmpeg(long timeUs, int mode, boolean interlaced, boolean topFieldFirst) {
        init(timeUs, mode, null);
        this.interlaced = interlaced;
        this.topFieldFirst = topFieldFirst;
    }
}
