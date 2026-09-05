package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

@UnstableApi
class DecoderManagerTest {
    @Test
    fun activeModesAreUnknownBeforeDecoderInitialization() {
        val manager = DecoderManager(
            initialVideoMode = DecoderMode.HARDWARE,
            initialAudioMode = DecoderMode.SOFTWARE,
        )

        assertEquals(DecoderMode.HARDWARE, manager.videoMode)
        assertEquals(DecoderMode.SOFTWARE, manager.audioMode)
        assertNull(manager.activeVideoMode)
        assertNull(manager.activeAudioMode)
    }

    @Test
    fun onlyChangesBetweenMediaCodecModesRequireRestart() {
        assertEquals(true, DecoderMode.AUTO.requiresMediaCodecRestart(DecoderMode.HARDWARE))
        assertEquals(true, DecoderMode.HARDWARE.requiresMediaCodecRestart(DecoderMode.SOFTWARE))
        assertEquals(true, DecoderMode.SOFTWARE.requiresMediaCodecRestart(DecoderMode.AUTO))
        assertEquals(false, DecoderMode.HARDWARE.requiresMediaCodecRestart(DecoderMode.HARDWARE))
        assertEquals(false, DecoderMode.AUTO.requiresMediaCodecRestart(DecoderMode.FFMPEG))
        assertEquals(false, DecoderMode.FFMPEG.requiresMediaCodecRestart(DecoderMode.HARDWARE))
    }

    @Test
    fun selectingBeforeAttachFailsClearly() {
        val manager = DecoderManager()

        val error = assertThrows(IllegalStateException::class.java) {
            manager.selectVideoDecoder(DecoderMode.FFMPEG)
        }

        assertEquals("Attach DecoderManager before selecting a decoder", error.message)
    }
}
