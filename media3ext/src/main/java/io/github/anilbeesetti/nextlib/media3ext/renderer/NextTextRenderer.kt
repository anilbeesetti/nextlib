package io.github.anilbeesetti.nextlib.media3ext.renderer

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.text.SubtitleDecoderFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer

@UnstableApi
class NextTextRenderer(
    output: TextOutput,
    outputLooper: Looper?,
    decoderFactory: SubtitleDecoderFactory = SubtitleDecoderFactory.DEFAULT,
    val delegate: TextRenderer = TextRenderer(output, outputLooper, decoderFactory)
): Renderer by delegate, OffsetRenderer() {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val finalPositionUs = getOffsetAdjustedPositionUs(positionUs)
        delegate.render(finalPositionUs, elapsedRealtimeUs)
    }

    override fun release() {
        delegate.release()
    }
}

/**
 * Extension property to get or set the subtitle delay (offset) in milliseconds.
 *
 * This property provides convenient access to the subtitle renderer's sync offset,
 * allowing you to shift subtitle timing forward or backward to synchronize with video.
 *
 * ## Behavior
 *
 * - **Positive values**: Delay subtitles (appear later than original timing)
 * - **Negative values**: Advance subtitles (appear earlier than original timing)
 * - **Zero**: No offset (default, subtitles use original timing)
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Subtitles appear 2 seconds too early - delay them
 * player.subtitleDelayMilliseconds = 2000L
 *
 * // Subtitles appear 1.5 seconds too late - advance them
 * player.subtitleDelayMilliseconds = -1500L
 *
 * // Reset to original timing
 * player.subtitleDelayMilliseconds = 0L
 *
 * // Check current delay
 * val currentDelay = player.subtitleDelayMilliseconds
 * ```
 *
 * ## How It Works
 *
 * When you set a delay of +2000ms:
 * - At video position 5 seconds, the renderer looks for subtitles at 3 seconds
 * - Subtitles meant for the 3-second mark appear at 5 seconds
 * - Result: All subtitles are delayed by 2 seconds
 *
 * ## Notes
 *
 * - Returns `0L` if no text renderer is available
 * - Setting a value has no effect if no text renderer is available
 * - Changes take effect immediately on the next render cycle
 * - This adjustment is independent of [subtitleSpeed]
 *
 * @see subtitleSpeed For correcting gradual timing drift
 * @see OffsetRenderer.syncOffsetMilliseconds The underlying property being accessed
 */
@get:UnstableApi
@set:UnstableApi
var ExoPlayer.subtitleDelayMilliseconds: Long
    get() {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return 0L
        return textRenderer.syncOffsetMilliseconds
    }
    set(value) {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return
        textRenderer.syncOffsetMilliseconds = value
    }

/**
 * Extension property to get or set the subtitle speed multiplier.
 *
 * This property provides convenient access to the subtitle renderer's sync speed multiplier,
 * allowing you to adjust how fast subtitles progress relative to video playback. This is
 * particularly useful for correcting gradual timing drift that accumulates over the duration
 * of a video.
 *
 * ## Behavior
 *
 * - **1.0**: Normal speed, no adjustment (default)
 * - **> 1.0**: Subtitles progress faster than video (catch up over time)
 * - **< 1.0**: Subtitles progress slower than video (fall behind over time)
 * - **Range**: Automatically clamped to [0.1, 10.0] by the underlying renderer
 *
 * ## Usage Examples
 *
 * ```kotlin
 * // Subtitles sync at start but are 10 seconds late by end of 60-min video
 * // Speed them up by ~0.3% to catch up gradually
 * player.subtitleSpeed = 1.003f
 *
 * // Subtitles sync at start but drift 5 seconds early by end
 * // Slow them down by 5%
 * player.subtitleSpeed = 0.95f
 *
 * // Reset to normal speed
 * player.subtitleSpeed = 1.0f
 *
 * // Check current speed
 * val currentSpeed = player.subtitleSpeed
 * ```
 *
 * ## How It Works
 *
 * When you set speed to 1.1 (10% faster):
 * - At video position 10 seconds, the renderer looks for subtitles at 11 seconds
 * - Subtitles meant for the 11-second mark appear at 10 seconds
 * - Over time, subtitles gradually catch up to correct drift
 *
 * ## Common Use Cases
 *
 * ### Framerate Mismatch
 * Subtitle files created for different framerates (23.976 fps vs 25 fps) will drift:
 * ```kotlin
 * // Subtitles for 23.976fps playing on 25fps video
 * player.subtitleSpeed = 25.0f / 23.976f  // ≈ 1.043
 * ```
 *
 * ### PAL Speedup
 * Content converted from 24fps to 25fps PAL:
 * ```kotlin
 * player.subtitleSpeed = 25.0f / 24.0f  // ≈ 1.042
 * ```
 *
 * ### Calculating Required Speed
 * If subtitles are X seconds off after Y minutes of video:
 * ```kotlin
 * val driftSeconds = 10.0  // 10 seconds off
 * val videoDurationSeconds = 3600.0  // 60 minutes
 * val requiredSpeed = 1.0f + (driftSeconds / videoDurationSeconds)
 * // If late: use positive, if early: use 1.0 - (drift/duration)
 * player.subtitleSpeed = requiredSpeed
 * ```
 *
 * ## Notes
 *
 * - Returns `1.0f` if no text renderer is available
 * - Setting a value has no effect if no text renderer is available
 * - Changes take effect immediately on the next render cycle
 * - This adjustment is independent of [subtitleDelayMilliseconds]
 * - Speed adjustment is applied **before** delay offset in the calculation
 *
 * ## Combining with Delay
 *
 * For complex synchronization issues, you can use both:
 * ```kotlin
 * // Subtitles start 2s early AND drift 5s more by the end
 * player.subtitleDelayMilliseconds = 2000L  // Fix initial offset
 * player.subtitleSpeed = 1.002f             // Fix gradual drift
 * ```
 *
 * @see subtitleDelayMilliseconds For fixed time offset adjustments
 * @see OffsetRenderer.syncSpeedMultiplier The underlying property being accessed
 */
@get:UnstableApi
@set:UnstableApi
var ExoPlayer.subtitleSpeed: Float
    get() {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return 1.0f
        return textRenderer.syncSpeedMultiplier
    }
    set(value) {
        val textRenderer = getOffsetRenderer(C.TRACK_TYPE_TEXT) ?: return
        textRenderer.syncSpeedMultiplier = value
    }