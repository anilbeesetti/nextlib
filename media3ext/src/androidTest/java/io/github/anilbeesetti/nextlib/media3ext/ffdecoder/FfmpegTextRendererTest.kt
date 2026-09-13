package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.util.zip.Deflater
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class FfmpegTextRendererTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ass = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
    private fun asset(name: String) = instrumentation.context.assets.open("subtitles/$name").use { it.readBytes() }
    private fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
    private fun decode(decoder: FfmpegSubtitleDecoder, bytes: ByteArray, time: Long = 0) =
        assertTrue(decoder.decode(buffer(bytes), time, time))

    private fun pixel(cue: Cue, x: Int, y: Int): Int {
        val bitmap = checkNotNull(cue.bitmap)
        return bitmap.getPixel(x - (cue.position * 1280).toInt(), y - (cue.line * 720).toInt())
    }

    @Test fun assRetainsDrawingColorAnimationAndClearsAtEnd() {
        FfmpegSubtitleDecoder(context, ass, null).use { decoder ->
            decode(decoder, asset("styled.ass"))
            assertTrue(decoder.render(0, 1280, 720)!!.isEmpty())
            val first = decoder.render(2_000_000, 1280, 720)!!.single()
            assertEquals(Color.RED, pixel(first, 180, 220))
            val later = decoder.render(6_000_000, 1280, 720)!!.single()
            assertFalse("Moving text must change the bitmap", first.bitmap!!.sameAs(later.bitmap!!))
            assertNull("Static render time should not allocate another bitmap", decoder.render(6_000_000, 1280, 720))
            assertTrue(decoder.render(7_500_000, 1280, 720)!!.isEmpty())
            assertTrue(decoder.render(8_500_000, 1280, 720)!!.isNotEmpty())
            assertTrue(decoder.render(22_000_000, 1280, 720)!!.isEmpty())
            assertEquals(21_000_000L, decoder.endTimeUs())
        }
    }

    @Test fun embeddedAssUsesPacketOffsetAndDurationAndPreservesOverlaps() {
        val header = asset("styled.ass").toString(Charsets.UTF_8).substringBefore("Dialogue:").toByteArray()
        val format = ass.buildUpon().setInitializationData(listOf(byteArrayOf(), header)).build()
        FfmpegSubtitleDecoder(context, format, null).use { decoder ->
            val sample = "Dialogue: 0:00:00:00,0:00:03:00,0,0,Default,,0,0,0,,{\\an7\\pos(100,200)\\1c&H0000FF&\\bord0\\shad0\\p1}m 0 0 l 160 0 160 40 0 40"
            decode(decoder, sample.toByteArray(), 5_000_000)
            decode(decoder, sample.replace(",0,0,Default", ",1,1,Default").replace("100,200", "100,300").toByteArray(), 6_000_000)
            assertTrue(decoder.render(4_000_000, 1280, 720)!!.isEmpty())
            val both = decoder.render(6_500_000, 1280, 720)!!.single()
            assertEquals(Color.RED, pixel(both, 180, 220))
            assertEquals(Color.RED, pixel(both, 180, 320))
            assertEquals(9_000_000L, decoder.endTimeUs())
            assertTrue(decoder.render(9_000_000, 1280, 720)!!.isEmpty())
            assertFalse(decoder.decode(buffer("Dialogue: broken".toByteArray()), 0, 0))
        }
    }

    @Test fun legacySsaAndUtf16AssRenderAndViewportResizes() {
        for ((bytes, format) in listOf(asset("legacy.ssa") to ass, asset("styled.ass").toString(Charsets.UTF_8).toByteArray(Charsets.UTF_16) to ass)) {
            FfmpegSubtitleDecoder(context, format, null).use { decoder ->
                decode(decoder, bytes)
                assertTrue(decoder.render(2_000_000, 1280, 720)!!.isNotEmpty())
                val resized = decoder.render(2_000_000, 720, 1280)!!.single()
                assertTrue(resized.position in 0f..1f && resized.line in 0f..1f)
            }
        }
    }

    @Test fun assUsesEmbeddedFontsAndDoesNotLeakThemToOtherDecoders() {
        val directory = java.io.File(context.cacheDir, "test-fonts").apply { mkdirs() }
        val font = java.io.File(directory, "shapes.ttf").apply { writeBytes(asset("shapes.ttf")) }
        fun render(script: String, fonts: java.io.File? = null) = FfmpegSubtitleDecoder(context, ass, fonts).use {
            decode(it, asset(script))
            checkNotNull(it.render(2_000_000, 1280, 720)!!.single().bitmap)
        }
        try {
            val expected = render("font.ass", directory)
            val fallback = render("font.ass")
            assertFalse("Fixture must distinguish the authored font from Android fallback", expected.sameAs(fallback))
            assertTrue("ASS [Fonts] payload must use the authored font", expected.sameAs(render("font-embedded.ass")))
            assertTrue("Fonts must remain scoped to one decoder", fallback.sameAs(render("font.ass")))
        } finally {
            font.delete()
            directory.delete()
        }
    }

    @Test fun matroskaExtractsFontsWithoutAddingThemToSessionBundles() {
        val args = InstrumentationRegistry.getArguments()
        val uri = android.net.Uri.parse(args.getString("fontSample") ?: "asset:///subtitles/font.ass.mkv")
        val dataSource = androidx.media3.datasource.DefaultDataSource.Factory(instrumentation.context).createDataSource()
        val extractor = FfmpegSubtitleExtractorsFactory().experimentalSetTextTrackTranscodingEnabled(false).createExtractors().first {
            it.underlyingImplementation is androidx.media3.extractor.mkv.MatroskaExtractor
        }
        val formats = mutableListOf<Format>()
        extractor.init(object : androidx.media3.extractor.ExtractorOutput {
            override fun track(id: Int, type: Int) = object : androidx.media3.extractor.ForwardingTrackOutput(androidx.media3.extractor.DiscardingTrackOutput()) {
                override fun format(format: Format) { if (format.sampleMimeType == MimeTypes.TEXT_SSA) formats += format }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: androidx.media3.extractor.SeekMap) = Unit
        })
        try {
            fun open(position: Long): androidx.media3.extractor.DefaultExtractorInput {
                dataSource.close()
                val length = dataSource.open(androidx.media3.datasource.DataSpec.Builder().setUri(uri).setPosition(position).build())
                return androidx.media3.extractor.DefaultExtractorInput(dataSource, position, if (length < 0) length else position + length)
            }
            var input = open(0)
            val seek = androidx.media3.extractor.PositionHolder()
            var result = androidx.media3.extractor.Extractor.RESULT_CONTINUE
            while (result != androidx.media3.extractor.Extractor.RESULT_END_OF_INPUT && formats.none { it.metadata != null }) {
                result = extractor.read(input, seek)
                if (result == androidx.media3.extractor.Extractor.RESULT_SEEK) input = open(seek.position)
            }
            val format = formats.last { it.metadata != null }
            val metadata = checkNotNull(format.metadata)
            val fonts = (0 until metadata.length()).mapNotNull {
                (metadata[it] as? androidx.media3.extractor.metadata.id3.BinaryFrame)?.takeIf { it.id == MATROSKA_FONT_ID }?.data
            }
            assertEquals(args.getString("fontCount")?.toInt() ?: 1, fonts.size)
            args.getString("fontHashes")?.let { hashes ->
                val actual = fonts.map { bytes ->
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                }.toSet()
                assertEquals(hashes.split(',').toSet(), actual)
            }
            assertEquals(2, format.initializationData.size)
            assertNull("Fonts must not enter MediaSession track bundles", Format.fromBundle(format.toBundle()).metadata)
        } finally {
            dataSource.close()
            extractor.release()
        }
    }

    @Test fun pgsSupportsZlibPalettePositionAndExplicitClear() {
        val original = asset("pgs-display.bin")
        val compressor = Deflater().apply { setInput(original); finish() }
        val compressed = ByteArray(original.size)
        val size = compressor.deflate(compressed)
        compressor.end()
        for (bytes in listOf(original, compressed.copyOf(size))) {
            val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_PGS).build()
            FfmpegSubtitleDecoder(context, format, null).use { decoder ->
                decode(decoder, bytes, 1_000_000)
                decode(decoder, asset("pgs-clear.bin"), 7_000_000)
                assertTrue(decoder.render(0, 1280, 720)!!.isEmpty())
                val cue = decoder.render(2_000_000, 1280, 720)!!.single()
                assertEquals(100f / 1280, cue.position, 0.0001f)
                assertEquals(200f / 720, cue.line, 0.0001f)
                assertTrue(Color.red(cue.bitmap!!.getPixel(10, 10)) > 200)
                assertTrue(decoder.render(7_000_000, 1280, 720)!!.isEmpty())
            }
        }
    }

    @Test fun fallbackParsersRetainCommonTextFormatsAndWebvttLayout() {
        for ((file, mime, text) in listOf(
            Triple("basic.srt", MimeTypes.APPLICATION_SUBRIP, "SRT bold and italic"),
            Triple("styled.vtt", MimeTypes.TEXT_VTT, "WebVTT green positioned"),
            Triple("basic.ttml", MimeTypes.APPLICATION_TTML, "TTML green italic"),
        )) {
            val data = asset(file)
            val subtitle = DefaultSubtitleParserFactory().create(Format.Builder().setSampleMimeType(mime).build())
                .parseToLegacySubtitle(data, 0, data.size)
            val cues = subtitle.getCues(3_000_000)
            assertTrue(file, cues.any { it.text.toString().contains(text) })
            assertTrue(subtitle.getCues(23_000_000).isEmpty())
            if (mime == MimeTypes.TEXT_VTT) {
                assertEquals(2, cues.size)
                val positioned = cues.first { it.text.toString().contains("positioned") }
                assertEquals(0.1f, positioned.position, 0.0001f)
                assertEquals(0.2f, positioned.line, 0.0001f)
                assertTrue(positioned.text is android.text.Spanned)
            }
        }
    }

    @Test fun rendererHandlesSeekDelaySpeedEndAndDisableOnLargeStreamOffset() {
        var cues = emptyList<Cue>()
        val renderer = FfmpegTextRenderer(context, object : TextOutput {
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup.cues }
        }, null)
        renderer.init(0, PlayerId.UNSET, Clock.DEFAULT)
        val offset = 1_000_000_000_000L
        val format = ass.buildUpon().setSubsampleOffsetUs(0).build()
        val stream = OneSampleStream(format, asset("styled.ass"))
        renderer.enable(RendererConfiguration.DEFAULT, arrayOf(format), stream, offset, false, true,
            offset, offset, MediaSource.MediaPeriodId(Any()))
        renderer.setCurrentStreamFinal()
        renderer.start()
        renderer.syncOffsetMilliseconds = 2000
        renderer.render(offset + 2_000_000, 0)
        assertTrue(cues.isEmpty())
        renderer.render(offset + 4_000_000, 0)
        assertTrue(cues.isNotEmpty())
        renderer.syncOffsetMilliseconds = 0
        renderer.syncSpeedMultiplier = 2f
        renderer.render(offset + 3_750_000, 0)
        assertTrue("2x subtitle speed reaches the 7.5-second gap", cues.isEmpty())
        stream.rewind()
        renderer.resetPosition(offset + 2_000_000, true)
        renderer.setCurrentStreamFinal()
        renderer.syncSpeedMultiplier = 1f
        renderer.render(offset + 2_000_000, 0)
        assertTrue("Seeking restores cues after native state is reset", cues.isNotEmpty())
        renderer.render(offset + 22_000_000, 0)
        assertTrue(cues.isEmpty())
        assertTrue(renderer.isEnded)
        renderer.stop()
        renderer.disable()
        assertTrue(cues.isEmpty())
        stream.rewind(skipFormat = true)
        renderer.enable(RendererConfiguration.DEFAULT, arrayOf(format), stream, offset + 2_000_000,
            false, true, offset, offset, MediaSource.MediaPeriodId(Any()))
        renderer.setCurrentStreamFinal()
        renderer.start()
        renderer.render(offset + 2_000_000, 0)
        assertTrue("Re-selection without a fresh Format must retain the absolute timestamp offset", cues.isNotEmpty())
        renderer.stop()
        renderer.disable()
        renderer.reset()
        renderer.release()
    }

    @Test fun capabilitiesRejectEncryptedAndUnsupportedTracks() {
        val renderer = FfmpegTextRenderer(context, object : TextOutput {
            override fun onCues(cueGroup: CueGroup) = Unit
        }, null)
        assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(renderer.capabilities.supportsFormat(ass)))
        assertEquals(C.FORMAT_UNSUPPORTED_DRM, RendererCapabilities.getFormatSupport(renderer.capabilities.supportsFormat(
            ass.buildUpon().setCryptoType(C.CRYPTO_TYPE_FRAMEWORK).build())))
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, RendererCapabilities.getFormatSupport(renderer.capabilities.supportsFormat(
            Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())))
    }

    private class OneSampleStream(val format: Format, val bytes: ByteArray) : SampleStream {
        private var state = 0
        fun rewind(skipFormat: Boolean = false) { state = if (skipFormat) 1 else 0 }
        override fun isReady() = true
        override fun maybeThrowError() = Unit
        override fun skipData(positionUs: Long) = 0
        override fun readData(holder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            if (state++ == 0) { holder.format = format; return C.RESULT_FORMAT_READ }
            if (state == 2) {
                buffer.ensureSpaceForWrite(bytes.size)
                buffer.data!!.put(bytes)
                buffer.timeUs = 0
                buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            } else buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
            return C.RESULT_BUFFER_READ
        }
    }
}
