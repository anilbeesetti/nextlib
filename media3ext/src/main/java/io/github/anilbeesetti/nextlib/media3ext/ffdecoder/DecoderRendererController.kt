package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

@UnstableApi
internal class DecoderRendererController(
    initialVideoMode: DecoderMode?,
    initialAudioMode: DecoderMode?,
) {
    @Volatile
    var videoMode: DecoderMode? = initialVideoMode
        private set

    @Volatile
    var audioMode: DecoderMode? = initialAudioMode
        private set

    val supportsDecoderSwitching: Boolean
        get() = DecoderRendererRole.entries.all { role ->
            managedRenderers.any { renderer -> renderer.role == role }
        }

    fun setMode(trackType: DecoderTrackType, mode: DecoderMode?) {
        when (trackType) {
            DecoderTrackType.VIDEO -> videoMode = mode
            DecoderTrackType.AUDIO -> audioMode = mode
        }
    }

    fun mode(trackType: DecoderTrackType): DecoderMode? {
        return when (trackType) {
            DecoderTrackType.VIDEO -> videoMode
            DecoderTrackType.AUDIO -> audioMode
        }
    }

    fun wrapRenderers(renderers: Array<Renderer>): Array<Renderer> {
        check(managedRenderers.isEmpty()) { "NextRenderersFactory can only create one renderer set" }
        return renderers.mapIndexed { index, renderer ->
            val role = renderer.decoderRole() ?: return@mapIndexed renderer
            managedRenderers += ManagedRenderer(index, role)
            ModeAwareRenderer(renderer) { role.isEnabled(videoMode, audioMode) }
        }.toTypedArray()
    }

    fun apply(trackSelector: DefaultTrackSelector) {
        val parameters = trackSelector.buildUponParameters()
        managedRenderers.forEach { renderer ->
            parameters.setRendererDisabled(
                renderer.index,
                !renderer.role.isEnabled(videoMode, audioMode),
            )
        }
        trackSelector.setParameters(parameters)
    }

    private val managedRenderers = mutableListOf<ManagedRenderer>()
}

internal enum class DecoderTrackType {
    VIDEO,
    AUDIO,
}

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
            null -> decoderInfos.sortedBy(MediaCodecInfo::decoderPriority)
            DecoderMode.HARDWARE -> decoderInfos.filter(MediaCodecInfo::hardwareAccelerated)
            DecoderMode.SOFTWARE -> decoderInfos.filter(MediaCodecInfo::softwareOnly)
            DecoderMode.APP_SOFTWARE -> emptyList()
        }
    }
}

internal enum class DecoderRendererRole {
    SYSTEM_VIDEO,
    APP_SOFTWARE_VIDEO,
    SYSTEM_AUDIO,
    APP_SOFTWARE_AUDIO,
    ;

    fun isEnabled(videoMode: DecoderMode?, audioMode: DecoderMode?): Boolean {
        return when (this) {
            SYSTEM_VIDEO -> videoMode != DecoderMode.APP_SOFTWARE
            APP_SOFTWARE_VIDEO ->
                videoMode == null || videoMode == DecoderMode.APP_SOFTWARE
            SYSTEM_AUDIO -> audioMode != DecoderMode.APP_SOFTWARE
            APP_SOFTWARE_AUDIO ->
                audioMode == null || audioMode == DecoderMode.APP_SOFTWARE
        }
    }
}

private data class ManagedRenderer(
    val index: Int,
    val role: DecoderRendererRole,
)

@UnstableApi
private class ModeAwareRenderer(
    delegate: Renderer,
    isEnabled: () -> Boolean,
) : ForwardingRenderer(delegate) {
    private val modeAwareCapabilities = ModeAwareCapabilities(delegate.capabilities, isEnabled)

    override fun getCapabilities(): RendererCapabilities = modeAwareCapabilities
}

@UnstableApi
private class ModeAwareCapabilities(
    private val delegate: RendererCapabilities,
    private val isEnabled: () -> Boolean,
) : RendererCapabilities {
    override fun getName(): String = delegate.name

    override fun getTrackType(): Int = delegate.trackType

    override fun supportsFormat(format: Format): Int {
        if (!isEnabled()) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        return delegate.supportsFormat(format)
    }

    override fun supportsMixedMimeTypeAdaptation(): Int = delegate.supportsMixedMimeTypeAdaptation()

    override fun setListener(listener: RendererCapabilities.Listener) {
        delegate.setListener(listener)
    }

    override fun clearListener() {
        delegate.clearListener()
    }
}

@UnstableApi
private fun Renderer.decoderRole(): DecoderRendererRole? {
    return when (this) {
        is FfmpegVideoRenderer -> DecoderRendererRole.APP_SOFTWARE_VIDEO
        is FfmpegAudioRenderer -> DecoderRendererRole.APP_SOFTWARE_AUDIO
        else -> when (capabilities.trackType) {
            C.TRACK_TYPE_VIDEO -> DecoderRendererRole.SYSTEM_VIDEO
            C.TRACK_TYPE_AUDIO -> DecoderRendererRole.SYSTEM_AUDIO
            else -> null
        }
    }
}

@UnstableApi
private fun MediaCodecInfo.decoderPriority(): Int {
    return when {
        hardwareAccelerated -> 0
        softwareOnly -> 1
        else -> 2
    }
}
