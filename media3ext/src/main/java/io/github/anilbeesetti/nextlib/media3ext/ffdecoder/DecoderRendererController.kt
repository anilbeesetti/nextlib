package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@UnstableApi
internal class DecoderRendererController(
    @Volatile var videoMode: DecoderMode,
    @Volatile var audioMode: DecoderMode,
) {
    private var renderers: Array<Renderer>? = null

    fun owns(player: ExoPlayer): Boolean = renderers?.let { renderers ->
        player.rendererCount == renderers.size &&
            renderers.indices.all { player.getRenderer(it) === renderers[it] }
    } ?: false

    fun mode(trackType: Int): DecoderMode =
        if (trackType == C.TRACK_TYPE_VIDEO) videoMode else audioMode

    fun wrapRenderers(renderers: Array<Renderer>): Array<Renderer> {
        check(this.renderers == null) { "NextRenderersFactory can only create one renderer set" }
        check(renderers.any { it is FfmpegVideoRenderer } && renderers.any { it is FfmpegAudioRenderer }) {
            "Runtime decoder switching requires FFmpeg audio and video renderers"
        }
        return renderers.map { renderer ->
            when (renderer.trackType) {
                C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO -> ModeAwareRenderer(renderer) {
                    mode(renderer.trackType).enables(renderer is FfmpegVideoRenderer || renderer is FfmpegAudioRenderer)
                }
                else -> renderer
            }
        }.toTypedArray().also { this.renderers = it }
    }

    fun apply(trackSelector: DefaultTrackSelector) {
        val parameters = trackSelector.buildUponParameters()
        renderers?.forEachIndexed { index, renderer ->
            if (renderer is ModeAwareRenderer) {
                parameters.setRendererDisabled(index, !renderer.isEnabled())
            }
        }
        trackSelector.setParameters(parameters)
    }
}

internal fun DecoderMode.enables(ffmpeg: Boolean): Boolean =
    this == DecoderMode.AUTO || (this == DecoderMode.FFMPEG) == ffmpeg

@UnstableApi
internal class DecoderMediaCodecSelector(
    private val controller: DecoderRendererController,
    private val delegate: MediaCodecSelector = MediaCodecSelector.DEFAULT,
) : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val decoderInfos = delegate.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        val mode = when {
            MimeTypes.isVideo(mimeType) -> controller.videoMode
            MimeTypes.isAudio(mimeType) -> controller.audioMode
            else -> return decoderInfos
        }
        return when (mode) {
            DecoderMode.AUTO -> decoderInfos.sortedBy(MediaCodecInfo::decoderPriority)
            DecoderMode.HARDWARE -> decoderInfos.filter(MediaCodecInfo::hardwareAccelerated)
            DecoderMode.SOFTWARE -> decoderInfos.filter(MediaCodecInfo::softwareOnly)
            DecoderMode.FFMPEG -> emptyList()
        }
    }
}

@UnstableApi
internal class ModeAwareRenderer(
    delegate: Renderer,
    val isEnabled: () -> Boolean,
) : ForwardingRenderer(delegate) {
    // MappingTrackSelector maps tracks before considering disabled renderer flags.
    private val modeAwareCapabilities = object : RendererCapabilities by delegate.capabilities {
        override fun supportsFormat(format: Format): Int =
            if (isEnabled()) delegate.capabilities.supportsFormat(format)
            else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
    }

    override fun getCapabilities(): RendererCapabilities = modeAwareCapabilities
}

@UnstableApi
private fun MediaCodecInfo.decoderPriority(): Int {
    return when {
        hardwareAccelerated -> 0
        softwareOnly -> 1
        else -> 2
    }
}
