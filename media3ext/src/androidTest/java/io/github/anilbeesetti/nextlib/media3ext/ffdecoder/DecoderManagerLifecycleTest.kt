package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@UnstableApi
class DecoderManagerLifecycleTest {
    @Test
    fun reusedDecodersRestoreActiveModesAfterRendererDisable() {
        val manager = DecoderManager()
        val listener = DecoderManager::class.java.getDeclaredField("analyticsListener").let {
            it.isAccessible = true
            it.get(manager) as AnalyticsListener
        }
        val event = AnalyticsListener.EventTime(0, Timeline.EMPTY, 0, null, 0, Timeline.EMPTY, 0, null, 0, 0)
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        manager.controller.decoderInfos["hardware"] = MediaCodecInfo.newInstance(
            "hardware", MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H264, null, true, false, false, false, false,
        )
        manager.controller.decoderInfos["software"] = MediaCodecInfo.newInstance(
            "software", MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_AAC, null, false, true, false, false, false,
        )

        for (reuse in listOf(
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH,
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION,
            DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,
        )) {
            listener.onVideoDecoderInitialized(event, "hardware", 0, 0)
            listener.onAudioDecoderInitialized(event, "software", 0, 0)
            listener.onVideoDisabled(event, DecoderCounters())
            listener.onAudioDisabled(event, DecoderCounters())
            assertNull(manager.activeVideoMode)
            assertNull(manager.activeAudioMode)

            listener.onVideoInputFormatChanged(event, format, DecoderReuseEvaluation("hardware", format, format, reuse, 0))
            assertEquals(DecoderMode.HARDWARE, manager.activeVideoMode)
            assertNull(manager.activeAudioMode)
            listener.onAudioInputFormatChanged(event, format, DecoderReuseEvaluation("software", format, format, reuse, 0))
            assertEquals(DecoderMode.SOFTWARE, manager.activeAudioMode)
        }

        // The old codec's failed reuse evaluation can arrive after its replacement initializes.
        listener.onVideoDecoderInitialized(event, "ffmpeg-h264", 0, 0)
        listener.onAudioDecoderInitialized(event, "ffmpeg-aac", 0, 0)
        val discarded = DecoderReuseEvaluation(
            "hardware", format, format, DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND,
        )
        listener.onVideoInputFormatChanged(event, format, discarded)
        listener.onAudioInputFormatChanged(event, format, discarded)
        listener.onVideoInputFormatChanged(event, format, null)
        listener.onAudioInputFormatChanged(event, format, null)
        assertEquals(DecoderMode.FFMPEG, manager.activeVideoMode)
        assertEquals(DecoderMode.FFMPEG, manager.activeAudioMode)
    }
}
