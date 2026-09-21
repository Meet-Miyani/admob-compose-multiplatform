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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@OptIn(ExperimentalCoroutinesApi::class)
class NativeInitializationOwnershipTest {

    @Test
    fun `the native initialization watchdog outlasts GMA's own internal bound`() {
        // Both platforms document the SAME bound, not just iOS: the completion fires once the SDK
        // and its mediation adapters finish initializing, "or after a 30-second timeout" (GMA
        // Next-Gen Android MobileAds.initialize, GMA iOS startWithCompletionHandler). A wrapper
        // watchdog set to that same nominal value races GMA's own fallback and turns a slow -- but
        // ultimately successful -- mediation setup into a false initialization failure.
        //
        // Asserted against the documented bound rather than against the other platform's constant:
        // a relative assertion was what let Android keep racing while iOS was fixed.
        assertTrue(
            InitializationTimeouts.nativeInitialize > gmaInternalCompletionBound,
            "the wrapper watchdog must outlast GMA's own $gmaInternalCompletionBound bound on " +
                "every platform, or a slow mediation setup races it into a false failure",
        )
    }

    /** What GMA itself waits before invoking its completion regardless of adapter state. */
    private val gmaInternalCompletionBound = 30.seconds

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

    // ---------------------------------------------------------------------------------
    // Ordering of status publication against native-session configuration and hooks.
    // ---------------------------------------------------------------------------------

    /**
     * A native session whose window was supplied before initialization finished must load.
     *
     * Dormant sessions exist precisely so a feed can be built while `initialize()` is still
     * running. On acceptance the manager materialises them and replays their windows, which
     * schedules demand immediately — and that demand passes the native request gate, which
     * requires `status == Ready`. With Ready published only after the After hooks, the gate
     * refused, the refusal was recorded as a NON-retryable slot error, and `reconcileDemands()`
     * skips a slot that has one: the slot stayed Failed forever despite a successful
     * initialization.
     *
     * The parked hook is what makes this deterministic rather than dispatcher-dependent: it
     * holds the operation open across the window where the old order published nothing.
     */
    @Test
    fun `a dormant native session loads once initialization is accepted`() = runSlotTest {
        val platform = RecordingNativePlatform()
        val hookEntered = CompletableDeferred<Unit>()
        val releaseHook = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            nativePlatform = platform,
            nativeScope = backgroundScope,
        )
        val hookConfig = config("ca-app-pub-A").copy(
            initializationHooks = listOf(
                object : AdInitializationHook {
                    override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                        if (phase != AdInitializationPhase.AfterMobileAdsInitialize) return
                        hookEntered.complete(Unit)
                        releaseHook.await()
                    }
                }
            )
        )

        // The session exists BEFORE initialize() — the supported dormant case.
        val session = manager.nativeAds.session("feed")
        session.updateWindow(
            dev.avinya.ads.nativead.NativeAdWindow(
                visible = listOf(dev.avinya.ads.nativead.NativeAdSlot("row-0", nativePlacement)),
            )
        )

        val init = async { manager.initialize(hookConfig, ConsentMode.SkipConsent) }
        hookEntered.await()
        testScheduler.advanceUntilIdle()

        assertEquals(
            AdManagerStatus.Ready,
            manager.status.value,
            "Ready must be published before the After hook, or a hook cannot load an ad",
        )
        val slotState = session.state.value.slots["row-0"]
        assertTrue(
            slotState !is dev.avinya.ads.nativead.NativeAdSlotState.Failed,
            "a dormant session's window must not be refused by the request gate it was " +
                "waiting for; got $slotState",
        )
        assertTrue(platform.loadCalls > 0, "the parked window must reach the platform")

        releaseHook.complete(Unit)
        assertEquals(AdManagerStatus.Ready, init.await())
    }

    /**
     * An `AfterMobileAdsInitialize` hook may await `Ready` without hanging.
     *
     * The virtual-time timeout is the assertion: a deadlock cannot fail an assert, it just
     * never returns, so the hang has to be converted into a value. `runTest`'s scheduler skips
     * the delay instantly when nothing is blocked.
     */
    @Test
    fun `an After hook may await Ready`() = runSlotTest {
        var observedReady: Boolean? = null
        val manager = FakeGoogleAdManager()
        val hookConfig = config("ca-app-pub-A").copy(
            initializationHooks = listOf(
                object : AdInitializationHook {
                    override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                        if (phase != AdInitializationPhase.AfterMobileAdsInitialize) return
                        observedReady = kotlinx.coroutines.withTimeoutOrNull(60.seconds) {
                            manager.status.first { it == AdManagerStatus.Ready }
                        } != null
                    }
                }
            )
        )

        manager.initialize(hookConfig, ConsentMode.SkipConsent)

        assertEquals(true, observedReady, "an After hook that awaits Ready must not hang")
    }

    /**
     * A re-entrant `initialize()` from inside a hook returns instead of joining its own attempt.
     *
     * The nested call is admitted as a follower of the in-flight attempt and awaits a completion
     * that cannot arrive until this hook returns. Nothing in that path has a timeout.
     */
    @Test
    fun `initialize from an After hook returns instead of joining its own attempt`() = runSlotTest {
        var nested: AdManagerStatus? = null
        val manager = FakeGoogleAdManager()
        lateinit var hookConfig: AdConfig
        hookConfig = config("ca-app-pub-A").copy(
            initializationHooks = listOf(
                object : AdInitializationHook {
                    override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                        if (phase != AdInitializationPhase.AfterMobileAdsInitialize) return
                        nested = kotlinx.coroutines.withTimeoutOrNull(60.seconds) {
                            manager.initialize(hookConfig, ConsentMode.SkipConsent)
                        }
                    }
                }
            )
        )

        val result = manager.initialize(hookConfig, ConsentMode.SkipConsent)

        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(AdManagerStatus.Ready, nested, "the nested call must return, not deadlock")
        assertEquals(1, manager.handoffMarks.size, "the nested call must not start a second attempt")
    }

    /** Same defect from the Before phase, which is dispatched from a different call site. */
    @Test
    fun `initialize from a Before hook returns instead of joining its own attempt`() = runSlotTest {
        var nested: AdManagerStatus? = null
        val manager = FakeGoogleAdManager()
        lateinit var hookConfig: AdConfig
        hookConfig = config("ca-app-pub-A").copy(
            initializationHooks = listOf(
                object : AdInitializationHook {
                    override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                        if (phase != AdInitializationPhase.BeforeMobileAdsInitialize) return
                        nested = kotlinx.coroutines.withTimeoutOrNull(60.seconds) {
                            manager.initialize(hookConfig, ConsentMode.SkipConsent)
                        }
                    }
                }
            )
        )

        manager.initialize(hookConfig, ConsentMode.SkipConsent)

        assertTrue(nested != null, "the nested call must return, not deadlock")
        assertEquals(1, manager.handoffMarks.size, "the nested call must not start a second attempt")
    }

    /**
     * A follower asking for a different native memory policy gets the same answer whether it
     * arrived during the leader's attempt or after it finished.
     *
     * `equivalentAttempt` compares the native identity and the consent mode, and the memory
     * policy is deliberately excluded from that identity (changing it needs no GMA re-init).
     * The follower therefore short-circuited on equality and returned the leader's Ready,
     * while the leader's policy was the one actually installed — the identical call made a
     * moment later returned INITIALIZATION_CONFLICT. Same request, different answer, decided
     * only by timing.
     */
    @Test
    fun `a concurrent follower with a different memory policy gets the sequential conflict`() = runSlotTest {
        val platform = RecordingNativePlatform()
        val nativePause = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            nativePlatform = platform,
            nativeScope = backgroundScope,
            nativeInitialize = { _, _ -> nativePause.await() },
        )
        val leaderConfig = config("ca-app-pub-A").copy(
            nativeAdMemoryPolicy = dev.avinya.ads.nativead.NativeAdMemoryPolicy(softLimit = 4, hardLimit = 6),
        )
        val followerConfig = config("ca-app-pub-A").copy(
            nativeAdMemoryPolicy = dev.avinya.ads.nativead.NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2),
        )

        val leader = async { manager.initialize(leaderConfig, ConsentMode.SkipConsent) }
        yield()
        val follower = async { manager.initialize(followerConfig, ConsentMode.SkipConsent) }
        yield()
        nativePause.complete(Unit)

        assertEquals(AdManagerStatus.Ready, leader.await())
        val concurrentResult = follower.await()
        // The same call, now strictly after initialization settled.
        val sequentialResult = manager.initialize(followerConfig, ConsentMode.SkipConsent)

        assertEquals(
            sequentialResult,
            concurrentResult,
            "a follower must not be told Ready for a policy that was never installed",
        )
        assertIs<AdManagerStatus.Failed>(concurrentResult)
        assertEquals(AdManagerStatus.Ready, manager.status.value, "the manager itself stays Ready")
    }

    private val nativePlacement = AdPlacement(
        id = "native",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds("test-android", "test-ios"),
    )

    /** Minimal native platform: records load calls and returns one ad per requested slot. */
    private class RecordingNativePlatform : dev.avinya.ads.internal.NativeAdPlatform<String> {
        var loadCalls = 0
            private set
        private var next = 0

        override suspend fun load(
            placement: AdPlacement,
            count: Int,
            generation: Long,
        ): AdAttemptResult<dev.avinya.ads.internal.NativeAdPlatformBatch<String>> {
            loadCalls++
            return AdAttemptResult.Success(
                dev.avinya.ads.internal.NativeAdPlatformBatch(
                    ads = List(count) { "ad-${next++}" },
                    unfilledError = null,
                )
            )
        }

        override suspend fun bindEvents(ad: String, adInstanceId: String, emit: (AdEvent) -> Unit) = Unit
        override fun destroy(ad: String) = Unit
        override fun responseInfo(ad: String): AdResponseInfo? = null
        override fun mediaInfo(ad: String): dev.avinya.ads.nativead.NativeMediaInfo? = null
    }
}
