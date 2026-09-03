package dev.avinya.admob.showcase.feature.onboarding

import androidx.lifecycle.viewModelScope
import dev.avinya.admob.showcase.core.mvi.MviViewModel
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.domain.ad.AdStartupController
import kotlinx.coroutines.launch

/**
 * First-run flow.
 *
 * Nothing SDK-related happens until the reader presses a button. That is the
 * whole point of splitting the ads choice onto its own panel: consent gathering
 * and initialisation start from an explicit action, in the correct order
 * (consent → ATT → initialise → load), and the reader can decline without being
 * trapped.
 *
 * Declining is a first-class path, not an escape hatch. It flips the ads master
 * switch off and still initialises the SDK, so every screen renders exactly as
 * it would with ads, minus the ads.
 */
class OnboardingViewModel(
    private val startup: AdStartupController,
    private val settings: SettingsRepository,
) : MviViewModel<OnboardingState, OnboardingIntent, OnboardingEffect>(OnboardingState()) {

    private var finished = false

    init {
        viewModelScope.launch {
            startup.state.collect { snapshot ->
                updateState {
                    copy(
                        busy = snapshot.running,
                        step = onboardingStepFor(snapshot.phase, snapshot.startup),
                        startup = snapshot.startup,
                        tracking = trackingStepDisplay(snapshot.tracking),
                    )
                }

            }
        }
    }

    override fun onIntent(intent: OnboardingIntent) {
        when (intent) {
            OnboardingIntent.Continue -> updateState { copy(panel = OnboardingPanel.AdsChoice) }

            OnboardingIntent.EnableAds -> {
                updateState { copy(panel = OnboardingPanel.Preparing) }
                viewModelScope.launch {
                    settings.setAdsMasterSwitch(true)
                    // `awaitComplete` starts the gather-consent-then-initialise
                    // sequence and suspends until it settles. Awaiting rather
                    // than watching for the next emission matters: a warm
                    // process may already be Complete, and a StateFlow does not
                    // re-emit an unchanged value — which left this screen
                    // stuck on the spinner forever.
                    val result = startup.awaitComplete()
                    if (shouldFinishOnboarding(result)) finish()
                }
            }

            OnboardingIntent.ContinueWithoutAds -> {
                updateState { copy(panel = OnboardingPanel.Preparing) }
                viewModelScope.launch {
                    settings.setAdsMasterSwitch(false)
                    // Still initialise: the SDK's own state stays honest and
                    // the Lab remains usable for a developer who declined.
                    startup.ensureStarted()
                    finish()
                }
            }

            OnboardingIntent.Retry -> viewModelScope.launch {
                val result = startup.retryAwaiting()
                if (shouldFinishOnboarding(result)) finish()
            }

            OnboardingIntent.Finish -> finish()
        }
    }

    /**
     * Marks onboarding complete and leaves.
     *
     * A consent refusal or a non-retryable init failure must not trap the user
     * here — the app is fully usable ad-free.
     */
    private fun finish() {
        if (finished) return
        finished = true
        viewModelScope.launch {
            settings.setOnboardingComplete(true)
            emitEffect(OnboardingEffect.Finished)
        }
    }
}
