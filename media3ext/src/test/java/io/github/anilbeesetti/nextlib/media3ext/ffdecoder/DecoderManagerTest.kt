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

        assertNull(manager.videoMode)
        assertNull(manager.audioMode)
    }

    @Test
    fun selectingBeforeAttachFailsClearly() {
        val manager = DecoderManager()

        val error = assertThrows(IllegalStateException::class.java) {
            manager.selectVideoDecoder(DecoderMode.APP_SOFTWARE)
        }

        assertEquals("Attach DecoderManager before selecting a decoder", error.message)
    }
}
