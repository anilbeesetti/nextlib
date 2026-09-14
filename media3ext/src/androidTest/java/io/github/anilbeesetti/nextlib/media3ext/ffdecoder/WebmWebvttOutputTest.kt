package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Test

@UnstableApi
class WebmWebvttOutputTest {
    @Test fun restoresCueHeadersAndKeepsSampleSizesInSync() {
        var bytes = byteArrayOf()
        val output = WebmWebvttOutput(object : ForwardingTrackOutput(DiscardingTrackOutput()) {
            override fun sampleData(data: ParsableByteArray, length: Int) {
                bytes = ByteArray(length).also { data.readBytes(it, 0, length) }
            }

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                assertEquals(1_234_000L, timeUs)
                assertEquals(C.BUFFER_FLAG_KEY_FRAME, flags)
                assertEquals(bytes.size, size)
                assertEquals(0, offset)
            }
        })
        val timing = "00:00:00.000 --> 00:00:02.590"
        val header = "WEBVTT\n\n$timing\n"
        fun check(payload: String, expected: String) {
            val input = (header + payload).toByteArray()
            // Include trailing unrelated bytes to verify the supplied sample length is respected.
            val data = ParsableByteArray(input + "ignored".toByteArray())
            output.sampleData(data, input.size)
            assertEquals(input.size, data.position)
            output.sampleMetadata(1_234_000, C.BUFFER_FLAG_KEY_FRAME, input.size, 0, null)
            assertEquals(expected, bytes.toString(Charsets.UTF_8))
        }
        for (newline in listOf("\n", "\r\n", "\r")) {
            check("cue-字幕${newline}line:20% position:10%${newline}<b>Γεια σας</b>\nSecond line",
                "WEBVTT\n\ncue-字幕\n$timing line:20% position:10%\n<b>Γεια σας</b>\nSecond line")
        }
        check("\n\n\nΓεια σας", "WEBVTT\n\n$timing \nΓεια σας")
        check("truncated identifier", "WEBVTT\n\n")
        check("id\nmissing settings terminator", "WEBVTT\n\n")
        check("\n\nNext cue", "WEBVTT\n\n$timing \nNext cue")
    }
}
