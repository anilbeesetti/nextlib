package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class DecoderRendererControllerTest {
    @Test
    fun rendererRolesFollowVideoAndAudioModesIndependently() {
        assertTrue(
            DecoderRendererRole.SYSTEM_VIDEO.isEnabled(
                videoMode = DecoderMode.HARDWARE,
                audioMode = DecoderMode.APP_SOFTWARE,
            ),
        )
        assertFalse(
            DecoderRendererRole.APP_SOFTWARE_VIDEO.isEnabled(
                videoMode = DecoderMode.HARDWARE,
                audioMode = DecoderMode.APP_SOFTWARE,
            ),
        )
        assertFalse(
            DecoderRendererRole.SYSTEM_AUDIO.isEnabled(
                videoMode = DecoderMode.HARDWARE,
                audioMode = DecoderMode.APP_SOFTWARE,
            ),
        )
        assertTrue(
            DecoderRendererRole.APP_SOFTWARE_AUDIO.isEnabled(
                videoMode = DecoderMode.HARDWARE,
                audioMode = DecoderMode.APP_SOFTWARE,
            ),
        )
    }

    @Test
    fun nullModeEnablesSystemAndAppSoftwareRenderers() {
        DecoderRendererRole.entries.forEach { role ->
            assertTrue(role.isEnabled(null, null))
        }
    }

    @Test
    fun videoAndAudioModesChangeIndependently() {
        val controller = DecoderRendererController(null, null)

        controller.setMode(DecoderTrackType.VIDEO, DecoderMode.APP_SOFTWARE)

        assertEquals(DecoderMode.APP_SOFTWARE, controller.videoMode)
        assertNull(controller.audioMode)

        controller.setMode(DecoderTrackType.AUDIO, DecoderMode.SOFTWARE)

        assertEquals(DecoderMode.APP_SOFTWARE, controller.videoMode)
        assertEquals(DecoderMode.SOFTWARE, controller.audioMode)
    }

    @Test
    fun autoOrdersHardwareThenSoftwareThenUnknownCodecs() {
        val controller = DecoderRendererController(null, null)
        val selector = DecoderMediaCodecSelector(controller, codecSelector)

        val result = selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)

        assertEquals(listOf("hardware", "software", "unknown"), result.map(MediaCodecInfo::name))
    }

    @Test
    fun hardwareAndSoftwareFilterTheirMediaCodecCategories() {
        val controller = DecoderRendererController(DecoderMode.HARDWARE, DecoderMode.SOFTWARE)
        val selector = DecoderMediaCodecSelector(controller, codecSelector)

        val video = selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        val audio = selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false)

        assertEquals(listOf("hardware"), video.map(MediaCodecInfo::name))
        assertEquals(listOf("software"), audio.map(MediaCodecInfo::name))
    }

    @Test
    fun appSoftwareDisablesMediaCodecForOnlyTheSelectedTrackType() {
        val controller = DecoderRendererController(DecoderMode.APP_SOFTWARE, DecoderMode.HARDWARE)
        val selector = DecoderMediaCodecSelector(controller, codecSelector)

        val video = selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        val audio = selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false)

        assertTrue(video.isEmpty())
        assertEquals(listOf("hardware"), audio.map(MediaCodecInfo::name))
    }

    private val codecSelector = MediaCodecSelector { mimeType, _, _ ->
        listOf(
            codecInfo("unknown", mimeType),
            codecInfo("software", mimeType, softwareOnly = true),
            codecInfo("hardware", mimeType, hardwareAccelerated = true),
        )
    }

    private fun codecInfo(
        name: String,
        mimeType: String,
        hardwareAccelerated: Boolean = false,
        softwareOnly: Boolean = false,
    ): MediaCodecInfo {
        return MediaCodecInfo.newInstance(
            name,
            mimeType,
            mimeType,
            null,
            hardwareAccelerated,
            softwareOnly,
            false,
            false,
            false,
        )
    }
}
