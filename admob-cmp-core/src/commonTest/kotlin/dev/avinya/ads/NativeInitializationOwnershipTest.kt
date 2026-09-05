package dev.avinya.ads

import dev.avinya.ads.internal.InitializationTimeouts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@OptIn(ExperimentalCoroutinesApi::class)
class NativeInitializationOwnershipTest {

    @Test
    fun `iOS native initialization timeout is strictly greater than GMA internal bound`() {
        val iosTimeout = InitializationTimeouts.nativeInitializeIos
        val androidTimeout = InitializationTimeouts.nativeInitialize
        
        assertTrue(
            iosTimeout > 30.seconds,
            "iOS timeout must be > 30s to win the race against GMA's internal watchdog"
        )
        assertTrue(
            iosTimeout > androidTimeout,
            "iOS timeout must be greater than the default/Android timeout"
        )
    }

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

    @Test
    fun `a detached native success publishes its terminal status even if the leader was cancelled`() = runSlotTest {
        val nativeCompletion = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> nativeCompletion.await() }
        )

        val job = launch {
            manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)
        }

        while (manager.handoffMarks.isEmpty()) { yield() }

        job.cancelAndJoin()
        nativeCompletion.complete(Unit)
        yield()

        assertEquals(AdManagerStatus.Ready, manager.status.value)
    }

    @Test
    fun `a detached native failure publishes its terminal status even if the leader was cancelled`() = runSlotTest {
        val nativeCompletion = CompletableDeferred<Unit>()
        val exception = RuntimeException("Native SDK crash")
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ ->
                nativeCompletion.await()
                throw exception
            }
        )

        val job = launch {
            manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)
        }

        while (manager.handoffMarks.isEmpty()) { yield() }
        job.cancelAndJoin()
        nativeCompletion.complete(Unit)
        yield()

        val status = manager.status.value
        assertIs<AdManagerStatus.Failed>(status)
        assertEquals(exception.message, status.error.message)

        val laterStatus = manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)
        assertEquals(status, laterStatus)
    }

    @Test
    fun `a detached native success unblocks ad requests after caller cancellation`() = runSlotTest {
        val nativeCompletion = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> nativeCompletion.await() }
        )

        val job = launch {
            manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent)
        }

        while (manager.handoffMarks.isEmpty()) { yield() }
        job.cancelAndJoin()
        nativeCompletion.complete(Unit)
        yield()

        // Assert on manager.status.value only since adRequestBlockedError() is protected 
        // and ad loading surface is not reachable from commonTest.
        assertEquals(AdManagerStatus.Ready, manager.status.value)
    }

    @Test
    fun `a detached native success runs the After hook exactly once`() = runSlotTest {
        val nativeCompletion = CompletableDeferred<Unit>()
        var afterCount = 0
        val hook = object : AdInitializationHook {
            override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                if (phase == AdInitializationPhase.AfterMobileAdsInitialize) afterCount++
            }
        }
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> nativeCompletion.await() }
        )
        val testConfig = AdConfig(
            androidAppId = "ca-app-pub-A",
            iosAppId = "ca-app-pub-A",
            initializationHooks = listOf(hook),
        )

        val job = launch {
            manager.initialize(testConfig, ConsentMode.SkipConsent)
        }

        while (manager.handoffMarks.isEmpty()) { yield() }
        job.cancelAndJoin()
        nativeCompletion.complete(Unit)
        yield()

        assertEquals(1, afterCount, "the After hook must run exactly once across the cancelled caller and the detached completion")
    }

    @Test
    fun `a cancelled leader before handoff passes leadership to an equivalent follower`() = runSlotTest {
        val leaderPause = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            failBeforeHandoff = {
                leaderPause.await()
                null
            }
        )
        val sharedConfig = config("ca-app-pub-A")

        val leaderJob = async { manager.initialize(sharedConfig, ConsentMode.SkipConsent) }
        yield() // Leader reaches failBeforeHandoff

        val followerJob = async { manager.initialize(sharedConfig, ConsentMode.SkipConsent) }
        yield() // Follower attaches

        leaderJob.cancelAndJoin()
        leaderPause.complete(Unit)

        val result = followerJob.await()
        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(AdManagerStatus.Ready, manager.status.value)
    }

    @Test
    fun `a cancelled leader after handoff leaves an equivalent follower waiting for native result`() = runSlotTest {
        val nativeCompletion = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> nativeCompletion.await() }
        )
        val sharedConfig = config("ca-app-pub-A")

        val leaderJob = async { manager.initialize(sharedConfig, ConsentMode.SkipConsent) }
        while (manager.handoffMarks.isEmpty()) { yield() }

        val followerJob = async { manager.initialize(sharedConfig, ConsentMode.SkipConsent) }
        yield() // Follower attaches

        leaderJob.cancelAndJoin()
        nativeCompletion.complete(Unit)

        val result = followerJob.await()
        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(AdManagerStatus.Ready, manager.status.value)
    }

    @Test
    fun `cancelling a follower leaves the leader running to completion`() = runSlotTest {
        val nativeCompletion = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> nativeCompletion.await() }
        )
        val sharedConfig = config("ca-app-pub-A")

        val leaderJob = async { manager.initialize(sharedConfig, ConsentMode.SkipConsent) }
        while (manager.handoffMarks.isEmpty()) { yield() }

        val followerJob = async { manager.initialize(sharedConfig, ConsentMode.SkipConsent) }
        yield() // Follower attaches

        followerJob.cancelAndJoin()
        nativeCompletion.complete(Unit)

        val result = leaderJob.await()
        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(AdManagerStatus.Ready, manager.status.value)
    }

    @Test
    fun `a cancelled leader before handoff allows a distinct follower to make its own attempt`() = runSlotTest {
        val leaderPause = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            failBeforeHandoff = {
                leaderPause.await()
                null
            }
        )

        val leaderJob = async { manager.initialize(config("ca-app-pub-A"), ConsentMode.SkipConsent) }
        yield() // Leader reaches failBeforeHandoff

        val distinctFollowerJob = async { manager.initialize(config("ca-app-pub-B"), ConsentMode.SkipConsent) }
        yield() // Follower waits for the attempt

        leaderJob.cancelAndJoin()
        leaderPause.complete(Unit)

        val result = distinctFollowerJob.await()
        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(listOf("ca-app-pub-B"), manager.handoffMarks.map { it.platformAppId })
    }
}
