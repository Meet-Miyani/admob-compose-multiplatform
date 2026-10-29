package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

/**
 * Manager behaviour around `AdManager.startFromStoredConsent`: native initialization may start
 * from the answer UMP stored in an earlier session while the launch's info update is still in
 * flight, and must not start, or must not change the outcome, otherwise.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartFromStoredConsentTest {

    private fun config(hooks: List<AdInitializationHook> = emptyList()) = AdConfig(
        androidAppId = "ca-app-pub-A",
        iosAppId = "ca-app-pub-A",
        initializationHooks = hooks,
    )

    /**
     * Models the platform controller publishing the stored answer when the update is requested,
     * and the trigger firing for it. Only the real trigger is covered by StoredConsentStartTest.
     */
    private fun modelStoredConsent(
        consent: ScriptedConsentController,
        manager: FakeGoogleAdManager,
        storedAnswer: Boolean,
    ): (AdConfig) -> Unit = { cfg ->
        if (manager.startFromStoredConsent) {
            consent.canRequestMutable.value = storedAnswer
            if (storedAnswer) manager.onStoredConsentAllows(cfg)
        }
    }

    @Test
    fun `switch on with stored consent starts MobileAds while the info update is still in flight`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        manager.startFromStoredConsent = true
        consent.onRequested = modelStoredConsent(consent, manager, storedAnswer = true)
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.GatherBeforeInitialize) }

        runCurrent()

        assertEquals(1, manager.nativeHandoffs.size, "native initialization must already have started")
        assertEquals(AdManagerStatus.Ready, manager.status.value, "the stored start must publish Ready before the update returns")
        assertNull(result, "initialize must still be waiting on the in-flight update")

        consent.update.complete(true)
        runCurrent()

        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(1, manager.nativeHandoffs.size, "completing the update must not hand off to the native SDK again")
    }

    @Test
    fun `switch off does not start from stored consent and waits for the info update`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        consent.onRequested = modelStoredConsent(consent, manager, storedAnswer = true)
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.GatherBeforeInitialize) }

        runCurrent()

        assertEquals(0, manager.nativeHandoffs.size, "with the switch off nothing may start before the update completes")

        consent.update.complete(true)
        runCurrent()

        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(1, manager.nativeHandoffs.size, "the ordinary path must start native initialization after the update")
    }

    @Test
    fun `switch on with stored consent that does not allow ads waits for the info update`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        manager.startFromStoredConsent = true
        consent.onRequested = modelStoredConsent(consent, manager, storedAnswer = false)
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.GatherBeforeInitialize) }

        runCurrent()

        assertEquals(0, manager.nativeHandoffs.size, "a stored refusal must not start native initialization")

        consent.update.complete(true)
        runCurrent()

        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(1, manager.nativeHandoffs.size)
    }

    @Test
    fun `consent withdrawn by the update after an early start keeps the started SDK Ready`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        manager.startFromStoredConsent = true
        consent.onRequested = modelStoredConsent(consent, manager, storedAnswer = true)
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.GatherBeforeInitialize) }

        runCurrent()

        assertEquals(1, manager.nativeHandoffs.size, "native initialization must have started before the update completes")

        consent.update.complete(false)
        runCurrent()

        assertEquals(
            AdManagerStatus.Ready,
            result,
            "the SDK cannot be un-initialized, so the outcome must be its own Ready, not ConsentRequired",
        )
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertFalse(consent.canRequestAds.value, "the withdrawal must still close the ad request gate")
    }

    @Test
    fun `an ATT refusal blocks the early start until the update completes`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(
            consent = consent,
            storedConsentRefusal = { "ATT not answered" },
        )
        manager.startFromStoredConsent = true
        consent.onRequested = modelStoredConsent(consent, manager, storedAnswer = true)
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.GatherBeforeInitialize) }

        runCurrent()

        assertEquals(0, manager.nativeHandoffs.size, "a platform refusal must stop the early start")

        consent.update.complete(true)
        runCurrent()

        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(1, manager.nativeHandoffs.size)
    }

    @Test
    fun `BeforeMobileAdsInitialize and AfterMobileAdsInitialize each fire exactly once on an early start`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        manager.startFromStoredConsent = true
        consent.onRequested = modelStoredConsent(consent, manager, storedAnswer = true)
        val phases = mutableListOf<AdInitializationPhase>()
        val hook = object : AdInitializationHook {
            override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                phases += phase
            }
        }
        var result: AdManagerStatus? = null
        backgroundScope.launch {
            result = manager.initialize(config(listOf(hook)), ConsentMode.GatherBeforeInitialize)
        }

        runCurrent()
        consent.update.complete(true)
        runCurrent()

        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(
            1,
            phases.count { it == AdInitializationPhase.BeforeMobileAdsInitialize },
            "the early start and the ordinary path must not both run the Before hooks",
        )
        assertEquals(
            1,
            phases.count { it == AdInitializationPhase.AfterMobileAdsInitialize },
            "the After hooks must fire once, not once per caller",
        )
    }

    @Test
    fun `consent withdrawn before the early start reaches admission never starts MobileAds`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        manager.startFromStoredConsent = true
        // The stored answer allows, then the update's own answer withdraws it before the trigger's
        // deferred start reads the gate.
        consent.onRequested = { cfg ->
            consent.canRequestMutable.value = true
            consent.canRequestMutable.value = false
            manager.onStoredConsentAllows(cfg)
        }
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.GatherBeforeInitialize) }

        runCurrent()

        assertEquals(0, manager.nativeHandoffs.size, "a withdrawn gate must stop the early start")

        consent.update.complete(false)
        runCurrent()

        assertEquals(AdManagerStatus.ConsentRequired, result)
    }

    @Test
    fun `a later InitializeOnlyIfAlreadyAllowed on a Ready manager reuses the flow and hands off nothing new`() = runSlotTest {
        val consent = ScriptedConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        manager.startFromStoredConsent = true
        consent.onRequested = modelStoredConsent(consent, manager, storedAnswer = true)
        backgroundScope.launch { manager.initialize(config(), ConsentMode.GatherBeforeInitialize) }
        runCurrent()
        consent.update.complete(true)
        runCurrent()
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(1, consent.gatherCalls)

        val second = manager.initialize(config(), ConsentMode.InitializeOnlyIfAlreadyAllowed)

        assertEquals(AdManagerStatus.Ready, second)
        assertEquals(1, manager.nativeHandoffs.size, "a Ready manager must not start native initialization again")
        assertEquals(1, consent.gatherCalls, "a Ready manager must not rerun consent")
    }

    @Test
    fun `the switch defaults off on the fake and reads back on while NoOpAdManager ignores it`() = runSlotTest {
        assertFalse(NoOpAdManager.startFromStoredConsent, "NoOpAdManager must report the switch off")
        NoOpAdManager.startFromStoredConsent = true
        assertFalse(NoOpAdManager.startFromStoredConsent, "NoOpAdManager must ignore the switch")

        val manager = FakeGoogleAdManager()
        assertFalse(manager.startFromStoredConsent, "the switch must default to off")
        manager.startFromStoredConsent = true
        assertTrue(manager.startFromStoredConsent, "the switch must read back what was set")
    }
}

/**
 * A consent controller whose info update is held open by the test. [canRequestMutable] is what
 * the controller "publishes", [update] is the in-flight UMP update, and [onRequested] runs at the
 * moment the update is requested, before it has reported back.
 */
private class ScriptedConsentController : ConsentController {
    val canRequestMutable = MutableStateFlow(false)
    override val canRequestAds: StateFlow<Boolean> = canRequestMutable

    override val status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    override val privacyOptionsRequirementStatus =
        MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)

    val update = CompletableDeferred<Boolean>()
    var onRequested: ((AdConfig) -> Unit)? = null
    var gatherCalls = 0

    private suspend fun awaitUpdate(config: AdConfig): ConsentStatus {
        onRequested?.invoke(config)
        val completed = update.await()
        canRequestMutable.value = completed
        status.value = if (completed) ConsentStatus.Obtained else ConsentStatus.Required
        return status.value
    }

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus = awaitUpdate(config)

    override suspend fun gatherConsent(config: AdConfig): ConsentStatus {
        gatherCalls++
        return awaitUpdate(config)
    }

    override suspend fun showPrivacyOptions(): Boolean = false

    override suspend fun resetConsentForDebug(): Boolean = false
}
