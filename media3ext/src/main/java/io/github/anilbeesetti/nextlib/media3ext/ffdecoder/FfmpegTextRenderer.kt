package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import io.github.anilbeesetti.nextlib.media3ext.renderer.OffsetRenderer
import java.io.File

/**
 * Renders ASS/SSA with libass and PGS, VobSub and DVB with FFmpeg into bitmap cues.
 * Pair with [NextRenderersFactory] and [withFfmpegSubtitles]; other formats use Media3.
 * [fontsDirectory] may contain application-supplied fonts (including extracted attachments).
 */
@UnstableApi
class FfmpegTextRenderer private constructor(private val renderer: NativeRenderer) :
    Renderer by renderer, OffsetRenderer() {

    @JvmOverloads
    constructor(context: Context, output: TextOutput, outputLooper: Looper?, fontsDirectory: File? = null) :
        this(NativeRenderer(context.applicationContext, output, outputLooper, fontsDirectory))

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        // Renderer timestamps include a large stream offset. Apply speed only to media time.
        val offset = renderer.offsetUs
        renderer.playbackPositionUs = positionUs
        renderer.render(getOffsetAdjustedPositionUs(positionUs - offset) + offset, elapsedRealtimeUs)
    }

    private class NativeRenderer(
        val context: Context,
        val output: TextOutput,
        looper: Looper?,
        val fontsDirectory: File?,
    ) : BaseRenderer(C.TRACK_TYPE_TEXT) {
        private val handler = looper?.let { Handler(it) }
        private val holder = FormatHolder()
        private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
        private var format: Format? = null
        private var decoder: FfmpegSubtitleDecoder? = null
        private var pending = false
        private var inputEnded = false
        private var outputEnded = false
        private var width = 1280
        private var height = 720
        var playbackPositionUs = 0L

        val offsetUs: Long get() = streamOffsetUs

        override fun getName() = "FfmpegTextRenderer"
        override fun isReady() = true
        override fun isEnded() = outputEnded

        override fun supportsFormat(format: Format): Int {
            val mime = format.sampleMimeType
            val supported = mime == MimeTypes.TEXT_SSA || mime == MimeTypes.APPLICATION_PGS ||
                mime == MimeTypes.APPLICATION_VOBSUB || mime == MimeTypes.APPLICATION_DVBSUBS
            return RendererCapabilities.create(when {
                !supported -> if (MimeTypes.isText(mime)) C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_UNSUPPORTED_TYPE
                format.cryptoType != C.CRYPTO_TYPE_NONE -> C.FORMAT_UNSUPPORTED_DRM
                !FfmpegLibrary.isAvailable() || (mime != MimeTypes.TEXT_SSA && !FfmpegLibrary.supportsFormat(mime!!)) ->
                    C.FORMAT_UNSUPPORTED_SUBTYPE
                else -> C.FORMAT_HANDLED
            })
        }

        override fun onStreamChanged(formats: Array<Format>, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId) {
            // A re-enabled SampleStream can return data without another format notification.
            // Match BaseRenderer.readSource's adjustment for absolute subtitle timestamps.
            format = formats[0].let {
                if (it.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) it else
                    it.buildUpon().setSubsampleOffsetUs(it.subsampleOffsetUs + offsetUs).build()
            }
            resetDecoder()
            updateOutput(emptyList(), startPositionUs)
        }

        override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
            resetDecoder()
            updateOutput(emptyList(), positionUs)
        }

        private fun resetDecoder() {
            decoder?.close()
            decoder = null
            input.clear()
            pending = false
            inputEnded = false
            outputEnded = false
        }

        override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
            // The video clock can stop here. Delayed/slowed subtitles must not hold EOS open.
            if (isCurrentStreamFinal && streamEndPositionUs != C.TIME_UNSET &&
                playbackPositionUs - streamOffsetUs >= streamEndPositionUs) {
                if (!outputEnded) updateOutput(emptyList(), playbackPositionUs)
                outputEnded = true
                return
            }
            // Bound work per render call even if a malformed stream has thousands of empty packets.
            for (sample in 0 until 100) {
                if (!pending && !inputEnded) {
                    input.clear()
                    when (readSource(holder, input, 0)) {
                        C.RESULT_NOTHING_READ -> break
                        C.RESULT_FORMAT_READ -> {
                            val next = checkNotNull(holder.format)
                            if (next.sampleMimeType != format?.sampleMimeType || next.initializationData != format?.initializationData) {
                                resetDecoder()
                            }
                            format = next
                        }
                        C.RESULT_BUFFER_READ -> {
                            if (input.isEndOfStream) inputEnded = true else {
                                input.flip()
                                pending = true
                            }
                        }
                    }
                }
                if (pending && input.timeUs <= positionUs + 1_000_000) {
                    val currentFormat = checkNotNull(format)
                    val currentDecoder = decoder ?: FfmpegSubtitleDecoder(context, currentFormat, fontsDirectory).also {
                        decoder = it
                        Log.i(name, "Initialized ${currentFormat.sampleMimeType}")
                    }
                    val offset = if (currentFormat.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) input.timeUs
                        else currentFormat.subsampleOffsetUs
                    val data = checkNotNull(input.data)
                    if (input.isEncrypted || data.remaining() > 16 * 1024 * 1024 || !currentDecoder.decode(data, input.timeUs, offset)) {
                        Log.w(name, "Ignoring malformed subtitle sample")
                    }
                    pending = false
                }
                if (pending || inputEnded) break
            }
            decoder?.let {
                it.render(positionUs, width, height)?.let { cues -> updateOutput(cues, positionUs) }
                outputEnded = inputEnded && !pending && positionUs >= it.endTimeUs()
            } ?: run { outputEnded = inputEnded }
        }

        private fun updateOutput(cues: List<Cue>, positionUs: Long) {
            val group = CueGroup(cues, positionUs - streamOffsetUs)
            val update = Runnable {
                @Suppress("DEPRECATION")
                output.onCues(cues)
                output.onCues(group)
            }
            if (handler == null) update.run() else {
                // A slow UI must not accumulate animation bitmaps or deliver cues after a clear.
                handler.removeCallbacksAndMessages(null)
                handler.post(update)
            }
        }

        override fun handleMessage(messageType: Int, message: Any?) {
            if (messageType == MSG_SET_VIEWPORT) {
                val size = message as android.util.Size
                require(size.width in 1..4096 && size.height in 1..4096)
                width = size.width
                height = size.height
            } else super.handleMessage(messageType, message)
        }

        override fun onDisabled() {
            resetDecoder()
            updateOutput(emptyList(), streamOffsetUs)
            format = null
        }

        override fun onRelease() {
            decoder?.close()
            decoder = null
            handler?.removeCallbacksAndMessages(null)
        }
    }

    companion object {
        internal const val MSG_SET_VIEWPORT = 10_001
    }
}

/** Call when the subtitle viewport changes; bitmaps otherwise use a 1280 x 720 canvas. */
@UnstableApi
fun ExoPlayer.setSubtitleViewportSize(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    // Bound raster work while preserving the viewport's aspect ratio.
    val scale = minOf(1.0, 1920.0 / width, 1080.0 / height)
    val size = android.util.Size(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
    for (i in 0 until rendererCount) {
        val renderer = getRenderer(i)
        if (renderer is FfmpegTextRenderer) createMessage(renderer).setType(FfmpegTextRenderer.MSG_SET_VIEWPORT).setPayload(size).send()
    }
}
