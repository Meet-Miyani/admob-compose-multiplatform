package dev.avinya.admob.showcase.feature.onboarding

import dev.avinya.admob.showcase.StartupState
import dev.avinya.admob.showcase.domain.ad.AdStartupPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingOutcomeTest {

    @Test
    fun `consent required and failed outcomes keep onboarding open`() {
        assertFalse(shouldFinishOnboarding(StartupState.ConsentRequired))
        assertFalse(shouldFinishOnboarding(StartupState.Failed("Network unavailable", retryable = true)))
    }

    @Test
    fun `ready and starting outcomes finish onboarding`() {
        assertTrue(shouldFinishOnboarding(StartupState.Ready))
        assertTrue(shouldFinishOnboarding(StartupState.Starting))
    }

    @Test
    fun `complete phase distinguishes consent required failed and ready outcomes`() {
        assertEquals(
            OnboardingStep.ConsentRequired,
            onboardingStepFor(AdStartupPhase.Complete, StartupState.ConsentRequired),
        )
        assertEquals(
            OnboardingStep.Failed,
            onboardingStepFor(
                AdStartupPhase.Complete,
                StartupState.Failed("Startup failed", retryable = true),
            ),
        )
        assertEquals(
            OnboardingStep.Done,
            onboardingStepFor(AdStartupPhase.Complete, StartupState.Ready),
        )
    }

    @Test
    fun `in progress phases retain their matching onboarding step`() {
        assertEquals(OnboardingStep.Consent, onboardingStepFor(AdStartupPhase.Idle, StartupState.Starting))
        assertEquals(OnboardingStep.Consent, onboardingStepFor(AdStartupPhase.Consent, StartupState.Starting))
        assertEquals(OnboardingStep.Tracking, onboardingStepFor(AdStartupPhase.Tracking, StartupState.Starting))
        assertEquals(
            OnboardingStep.Initializing,
            onboardingStepFor(AdStartupPhase.Initializing, StartupState.Starting),
        )
    }

    @Test
    fun `progress remains consent tracking and initializing only`() {
        assertEquals(
            listOf(OnboardingStep.Consent, OnboardingStep.Tracking, OnboardingStep.Initializing),
            OnboardingStep.orderedSteps(),
        )
    }
}
