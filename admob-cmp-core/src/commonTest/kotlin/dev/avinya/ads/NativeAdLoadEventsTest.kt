package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdCoordinatorCore
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.internal.NativeAdSessionCore
import dev.avinya.ads.nativead.NativeAdBatching
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdOptions
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAdLoadEventsTest {

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

    private fun List<AdEvent>.loaded(): List<AdEvent.Loaded> = filterIsInstance<AdEvent.Loaded>()

    private fun List<AdEvent>.loadFailed(): List<AdEvent.LoadFailed> = filterIsInstance<AdEvent.LoadFailed>()

    @Test fun `an admitted ad reports Loaded with its response info`() = runTest(dispatcher) {
        val platform = FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        platform.responseInfoFor = { AdResponseInfo(responseId = "r-${it.id}") }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        assertEquals(listOf<AdEvent>(AdEvent.Loaded("p", AdResponseInfo(responseId = "r-0"))), emitted)
    }

    @Test fun `a batched load reports one Loaded per admitted ad`() = runTest(dispatcher) {
        val placement = nativePlacement.copy(nativeOptions = NativeAdOptions(batching = NativeAdBatching.GoogleOnly))
        val platform = FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        platform.responseInfoFor = { AdResponseInfo(responseId = "r-${it.id}") }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 6, hardLimit = 6),
            platform = platform,
            eventSink = emitted::add,
        )
        coord.session("s1", NativeAdSessionPolicy(maxRetainedAds = 3))
        coord.updateWindow(
            "s1",
            NativeAdWindow(visible = listOf("a", "b", "c").map { NativeAdSlot(it, placement) }),
        )
        advanceUntilIdle()

        val loaded = emitted.loaded()
        assertEquals(3, loaded.size, "one Loaded per admitted ad")
        assertEquals(
            setOf("r-0", "r-1", "r-2"),
            loaded.map { it.responseInfo?.responseId }.toSet(),
        )
    }

    @Test fun `Loaded arrives after its slot is published Ready`() = runTest(dispatcher) {
        val platform = FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        var session: NativeAdSessionCore? = null
        val slotStatesAtLoaded = mutableListOf<NativeAdSlotState?>()
        val coord = coordinator(
            platform = platform,
            eventSink = { event ->
                if (event is AdEvent.Loaded) slotStatesAtLoaded += session?.state?.value?.slots?.get("a")
            },
        )
        session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        val atLoaded = slotStatesAtLoaded.single()
        assertTrue(atLoaded is NativeAdSlotState.Ready, "slot must be Ready when Loaded arrives, was $atLoaded")
    }

    @Test fun `adopting a spare reports no second Loaded`() = runTest(dispatcher) {
        val platform = FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        platform.responseInfoFor = { AdResponseInfo(responseId = "r-${it.id}") }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        advanceUntilIdle()
        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        assertEquals(1, emitted.loaded().size, "adopting the kept ad is not a load")
        assertEquals(1, platform.loadCalls.size)
    }

    @Test fun `a late arrival kept as a spare reports Loaded`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = FakePlatform { _, count, _ ->
            gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, emitted.loaded().size, "the late ad is admitted as a spare and reports Loaded once")
        assertTrue(platform.destroyed.isEmpty(), "the late ad must be kept")
    }

    @Test fun `a failed load reports one LoadFailed after its retries`() = runTest(dispatcher) {
        val error = AdError(code = "NETWORK_ERROR", message = "offline")
        val placement = nativePlacement.copy(
            retryPolicy = AdRetryPolicy(maxAttempts = 3, initialDelay = 1.milliseconds, maxDelay = 1.milliseconds),
        )
        val platform = FakePlatform { _, _, _ -> AdAttemptResult.Failure(error) }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        val session = coord.session("s1")
        coord.updateWindow("s1", NativeAdWindow(visible = listOf(NativeAdSlot("a", placement))))
        advanceUntilIdle()

        assertEquals(3, platform.loadCalls.size, "the load must be retried until attempts are spent")
        assertEquals(listOf<AdEvent>(AdEvent.LoadFailed("p", error)), emitted)
        assertTrue(session.state.value.slots["a"] is NativeAdSlotState.Failed)
    }

    @Test fun `a slot waiting for consent reports nothing and loads once requests are allowed`() = runTest(dispatcher) {
        val platform = FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val emitted = mutableListOf<AdEvent>()
        var allowed = false
        val coord = coordinator(platform = platform, canRequestAds = { allowed }, eventSink = emitted::add)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        assertTrue(platform.loadCalls.isEmpty(), "no load may start without consent")
        assertTrue(emitted.isEmpty(), "a slot waiting for consent has not failed")
        assertEquals(NativeAdSlotState.Empty, session.state.value.slots["a"])

        allowed = true
        coord.onRequestsAllowed()
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size)
        assertEquals(1, emitted.filterIsInstance<AdEvent.Loaded>().size)
        assertTrue(session.state.value.slots["a"] is NativeAdSlotState.Ready)
    }

    @Test fun `unfilled slots of a partial batch each report LoadFailed`() = runTest(dispatcher) {
        val placement = nativePlacement.copy(nativeOptions = NativeAdOptions(batching = NativeAdBatching.GoogleOnly))
        val noFill = AdError(code = "NO_FILL", message = "none")
        val platform = FakePlatform { _, _, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(FakeAd(0)), noFill))
        }
        platform.responseInfoFor = { AdResponseInfo(responseId = "r-${it.id}") }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 6, hardLimit = 6),
            platform = platform,
            eventSink = emitted::add,
        )
        coord.session("s1", NativeAdSessionPolicy(maxRetainedAds = 3))
        coord.updateWindow(
            "s1",
            NativeAdWindow(visible = listOf("a", "b", "c").map { NativeAdSlot(it, placement) }),
        )
        advanceUntilIdle()

        assertEquals(1, emitted.loaded().size, "the one admitted ad reports Loaded")
        assertEquals(
            listOf<AdEvent>(
                AdEvent.Loaded("p", AdResponseInfo(responseId = "r-0")),
                AdEvent.LoadFailed("p", noFill),
                AdEvent.LoadFailed("p", noFill),
            ),
            emitted.sortedBy { it is AdEvent.LoadFailed },
        )
    }

    @Test fun `a load whose slot left the window reports nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = FakePlatform { _, count, _ ->
            gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(emitted.isEmpty(), "nobody waits for this load any more, so no event: $emitted")
    }

    @Test fun `a load made stale by clear reports nothing`() = runTest(dispatcher) {
        val platform = FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        coord.clear()
        advanceUntilIdle()

        assertTrue(emitted.loaded().isEmpty(), "a stale admit reports no Loaded: $emitted")
        assertTrue(emitted.loadFailed().isEmpty(), "a stale failure reports no LoadFailed: $emitted")
    }

    @Test fun `a failed slot reports its failure once`() = runTest(dispatcher) {
        val noFill = AdError(code = "NO_FILL", message = "none")
        val platform = FakePlatform { _, _, _ -> AdAttemptResult.Failure(noFill) }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.session("s1")
        val window = windowWith("a")
        coord.updateWindow("s1", window)
        advanceUntilIdle()
        val callsAfterFailure = platform.loadCalls.size

        repeat(3) { coord.updateWindow("s1", window) }
        advanceUntilIdle()

        assertEquals(listOf<AdEvent>(AdEvent.LoadFailed("p", noFill)), emitted)
        assertEquals(callsAfterFailure, platform.loadCalls.size, "a settled failure must not reload on its own")
    }

    @Test fun `an event binding failure reports LoadFailed`() = runTest(dispatcher) {
        val ad = FakeAd(0)
        val platform = FakePlatform { _, _, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null))
        }
        platform.bindFailure = IllegalArgumentException("bind broke")
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        assertTrue(emitted.loaded().isEmpty(), "a slot whose binding failed must not report Loaded: $emitted")
        val failed = emitted.loadFailed()
        assertEquals(1, failed.size, "exactly one LoadFailed for the binding failure")
        assertEquals("bind broke", failed.single().error.message)
        assertTrue(session.state.value.slots["a"] is NativeAdSlotState.Failed)
        assertTrue(ad in platform.destroyed, "the ad whose binding failed must be destroyed")
    }
}
