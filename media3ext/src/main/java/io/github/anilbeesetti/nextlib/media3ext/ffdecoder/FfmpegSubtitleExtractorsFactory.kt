package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import java.io.EOFException

/** Media3 extractors with WebM WebVTT support and Matroska font attachments for [FfmpegTextRenderer]. */
@UnstableApi
class FfmpegSubtitleExtractorsFactory @JvmOverloads constructor(
    private val delegate: DefaultExtractorsFactory = DefaultExtractorsFactory(),
    private val matroskaFlags: Int = 0,
) : ExtractorsFactory by delegate {
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
                val matroska = SubtitleMatroskaExtractor(parsers, matroskaFlags)
                object : ForwardingExtractor(matroska) {
                    override fun read(input: ExtractorInput, seekPosition: PositionHolder) =
                        matroska.readWithIndexedFonts(input, seekPosition)

                    override fun init(output: ExtractorOutput) {
                        // Adapt container packets before Media3 parses them into cues.
                        val target = if (transcode) SubtitleTranscodingExtractorOutput(output, parsers) else output
                        matroska.init(object : ExtractorOutput by target {
                            override fun track(id: Int, type: Int): TrackOutput = matroska.wrap(target.track(id, type))
                        })
                    }
                }
            }
        }.toTypedArray()
}

/** Reuses Media3's EBML parser; fonts never touch disk or a global cache. */
@UnstableApi
private class SubtitleMatroskaExtractor(parsers: SubtitleParser.Factory, flags: Int) : MatroskaExtractor(parsers, flags or FLAG_EMIT_RAW_SUBTITLE_DATA) {
    private val fonts = linkedMapOf<Long, ByteArray>()
    private val outputs = mutableListOf<Pair<TrackOutput, Format>>()
    private var attachmentPosition = 0L
    private var attachment: ByteArray? = null
    private var totalBytes = 0
    private var webmWebvtt = false
    private var segmentStart = 0L
    private var segmentEnd = Long.MAX_VALUE
    private val ebmlScratch = ByteArray(8)
    private var seekIndexesAttachments = false
    private var seekOffset = -1L
    private var indexedAttachments = -1L
    private var resumeAfterAttachments = -1L
    private val canSeekForAttachments = flags and FLAG_DISABLE_SEEK_FOR_CUES == 0
    private var triedIndexedAttachments = false
    private var readTracks = false
    private var hasAssTrack = false

    fun readWithIndexedFonts(input: ExtractorInput, seek: PositionHolder): Int {
        if (resumeAfterAttachments >= 0) {
            if (input.position != indexedAttachments) {
                seek.position = indexedAttachments
                return RESULT_SEEK
            }
            try {
                readIndexedAttachments(input)
            } catch (_: EOFException) {
                // A broken attachment index must not prevent playback.
                clearPrefetchedFonts()
            }
            seek.position = resumeAfterAttachments
            resumeAfterAttachments = -1L
            return RESULT_SEEK
        }
        try {
            return read(input, seek)
        } catch (_: ReadIndexedAttachments) {
            triedIndexedAttachments = true
            if (indexedAttachments < input.position || input.length == C.LENGTH_UNSET.toLong() ||
                indexedAttachments >= input.length) return read(input, seek)
            resumeAfterAttachments = input.position
            seek.position = indexedAttachments
            return RESULT_SEEK
        }
    }

    private fun readIndexedAttachments(input: ExtractorInput) {
        val limit = minOf(input.length, segmentEnd)
        if (readEbmlValue(input, limit, id = true) != ATTACHMENTS.toLong()) return
        val end = readElementEnd(input, limit)
        readElements(input, end, ATTACHED_FILE) { fileEnd ->
            startMasterElement(ATTACHED_FILE, input.position, fileEnd - input.position)
            readElements(input, fileEnd, FILE_DATA) { dataEnd ->
                binaryElement(FILE_DATA, (dataEnd - input.position).toInt(), input)
            }
            endMasterElement(ATTACHED_FILE)
        }
        endMasterElement(ATTACHMENTS)
    }

    private inline fun readElements(input: ExtractorInput, end: Long, childId: Int, readChild: (Long) -> Unit) {
        while (input.position < end) {
            val id = readEbmlValue(input, end, id = true)
            val childEnd = readElementEnd(input, end)
            if (id == childId.toLong()) readChild(childEnd)
            else input.skipFully((childEnd - input.position).toInt())
        }
    }

    private fun readElementEnd(input: ExtractorInput, limit: Long): Long {
        val size = readEbmlValue(input, limit, id = false)
        if (size > Int.MAX_VALUE || size > limit - input.position) throw EOFException()
        return input.position + size
    }

    private fun readEbmlValue(input: ExtractorInput, limit: Long, id: Boolean): Long {
        if (input.position >= limit) throw EOFException()
        input.readFully(ebmlScratch, 0, 1)
        val first = ebmlScratch[0].toInt() and 0xff
        val length = Integer.numberOfLeadingZeros(first) - 23
        if (length !in 1..(if (id) 4 else 8) || length - 1 > limit - input.position) throw EOFException()
        input.readFully(ebmlScratch, 1, length - 1)
        var value = (if (id) first else first and (0xff shr length)).toLong()
        for (i in 1 until length) value = (value shl 8) or (ebmlScratch[i].toLong() and 0xff)
        if (!id && value == (1L shl (7 * length)) - 1L) throw EOFException()
        return value
    }

    private object ReadIndexedAttachments : RuntimeException(null, null, false, false)

    fun wrap(output: TrackOutput): TrackOutput = if (webmWebvtt) WebmWebvttOutput(output) else object : ForwardingTrackOutput(output) {
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
        CLUSTER -> {
            // Media3 has read the Cluster header and hasn't opened its master element yet.
            if (canSeekForAttachments && !triedIndexedAttachments && indexedAttachments >= 0 &&
                fonts.isEmpty() && (!readTracks || hasAssTrack)) {
                throw ReadIndexedAttachments
            }
            super.getElementType(id)
        }
        ATTACHMENTS, ATTACHED_FILE -> EbmlProcessor.ELEMENT_TYPE_MASTER
        FILE_DATA -> EbmlProcessor.ELEMENT_TYPE_BINARY
        else -> super.getElementType(id)
    }

    override fun isLevel1Element(id: Int) = id == ATTACHMENTS || super.isLevel1Element(id)

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        super.startMasterElement(id, contentPosition, contentSize)
        if (id == SEGMENT) {
            segmentStart = contentPosition
            segmentEnd = if (contentSize in 0..(Long.MAX_VALUE - contentPosition)) contentPosition + contentSize else Long.MAX_VALUE
        }
        if (id == SEEK) {
            seekIndexesAttachments = false
            seekOffset = -1L
        }
        if (id == TRACK_ENTRY) webmWebvtt = false
        if (id == ATTACHED_FILE) {
            attachmentPosition = contentPosition
            attachment = null
        }
    }

    override fun stringElement(id: Int, value: String) {
        if (id == CODEC_ID) {
            if (value == "S_TEXT/ASS" || value == "S_TEXT/SSA") hasAssTrack = true
            webmWebvtt = value == "D_WEBVTT/SUBTITLES" || value == "D_WEBVTT/CAPTIONS"
            if (webmWebvtt) {
                // Reuse Matroska block timing; WebmWebvttOutput relocates the ID and settings.
                super.stringElement(id, "S_TEXT/WEBVTT")
                return
            }
        }
        super.stringElement(id, value)
    }

    override fun integerElement(id: Int, value: Long) {
        if (id == SEEK_POSITION) seekOffset = value
        super.integerElement(id, value)
    }

    override fun binaryElement(id: Int, contentSize: Int, input: ExtractorInput) {
        if (id == SEEK_ID && contentSize == 4) {
            input.peekFully(ebmlScratch, 0, 4)
            input.resetPeekPosition()
            seekIndexesAttachments = java.nio.ByteBuffer.wrap(ebmlScratch).int == ATTACHMENTS
        }
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
        if (id == TRACKS) readTracks = true
        if (id == SEEK && seekIndexesAttachments && seekOffset in 0 until (segmentEnd - segmentStart)) {
            indexedAttachments = segmentStart + seekOffset
        }
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
        if (resumeAfterAttachments >= 0) {
            clearPrefetchedFonts()
            triedIndexedAttachments = false
            resumeAfterAttachments = -1L
        }
        attachment = null
    }

    private fun clearPrefetchedFonts() {
        fonts.clear()
        totalBytes = 0
        attachment = null
    }

    companion object {
        private const val SEGMENT = 0x18538067
        private const val SEEK = 0x4DBB
        private const val SEEK_ID = 0x53AB
        private const val SEEK_POSITION = 0x53AC
        private const val CLUSTER = 0x1F43B675
        private const val TRACKS = 0x1654AE6B
        private const val TRACK_ENTRY = 0xAE
        private const val CODEC_ID = 0x86
        private const val ATTACHMENTS = 0x1941A469
        private const val ATTACHED_FILE = 0x61A7
        private const val FILE_DATA = 0x465C
        private const val MAX_FONT_BYTES = 16 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    }
}

/** WebM stores identifier, settings and text; MatroskaExtractor prepends a timed WebVTT header. */
@UnstableApi
internal class WebmWebvttOutput(private val output: TrackOutput) : ForwardingTrackOutput(output) {
    private var sizeAdjustment = 0

    override fun sampleData(data: ParsableByteArray, length: Int) {
        val sample = ParsableByteArray(ByteArray(length).also { data.readBytes(it, 0, length) })
        sample.readLine() // WEBVTT
        sample.readLine() // Empty header line
        val timing = sample.readLine()
        val identifier = sample.readLine()
        val settings = sample.readLine()
        val converted = if (timing == null || identifier == null || settings == null || sample.bytesLeft() == 0) {
            // A truncated WebM cue must not turn its identifier/settings into visible text.
            "WEBVTT\n\n".toByteArray()
        } else {
            val payload = sample.readString(sample.bytesLeft()).trimStart('\r', '\n')
            "WEBVTT\n\n${if (identifier.isEmpty()) "" else "$identifier\n"}$timing $settings\n$payload".toByteArray()
        }
        sizeAdjustment += converted.size - length
        output.sampleData(ParsableByteArray(converted), converted.size)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        output.sampleMetadata(timeUs, flags, size + sizeAdjustment, offset, cryptoData)
        sizeAdjustment = 0
    }
}

internal const val MATROSKA_FONT_ID = "nextlib/matroska-font"
