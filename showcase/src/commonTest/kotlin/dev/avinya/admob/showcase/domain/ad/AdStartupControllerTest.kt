package dev.avinya.admob.showcase.domain.ad

import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdDiagnostics
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdTrackingAuthorization
import dev.avinya.ads.AdTrackingController
import dev.avinya.ads.AppOpenAdController
import dev.avinya.ads.BannerAdController
import dev.avinya.ads.ConsentController
import dev.avinya.ads.ConsentDebugGeography
import dev.avinya.ads.ConsentMode
import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.InterstitialAdController
import dev.avinya.ads.NoOpAdManager
import dev.avinya.ads.PrivacyOptionsRequirementStatus
import dev.avinya.ads.RewardedAdController
import dev.avinya.ads.RewardedInterstitialAdController
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.admob.showcase.StartupState
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.data.prefs.inMemoryPreferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AdStartupControllerTest {

    private open class FakeAdManager(
        var initResult: AdManagerStatus = AdManagerStatus.Ready,
        trackingController: AdTrackingController? = null,
    ) : AdManager {
        private val _status = MutableStateFlow<AdManagerStatus>(AdManagerStatus.Disabled("Disabled"))
        override val status: StateFlow<AdManagerStatus> = _status.asStateFlow()
        override val events: SharedFlow<AdEvent> get() = NoOpAdManager.events
        override val diagnostics: AdDiagnostics get() = NoOpAdManager.diagnostics
        override val nativeAds: NativeAdManager get() = NoOpAdManager.nativeAds

        var gatherConsentCalls = 0
        var initializeCalls = 0
        var lastConfig: AdConfig? = null
        var lastConsentMode: ConsentMode? = null
        val gatherConfigs = mutableListOf<AdConfig>()
        val initializeConfigs = mutableListOf<AdConfig>()

        override val consent: ConsentController = object : ConsentController {
            override val status: StateFlow<ConsentStatus> = MutableStateFlow(ConsentStatus.Unknown)
            override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> =
                MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
            override val canRequestAds: StateFlow<Boolean> = MutableStateFlow(true)
            override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus = ConsentStatus.Obtained
            override suspend fun gatherConsent(config: AdConfig): ConsentStatus {
                gatherConsentCalls++
                lastConfig = config
                gatherConfigs += config
                return ConsentStatus.Obtained
            }
            override suspend fun showPrivacyOptions(): Boolean = true
            override suspend fun resetConsentForDebug(): Boolean = true
        }

        override val tracking: AdTrackingController = trackingController ?: object : AdTrackingController {
            override fun status(): AdTrackingAuthorization = AdTrackingAuthorization.Authorized
            override suspend fun requestAuthorization(): AdTrackingAuthorization = AdTrackingAuthorization.Authorized
        }

        override suspend fun initialize(config: AdConfig, consentMode: ConsentMode): AdManagerStatus {
            initializeCalls++
            lastConfig = config
            initializeConfigs += config
            lastConsentMode = consentMode
            config.initializationHooks.forEach {
                it.onPhase(dev.avinya.ads.AdInitializationPhase.BeforeMobileAdsInitialize, config)
            }
            _status.value = initResult
            return initResult
        }

        override fun banner(placement: AdPlacement): BannerAdController = NoOpAdManager.banner(placement)
        override fun interstitial(placement: AdPlacement): InterstitialAdController = NoOpAdManager.interstitial(placement)
        override fun rewarded(placement: AdPlacement): RewardedAdController = NoOpAdManager.rewarded(placement)
        override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController = NoOpAdManager.rewardedInterstitial(placement)
        override fun appOpen(placement: AdPlacement): AppOpenAdController = NoOpAdManager.appOpen(placement)
    }

    @Test
    fun `first run gathers then initialises`() = runTest {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        val controller = AdStartupController(settings, this)
        val manager = FakeAdManager()

        controller.attach(manager)
        testScheduler.advanceUntilIdle()

        assertEquals(1, manager.gatherConsentCalls)
        assertEquals(1, manager.initializeCalls)
        assertEquals(ConsentMode.InitializeOnlyIfAlreadyAllowed, manager.lastConsentMode)
        assertEquals(AdStartupPhase.Complete, controller.state.value.phase)
        assertEquals(StartupState.Ready, controller.state.value.startup)
    }

    @Test
    fun `returning run gathers consent before initialising`() = runTest {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        settings.setOnboardingComplete(true)
        val controller = AdStartupController(settings, this)
        val manager = FakeAdManager()

        controller.attach(manager)
        testScheduler.advanceUntilIdle()

        // UMP decides whether a form is necessary; skipping the gather on a
        // later launch can strand a user whose consent has become required.
        assertEquals(1, manager.gatherConsentCalls)
        assertEquals(1, manager.initializeCalls)
        assertEquals(ConsentMode.InitializeOnlyIfAlreadyAllowed, manager.lastConsentMode)
        assertEquals(AdStartupPhase.Complete, controller.state.value.phase)
        assertEquals(StartupState.Ready, controller.state.value.startup)
    }

    @Test
    fun `persisted debug geography reaches the config`() = runTest {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        settings.setConsentDebugGeography(ConsentDebugGeography.Eea.name)
        val controller = AdStartupController(settings, this)
        val manager = FakeAdManager()

        controller.attach(manager)
        testScheduler.advanceUntilIdle()

        assertEquals(ConsentDebugGeography.Eea, manager.lastConfig?.debugOptions?.consentDebugGeography)
    }

    @Test
    fun `persisted test device id reaches gathering and initialization`() = runTest {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        val id = "1BFD804287B2C3AE94087F1138DDA00E"
        settings.setConsentTestDeviceId(id)
        val controller = AdStartupController(settings, this)
        val manager = FakeAdManager()

        controller.attach(manager)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(id), manager.gatherConfigs.single().testDeviceIds)
        assertEquals(listOf(id), manager.initializeConfigs.single().testDeviceIds)
    }

    @Test
    fun `double attach runs once`() = runTest {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        val controller = AdStartupController(settings, this)
        val manager = FakeAdManager()

        controller.attach(manager)
        controller.attach(manager)
        testScheduler.advanceUntilIdle()

        assertEquals(1, manager.initializeCalls)
    }

    @Test
    fun `retryAwaiting re-runs after ConsentRequired`() = runTest {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        settings.setOnboardingComplete(true)
        val controller = AdStartupController(settings, this)
        val manager = FakeAdManager(initResult = AdManagerStatus.ConsentRequired)

        controller.attach(manager)
        testScheduler.advanceUntilIdle()

        assertEquals(1, manager.gatherConsentCalls)
        assertEquals(1, manager.initializeCalls)
        assertEquals(StartupState.ConsentRequired, controller.state.value.startup)

        manager.initResult = AdManagerStatus.Ready
        val result = controller.retryAwaiting()

        assertEquals(2, manager.gatherConsentCalls)
        assertEquals(2, manager.initializeCalls)
        assertEquals(StartupState.Ready, result)
        assertEquals(StartupState.Ready, controller.state.value.startup)
    }

    @Test
    fun `tracking phase publishes before authorization resolves`() = runTest {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        val controller = AdStartupController(settings, this)

        var phaseDuringTrackingHook: AdStartupPhase? = null
        var currentStatus = AdTrackingAuthorization.NotDetermined
        val customTracking = object : AdTrackingController {
            override fun status(): AdTrackingAuthorization = currentStatus
            override suspend fun requestAuthorization(): AdTrackingAuthorization {
                phaseDuringTrackingHook = controller.state.value.phase
                currentStatus = AdTrackingAuthorization.Authorized
                return AdTrackingAuthorization.Authorized
            }
        }
        val manager = FakeAdManager(trackingController = customTracking)

        controller.attach(manager)
        testScheduler.advanceUntilIdle()

        assertEquals(AdStartupPhase.Tracking, phaseDuringTrackingHook)
        assertEquals(AdStartupPhase.Complete, controller.state.value.phase)
        assertEquals(AdTrackingAuthorization.Authorized, controller.state.value.tracking)
    }
}
