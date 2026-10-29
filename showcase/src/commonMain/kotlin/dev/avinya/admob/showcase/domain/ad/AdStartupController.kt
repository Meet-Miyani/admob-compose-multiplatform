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
import kotlin.time.Duration

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

    /**
     * Starts the UMP-aware startup sequence.
     *
     * UMP must be asked on every launch: it presents a form only when required,
     * while one-time gathering can strand a user whose consent is now required.
     */
    fun ensureStarted() {
        val manager = boundManager ?: return
        if (_state.value.running || _state.value.phase == AdStartupPhase.Complete || startupJob?.isActive == true) return
        _state.update { it.copy(running = true) }
        startupJob = scope.launch {
            runStartup(manager, gather = true)
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
            val testDeviceIds = listOfNotNull(settings.consentTestDeviceId.first())
            val config = showcaseAdConfig(
                trackingHook = hook,
                debugGeography = debugGeography,
                testDeviceIds = testDeviceIds,
            )

            // Both are read by the SDK when the work they govern starts, so they must be set before
            // the consent call: the early start happens inside gatherConsent's UMP update, and the
            // adapter wait is read when native initialization begins.
            adManager.startFromStoredConsent = settings.startFromStoredConsent.first()
            adManager.adapterStartupWait =
                if (settings.waitForAdapters.first()) AdManager.DEFAULT_ADAPTER_STARTUP_WAIT else Duration.ZERO

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
