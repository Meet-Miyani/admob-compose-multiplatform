package dev.avinya.ads


/**
 * Display options for full-screen ads (interstitial, rewarded, app-open).
 *
 * @param immersiveMode **Android only.** Enable immersive mode for the ad;
 *   ignored on iOS.
 * @param serverSideVerification Server-side verification options for
 *   rewarded / rewarded-interstitial formats.
 * @param audioMuted Optional per-presentation override for ad audio mute state.
 *   When non-null, temporarily applies to the global SDK audio configuration for the duration
 *   of the presentation and restores on dismissal.
 * @param audioVolume Optional per-presentation override for ad audio volume (0.0f..1.0f).
 *   When non-null, temporarily applies to the global SDK audio configuration for the duration
 *   of the presentation and restores on dismissal.
 */
public data class FullScreenAdOptions(
    val immersiveMode: Boolean = false,
    val serverSideVerification: ServerSideVerificationOptions? = null,
    val audioMuted: Boolean? = null,
    val audioVolume: Float? = null
) {
    init {
        // coerceIn does NOT sanitise NaN -- it returns NaN -- so clamping downstream was never
        // enough to keep an invalid float out of the platform audio setter.
        audioVolume?.let {
            require(it.isFinite()) { "FullScreenAdOptions.audioVolume must be finite, was $it." }
            require(it in 0.0f..1.0f) {
                "FullScreenAdOptions.audioVolume must be within 0.0..1.0, was $it."
            }
        }
    }
}

/**
 * Server-side verification options for rewarded and rewarded-interstitial
 * ads. Provides [userId] and [customData] to the reward server callback
 * for reward validation.
 */
public data class ServerSideVerificationOptions(
    /** User identifier sent to the reward server. */
    val userId: String? = null,
    /** Custom data sent to the reward server. */
    val customData: String? = null
)

/**
 * Reward granted to the user after watching a rewarded ad.
 *
 * @property amountMicros the reward amount in millionths. Mediated rewards can be
 *   fractional (iOS GMA exposes `NSDecimalNumber`); the previous `Int` rounded `0.5` to
 *   `1` and granted double. Micros are exact and match how [AdValue.valueMicros] already
 *   models money.
 * @property type Reward type (e.g., "coins", "lives").
 */
public data class AdReward(val amountMicros: Long, val type: String) {
    /** The amount as a whole number, or null if it is fractional. */
    public fun wholeAmountOrNull(): Int? {
        if (amountMicros % 1_000_000L != 0L) return null
        val whole = amountMicros / 1_000_000L
        return whole.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
    }
}
