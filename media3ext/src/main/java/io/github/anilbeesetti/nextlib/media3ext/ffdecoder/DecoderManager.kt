package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

/**
 * Changes the video and audio decoders created by [NextRenderersFactory].
 *
 * Selection keeps the same player, playlist, position, and `playWhenReady` value. Switching between
 * MediaCodec categories may stop and prepare that player so an active codec is released.
 */
@UnstableApi
class DecoderManager(
    initialVideoMode: DecoderMode = DecoderMode.AUTO,
    initialAudioMode: DecoderMode = DecoderMode.AUTO,
) {
    /** The video decoder mode currently initialized by the player. */
    @Volatile
    var activeVideoMode: DecoderMode? = null
        private set

    /** The audio decoder mode currently initialized by the player. */
    @Volatile
    var activeAudioMode: DecoderMode? = null
        private set

    /** The requested video mode, including [DecoderMode.AUTO]. */
    val videoMode: DecoderMode get() = controller.videoMode

    /** The requested audio mode, including [DecoderMode.AUTO]. */
    val audioMode: DecoderMode get() = controller.audioMode

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
            activeVideoMode = controller.activeMode(decoderName)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            activeAudioMode = controller.activeMode(decoderName)
        }

        override fun onVideoDisabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters,
        ) {
            activeVideoMode = null
        }

        override fun onAudioDisabled(
            eventTime: AnalyticsListener.EventTime,
            decoderCounters: DecoderCounters,
        ) {
            activeAudioMode = null
        }
    }

    /** Connects this manager to a player and its track selector. */
    fun attach(player: ExoPlayer) {
        check(Looper.myLooper() == player.applicationLooper) { "Use the player's application thread" }
        val trackSelector = player.trackSelector as? DefaultTrackSelector
            ?: error("DecoderManager requires DefaultTrackSelector")
        check(this.player == null) { "DecoderManager is already attached to a player" }
        check(controller.owns(player)) {
            "Set this DecoderManager on NextRenderersFactory before building the player"
        }

        this.player = player
        this.trackSelector = trackSelector
        player.addAnalyticsListener(analyticsListener)
        controller.apply(trackSelector)
    }

    /** Selects [mode] for video without changing audio. [DecoderMode.AUTO] enables automatic selection. */
    fun selectVideoDecoder(mode: DecoderMode) {
        select(C.TRACK_TYPE_VIDEO, mode)
    }

    /** Selects [mode] for audio without changing video. [DecoderMode.AUTO] enables automatic selection. */
    fun selectAudioDecoder(mode: DecoderMode) {
        select(C.TRACK_TYPE_AUDIO, mode)
    }

    /** Detaches the player and track selector. Calling this more than once is safe. */
    fun detach() {
        val attachedPlayer = player ?: return
        check(Looper.myLooper() == attachedPlayer.applicationLooper) { "Use the player's application thread" }
        attachedPlayer.removeAnalyticsListener(analyticsListener)
        player = null
        trackSelector = null
        activeVideoMode = null
        activeAudioMode = null
    }

    private fun select(trackType: Int, mode: DecoderMode) {
        val player = checkNotNull(player) { "Attach DecoderManager before selecting a decoder" }
        check(Looper.myLooper() == player.applicationLooper) { "Use the player's application thread" }
        val trackSelector = checkNotNull(trackSelector)
        val previousMode = controller.mode(trackType)
        if (previousMode == mode) return

        if (trackType == C.TRACK_TYPE_VIDEO) controller.videoMode = mode else controller.audioMode = mode
        val restart = previousMode.requiresMediaCodecRestart(mode)
        val shouldPrepare = restart && player.playbackState != Player.STATE_IDLE
        if (restart) {
            activeVideoMode = null
            activeAudioMode = null
            player.stop()
        }
        controller.apply(trackSelector)
        if (shouldPrepare) player.prepare()
    }
}

internal fun DecoderMode.requiresMediaCodecRestart(other: DecoderMode): Boolean =
    this != other && this != DecoderMode.FFMPEG && other != DecoderMode.FFMPEG
