package dev.avinya.ads

import dev.avinya.ads.internal.LaunchConsentUpdater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchConsentUpdateTest {

    private fun config() = AdConfig(
        androidAppId = "ca-app-pub-A",
        iosAppId = "ca-app-pub-A",
    )

    @Test
    fun `InitializeOnlyIfAlreadyAllowed reuses the launch update through the launch capability`() = runSlotTest {
        val consent = CountingLaunchConsentController()
        val manager = FakeGoogleAdManager(consent = consent)

        val status = manager.initialize(config(), ConsentMode.InitializeOnlyIfAlreadyAllowed)

        assertEquals(AdManagerStatus.Ready, status)
        assertEquals(1, consent.ensureCalls, "the launch capability must be asked exactly once")
        assertEquals(0, consent.requestUpdateCalls, "the capability replaces the plain info update, it does not add one")
        assertEquals(0, consent.gatherCalls, "InitializeOnlyIfAlreadyAllowed never gathers consent")
    }

    @Test
    fun `InitializeOnlyIfAlreadyAllowed falls back to a plain info update without the capability`() = runSlotTest {
        val consent = CountingPlainConsentController()
        val manager = FakeGoogleAdManager(consent = consent)

        val status = manager.initialize(config(), ConsentMode.InitializeOnlyIfAlreadyAllowed)

        assertEquals(AdManagerStatus.Ready, status)
        assertEquals(1, consent.requestUpdateCalls, "a controller without the capability gets one plain info update")
        assertEquals(0, consent.gatherCalls, "InitializeOnlyIfAlreadyAllowed never gathers consent")
    }

    @Test
    fun `GatherBeforeInitialize gathers consent and never uses the launch capability`() = runSlotTest {
        val consent = CountingLaunchConsentController()
        val manager = FakeGoogleAdManager(consent = consent)

        val status = manager.initialize(config(), ConsentMode.GatherBeforeInitialize)

        assertEquals(AdManagerStatus.Ready, status)
        assertEquals(1, consent.gatherCalls, "GatherBeforeInitialize must gather consent")
        assertEquals(0, consent.ensureCalls, "gathering already updates UMP; the launch capability must not run")
        assertEquals(0, consent.requestUpdateCalls, "gatherConsent replaces the plain info update")
    }

    @Test
    fun `SkipConsent touches no consent entry point`() = runSlotTest {
        val consent = CountingLaunchConsentController()
        val manager = FakeGoogleAdManager(consent = consent)

        val status = manager.initialize(config(), ConsentMode.SkipConsent)

        assertEquals(AdManagerStatus.Ready, status)
        assertEquals(0, consent.gatherCalls, "SkipConsent must not gather consent")
        assertEquals(0, consent.requestUpdateCalls, "SkipConsent must not request an info update")
        assertEquals(0, consent.ensureCalls, "SkipConsent must not run the launch capability")
    }

    @Test
    fun `an applied manager does not rerun consent on an equivalent InitializeOnlyIfAlreadyAllowed call`() = runSlotTest {
        val consent = CountingLaunchConsentController()
        val manager = FakeGoogleAdManager(consent = consent)

        val first = manager.initialize(config(), ConsentMode.InitializeOnlyIfAlreadyAllowed)
        assertEquals(AdManagerStatus.Ready, first)

        val second = manager.initialize(config(), ConsentMode.InitializeOnlyIfAlreadyAllowed)

        assertEquals(AdManagerStatus.Ready, second)
        assertEquals(1, consent.ensureCalls, "a Ready manager must not run the launch update a second time")
    }

    @Test
    fun `InitializeOnlyIfAlreadyAllowed reports ConsentRequired when the launch update says ads may not be requested`() =
        runSlotTest {
            val consent = CountingLaunchConsentController()
            consent.canRequestAdsFlow.value = false
            val manager = FakeGoogleAdManager(consent = consent)

            val status = manager.initialize(config(), ConsentMode.InitializeOnlyIfAlreadyAllowed)

            assertEquals(AdManagerStatus.ConsentRequired, status)
            assertEquals(1, consent.ensureCalls, "the gate must read the state after the launch capability ran")
        }
}

/** Implements the launch capability; counts every consent entry point it is driven through. */
private class CountingLaunchConsentController : ConsentController, LaunchConsentUpdater {
    var gatherCalls = 0
    var requestUpdateCalls = 0
    var ensureCalls = 0

    val canRequestAdsFlow = MutableStateFlow(true)
    override val canRequestAds: StateFlow<Boolean> = canRequestAdsFlow

    override val status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    override val privacyOptionsRequirementStatus =
        MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus {
        requestUpdateCalls++
        return status.value
    }

    override suspend fun gatherConsent(config: AdConfig): ConsentStatus {
        gatherCalls++
        return status.value
    }

    override suspend fun ensureUpdatedThisLaunch(config: AdConfig): ConsentStatus {
        ensureCalls++
        return status.value
    }

    override suspend fun showPrivacyOptions(): Boolean = false

    override suspend fun resetConsentForDebug(): Boolean = false
}

/** Same counters as [CountingLaunchConsentController], but without the launch capability. */
private class CountingPlainConsentController : ConsentController {
    var gatherCalls = 0
    var requestUpdateCalls = 0

    val canRequestAdsFlow = MutableStateFlow(true)
    override val canRequestAds: StateFlow<Boolean> = canRequestAdsFlow

    override val status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    override val privacyOptionsRequirementStatus =
        MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus {
        requestUpdateCalls++
        return status.value
    }

    override suspend fun gatherConsent(config: AdConfig): ConsentStatus {
        gatherCalls++
        return status.value
    }

    override suspend fun showPrivacyOptions(): Boolean = false

    override suspend fun resetConsentForDebug(): Boolean = false
}
