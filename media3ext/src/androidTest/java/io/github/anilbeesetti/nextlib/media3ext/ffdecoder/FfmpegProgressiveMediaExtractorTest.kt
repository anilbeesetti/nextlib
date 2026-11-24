package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegProgressiveMediaExtractorTest {
    @Test
    fun exposesDolbyVisionProfile7BaseLayerWithHdr10Metadata() {
        lateinit var video: Format
        val track = object : TrackOutput by DiscardingTrackOutput() {
            override fun format(format: Format) { video = format }
        }
        readFixture("dolby-vision-profile7.mkv", C.TRACK_TYPE_VIDEO, track) { extractor ->
            assertEquals(MimeTypes.VIDEO_H265, video.sampleMimeType)
            assertTrue(video.initializationData.isNotEmpty())
            val color = checkNotNull(video.colorInfo)
            assertEquals(C.COLOR_SPACE_BT2020, color.colorSpace)
            assertEquals(C.COLOR_RANGE_LIMITED, color.colorRange)
            assertEquals(C.COLOR_TRANSFER_ST2084, color.colorTransfer)
            val staticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
            staticInfo.put(0)
            for (value in intArrayOf(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, 1000, 50, 1000, 400)) {
                staticInfo.putShort(value.toShort())
            }
            assertArrayEquals(staticInfo.array(), color.hdrStaticInfo)
            assertEquals(Extractor.RESULT_CONTINUE, extractor.read(PositionHolder()))
        }
    }

    @Test
    fun timestampsEveryTrueHdAccessUnitInLacedMatroskaBlocks() {
        val timesUs = mutableListOf<Long>()
        val track = object : TrackOutput by DiscardingTrackOutput() {
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                timesUs += timeUs
            }
        }
        readFixture("truehd-laced.mka", C.TRACK_TYPE_AUDIO, track) { extractor ->
            val position = PositionHolder()
            repeat(360) { assertEquals(Extractor.RESULT_CONTINUE, extractor.read(position)) }
            assertEquals(Extractor.RESULT_END_OF_INPUT, extractor.read(position))
        }
        assertEquals(360, timesUs.size)
        assertEquals(listOf(0L, 833L, 1667L, 2500L), timesUs.take(4))
        assertEquals(100_000L, timesUs[120])
        assertEquals(299_167L, timesUs.last())
        assertTrue(timesUs.zipWithNext().all { (earlier, later) -> earlier < later })
    }

    private fun readFixture(
        name: String,
        trackType: Int,
        track: TrackOutput,
        check: (FfmpegProgressiveMediaExtractor) -> Unit,
    ) {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bytes = assets.open(name).use { it.readBytes() }
        val source = ReopeningDataSource(ByteArrayDataSource(bytes))
        val extractor = FfmpegProgressiveMediaExtractor(source)
        val uri = Uri.parse("memory:///$name")
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                assertEquals(trackType, type)
                return track
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { assertTrue(seekMap.isSeekable) }
        }
        try {
            source.open(DataSpec(uri))
            extractor.init(source, uri, emptyMap(), 0, bytes.size.toLong(), output)
            check(extractor)
        } finally {
            extractor.release()
            source.close()
        }
    }
}
