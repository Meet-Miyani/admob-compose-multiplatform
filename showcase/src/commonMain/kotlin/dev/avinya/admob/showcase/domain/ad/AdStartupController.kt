package dev.avinya.admob.showcase.domain.ad

import dev.avinya.ads.AdManager
import dev.avinya.ads.AdTrackingAuthorization
import dev.avinya.ads.ConsentDebugGeography
import dev.avinya.ads.ConsentMode
import dev.avinya.admob.showcase.StartupState
import dev.avinya.admob.showcase.TrackingAuthorizationHook
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.showcaseAdConfig
import dev.avinya.admob.showcase.toStartupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AdStartupPhase { Idle, Consent, Tracking, Initializing, Complete }

data class AdStartupSnapshot(
    val phase: AdStartupPhase = AdStartupPhase.Idle,
    val startup: StartupState = StartupState.Starting,
    val tracking: AdTrackingAuthorization = AdTrackingAuthorization.NotDetermined,
    val running: Boolean = false,
)

class AdStartupController(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AdStartupSnapshot())
    val state: StateFlow<AdStartupSnapshot> = _state.asStateFlow()

    private val mutex = Mutex()
    private var boundManager: AdManager? = null
    private var startupJob: Job? = null

    /**
     * Binds the manager.
     *
     * [autoStart] is false during first-run onboarding: consent gathering is
     * the thing the reader is about to be asked to authorise, so it must not
     * begin before they press the button.
     */
    fun attach(adManager: AdManager, autoStart: Boolean = true) {
        boundManager = adManager
        if (autoStart) ensureStarted()
    }

    /** Suspends until startup has settled, starting it if it has not begun. */
    suspend fun awaitComplete(): StartupState {
        ensureStarted()
        return state.first { it.phase == AdStartupPhase.Complete }.startup
    }

    fun ensureStarted() {
        val manager = boundManager ?: return
        if (_state.value.running || _state.value.phase == AdStartupPhase.Complete || startupJob?.isActive == true) return
        _state.update { it.copy(running = true) }
        startupJob = scope.launch {
            val isFirstRun = !settings.onboardingComplete.first()
            runStartup(manager, gather = isFirstRun)
        }
    }

    fun retry() {
        val manager = boundManager ?: return
        if (startupJob?.isActive == true) return
        _state.update { it.copy(running = true) }
        startupJob = scope.launch {
            runStartup(manager, gather = true)
        }
    }

    suspend fun retryAwaiting(): StartupState {
        val manager = boundManager ?: return StartupState.Failed("SDK not attached.", retryable = true)
        startupJob?.join()
        val job = scope.launch {
            runStartup(manager, gather = true)
        }.also { startupJob = it }
        job.join()
        return _state.value.startup
    }

    private suspend fun runStartup(adManager: AdManager, gather: Boolean) = mutex.withLock {
        _state.update {
            it.copy(
                phase = AdStartupPhase.Consent,
                running = true,
            )
        }

        try {
            val storedGeography = settings.consentDebugGeography.first()
            val debugGeography = ConsentDebugGeography.entries.firstOrNull { it.name == storedGeography }
                ?: ConsentDebugGeography.Disabled

            val hook = TrackingAuthorizationHook {
                _state.update { it.copy(phase = AdStartupPhase.Tracking) }
                val trackingResult = adManager.tracking.requestAuthorization()
                _state.update {
                    it.copy(
                        phase = AdStartupPhase.Initializing,
                        tracking = trackingResult,
                    )
                }
            }
            val config = showcaseAdConfig(trackingHook = hook, debugGeography = debugGeography)

            if (gather) {
                adManager.consent.gatherConsent(config)
            }

            _state.update {
                it.copy(
                    phase = AdStartupPhase.Initializing,
                    tracking = adManager.tracking.status(),
                )
            }

            val status = adManager.initialize(config, ConsentMode.InitializeOnlyIfAlreadyAllowed)
            val startup = status.toStartupState()

            _state.update {
                it.copy(
                    phase = AdStartupPhase.Complete,
                    startup = startup,
                    tracking = adManager.tracking.status(),
                    running = false,
                )
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(running = false) }
            throw e
        } catch (e: Throwable) {
            _state.update {
                it.copy(
                    phase = AdStartupPhase.Complete,
                    startup = StartupState.Failed(e.message ?: "Startup failed", retryable = true),
                    running = false,
                )
            }
        }
    }
}
