package io.github.anilbeesetti.nextlib.media3ext.rendrer

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
): Renderer by delegate, OffsetRenderer() {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val finalPositionUs = getOffsetAdjustedPositionUs(positionUs)
        delegate.render(finalPositionUs, elapsedRealtimeUs)
    }

    override fun release() {
        delegate.release()
    }
}

@get:UnstableApi
@set:UnstableApi
var ExoPlayer.subtitleDelayMilliseconds: Long
    get() {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return 0L
        return textRenderer.syncOffsetMilliseconds
    }
    set(value) {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return
        textRenderer.syncOffsetMilliseconds = value
    }

@get:UnstableApi
@set:UnstableApi
var ExoPlayer.subtitleSpeed: Float
    get() {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return 0f
        return textRenderer.syncSpeedMultiplier
    }
    set(value) {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return
        textRenderer.syncSpeedMultiplier = value
    }