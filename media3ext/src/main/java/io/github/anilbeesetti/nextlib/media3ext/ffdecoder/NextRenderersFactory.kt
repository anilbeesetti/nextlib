package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.anilbeesetti.nextlib.media3ext.renderer.NextTextRenderer

/**
 * A [DefaultRenderersFactory] with nextlib's FFmpeg renderers and runtime decoder switching support.
 *
 * Create a [DecoderManager] separately and attach it
 * after building the player to change video or audio modes.
 * Native subtitles automatically follow video surface dimensions reported by ExoPlayer.
 */
@UnstableApi
open class NextRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private var decoderManager: DecoderManager? = null

    fun setDecoderManager(decoderManager: DecoderManager): NextRenderersFactory = this.apply {
        this.decoderManager = decoderManager
        this.setEnableDecoderFallback(true)
        this.setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> {
        val renderers = super.createRenderers(
            eventHandler,
            videoRendererEventListener,
            audioRendererEventListener,
            textRendererOutput,
            metadataRendererOutput,
        )
        val subtitles = renderers.filterIsInstance<FfmpegTextRenderer>()
        val delegates = renderers.map { renderer ->
            if (renderer.trackType != C.TRACK_TYPE_VIDEO || subtitles.isEmpty()) renderer else {
                object : ForwardingRenderer(renderer) {
                    override fun handleMessage(messageType: Int, message: Any?) {
                        super.handleMessage(messageType, message)
                        // ExoPlayer sends surface dimensions to video renderers on the playback thread.
                        if (messageType == MSG_SET_VIDEO_OUTPUT_RESOLUTION) {
                            subtitles.forEach { it.handleMessage(messageType, message) }
                        }
                    }
                }
            }
        }.toTypedArray()
        return decoderManager?.controller?.wrapRenderers(renderers, delegates) ?: delegates
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            decoderManager?.let { DecoderMediaCodecSelector(it.controller, mediaCodecSelector) }
                ?: mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return

        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            val renderer = FfmpegAudioRenderer(eventHandler, eventListener, audioSink)
            out.add(extensionRendererIndex++, renderer)
            Log.i(TAG, "Loaded FfmpegAudioRenderer.")
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw RuntimeException("Error instantiating Ffmpeg extension", e)
        }
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context,
            extensionRendererMode,
            decoderManager?.let { DecoderMediaCodecSelector(it.controller, mediaCodecSelector) }
                ?: mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out,
        )

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return

        var extensionRendererIndex = out.size
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--
        }

        try {
            val renderer = FfmpegVideoRenderer(
                /* allowedJoiningTimeMs = */ allowedVideoJoiningTimeMs,
                /* eventHandler = */ eventHandler,
                /* eventListener = */ eventListener,
                /* maxDroppedFramesToNotify = */ MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            )
            out.add(extensionRendererIndex++, renderer)
            Log.i(TAG, "Loaded FfmpegVideoRenderer.")
        } catch (e: Exception) {
            // The extension is present, but instantiation failed.
            throw RuntimeException("Error instantiating Ffmpeg extension", e)
        }
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>,
    ) {
        out.add(FfmpegTextRenderer(context, output, outputLooper))
        out.add(NextTextRenderer(output, outputLooper))
    }

    companion object {
        const val TAG = "NextRenderersFactory"
    }
}
