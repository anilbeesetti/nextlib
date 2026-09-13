package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import android.graphics.Bitmap
import android.text.TextUtils
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.BinaryFrame
import java.io.File
import java.nio.ByteBuffer

/** Playback-thread confined native subtitle state. Returned bitmaps belong to the output. */
@UnstableApi
internal class FfmpegSubtitleDecoder(context: Context, format: Format, fontsDirectory: File?) : AutoCloseable {
    private val frame = IntArray(7)
    private val ass = format.sampleMimeType == MimeTypes.TEXT_SSA
    private var handle: Long

    init {
        check(FfmpegLibrary.isAvailable()) { "FFmpeg library unavailable" }
        val header = if (ass) format.initializationData.getOrNull(1) else format.initializationData.firstOrNull()
        val fontConfig = if (ass) {
            val directory = File(context.cacheDir, "nextlib-fonts").apply { mkdirs() }
            // Fontconfig supplies Android system-font fallback, including non-Latin scripts.
            val config = """<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd"><fontconfig>
                <dir>/system/fonts</dir><dir>/product/fonts</dir><dir>/system_ext/fonts</dir>
                ${fontsDirectory?.let { "<dir>${TextUtils.htmlEncode(it.absolutePath)}</dir>" } ?: ""}
                <cachedir>${TextUtils.htmlEncode(directory.absolutePath)}</cachedir>
                </fontconfig>""".trimIndent()
            File.createTempFile("fonts-", ".conf", directory).apply { writeText(config) }
        } else null
        try {
            handle = nativeCreate(
                if (ass) "ass" else checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType!!)),
                header ?: byteArrayOf(),
                fontConfig?.absolutePath,
                if (ass) format.metadata?.let { metadata ->
                    (0 until metadata.length()).mapNotNull { i ->
                        (metadata[i] as? BinaryFrame)?.takeIf { it.id == MATROSKA_FONT_ID }?.data
                    }.toTypedArray()
                } ?: emptyArray() else emptyArray(),
            )
            check(handle != 0L) { "Unable to initialize ${format.sampleMimeType} subtitles" }
        } finally {
            fontConfig?.delete()
        }
    }

    fun decode(data: ByteBuffer, timeUs: Long, subsampleOffsetUs: Long): Boolean {
        var sample = data.slice()
        // The libass Android build has no iconv. Normalize BOM-marked UTF-16 using the platform.
        if (ass && sample.remaining() >= 2 &&
            ((sample[0] == 0xff.toByte() && sample[1] == 0xfe.toByte()) ||
                (sample[0] == 0xfe.toByte() && sample[1] == 0xff.toByte()))) {
            val utf8 = Charsets.UTF_16.decode(sample).toString().toByteArray(Charsets.UTF_8)
            sample = ByteBuffer.allocateDirect(utf8.size).apply { put(utf8); flip() }
        }
        return nativeDecode(handle, sample, sample.remaining(), timeUs / 1000, subsampleOffsetUs / 1000)
    }

    /** null means unchanged; an empty list explicitly clears the subtitle view. */
    fun render(timeUs: Long, width: Int, height: Int): List<Cue>? {
        val bitmap = nativeRender(handle, timeUs / 1000, width, height, frame)
        if (frame[0] == 0) return null
        if (bitmap == null) return emptyList()
        return listOf(
            Cue.Builder().setBitmap(bitmap)
                .setPosition(frame[1].toFloat() / frame[5]).setPositionAnchor(Cue.ANCHOR_TYPE_START)
                .setLine(frame[2].toFloat() / frame[6], Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
                .setSize(frame[3].toFloat() / frame[5]).setBitmapHeight(frame[4].toFloat() / frame[6])
                .build(),
        )
    }

    fun endTimeUs(): Long = nativeEndTimeMs(handle) * 1000

    override fun close() {
        if (handle != 0L) nativeRelease(handle)
        handle = 0
    }

    private external fun nativeCreate(codec: String, header: ByteArray, fontConfig: String?, fonts: Array<ByteArray>): Long
    private external fun nativeDecode(handle: Long, data: ByteBuffer, length: Int, timeMs: Long, offsetMs: Long): Boolean
    private external fun nativeRender(handle: Long, timeMs: Long, width: Int, height: Int, frame: IntArray): Bitmap?
    private external fun nativeEndTimeMs(handle: Long): Long
    private external fun nativeRelease(handle: Long)
}
