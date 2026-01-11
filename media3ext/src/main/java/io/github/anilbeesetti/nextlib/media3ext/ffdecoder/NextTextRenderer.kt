package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

@UnstableApi
class NextTextRenderer(
    output: TextOutput,
    outputLooper: Looper?,
    decoderFactory: SubtitleDecoderFactory = SubtitleDecoderFactory.DEFAULT,
    val delegate: TextRenderer = TextRenderer(output, outputLooper, decoderFactory)
): Renderer by delegate {

    var syncOffsetMilliseconds: Long = 0L

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        delegate.render(positionUs + syncOffsetMilliseconds * 1000, elapsedRealtimeUs + syncOffsetMilliseconds * 1000)
    }

    override fun release() {
        super.release()
    }
}

@get:UnstableApi
@set:UnstableApi
var ExoPlayer.subtitleDelayMilliseconds: Long
    get() {
        val textRenderer = getNextTextRenderer() ?: return 0L
        return textRenderer.syncOffsetMilliseconds
    }
    set(value) {
        val textRenderer = getNextTextRenderer() ?: return
        textRenderer.syncOffsetMilliseconds = value
    }

@UnstableApi
private fun ExoPlayer.getNextTextRenderer(): NextTextRenderer? {
    for (i in 0 until rendererCount) {
        val rendererType = getRendererType(i)
        if (rendererType == C.TRACK_TYPE_TEXT) {
            return getRenderer(i) as? NextTextRenderer
        }
    }
    return null
}