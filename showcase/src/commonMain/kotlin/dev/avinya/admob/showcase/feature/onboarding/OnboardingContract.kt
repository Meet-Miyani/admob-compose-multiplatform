package dev.avinya.admob.showcase.feature.onboarding

import dev.avinya.ads.AdTrackingAuthorization
import dev.avinya.admob.showcase.StartupState

/**
 * The initialisation steps, in the only order that is correct.
 *
 * Requesting ads before ATT resolves permanently forfeits the IDFA for those
 * requests, so consent must precede tracking, and tracking must precede the
 * first ad request. This is load-bearing, not cosmetic.
 */
enum class OnboardingStep {
    Consent,
    Tracking,
    Initializing,
    Done,
    Failed,
    ;

    companion object {
        /** The three steps the user actually progresses through. */
        fun orderedSteps(): List<OnboardingStep> = listOf(Consent, Tracking, Initializing)
    }
}

/**
 * Which panel of the onboarding flow is on screen.
 *
 * Three, deliberately: what this is, the ads choice, and the SDK doing the
 * work. The choice gets its own panel because burying a consent decision
 * under a spinner is how a sample teaches the wrong thing — the reader should
 * see the exchange stated plainly and press a button.
 */
enum class OnboardingPanel { Welcome, AdsChoice, Preparing }

/** How the tracking step renders. Android has no ATT and says so. */
enum class TrackingStepDisplay { Pending, Granted, Refused, NotApplicable }

/**
 * Android reports [AdTrackingAuthorization.NotApplicable]. That is shown
 * explicitly rather than hidden: a consumer reading this app needs to see
 * that ATT is an iOS-only concept, not be left wondering why a step vanished.
 */
fun trackingStepDisplay(status: AdTrackingAuthorization): TrackingStepDisplay = when (status) {
    AdTrackingAuthorization.NotApplicable -> TrackingStepDisplay.NotApplicable
    AdTrackingAuthorization.NotDetermined -> TrackingStepDisplay.Pending
    AdTrackingAuthorization.Authorized -> TrackingStepDisplay.Granted
    AdTrackingAuthorization.Denied, AdTrackingAuthorization.Restricted -> TrackingStepDisplay.Refused
}

data class OnboardingState(
    val panel: OnboardingPanel = OnboardingPanel.Welcome,
    val step: OnboardingStep = OnboardingStep.Consent,
    val tracking: TrackingStepDisplay = TrackingStepDisplay.Pending,
    val startup: StartupState = StartupState.Starting,
    val busy: Boolean = false,
)

sealed interface OnboardingIntent {
    /** Move from the welcome panel to the ads choice. */
    data object Continue : OnboardingIntent

    /**
     * Accept ads: runs the consent gather, the ATT prompt where applicable,
     * and SDK initialisation, in that order.
     */
    data object EnableAds : OnboardingIntent

    /** Decline ads: the app is fully usable without them, and says so. */
    data object ContinueWithoutAds : OnboardingIntent

    data object Retry : OnboardingIntent

    /** Leave once initialisation has settled. */
    data object Finish : OnboardingIntent
}

sealed interface OnboardingEffect {
    data object Finished : OnboardingEffect
}
