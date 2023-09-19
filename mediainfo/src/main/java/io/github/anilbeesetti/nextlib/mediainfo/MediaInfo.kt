package io.github.anilbeesetti.nextlib.mediainfo

import android.graphics.Bitmap

data class MediaInfo(
    val format: String,
    val duration: Long,
    val videoStream: VideoStream?,
    val audioStreams: List<AudioStream>,
    val subtitleStreams: List<SubtitleStream>,
    private val frameLoaderContext: Long?
) {

    private var frameLoader = frameLoaderContext?.let { FrameLoader(frameLoaderContext) }

    val supportsFrameLoading: Boolean = frameLoader != null

    fun getFrame(): Bitmap? {
        val bitmap = Bitmap.createBitmap(videoStream!!.frameWidth, videoStream.frameHeight, Bitmap.Config.ARGB_8888)
        val result = frameLoader?.loadFrameInto(bitmap, this.duration / 3)
        return if (result == true) bitmap else null
    }

    fun getFrameAt(durationMillis: Long): Bitmap? {
        return null
    }

    fun release() {
        frameLoader?.release()
        frameLoader = null
    }
}
