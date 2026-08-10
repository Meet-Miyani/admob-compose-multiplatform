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
    ) = AppOpenEligibilitySnapshot(
        onboardingComplete = onboardingComplete,
        onSensitiveRoute = onSensitiveRoute,
        fullScreenAdShowing = fullScreenAdShowing,
        sdkReady = sdkReady,
        canRequestAds = canRequestAds,
        backgroundDuration = backgroundDuration,
        minimumBackgroundDuration = minimumBackgroundDuration,
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
}
