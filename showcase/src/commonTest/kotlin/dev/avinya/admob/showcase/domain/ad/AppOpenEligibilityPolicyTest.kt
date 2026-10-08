package dev.avinya.admob.showcase.domain.ad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class AppOpenEligibilityPolicyTest {

    private val policy = AppOpenEligibilityPolicy()

    private fun eligibleSnapshot(
        onboardingComplete: Boolean = true,
        onSensitiveRoute: Boolean = false,
        fullScreenAdShowing: Boolean = false,
        sdkReady: Boolean = true,
        canRequestAds: Boolean = true,
        backgroundDuration: kotlin.time.Duration = 10.seconds,
        minimumBackgroundDuration: kotlin.time.Duration = 4.seconds,
        onScreenWithAds: Boolean = false,
    ) = AppOpenEligibilitySnapshot(
        onboardingComplete = onboardingComplete,
        onSensitiveRoute = onSensitiveRoute,
        fullScreenAdShowing = fullScreenAdShowing,
        sdkReady = sdkReady,
        canRequestAds = canRequestAds,
        backgroundDuration = backgroundDuration,
        minimumBackgroundDuration = minimumBackgroundDuration,
        onScreenWithAds = onScreenWithAds,
    )

    @Test
    fun fullyEligibleContext_shows() {
        assertEquals(AppOpenDecision.Show, policy.isEligible(eligibleSnapshot()))
    }

    @Test
    fun firstSessionAndSensitiveFlows_areNeverEligibleForAppOpen() {
        assertIs<AppOpenDecision.Suppress>(
            policy.isEligible(eligibleSnapshot(onboardingComplete = false)),
        )
        assertIs<AppOpenDecision.Suppress>(
            policy.isEligible(eligibleSnapshot(onSensitiveRoute = true)),
        )
    }

    @Test
    fun firstSessionSuppressionIsAttributedToFirstSession() {
        assertEquals(
            AppOpenSuppressionReason.FirstSession,
            (policy.isEligible(eligibleSnapshot(onboardingComplete = false)) as AppOpenDecision.Suppress).reason,
        )
    }

    @Test
    fun sensitiveRouteSuppressionIsAttributedToTheRoute() {
        assertEquals(
            AppOpenSuppressionReason.SensitiveRoute,
            (policy.isEligible(eligibleSnapshot(onSensitiveRoute = true)) as AppOpenDecision.Suppress).reason,
        )
    }

    @Test
    fun fullScreenAdShowing_suppresses() {
        assertEquals(
            AppOpenSuppressionReason.FullScreenAdShowing,
            (policy.isEligible(eligibleSnapshot(fullScreenAdShowing = true)) as AppOpenDecision.Suppress).reason,
        )
    }

    @Test
    fun uninitializedSdk_suppresses() {
        assertEquals(
            AppOpenSuppressionReason.SdkNotReady,
            (policy.isEligible(eligibleSnapshot(sdkReady = false)) as AppOpenDecision.Suppress).reason,
        )
    }

    @Test
    fun consentMissing_suppresses() {
        assertEquals(
            AppOpenSuppressionReason.ConsentMissing,
            (policy.isEligible(eligibleSnapshot(canRequestAds = false)) as AppOpenDecision.Suppress).reason,
        )
    }

    @Test
    fun shortBackground_suppresses() {
        assertEquals(
            AppOpenSuppressionReason.BackgroundTooShort,
            (policy.isEligible(
                eligibleSnapshot(backgroundDuration = 2.seconds, minimumBackgroundDuration = 4.seconds),
            ) as AppOpenDecision.Suppress).reason,
        )
    }

    @Test
    fun exactlyMinimumBackground_isEligible() {
        assertEquals(
            AppOpenDecision.Show,
            policy.isEligible(
                eligibleSnapshot(backgroundDuration = 4.seconds, minimumBackgroundDuration = 4.seconds),
            ),
        )
    }

    @Test
    fun aScreenShowingAds_suppressesWithAdsOnScreen() {
        assertEquals(
            AppOpenDecision.Suppress(AppOpenSuppressionReason.AdsOnScreen),
            policy.isEligible(eligibleSnapshot(onScreenWithAds = true)),
        )
    }

    @Test
    fun firstSessionAndSensitiveRoute_outrankAdsOnScreen() {
        assertEquals(
            AppOpenDecision.Suppress(AppOpenSuppressionReason.FirstSession),
            policy.isEligible(eligibleSnapshot(onboardingComplete = false, onScreenWithAds = true)),
        )
        assertEquals(
            AppOpenDecision.Suppress(AppOpenSuppressionReason.SensitiveRoute),
            policy.isEligible(eligibleSnapshot(onSensitiveRoute = true, onScreenWithAds = true)),
        )
    }

    @Test
    fun adsOnScreen_outranksOnlyBackgroundTooShort() {
        assertEquals(
            AppOpenDecision.Suppress(AppOpenSuppressionReason.AdsOnScreen),
            policy.isEligible(eligibleSnapshot(onScreenWithAds = true, backgroundDuration = 1.seconds)),
        )
    }

    @Test
    fun fullScreenSdkAndConsentReasons_outrankAdsOnScreen() {
        // Diagnostics must name the more fundamental blocker: on the default tab the ads-on-screen
        // reason would otherwise hide a missing consent or an uninitialized SDK.
        assertEquals(
            AppOpenDecision.Suppress(AppOpenSuppressionReason.FullScreenAdShowing),
            policy.isEligible(eligibleSnapshot(onScreenWithAds = true, fullScreenAdShowing = true)),
        )
        assertEquals(
            AppOpenDecision.Suppress(AppOpenSuppressionReason.SdkNotReady),
            policy.isEligible(eligibleSnapshot(onScreenWithAds = true, sdkReady = false)),
        )
        assertEquals(
            AppOpenDecision.Suppress(AppOpenSuppressionReason.ConsentMissing),
            policy.isEligible(eligibleSnapshot(onScreenWithAds = true, canRequestAds = false)),
        )
    }
}
