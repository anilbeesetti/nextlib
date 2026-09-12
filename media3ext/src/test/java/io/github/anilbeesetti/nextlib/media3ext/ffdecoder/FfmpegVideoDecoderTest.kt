package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FfmpegVideoDecoderTest {
    @Test
    fun h264PreservesEmptySingleAndMultipleEntries() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68)
        assertNull(FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_H264, emptyList()))
        assertArrayEquals(sps, FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_H264, listOf(sps)))
        assertArrayEquals(
            sps + pps + pps,
            FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_H264, listOf(sps, byteArrayOf(), pps, pps)),
        )
        assertArrayEquals(byteArrayOf(), FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_H264, listOf(byteArrayOf())))
    }

    @Test
    fun hevcAndMpegKeepStartCodeHeaders() {
        val header = byteArrayOf(0, 0, 0, 1, 0x40)
        for (mime in listOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MPEG, MimeTypes.VIDEO_MPEG2)) {
            assertArrayEquals(header, FfmpegVideoDecoder.getExtraData(mime, listOf(header)))
            assertArrayEquals(header + header, FfmpegVideoDecoder.getExtraData(mime, listOf(header, header)))
        }
    }

    @Test
    fun av1KeepsConfigurationRecordWhileVp9MetadataIsNotConcatenated() {
        val av1c = byteArrayOf(0x81.toByte(), 0, 0, 0)
        assertArrayEquals(av1c, FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_AV1, listOf(av1c)))
        assertNull(FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_VP9, listOf(byteArrayOf(1, 1, 0))))
        assertNull(FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_VP8, emptyList()))
    }

    @Test
    fun malformedListsFailWithDecoderException() {
        @Suppress("UNCHECKED_CAST")
        val malformed = listOf<ByteArray?>(null) as List<ByteArray>
        for (mime in listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_MPEG2, MimeTypes.VIDEO_AV1)) {
            assertThrows(FfmpegDecoderException::class.java) {
                FfmpegVideoDecoder.getExtraData(mime, malformed)
            }
        }
        assertThrows(FfmpegDecoderException::class.java) {
            FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_AV1, listOf(byteArrayOf(0x81.toByte()), byteArrayOf(0)))
        }
        assertThrows(FfmpegDecoderException::class.java) {
            FfmpegVideoDecoder.getExtraData(MimeTypes.VIDEO_H264, java.util.Collections.nCopies(2048, ByteArray(1024 * 1024)))
        }
    }
}
