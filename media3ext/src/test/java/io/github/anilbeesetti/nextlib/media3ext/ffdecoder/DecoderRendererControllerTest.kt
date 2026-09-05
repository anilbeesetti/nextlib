package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy

@UnstableApi
class DecoderRendererControllerTest {
    @Test
    fun initializedDecoderUsesQueriedCodecInfoWithoutPlayerFormat() {
        val controller = DecoderRendererController(DecoderMode.AUTO, DecoderMode.AUTO)
        DecoderMediaCodecSelector(controller, codecSelector).getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        assertEquals(DecoderMode.HARDWARE, controller.activeMode("hardware"))
        assertEquals(DecoderMode.SOFTWARE, controller.activeMode("software"))
        assertEquals(DecoderMode.FFMPEG, controller.activeMode("ffmpeg6.0-h264"))
        assertNull(controller.activeMode("unknown"))
        assertNull(controller.activeMode("not-queried"))
    }

    @Test
    fun modesEnableOnlyTheirRendererCategory() {
        assertTrue(DecoderMode.AUTO.enables(ffmpeg = false))
        assertTrue(DecoderMode.AUTO.enables(ffmpeg = true))
        for (mode in listOf(DecoderMode.HARDWARE, DecoderMode.SOFTWARE)) {
            assertTrue(mode.enables(ffmpeg = false))
            assertFalse(mode.enables(ffmpeg = true))
        }
        assertFalse(DecoderMode.FFMPEG.enables(ffmpeg = false))
        assertTrue(DecoderMode.FFMPEG.enables(ffmpeg = true))
    }

    @Test
    fun autoOrdersHardwareThenSoftwareThenUnknownCodecs() {
        val controller = DecoderRendererController(DecoderMode.AUTO, DecoderMode.AUTO)
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
    fun ffmpegDisablesMediaCodecForOnlyTheSelectedTrackType() {
        val controller = DecoderRendererController(DecoderMode.FFMPEG, DecoderMode.HARDWARE)
        val selector = DecoderMediaCodecSelector(controller, codecSelector)

        val video = selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        val audio = selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false)

        assertTrue(video.isEmpty())
        assertEquals(listOf("hardware"), audio.map(MediaCodecInfo::name))
    }

    @Test
    fun existingSelectorReadsChangedModesAndPreservesQueryFlags() {
        val controller = DecoderRendererController(DecoderMode.AUTO, DecoderMode.HARDWARE)
        val selector = DecoderMediaCodecSelector(controller) { mimeType, secure, tunneling ->
            assertEquals(MimeTypes.VIDEO_H264, mimeType)
            assertTrue(secure)
            assertTrue(tunneling)
            codecSelector.getDecoderInfos(mimeType, secure, tunneling)
        }
        controller.videoMode = DecoderMode.SOFTWARE
        assertEquals(
            listOf("software"),
            selector.getDecoderInfos(MimeTypes.VIDEO_H264, true, true).map { it.name },
        )
        assertEquals(DecoderMode.HARDWARE, controller.audioMode)
    }

    @Test
    fun capabilityGateHidesDisabledRendererAndRestoresDelegateSupport() {
        val supported = RendererCapabilities.create(C.FORMAT_HANDLED)
        val capabilities = Proxy.newProxyInstance(
            RendererCapabilities::class.java.classLoader,
            arrayOf(RendererCapabilities::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "supportsFormat" -> supported
                "getTrackType" -> C.TRACK_TYPE_VIDEO
                "getName" -> "test"
                else -> error("Unexpected capability call: ${method.name}")
            }
        } as RendererCapabilities
        val renderer = Proxy.newProxyInstance(
            Renderer::class.java.classLoader,
            arrayOf(Renderer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getCapabilities" -> capabilities
                else -> error("Unexpected renderer call: ${method.name}")
            }
        } as Renderer
        var enabled = true
        val gated = ModeAwareRenderer(renderer) { enabled }.capabilities
        val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()

        assertEquals(supported, gated.supportsFormat(format))
        enabled = false
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, RendererCapabilities.getFormatSupport(gated.supportsFormat(format)))
        enabled = true
        assertEquals(supported, gated.supportsFormat(format))
        assertEquals(C.TRACK_TYPE_VIDEO, gated.trackType)
        assertEquals("test", gated.name)
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
