package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** Media3 extractors with Matroska font attachments retained for [FfmpegTextRenderer]. */
@UnstableApi
class FfmpegSubtitleExtractorsFactory private constructor(private val delegate: DefaultExtractorsFactory) : ExtractorsFactory by delegate {
    constructor() : this(DefaultExtractorsFactory())
    private var parsers: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var transcode = true

    override fun setSubtitleParserFactory(factory: SubtitleParser.Factory) = apply {
        delegate.setSubtitleParserFactory(factory)
        parsers = factory
    }

    @Deprecated("Legacy subtitle decoding is deprecated in Media3")
    override fun experimentalSetTextTrackTranscodingEnabled(enabled: Boolean) = apply {
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled)
        transcode = enabled
    }

    override fun createExtractors() = createExtractors(Uri.EMPTY, emptyMap())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>) =
        delegate.createExtractors(uri, responseHeaders).map { extractor ->
            if (extractor !is MatroskaExtractor) extractor else {
                val flags = if (transcode) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA
                val matroska = FontMatroskaExtractor(parsers, flags)
                object : ForwardingExtractor(matroska) {
                    override fun init(output: ExtractorOutput) {
                        matroska.init(object : ExtractorOutput by output {
                            override fun track(id: Int, type: Int): TrackOutput = matroska.wrap(output.track(id, type))
                        })
                    }
                }
            }
        }.toTypedArray()
}

/** Reuses Media3's EBML parser; fonts never touch disk or a global cache. */
@UnstableApi
private class FontMatroskaExtractor(parsers: SubtitleParser.Factory, flags: Int) : MatroskaExtractor(parsers, flags) {
    // ponytail: load attachments as encountered; add SeekHead prefetch for fonts stored after clusters.
    private val fonts = linkedMapOf<Long, ByteArray>()
    private val outputs = mutableListOf<Pair<TrackOutput, Format>>()
    private var attachmentPosition = 0L
    private var attachment: ByteArray? = null
    private var totalBytes = 0

    fun wrap(output: TrackOutput): TrackOutput = object : ForwardingTrackOutput(output) {
        override fun format(format: Format) {
            if (format.sampleMimeType == MimeTypes.TEXT_SSA) {
                outputs.removeAll { it.first === output }
                outputs += output to format
                output.format(withFonts(format))
            } else output.format(format)
        }
    }

    // Format.toBundle excludes metadata: large fonts must not enter MediaSession Binder messages.
    private fun withFonts(format: Format): Format {
        val entries = fonts.values.map { BinaryFrame(MATROSKA_FONT_ID, it) }.toTypedArray()
        return format.buildUpon().setMetadata((format.metadata ?: Metadata()).copyWithAppendedEntries(*entries)).build()
    }

    override fun getElementType(id: Int): Int = when (id) {
        ATTACHMENTS, ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
        FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
        else -> super.getElementType(id)
    }

    override fun isLevel1Element(id: Int) = id == ATTACHMENTS || super.isLevel1Element(id)

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        super.startMasterElement(id, contentPosition, contentSize)
        if (id == ATTACHED_FILE) {
            attachmentPosition = contentPosition
            attachment = null
        }
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        if (id != FILE_DATA) { super.binaryElement(id, contentSize, input); return }
        // Bound untrusted attachments, including repeats and malformed non-font files.
        if (fonts.containsKey(attachmentPosition) || contentSize !in 4..MAX_FONT_BYTES ||
            fonts.size >= 64 || contentSize > MAX_TOTAL_BYTES - totalBytes) {
            input.skipFully(contentSize)
            return
        }
        val bytes = ByteArray(contentSize)
        input.readFully(bytes, 0, contentSize)
        // SFNT TrueType, OpenType/CFF, collections, and legacy Apple TrueType.
        if (java.nio.ByteBuffer.wrap(bytes).int in listOf(0x00010000, 0x4f54544f, 0x74746366, 0x74727565)) {
            attachment = bytes
        }
    }

    override fun endMasterElement(id: Int) {
        super.endMasterElement(id)
        if (id == ATTACHED_FILE) {
            attachment?.let { fonts[attachmentPosition] = it; totalBytes += it.size }
            attachment = null
        }
        if (id == ATTACHMENTS && fonts.isNotEmpty()) {
            for ((output, format) in outputs) output.format(withFonts(format))
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        super.seek(position, timeUs)
        attachment = null
    }

    companion object {
        private const val ATTACHMENTS = 0x1941A469
        private const val ATTACHED_FILE = 0x61A7
        private const val FILE_DATA = 0x465C
        private const val MAX_FONT_BYTES = 16 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    }
}

internal const val MATROSKA_FONT_ID = "nextlib/matroska-font"
