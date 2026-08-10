package dev.avinya.admob.showcase.domain.ad

import kotlin.time.Duration

/**
 * Everything the app-open eligibility rules need, with nothing attached.
 *
 * Time arrives pre-computed (like [AdPolicySnapshot]); the policy has no clock
 * of its own, which keeps every boundary testable by comparing two values.
 */
data class AppOpenEligibilitySnapshot(
    val onboardingComplete: Boolean,
    val onSensitiveRoute: Boolean,
    val fullScreenAdShowing: Boolean,
    val sdkReady: Boolean,
    val canRequestAds: Boolean,
    val backgroundDuration: Duration,
    val minimumBackgroundDuration: Duration,
)

/** Why an app-open ad was not shown. Rendered by the Diagnostics Lab. */
enum class AppOpenSuppressionReason {
    FirstSession,
    SensitiveRoute,
    FullScreenAdShowing,
    SdkNotReady,
    ConsentMissing,
    BackgroundTooShort,
}

/** The outcome of asking [AppOpenEligibilityPolicy]. */
sealed interface AppOpenDecision {
    data object Show : AppOpenDecision
    data class Suppress(val reason: AppOpenSuppressionReason) : AppOpenDecision
}

/**
 * When a coordinator-driven app-open ad may interrupt the user.
 *
 * Pure and platform-free. The product rules are:
 *
 * 1. Never during onboarding — a fresh install's first session is exempt.
 * 2. Never over a sensitive route (privacy flows) or another full-screen ad.
 * 3. Never before the SDK is initialized and consent allows requests.
 * 4. Only after the app has been backgrounded for at least
 *    [minimumBackgroundDuration] — a foreground transition that was never a
 *    real backgrounding must not show an ad.
 */
class AppOpenEligibilityPolicy {

    fun isEligible(snapshot: AppOpenEligibilitySnapshot): AppOpenDecision = when {
        // Onboarding has not completed: this is (at least) the first session.
        // The policy refuses app-open ads during the first product session.
        !snapshot.onboardingComplete -> AppOpenDecision.Suppress(AppOpenSuppressionReason.FirstSession)
        snapshot.onSensitiveRoute -> AppOpenDecision.Suppress(AppOpenSuppressionReason.SensitiveRoute)
        snapshot.fullScreenAdShowing -> AppOpenDecision.Suppress(AppOpenSuppressionReason.FullScreenAdShowing)
        !snapshot.sdkReady -> AppOpenDecision.Suppress(AppOpenSuppressionReason.SdkNotReady)
        !snapshot.canRequestAds -> AppOpenDecision.Suppress(AppOpenSuppressionReason.ConsentMissing)
        snapshot.backgroundDuration < snapshot.minimumBackgroundDuration ->
            AppOpenDecision.Suppress(AppOpenSuppressionReason.BackgroundTooShort)
        else -> AppOpenDecision.Show
    }
}
