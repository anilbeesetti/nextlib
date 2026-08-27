package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Changes the video and audio decoders created by [NextRenderersFactory].
 *
 * Selection keeps the same player, playlist, position, and `playWhenReady` value. Switching between
 * MediaCodec categories may stop and prepare that player so an active codec is released.
 */
@UnstableApi
class DecoderManager(
    initialVideoMode: DecoderMode? = null,
    initialAudioMode: DecoderMode? = null,
) {
    /** The video decoder mode currently initialized by the player. */
    @Volatile
    var videoMode: DecoderMode? = null
        private set

    /** The audio decoder mode currently initialized by the player. */
    @Volatile
    var audioMode: DecoderMode? = null
        private set

    internal val controller = DecoderRendererController(initialVideoMode, initialAudioMode)

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            videoMode = activeDecoderMode(
                selectedMode = controller.videoMode,
                decoderName = decoderName,
                mimeType = player?.currentTracks?.selectedMimeType(C.TRACK_TYPE_VIDEO),
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            audioMode = activeDecoderMode(
                selectedMode = controller.audioMode,
                decoderName = decoderName,
                mimeType = player?.currentTracks?.selectedMimeType(C.TRACK_TYPE_AUDIO),
            )
        }
    }

    /** Connects this manager to a player and its track selector. */
    fun attach(
        player: ExoPlayer,
        trackSelector: DefaultTrackSelector,
    ) {
        check(this.player == null) { "DecoderManager is already attached to a player" }
        check(controller.supportsDecoderSwitching) {
            "Set this DecoderManager on NextRenderersFactory before building the player"
        }

        this.player = player
        this.trackSelector = trackSelector
        player.addAnalyticsListener(analyticsListener)
        controller.apply(trackSelector)
    }

    /** Selects [mode] for video without changing audio. `null` enables automatic selection. */
    fun selectVideoDecoder(mode: DecoderMode?) {
        select(DecoderTrackType.VIDEO, mode)
    }

    /** Selects [mode] for audio without changing video. `null` enables automatic selection. */
    fun selectAudioDecoder(mode: DecoderMode?) {
        select(DecoderTrackType.AUDIO, mode)
    }

    /** Detaches the player and track selector. Calling this more than once is safe. */
    fun detach() {
        val attachedPlayer = player ?: return
        attachedPlayer.removeAnalyticsListener(analyticsListener)
        player = null
        trackSelector = null
        videoMode = null
        audioMode = null
    }

    private fun select(trackType: DecoderTrackType, mode: DecoderMode?) {
        val player = checkNotNull(player) { "Attach DecoderManager before selecting a decoder" }
        val trackSelector = checkNotNull(trackSelector)
        val previousMode = controller.mode(trackType)
        if (previousMode == mode) return

        when (trackType) {
            DecoderTrackType.VIDEO -> videoMode = null
            DecoderTrackType.AUDIO -> audioMode = null
        }
        controller.setMode(trackType, mode)
        if (previousMode.requiresMediaCodecRestart(mode)) {
            restartPlayer(player, trackSelector, controller)
        } else {
            controller.apply(trackSelector)
        }
    }

    private fun restartPlayer(
        player: ExoPlayer,
        trackSelector: DefaultTrackSelector,
        controller: DecoderRendererController,
    ) {
        val playWhenReady = player.playWhenReady
        val shouldPrepare = player.mediaItemCount > 0
        player.stop()
        controller.apply(trackSelector)
        if (!shouldPrepare) return

        player.prepare()
        player.playWhenReady = playWhenReady
    }

}

private val DecoderMode?.usesMediaCodec: Boolean
    get() = this != DecoderMode.APP_SOFTWARE

private fun DecoderMode?.requiresMediaCodecRestart(other: DecoderMode?): Boolean {
    return this != other && usesMediaCodec && other.usesMediaCodec
}

@UnstableApi
private fun activeDecoderMode(
    selectedMode: DecoderMode?,
    decoderName: String,
    mimeType: String?,
): DecoderMode {
    if (decoderName.contains("ffmpeg", ignoreCase = true)) return DecoderMode.APP_SOFTWARE
    if (selectedMode != null) return selectedMode

    val codecInfo = mimeType?.let {
        runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                /* mimeType = */ it,
                /* requiresSecureDecoder = */ decoderName.endsWith(".secure", ignoreCase = true),
                /* requiresTunnelingDecoder = */ false,
            )
        }.getOrNull()?.firstOrNull { info -> info.name == decoderName }
    }
    return if (codecInfo?.hardwareAccelerated == true) {
        DecoderMode.HARDWARE
    } else {
        DecoderMode.SOFTWARE
    }
}

@OptIn(UnstableApi::class)
private fun Tracks.selectedMimeType(trackType: Int): String? {
    return groups.firstOrNull { group -> group.type == trackType && group.isSelected }
        ?.mediaTrackGroup
        ?.getFormat(0)
        ?.sampleMimeType
}
