package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class FfmpegLibraryTest {
    @Test
    fun legacyCodecsSelectTheirExactDecoders() {
        assertEquals("mpeg1video", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_MPEG))
        assertEquals("mpeg4", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_MP4V))
        assertEquals("wmv2", FfmpegLibrary.getCodecName("video/x-ms-wmv2"))
        assertEquals("wmav2", FfmpegLibrary.getCodecName("audio/x-ms-wmav2"))
        assertEquals("pcm_dvd", FfmpegLibrary.getCodecName("audio/x-pcm-dvd"))
    }

    @Test
    fun av1SelectsDav1d() {
        assertEquals("libdav1d", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_AV1))
    }
}
