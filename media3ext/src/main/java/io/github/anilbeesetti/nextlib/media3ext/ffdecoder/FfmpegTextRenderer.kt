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
import java.util.concurrent.Executors

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

    internal constructor(context: Context, output: TextOutput, outputLooper: Looper?, createDecoder: (Format) -> FfmpegSubtitleDecoder) :
        this(NativeRenderer(context.applicationContext, output, outputLooper, null, createDecoder))

    private var lastDelayMs = 0L
    private var lastSpeed = 1f

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (lastDelayMs != syncOffsetMilliseconds || lastSpeed != syncSpeedMultiplier) {
            renderer.invalidateOutput()
            lastDelayMs = syncOffsetMilliseconds
            lastSpeed = syncSpeedMultiplier
        }
        // Renderer timestamps include a large stream offset. Apply speed only to media time.
        val offset = renderer.offsetUs
        renderer.playbackPositionUs = positionUs
        renderer.render(getOffsetAdjustedPositionUs(positionUs - offset) + offset, elapsedRealtimeUs)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        renderer.seekPositionUs = getOffsetAdjustedPositionUs(positionUs - renderer.offsetUs) + renderer.offsetUs
        renderer.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }

    private class NativeRenderer(
        val context: Context,
        val output: TextOutput,
        looper: Looper?,
        val fontsDirectory: File?,
        val createDecoder: (Format) -> FfmpegSubtitleDecoder = { FfmpegSubtitleDecoder(context, it, fontsDirectory) },
    ) : BaseRenderer(C.TRACK_TYPE_TEXT) {
        private val handler = looper?.let { Handler(it) }
        private val holder = FormatHolder()
        private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
        private var format: Format? = null
        private var decoder: FfmpegSubtitleDecoder? = null
        private var initialization: DecoderInitialization? = null
        private var initializationFailed = false
        private var pending = false
        private var inputEnded = false
        private var outputEnded = false
        private var width = 1280
        private var height = 720
        var playbackPositionUs = 0L
        private var lastRenderedPositionUs = C.TIME_UNSET
        var seekPositionUs = C.TIME_UNSET

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
            val targetUs = if (seekPositionUs == C.TIME_UNSET) positionUs else seekPositionUs
            seekPositionUs = C.TIME_UNSET
            val bitmapCovered = targetUs >= lastRenderedPositionUs &&
                (inputEnded || (pending && targetUs <= input.timeUs) || targetUs == lastRenderedPositionUs)
            if (format?.sampleMimeType == MimeTypes.TEXT_SSA && initialization != null) {
                // Retain consumed scripts, but let the source resend packets ahead of a backward seek.
                val embeddedAss = format?.initializationData?.getOrNull(1)?.isNotEmpty() == true
                if (embeddedAss && pending && input.timeUs > targetUs) {
                    input.clear()
                    pending = false
                }
                inputEnded = false
                outputEnded = false
            } else if (decoder != null && (format?.sampleMimeType == MimeTypes.TEXT_SSA || bitmapCovered)) {
                // Retain only covered bitmap time ranges; skipped palette/clear packets require a reset.
                if (pending) decodePendingSample()
                decoder?.invalidateOutput()
                input.clear()
                pending = false
                inputEnded = false
                outputEnded = false
            } else {
                resetDecoder()
            }
            updateOutput(emptyList(), positionUs)
        }

        fun invalidateOutput() { decoder?.invalidateOutput() }

        private fun resetDecoder() {
            // A detached initializer owns its eventual result; never block playback to cancel it.
            initialization?.abandon()
            initialization = null
            initializationFailed = false
            decoder?.close()
            decoder = null
            lastRenderedPositionUs = C.TIME_UNSET
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
                            if (next.sampleMimeType != format?.sampleMimeType || next.initializationData != format?.initializationData ||
                                next.metadata != format?.metadata) {
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
                    decodePendingSample()
                }
                if (pending || inputEnded) break
            }
            decoder?.let {
                it.render(positionUs, width, height)?.let { cues -> updateOutput(cues, positionUs) }
                outputEnded = inputEnded && !pending && positionUs >= it.endTimeUs()
            } ?: run { outputEnded = inputEnded }
            lastRenderedPositionUs = positionUs
        }

        private fun decodePendingSample() {
            val currentFormat = checkNotNull(format)
            if (initializationFailed) {
                pending = false
                return
            }
            if (decoder == null) {
                val task = initialization ?: DecoderInitialization {
                    try {
                        createDecoder(currentFormat)
                    } catch (e: Throwable) {
                        Log.e(name, "Unable to initialize ${currentFormat.sampleMimeType} subtitles", e)
                        null
                    }
                }.also { initialization = it; INITIALIZER.execute(it) }
                if (!task.complete) return
                decoder = task.take()
                initialization = null
                if (decoder == null) {
                    initializationFailed = true
                    pending = false
                    return
                }
                Log.i(name, "Initialized ${currentFormat.sampleMimeType}")
            }
            val currentDecoder = checkNotNull(decoder)
            val offset = if (currentFormat.subsampleOffsetUs == Format.OFFSET_SAMPLE_RELATIVE) input.timeUs
                else currentFormat.subsampleOffsetUs
            val data = checkNotNull(input.data)
            if (input.isEncrypted || data.remaining() > 16 * 1024 * 1024 || !currentDecoder.decode(data, input.timeUs, offset)) {
                Log.w(name, "Ignoring malformed subtitle sample")
            }
            pending = false
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
            when (messageType) {
                MSG_SET_VIDEO_OUTPUT_RESOLUTION -> {
                    val size = message as androidx.media3.common.util.Size
                    setViewportSize(size.width, size.height)
                }
                MSG_SET_VIEWPORT -> {
                    val size = message as android.util.Size
                    setViewportSize(size.width, size.height)
                }
                else -> super.handleMessage(messageType, message)
            }
        }

        private fun setViewportSize(width: Int, height: Int) {
            // Bare/detached surfaces report unknown/zero dimensions; retain the last usable canvas.
            if (width <= 0 || height <= 0) return
            val scale = minOf(1.0, 1920.0 / width, 1080.0 / height)
            this.width = maxOf(1, (width * scale).toInt())
            this.height = maxOf(1, (height * scale).toInt())
        }

        override fun onDisabled() {
            resetDecoder()
            updateOutput(emptyList(), streamOffsetUs)
            format = null
        }

        override fun onRelease() {
            resetDecoder()
            handler?.removeCallbacksAndMessages(null)
        }
    }

    internal class DecoderInitialization(val create: () -> FfmpegSubtitleDecoder?) : Runnable {
        @Volatile var complete = false
            private set
        private var abandoned = false
        private var result: FfmpegSubtitleDecoder? = null

        override fun run() {
            synchronized(this) { if (abandoned) return }
            val decoder = create()
            synchronized(this) {
                if (abandoned) decoder?.close() else result = decoder
                complete = true
            }
        }

        @Synchronized fun take(): FfmpegSubtitleDecoder? = result.also { result = null }

        @Synchronized fun abandon() {
            abandoned = true
            result?.close()
            result = null
        }
    }

    companion object {
        private val INITIALIZER = Executors.newSingleThreadExecutor { task ->
            Thread(task, "nextlib-subtitle-init").apply { isDaemon = true }
        }
        internal const val MSG_SET_VIEWPORT = 10_001
    }
}

/**
 * Supplies dimensions for bare Surfaces or custom renderer factories. [NextRenderersFactory]
 * automatically follows SurfaceView/TextureView size changes; its fallback canvas is 1280 x 720.
 */
@UnstableApi
fun ExoPlayer.setSubtitleViewportSize(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    val size = android.util.Size(width, height)
    for (i in 0 until rendererCount) {
        val renderer = getRenderer(i)
        if (renderer is FfmpegTextRenderer) createMessage(renderer).setType(FfmpegTextRenderer.MSG_SET_VIEWPORT).setPayload(size).send()
    }
}
