package dev.avinya.admob.showcase.feature.onboarding

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
import dev.avinya.admob.showcase.domain.ad.AdStartupController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `retrying from consent required to ready finishes and persists onboarding`() = runTest(dispatcher) {
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        val controller = AdStartupController(settings, this)
        val manager = ControllableAdManager(AdManagerStatus.ConsentRequired)
        controller.attach(manager)
        advanceUntilIdle()
        assertEquals(StartupState.ConsentRequired, controller.state.value.startup)

        val viewModel = OnboardingViewModel(controller, settings)
        advanceUntilIdle()
        manager.initResult = AdManagerStatus.Ready
        val finished = async { viewModel.effects.first() }

        viewModel.onIntent(OnboardingIntent.Retry)
        advanceUntilIdle()

        // This catches a fire-and-forget retry that reaches Ready but never
        // calls finish(), leaving the preparing panel without a terminal action.
        try {
            assertTrue(finished.isCompleted, "A successful retry must emit Finished")
            assertEquals(OnboardingEffect.Finished, finished.await())
            assertTrue(settings.onboardingComplete.first())
        } finally {
            finished.cancel()
        }
    }

    private class ControllableAdManager(
        var initResult: AdManagerStatus,
    ) : AdManager {
        private val mutableStatus = MutableStateFlow<AdManagerStatus>(AdManagerStatus.Disabled("Disabled"))
        override val status: StateFlow<AdManagerStatus> = mutableStatus.asStateFlow()
        override val events: SharedFlow<AdEvent> get() = NoOpAdManager.events
        override val diagnostics: AdDiagnostics get() = NoOpAdManager.diagnostics
        override val nativeAds: NativeAdManager get() = NoOpAdManager.nativeAds

        override val consent: ConsentController = object : ConsentController {
            override val status: StateFlow<ConsentStatus> = MutableStateFlow(ConsentStatus.Unknown)
            override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> =
                MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
            override val canRequestAds: StateFlow<Boolean> = MutableStateFlow(true)
            override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus = ConsentStatus.Obtained
            override suspend fun gatherConsent(config: AdConfig): ConsentStatus = ConsentStatus.Obtained
            override suspend fun showPrivacyOptions(): Boolean = true
            override suspend fun resetConsentForDebug(): Boolean = true
        }

        override val tracking: AdTrackingController = object : AdTrackingController {
            override fun status(): AdTrackingAuthorization = AdTrackingAuthorization.Authorized
            override suspend fun requestAuthorization(): AdTrackingAuthorization = AdTrackingAuthorization.Authorized
        }

        override suspend fun initialize(config: AdConfig, consentMode: ConsentMode): AdManagerStatus =
            initResult.also { mutableStatus.value = it }

        override fun banner(placement: AdPlacement): BannerAdController = NoOpAdManager.banner(placement)
        override fun interstitial(placement: AdPlacement): InterstitialAdController = NoOpAdManager.interstitial(placement)
        override fun rewarded(placement: AdPlacement): RewardedAdController = NoOpAdManager.rewarded(placement)
        override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController =
            NoOpAdManager.rewardedInterstitial(placement)
        override fun appOpen(placement: AdPlacement): AppOpenAdController = NoOpAdManager.appOpen(placement)
    }
}
