package dev.avinya.ads

import dev.avinya.ads.internal.InitializationTimeouts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation

@OptIn(ExperimentalCoroutinesApi::class)
class NativeInitializationOwnershipTest {

    private fun config(appId: String) = AdConfig(
        androidAppId = appId,
        iosAppId = appId,
    )

    @Test
    fun `a config that never gets its native callback reports a retryable failure`() = runSlotTest {
        val manager = FakeGoogleAdManager(nativeInitialize = { _, _ -> awaitCancellation() })

        val status = manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)

        val failed = assertIs<AdManagerStatus.Failed>(status)
        assertTrue(failed.retryable, "a hung GMA callback must leave a retry open")
    }

    @Test
    fun `a different config after a timed-out handoff is refused not reported ready`() = runSlotTest {
        var callbacksArrive = false
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> if (!callbacksArrive) awaitCancellation() },
        )

        // Config A is handed to the process-global SDK; its callback never arrives.
        manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)

        // GMA starts answering again -- but it is still the singleton that accepted A.
        callbacksArrive = true
        val second = manager.initialize(config("ca-app-pub-B"), ConsentMode.SkipConsent)

        val refused = assertIs<AdManagerStatus.Failed>(second)
        assertEquals(AdErrorCode.INITIALIZATION_CONFLICT, refused.error.code)
        assertFalse(refused.retryable, "the native singleton cannot be reconfigured")
        assertNotEquals(
            AdManagerStatus.Ready,
            manager.status.value,
            "the wrapper must never publish Ready for a configuration the process does not own",
        )
        assertEquals(
            1,
            manager.nativeHandoffs.size,
            "the refused configuration must never reach the native SDK",
        )
    }

    @Test
    fun `the same config after a timed-out handoff may still retry to Ready`() = runSlotTest {
        var callbacksArrive = false
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> if (!callbacksArrive) awaitCancellation() },
        )

        manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)
        callbacksArrive = true
        val second = manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)

        // The documented recovery from a hung GMA callback must keep working.
        assertEquals(AdManagerStatus.Ready, second)
        assertEquals(2, manager.nativeHandoffs.size, "the same identity is allowed to retry")
    }

    @Test
    fun `a refused handoff does not run publisher initialization hooks`() = runSlotTest {
        val hook = object : AdInitializationHook {
            var afterCount = 0
            override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                if (phase == AdInitializationPhase.BeforeMobileAdsInitialize) afterCount++
            }
        }
        val manager = FakeGoogleAdManager(nativeInitialize = { _, _ -> awaitCancellation() })

        manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)
        val before = hook.afterCount
        manager.initialize(
            AdConfig(
                androidAppId = "ca-app-pub-B",
                iosAppId = "ca-app-pub-B",
                initializationHooks = listOf(hook),
            ),
            ConsentMode.SkipConsent,
        )

        assertEquals(before, hook.afterCount, "a refused configuration must not fire host hooks")
    }

    @Test
    fun `a failure before the native handoff leaves a different config free to retry`() = runSlotTest {
        var rejectAppId = true
        val manager = FakeGoogleAdManager(
            failBeforeHandoff = { if (rejectAppId) IllegalArgumentException("invalid app id") else null },
        )

        // AdAppIds validates only non-blank, so an ad-unit id reaches the platform builder and is
        // rejected there -- before MobileAds.initialize is ever called.
        val first = manager.initialize(
            AdConfig(androidAppId = "ca-app-pub-1/2", iosAppId = "ca-app-pub-1/2"),
            ConsentMode.SkipConsent,
        )
        assertIs<AdManagerStatus.Failed>(first)

        // The host fixes the id and retries. Nothing was handed to native, so this MUST work.
        rejectAppId = false
        val second = manager.initialize(
            AdConfig(androidAppId = "ca-app-pub-A", iosAppId = "ca-app-pub-A"),
            ConsentMode.SkipConsent,
        )

        assertEquals(
            AdManagerStatus.Ready,
            second,
            "a throw before the native handoff must not pin ownership the process does not have",
        )
    }

    @Test
    fun `the handoff mark is taken exactly once per native attempt`() = runSlotTest {
        val manager = FakeGoogleAdManager()

        manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)

        assertEquals(
            listOf("ca-app-pub-A"),
            manager.handoffMarks.map { it.platformAppId },
            "each native attempt marks its handoff once, at the platform's own boundary",
        )
    }

    @Test
    fun `a configuration that will be refused never gathers consent`() = runSlotTest {
        var callbacksArrive = false
        val consent = FakeConsentController()
        val manager = FakeGoogleAdManager(
            consent = consent,
            nativeInitialize = { _, _ -> if (!callbacksArrive) awaitCancellation() },
        )

        // Identity A is handed off and times out.
        manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)
        callbacksArrive = true
        val before = consent.gatherConsentCalls

        val refused = manager.initialize(config("ca-app-pub-B"), ConsentMode.GatherBeforeInitialize)

        assertIs<AdManagerStatus.Failed>(refused)
        assertEquals(
            before,
            consent.gatherConsentCalls,
            "a configuration the SDK already knows it will refuse must not put a consent form on screen",
        )
    }
}
