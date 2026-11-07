package dev.avinya.ads.appopen

import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdShowResult
import kotlin.time.Duration

/**
 * One return to the foreground that an [AppOpenAdCoordinator] evaluated, and what it decided.
 *
 * Emitted on [AppOpenAdCoordinator.returns] exactly once per return. Never for the start-up
 * foreground — the one the platform reports as the app starts, with no background before it —
 * which is not a return.
 *
 * @property placementId the coordinator's app-open placement.
 * @property awayFor how long the app was in the background before this return.
 * @property outcome why no ad was shown, or how the show went.
 */
public data class AppOpenReturn(
    val placementId: String,
    val awayFor: Duration,
    val outcome: AppOpenReturnOutcome,
)

/**
 * What the coordinator decided for one return.
 *
 * New outcomes may be added in minor releases. Include an `else` branch when you switch on it.
 */
public sealed interface AppOpenReturnOutcome {
    /** Away for less than [AppOpenConfig.minBackgroundDuration]. */
    public data object AwayTooShort : AppOpenReturnOutcome

    /** The first return after an ad click, with [AppOpenAdCoordinator.skipAfterAdClick] on. */
    public data object ReturnedFromAdClick : AppOpenReturnOutcome

    /** [AppOpenAdCoordinator.isBlocked] was true. */
    public data object Blocked : AppOpenReturnOutcome

    /** The SDK was not [AdManagerStatus.Ready]; [status] is what it was instead. */
    public data class SdkNotReady(val status: AdManagerStatus) : AppOpenReturnOutcome

    /** [AppOpenConfig.cooldownBetweenShows] had not passed; [remaining] was still to go. */
    public data class CoolingDown(val remaining: Duration) : AppOpenReturnOutcome

    /** No app-open ad was loaded. The coordinator loads one for the next return. */
    public data object NoAdLoaded : AppOpenReturnOutcome

    /** Another full-screen ad was presenting, or a coordinator show was still in flight. */
    public data object AnotherAdShowing : AppOpenReturnOutcome

    /**
     * The coordinator asked the controller to show its ad. Emitted when that call returns, so
     * for an ad that was shown it comes after dismissal, once the outcome is final.
     */
    public data class ShowAttempted(val result: AdShowResult) : AppOpenReturnOutcome
}
