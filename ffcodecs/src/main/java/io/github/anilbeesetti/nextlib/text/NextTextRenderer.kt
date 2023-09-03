package io.github.anilbeesetti.nextlib.text

import android.os.Looper
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput

class NextTextRenderer(
    syncOffsetUs: Long,
    output: TextOutput?,
    outputLooper: Looper?,
    decoderFactory: SubtitleDecoderFactory = SubtitleDecoderFactory.DEFAULT
) : Media3TextRenderer(output, outputLooper, decoderFactory) {

    var syncOffsetDurationUs: Long = 0L

    init {
        syncOffsetDurationUs = syncOffsetUs
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + syncOffsetDurationUs, elapsedRealtimeUs + syncOffsetDurationUs)
    }
}