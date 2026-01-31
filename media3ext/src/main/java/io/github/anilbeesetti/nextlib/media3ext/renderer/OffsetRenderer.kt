package io.github.anilbeesetti.nextlib.media3ext.renderer

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Abstract base class for renderers that support time synchronization adjustments.
 *
 * This class provides functionality to adjust media timeline positions using both offset (delay)
 * and speed multiplier adjustments. These adjustments are commonly used for synchronizing
 * subtitles or audio tracks with video playback.
 *
 * ## Use Cases
 *
 * ### Sync Offset (Delay)
 * Use when content has a fixed time offset throughout playback:
 * - Positive offset: Content appears later (delayed)
 * - Negative offset: Content appears earlier (advanced)
 * - Example: Subtitles consistently appear 2 seconds too early → set offset to +2000ms
 *
 * ### Sync Speed Multiplier
 * Use when content gradually drifts out of sync over time:
 * - Multiplier > 1.0: Content progresses faster than video
 * - Multiplier < 1.0: Content progresses slower than video
 * - Example: Subtitles sync at start but drift 10s late by end → adjust multiplier to ~1.05x
 *
 * ## Calculation Order
 *
 * The adjusted position is calculated as:
 * ```
 * adjustedPosition = (originalPosition × speedMultiplier) - offset
 * ```
 *
 * Speed is applied first, then offset. This order ensures:
 * 1. Speed correction accumulates over time (for drift issues)
 * 2. Offset applies a constant shift (for fixed timing issues)
 *
 * @see syncOffsetMilliseconds
 * @see syncSpeedMultiplier
 * @see getOffsetAdjustedPositionUs
 */
open class OffsetRenderer {

    /**
     * The time offset to apply to the media position, in milliseconds.
     *
     * This value shifts the entire timeline forward or backward by a fixed amount.
     *
     * - **Positive values** delay the content (content appears later)
     * - **Negative values** advance the content (content appears earlier)
     * - **Zero** means no offset adjustment
     *
     * ### Examples
     * ```
     * // Subtitles appear 1.5 seconds too early
     * syncOffsetMilliseconds = 1500L  // Delay by 1.5s
     *
     * // Subtitles appear 0.8 seconds too late
     * syncOffsetMilliseconds = -800L  // Advance by 0.8s
     * ```
     *
     * ### How It Works
     * At video position 5000ms with offset +1000ms:
     * - Renderer looks for content at position (5000ms - 1000ms) = 4000ms
     * - Content meant for 4s now appears at 5s (delayed by 1s)
     *
     * @see getOffsetAdjustedPositionUs
     */
    var syncOffsetMilliseconds: Long = 0L

    /**
     * The speed multiplier to apply to the media position.
     *
     * This value scales the playback timeline, allowing content to progress faster or
     * slower than the video. This is useful for correcting gradual timing drift that
     * accumulates over the duration of playback.
     *
     * - **1.0** = normal speed (no adjustment, default)
     * - **> 1.0** = content progresses faster than video
     * - **< 1.0** = content progresses slower than video
     *
     * The value is automatically clamped to the range [0.1, 10.0] to prevent extreme
     * values that could cause rendering issues.
     *
     * ### Examples
     * ```
     * // Subtitles sync at start but are late by the end
     * syncSpeedMultiplier = 1.05f  // Run 5% faster
     *
     * // Subtitles sync at start but are early by the end
     * syncSpeedMultiplier = 0.95f  // Run 5% slower
     * ```
     *
     * ### How It Works
     * At video position 10,000ms (10s) with multiplier 1.1x:
     * - Renderer looks for content at position (10,000ms × 1.1) = 11,000ms
     * - Content meant for 11s appears at 10s (content running 10% faster)
     *
     * ### Common Causes of Speed Mismatch
     * - Framerate differences (23.976 fps vs 25 fps)
     * - Different video encoding standards (PAL vs NTSC)
     * - Subtitle files created for different video versions
     *
     * @see getOffsetAdjustedPositionUs
     */
    var syncSpeedMultiplier: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.1f, 10f)
        }

    /**
     * Calculates the adjusted media position after applying speed and offset adjustments.
     *
     * This method transforms the current playback position by first applying the speed
     * multiplier, then subtracting the offset. The result determines what content should
     * be rendered at the given playback position.
     *
     * ## Calculation
     * ```
     * adjustedPosition = (positionUs × syncSpeedMultiplier) - (syncOffsetMilliseconds × 1000)
     * ```
     *
     * The calculation is performed in this order:
     * 1. **Speed adjustment**: Scales the position to correct for drift
     * 2. **Offset adjustment**: Shifts the position by a fixed amount
     *
     * ## Examples
     *
     * ### Example 1: Offset Only
     * ```
     * syncOffsetMilliseconds = 2000L  // 2 second delay
     * syncSpeedMultiplier = 1.0f      // Normal speed
     *
     * getOffsetAdjustedPositionUs(5_000_000L)
     * // = (5,000,000 × 1.0) - (2000 × 1000)
     * // = 5,000,000 - 2,000,000
     * // = 3,000,000 (3 seconds)
     * // → Content at 3s will be shown at 5s
     * ```
     *
     * ### Example 2: Speed Only
     * ```
     * syncOffsetMilliseconds = 0L     // No offset
     * syncSpeedMultiplier = 1.1f      // 10% faster
     *
     * getOffsetAdjustedPositionUs(10_000_000L)
     * // = (10,000,000 × 1.1) - 0
     * // = 11,000,000 (11 seconds)
     * // → Content at 11s will be shown at 10s
     * ```
     *
     * ### Example 3: Combined Adjustment
     * ```
     * syncOffsetMilliseconds = 1000L  // 1 second delay
     * syncSpeedMultiplier = 1.05f     // 5% faster
     *
     * getOffsetAdjustedPositionUs(20_000_000L)
     * // = (20,000,000 × 1.05) - (1000 × 1000)
     * // = 21,000,000 - 1,000,000
     * // = 20,000,000 (20 seconds)
     * // → Content at 20s will be shown at 20s (adjustments cancel out at this point)
     * ```
     *
     * @param positionUs The current playback position in microseconds.
     * @return The adjusted position in microseconds, indicating which content should be
     *         rendered at the current playback time.
     *
     * @see syncOffsetMilliseconds
     * @see syncSpeedMultiplier
     */
    fun getOffsetAdjustedPositionUs(positionUs: Long): Long {
        val speedAdjustedPositionUs = (positionUs * syncSpeedMultiplier).toLong()
        return speedAdjustedPositionUs - (syncOffsetMilliseconds * 1000)
    }
}


/**
 * Helper function to retrieve an [OffsetRenderer] for a specific track type.
 *
 * This function searches through the player's renderers to find one that implements
 * [OffsetRenderer] and handles the specified track type.
 *
 * @param trackType The type of track to find (e.g., [C.TRACK_TYPE_TEXT], [C.TRACK_TYPE_AUDIO])
 * @return The [OffsetRenderer] for the specified track type, or `null` if not found
 */
@UnstableApi
internal fun ExoPlayer.getOffsetRenderer(trackType: @C.TrackType Int): OffsetRenderer? {
    for (i in 0 until rendererCount) {
        val rendererType = getRendererType(i)
        if (rendererType == trackType) {
            return getRenderer(i) as? OffsetRenderer
        }
    }
    return null
}