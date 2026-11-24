package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.net.Uri
import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.ColorInfo
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.HevcConfig
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.common.Metadata
import androidx.media3.container.OpusUtil
import androidx.media3.extractor.metadata.id3.BinaryFrame
import java.util.Locale
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException

@Keep
@UnstableApi
internal class FfmpegProgressiveMediaExtractor(private val source: ReopeningDataSource) : ProgressiveMediaExtractor {
    private lateinit var reader: DataReader
    private lateinit var output: ExtractorOutput
    private val tracks = mutableMapOf<Int, TrackOutput>()
    private val formats = mutableMapOf<Int, Format>()
    private val nalLengths = mutableMapOf<Int, Int>()
    private val mimes = mutableMapOf<Int, String>()
    private val fonts = mutableListOf<ByteArray>()
    private var handle = 0L
    private var started = false
    private var pendingSeekUs: Long? = null

    override fun init(dataReader: DataReader, uri: Uri, responseHeaders: Map<String, List<String>>, position: Long, length: Long, output: ExtractorOutput) {
        reader = dataReader
        val parsers = DefaultSubtitleParserFactory()
        this.output = SubtitleTranscodingExtractorOutput(output, object : SubtitleParser.Factory by parsers {
            override fun supportsFormat(format: Format): Boolean = !usesNativeSubtitles(format.sampleMimeType) && parsers.supportsFormat(format)
        })
        if (handle == 0L) {
            if (!FfmpegLibrary.isAvailable()) throw IOException("FFmpeg extraction library unavailable")
            handle = try { nativeOpen() } catch (e: IOException) {
                throw ParserException.createForMalformedContainer("FFmpeg could not probe media", e)
            }
            if (handle == 0L) throw ParserException.createForMalformedContainer("FFmpeg could not open media", null)
        }
    }

    override fun read(positionHolder: PositionHolder): Int {
        started = true
        // A failed native packet read cannot safely resume partial parser state through Loader retries.
        return try {
            pendingSeekUs?.let { nativeSeek(handle, it); pendingSeekUs = null }
            if (nativeRead(handle)) Extractor.RESULT_CONTINUE else Extractor.RESULT_END_OF_INPUT
        }
        catch (e: IOException) { throw ParserException.createForMalformedContainer("FFmpeg extraction failed", e) }
    }

    override fun seek(position: Long, seekTimeUs: Long) {
        pendingSeekUs = if (started || seekTimeUs != 0L) seekTimeUs else null
    }
    override fun getCurrentInputPosition(): Long = source.position
    override fun getUnderlyingImplementationName(): String = "NextLib FFmpeg"
    override fun disableSeekingOnMp3Streams() = Unit
    override fun release() {
        if (handle != 0L) { nativeRelease(handle); handle = 0L }
        tracks.clear(); formats.clear(); fonts.clear(); mimes.clear(); nalLengths.clear()
        started = false
        pendingSeekUs = null
    }

    private fun readInput(bytes: ByteArray, size: Int): Int {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        return reader.read(bytes, 0, size)
    }

    private fun seekInput(position: Long): Long {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        return if (position == -1L) source.length else source.seek(position)
    }

    private fun onTrack(index: Int, mime: String, width: Int, height: Int, channels: Int, sampleRate: Int, pcmEncoding: Int, selectionFlags: Int, extra: ByteArray, language: String?) {
        var initializationData = if (extra.isEmpty()) emptyList() else listOf(extra)
        val format = Format.Builder().setId(index.toString()).setSampleMimeType(mime).setLanguage(language).setSelectionFlags(selectionFlags)
        if (mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265) {
            if (extra.firstOrNull() == 1.toByte()) {
                if (mime == MimeTypes.VIDEO_H264) {
                    val config = AvcConfig.parse(ParsableByteArray(extra))
                    initializationData = config.initializationData
                    nalLengths[index] = config.nalUnitLengthFieldLength
                    format.setCodecs(config.codecs).setPixelWidthHeightRatio(config.pixelWidthHeightRatio)
                } else {
                    val config = HevcConfig.parse(ParsableByteArray(extra))
                    initializationData = config.initializationData ?: emptyList()
                    nalLengths[index] = config.nalUnitLengthFieldLength
                    format.setCodecs(config.codecs)
                }
            } else if (mime == MimeTypes.VIDEO_H264 && extra.isNotEmpty()) {
                initializationData = CodecSpecificDataUtil.splitNalUnits(extra)?.toList() ?: listOf(extra)
            }
        }
        mimes[index] = mime
        if (mime == MimeTypes.TEXT_SSA) initializationData = listOf(byteArrayOf(), extra)
        if (fonts.isNotEmpty()) format.setMetadata(Metadata(*fonts.map { BinaryFrame(MATROSKA_FONT_ID, it) }.toTypedArray()))
        initializationData = when (mime) {
            MimeTypes.AUDIO_OPUS -> {
                if (extra.size < 19) throw IOException("Truncated Opus header")
                OpusUtil.buildInitializationData(extra)
            }
            MimeTypes.AUDIO_VORBIS -> vorbisInitialization(extra)
            MimeTypes.AUDIO_FLAC -> {
                if (extra.size != 34) throw IOException("Invalid FLAC STREAMINFO")
                listOf(byteArrayOf(102, 76, 97, 67, -128, 0, 0, 34) + extra)
            }
            MimeTypes.AUDIO_ALAC -> {
                if (extra.size < 36) throw IOException("Truncated ALAC configuration")
                listOf(extra.copyOfRange(extra.size - 24, extra.size))
            }
            else -> initializationData
        }
        if (pcmEncoding != -1) format.setPcmEncoding(pcmEncoding)
        val type = when {
            MimeTypes.isVideo(mime) -> C.TRACK_TYPE_VIDEO
            MimeTypes.isAudio(mime) -> C.TRACK_TYPE_AUDIO
            else -> C.TRACK_TYPE_TEXT
        }
        if (type == C.TRACK_TYPE_VIDEO) format.setWidth(width).setHeight(height)
        else if (type == C.TRACK_TYPE_AUDIO) format.setChannelCount(channels).setSampleRate(sampleRate)
        val built = format.setInitializationData(initializationData).build()
        formats[index] = built
        tracks[index] = output.track(index, type).also { it.format(built) }
    }

    private fun onVideoFormat(index: Int, rotation: Int, ratio: Float, primaries: Int, range: Int, transfer: Int, hdrStaticInfo: ByteArray?) {
        val format = checkNotNull(formats[index]).buildUpon().setRotationDegrees(rotation)
        if (ratio > 0) format.setPixelWidthHeightRatio(ratio)
        format.setColorInfo(ColorInfo.Builder()
            .setColorSpace(ColorInfo.isoColorPrimariesToColorSpace(primaries))
            .setColorRange(when (range) { 1 -> C.COLOR_RANGE_LIMITED; 2 -> C.COLOR_RANGE_FULL; else -> Format.NO_VALUE })
            .setColorTransfer(ColorInfo.isoTransferCharacteristicsToColorTransfer(transfer))
            .setHdrStaticInfo(hdrStaticInfo).build())
        tracks[index]?.format(format.build().also { formats[index] = it })
    }

    private fun onTracksEnded(durationUs: Long, seekable: Boolean) {
        if (tracks.isEmpty()) throw ParserException.createForUnsupportedContainerFeature("No supported FFmpeg tracks")
        output.endTracks()
        output.seekMap(object : SeekMap {
            override fun isSeekable() = seekable && durationUs != C.TIME_UNSET
            override fun getDurationUs() = durationUs
            override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(timeUs, 0))
        })
    }

    private fun onFont(bytes: ByteArray) {
        if (fonts.size >= 64 || bytes.size !in 4..16 * 1024 * 1024 || fonts.sumOf { it.size } + bytes.size > 32 * 1024 * 1024) return
        if (java.nio.ByteBuffer.wrap(bytes).int in listOf(0x00010000, 0x4f54544f, 0x74746366, 0x74727565)) fonts += bytes
    }

    private fun subtitleTime(timeUs: Long, ass: Boolean): String {
        val ms = timeUs.coerceAtLeast(0) / 1_000
        return if (ass) String.format(Locale.ROOT, "%d:%02d:%02d.%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000 / 10)
        else String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1_000 % 60, ms % 1_000)
    }

    private fun onSample(index: Int, timeUs: Long, durationUs: Long, flags: Int, bytes: ByteArray) {
        val track = tracks[index] ?: return
        val data = when (mimes[index]) {
            MimeTypes.TEXT_SSA -> ("Dialogue: 0:00:00.00,${subtitleTime(durationUs, true)},".toByteArray() + bytes)
            MimeTypes.APPLICATION_SUBRIP -> "1\n00:00:00,000 --> ${subtitleTime(durationUs, false)}\n${bytes.toString(Charsets.UTF_8)}\n".toByteArray()
            else -> nalLengths[index]?.let { annexB(bytes, it) } ?: bytes
        }
        track.sampleData(ParsableByteArray(data), data.size)
        track.sampleMetadata(timeUs, flags, data.size, 0, null)
    }

    private external fun nativeOpen(): Long
    private external fun nativeRead(handle: Long): Boolean
    private external fun nativeSeek(handle: Long, timeUs: Long)
    private external fun nativeRelease(handle: Long)
}

/** Converts container length-prefixed NAL units to the Annex B form shared by both renderers. */
internal fun annexB(bytes: ByteArray, lengthSize: Int): ByteArray {
    if (lengthSize !in 1..4) throw IOException("Invalid NAL length size")
    val output = ByteArrayOutputStream(bytes.size)
    var offset = 0
    while (offset < bytes.size) {
        if (bytes.size - offset < lengthSize) throw IOException("Truncated NAL length")
        var size = 0L
        repeat(lengthSize) { size = (size shl 8) or (bytes[offset++].toLong() and 255) }
        if (size > bytes.size - offset) throw IOException("Truncated NAL unit")
        if (output.size().toLong() + size + 4 > 32 * 1024 * 1024) throw IOException("Oversized Annex B sample")
        output.write(byteArrayOf(0, 0, 0, 1))
        output.write(bytes, offset, size.toInt())
        offset += size.toInt()
    }
    return output.toByteArray()
}

/** The two Vorbis decoder headers inside FFmpeg's three-header Xiph private data. */
internal fun vorbisInitialization(extra: ByteArray): List<ByteArray> {
    if (extra.firstOrNull() != 2.toByte()) throw IOException("Invalid Vorbis private data")
    var offset = 1
    fun length(): Int {
        var length = 0
        var part: Int
        do {
            if (offset >= extra.size) throw IOException("Truncated Vorbis header lengths")
            part = extra[offset++].toInt() and 255
            length += part
        } while (part == 255)
        return length
    }
    val first = length()
    val second = length()
    if (first <= 0 || second <= 0 || first.toLong() + second >= extra.size - offset ||
        extra[offset] != 1.toByte() || extra[offset + first] != 3.toByte() || extra[offset + first + second] != 5.toByte()) {
        throw IOException("Invalid Vorbis headers")
    }
    return listOf(extra.copyOfRange(offset, offset + first), extra.copyOfRange(offset + first + second, extra.size))
}
