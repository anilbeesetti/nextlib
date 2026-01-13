package io.github.anilbeesetti.nextlib.media3ext.rendrer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

abstract class OffsetRenderer {
    var syncOffsetMilliseconds: Long = 0L
    var syncSpeedMultiplier: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 10f)
        }

    fun getOffsetAdjustedPositionUs(positionUs: Long): Long {
        val speedAdjustedPositionUs = (positionUs * syncSpeedMultiplier).toLong()
        return speedAdjustedPositionUs - (syncOffsetMilliseconds * 1000)
    }
}

@UnstableApi
internal fun ExoPlayer.getOffsetRenderer(trackType: @C.TrackType Int): OffsetRenderer? {
    for (i in 0 until rendererCount) {
        val rendererType = getRendererType(i)
        if (rendererType == trackType) {
            return getRenderer(i) as? OffsetRenderer
        }
    }
    return null
}