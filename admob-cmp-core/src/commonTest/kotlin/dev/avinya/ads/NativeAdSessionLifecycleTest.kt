package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdCoordinatorCore
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAdSessionLifecycleTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nativePlacement = AdPlacement(
        id = "p",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds(android = "x", ios = "y"),
    )

    @AfterTest
    fun teardown() {
        scope.cancel()
    }

    private fun coordinator(
        memoryPolicy: NativeAdMemoryPolicy = NativeAdMemoryPolicy(),
        platform: FakePlatform,
        canRequestAds: () -> Boolean = { true },
        eventSink: (AdEvent) -> Unit = {},
    ): NativeAdCoordinatorCore<FakeAd> = NativeAdCoordinatorCore(
        memoryPolicy = memoryPolicy,
        platform = platform,
        scope = scope,
        canRequestAds = canRequestAds,
        eventSink = eventSink,
    )

    private fun windowWith(vararg visible: String): NativeAdWindow = NativeAdWindow(
        visible = visible.map { NativeAdSlot(it, nativePlacement) },
    )

    /** Serves `count` fresh ads per load, with increasing ids starting at 0. */
    private fun servingPlatform(): FakePlatform {
        var next = 0
        return FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(next++) }, null))
        }
    }

    private val retryingPlacement = nativePlacement.copy(
        retryPolicy = AdRetryPolicy(maxAttempts = 3, initialDelay = 1.minutes, maxDelay = 1.minutes),
    )

    // Consent purge

    @Test fun `withdrawn consent keeps the session and destroys every ad once`() = runTest(dispatcher) {
        val platform = servingPlatform()
        var allowed = true
        val coord = coordinator(platform = platform, canRequestAds = { allowed })
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        val generation = coord.sessionGeneration("s1")
        assertNotNull(generation)

        allowed = false
        coord.onConsentRevoked()

        assertEquals(generation, coord.sessionGeneration("s1"), "session must keep its generation")
        assertEquals(1, coord.managerState().activeSessions, "session must stay active")
        assertEquals(0, coord.managerState().loadedAds, "no ad may survive the purge")
        assertEquals(listOf(0, 1), platform.destroyed.map { it.id }.sorted(), "every ad destroyed once")
        assertEquals(NativeAdSlotState.Empty, session.state.value.slots["a"], "slot a must be Empty")
        assertEquals(NativeAdSlotState.Empty, session.state.value.slots["b"], "slot b must be Empty")
        assertTrue(session.adInfo.value.isEmpty(), "adInfo must be empty after the purge")
        assertTrue(session.state.value.active, "session must still be active")
    }

    @Test fun `nothing is requested or reported while requests are refused`() = runTest(dispatcher) {
        val platform = servingPlatform()
        var allowed = true
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, canRequestAds = { allowed }, eventSink = emitted::add)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        allowed = false
        coord.onConsentRevoked()
        emitted.clear()

        coord.updateWindow("s1", windowWith("a", "b", "c"))
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size, "no load while requests are refused")
        assertTrue(emitted.filterIsInstance<AdEvent.LoadFailed>().isEmpty(), "no LoadFailed while refused: $emitted")
        assertEquals(NativeAdSlotState.Empty, session.state.value.slots["a"], "slot a must be Empty")
        assertEquals(NativeAdSlotState.Empty, session.state.value.slots["b"], "slot b must be Empty")
        assertEquals(NativeAdSlotState.Empty, session.state.value.slots["c"], "slot c must be Empty")
    }

    @Test fun `requests allowed again reload the wanted slots without a window update`() = runTest(dispatcher) {
        val platform = servingPlatform()
        var allowed = true
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, canRequestAds = { allowed }, eventSink = emitted::add)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        allowed = false
        coord.onConsentRevoked()
        advanceUntilIdle()
        val loadedBefore = emitted.filterIsInstance<AdEvent.Loaded>().size

        allowed = true
        coord.onRequestsAllowed()
        advanceUntilIdle()

        assertEquals(2, platform.loadCalls.size, "wanted slots must reload once allowed")
        assertTrue(session.state.value.slots["a"] is NativeAdSlotState.Ready, "slot a must be Ready")
        assertTrue(session.state.value.slots["b"] is NativeAdSlotState.Ready, "slot b must be Ready")
        assertEquals(
            2,
            emitted.filterIsInstance<AdEvent.Loaded>().size - loadedBefore,
            "two more Loaded events expected",
        )
    }

    @Test fun `a load in retry backoff is cancelled by the purge`() = runTest(dispatcher) {
        val platform = FakePlatform { _, _, _ ->
            AdAttemptResult.Failure(AdError(code = "NETWORK_ERROR", message = "retry"))
        }
        var allowed = true
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, canRequestAds = { allowed }, eventSink = emitted::add)
        coord.session("s1")
        coord.updateWindow("s1", NativeAdWindow(visible = listOf(NativeAdSlot("a", retryingPlacement))))
        runCurrent()

        allowed = false
        coord.onConsentRevoked()
        runCurrent()
        assertEquals(0, coord.schedulerCount(), "the load is cancelled, not left to wait out its backoff")

        advanceTimeBy(2.minutes)
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size, "the backoff retry must be cancelled")
        assertEquals(0, coord.schedulerCount(), "no scheduler may remain")
        assertEquals(0, coord.managerState().reservedLoads, "no reservation may remain")
        assertTrue(emitted.filterIsInstance<AdEvent.LoadFailed>().isEmpty(), "no LoadFailed: $emitted")
    }

    @Test fun `a configuration purge with requests still allowed reloads at once`() = runTest(dispatcher) {
        val platform = servingPlatform()
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        val oldInstanceId = session.adInfo.value["a"]!!.adInstanceId

        coord.onConsentRevoked()
        advanceUntilIdle()

        assertTrue(FakeAd(0) in platform.destroyed, "ad 0 must be destroyed")
        assertEquals(2, platform.loadCalls.size, "slot a must reload at once")
        assertTrue(session.state.value.slots["a"] is NativeAdSlotState.Ready, "slot a must be Ready")
        assertNotEquals(oldInstanceId, session.adInfo.value["a"]!!.adInstanceId, "a new ad instance")
    }

    @Test fun `a refusal reaching a running load defers its slot instead of failing it`() = runTest(dispatcher) {
        var calls = 0
        val platform = FakePlatform { _, count, _ ->
            calls++
            if (calls == 1) {
                AdAttemptResult.Failure(AdError(code = "NETWORK_ERROR", message = "retry"))
            } else {
                AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(it) }, null))
            }
        }
        var allowed = true
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, canRequestAds = { allowed }, eventSink = emitted::add)
        val session = coord.session("s1")
        // loadTimeout covers retry backoff too, so it must outlast the one-minute backoff here;
        // otherwise the default 30 seconds would time the load out before the refusal is tested.
        val placement = retryingPlacement.copy(timeoutPolicy = AdTimeoutPolicy(loadTimeout = 5.minutes))
        coord.updateWindow("s1", NativeAdWindow(visible = listOf(NativeAdSlot("a", placement))))
        runCurrent()

        allowed = false
        advanceTimeBy(2.minutes)
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size, "no retry while refused")
        assertTrue(emitted.filterIsInstance<AdEvent.LoadFailed>().isEmpty(), "no LoadFailed: $emitted")
        assertEquals(NativeAdSlotState.Empty, session.state.value.slots["a"], "slot a must be Empty, not Failed")

        allowed = true
        coord.onRequestsAllowed()
        advanceUntilIdle()

        assertEquals(2, platform.loadCalls.size, "slot a must load once allowed again")
        assertTrue(session.state.value.slots["a"] is NativeAdSlotState.Ready, "slot a must be Ready")
    }

    // Parking

    @Test fun `a reaped session keeps its generation and resumes on its next window update`() = runTest(dispatcher) {
        val platform = servingPlatform()
        val coord = coordinator(memoryPolicy = NativeAdMemoryPolicy(inactiveSessionTtl = 30.minutes), platform = platform)
        val core = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        val gen = coord.sessionGeneration("s1")!!

        coord.deactivateSession("s1", gen)
        coord.tickForTest(31.minutes)

        assertEquals(0, coord.managerState().activeSessions, "a reaped session is not active")
        assertEquals(0, coord.managerState().inactiveSessions, "a parked session is not counted")
        assertEquals(gen, coord.sessionGeneration("s1"), "generation must survive the reap")
        assertTrue(FakeAd(0) in platform.destroyed, "ad 0 must be destroyed by the reap")
        assertTrue(core.state.value.slots.isEmpty(), "a parked session holds no slots")
        assertFalse(core.state.value.active, "a parked session is not active")

        assertTrue(coord.updateWindow("s1", gen, windowWith("a")), "the next update must resume the session")
        advanceUntilIdle()

        assertSame(core, coord.session("s1"), "the same core object must resume")
        assertTrue(core.state.value.slots["a"] is NativeAdSlotState.Ready, "slot a must be Ready after resume")
        assertEquals(1, coord.managerState().activeSessions, "the resumed session is active")
    }

    @Test fun `every holder of a reaped session can use it after it resumes`() = runTest(dispatcher) {
        val platform = servingPlatform()
        val coord = coordinator(memoryPolicy = NativeAdMemoryPolicy(inactiveSessionTtl = 30.minutes), platform = platform)
        val first = coord.openSession("s1")
        val second = coord.openSession("s1")
        assertSame(first.core, second.core, "both holders share one core")
        coord.updateWindow("s1", first.generation, windowWith("a"))
        advanceUntilIdle()

        coord.deactivateSession("s1", first.generation)
        coord.tickForTest(31.minutes)
        assertTrue(coord.updateWindow("s1", first.generation, windowWith("a")), "resume through the first handle")
        advanceUntilIdle()

        assertNotNull(
            coord.acquireForRender("s1", second.generation, "a", nativePlacement, "r1"),
            "the second holder must render the resumed slot",
        )
    }

    @Test fun `a parked session counts toward no limit`() = runTest(dispatcher) {
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(maxInactiveSessions = 1, maxSessionRecords = 2),
            platform = servingPlatform(),
        )
        coord.session("s1")
        coord.deactivateSession("s1")
        coord.session("s2")
        coord.deactivateSession("s2")

        coord.session("s3")

        assertEquals(1, coord.managerState().inactiveSessions, "only s2 is counted as inactive")
        assertEquals(1, coord.managerState().activeSessions, "only s3 is active")
        assertNotNull(coord.sessionGeneration("s1"), "s1 stays parked")
    }

    @Test fun `another policy replaces a parked session`() = runTest(dispatcher) {
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(inactiveSessionTtl = 30.minutes),
            platform = servingPlatform(),
        )
        coord.session("s1")
        coord.deactivateSession("s1")
        coord.tickForTest(31.minutes)
        val gen1 = coord.sessionGeneration("s1")!!

        val replaced = coord.openSession("s1", NativeAdSessionPolicy(maxRetainedAds = 4))

        assertNotEquals(gen1, replaced.generation, "a different policy must start a new session")
        assertFalse(coord.updateWindow("s1", gen1, windowWith("a")), "the replaced generation must not apply")
    }

    @Test fun `the same policy resumes a parked session`() = runTest(dispatcher) {
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(inactiveSessionTtl = 30.minutes),
            platform = servingPlatform(),
        )
        val core = coord.session("s1")
        coord.deactivateSession("s1")
        coord.tickForTest(31.minutes)
        val gen = coord.sessionGeneration("s1")!!

        val resumed = coord.openSession("s1")

        assertEquals(gen, resumed.generation, "the same generation must come back")
        assertSame(core, resumed.core, "the same core must come back")
        assertEquals(1, coord.managerState().activeSessions, "the resumed session is active")
    }

    @Test fun `closing a parked session ends it`() = runTest(dispatcher) {
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(inactiveSessionTtl = 30.minutes),
            platform = servingPlatform(),
        )
        coord.session("s1")
        coord.deactivateSession("s1")
        coord.tickForTest(31.minutes)
        val gen = coord.sessionGeneration("s1")!!

        coord.closeSession("s1", gen)

        assertNull(coord.sessionGeneration("s1"), "a closed parked session has no generation")
        assertFalse(coord.updateWindow("s1", gen, windowWith("a")), "a closed parked session must not resume")
    }

    @Test fun `at most maxSessionRecords sessions stay parked`() = runTest(dispatcher) {
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(maxInactiveSessions = 1, maxSessionRecords = 2),
            platform = servingPlatform(),
        )
        for (key in listOf("s1", "s2", "s3", "s4")) {
            coord.session(key)
            coord.deactivateSession(key)
        }

        assertNull(coord.sessionGeneration("s1"), "the oldest parked session must end")
        assertNotNull(coord.sessionGeneration("s2"), "s2 must stay parked")
        assertNotNull(coord.sessionGeneration("s3"), "s3 must stay parked")
    }
}
