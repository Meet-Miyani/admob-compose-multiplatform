package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdCoordinatorCore
import dev.avinya.ads.internal.NativeAdPlatform
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdBatching
import dev.avinya.ads.nativead.NativeAdOptions
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAdCoordinatorCoreTest {

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

    private fun fakePlatform(
        loadFn: suspend (AdPlacement, Int, Long) -> AdAttemptResult<NativeAdPlatformBatch<FakeAd>>,
    ): FakePlatform = FakePlatform(loadFn)

    private fun coordinator(
        memoryPolicy: NativeAdMemoryPolicy = NativeAdMemoryPolicy(),
        platform: NativeAdPlatform<FakeAd>,
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

    // --- Test 1: 65th live session is rejected ---------------------------------

    @Test fun `zero granted reservations never call the platform`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 1),
            platform = platform,
        )
        coord.session("s1")
        coord.updateWindow("s1", windowWith("visible"))
        advanceUntilIdle()
        coord.session("s2")
        coord.updateWindow(
            "s2",
            NativeAdWindow(visible = emptyList(), prefetchAhead = listOf(NativeAdSlot("prefetch", nativePlacement))),
        )
        advanceUntilIdle()
        assertEquals(1, platform.loadCalls.size, "a denied speculative reservation must not call the platform")
    }

    @Test fun `rejection of 65th live session`() {
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), null)) }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(maxInactiveSessions = 2, maxSessionRecords = 3),
            platform = platform,
        )
        coord.session("s1")
        coord.session("s2")
        coord.session("s3")
        assertFailsWith<IllegalStateException> {
            coord.session("s4")
        }
    }

    // --- Test 1b: blank session key is rejected --------------------------------

    @Test fun `blank session key is rejected`() {
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), null)) }
        val coord = coordinator(platform = platform)
        assertFailsWith<IllegalArgumentException> { coord.session("") }
    }

    // --- Test 1c: policy mismatch on reuse is rejected -------------------------

    @Test fun `reusing a session key with a different policy is rejected`() {
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), null)) }
        val coord = coordinator(platform = platform)
        coord.session("s1", NativeAdSessionPolicy(maxRetainedAds = 3))
        assertFailsWith<IllegalStateException> {
            coord.session("s1", NativeAdSessionPolicy(maxRetainedAds = 4))
        }
    }

    // --- Test 1d: repeated identical windows do not re-issue demand -----------

    @Test fun `repeated identical windows do not re-issue demand`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(it) }, null))
        }
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        val window = windowWith("a", "b", "c")
        coord.updateWindow("s1", window)
        advanceUntilIdle()
        val firstCallCount = platform.loadCalls.size
        coord.updateWindow("s1", window)
        advanceUntilIdle()
        assertEquals(
            firstCallCount,
            platform.loadCalls.size,
            "second identical window must not re-issue demand",
        )
    }

    // --- Test 1e: clear destroys every owned platform ad exactly once -------

    @Test fun `clear destroys every owned platform ad exactly once`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(it) }, null))
        }
        val coord = coordinator(platform = platform)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        coord.clear()
        assertEquals(2, platform.destroyed.size, "two ads destroyed on clear")
        assertEquals(2, platform.destroyed.toSet().size, "no duplicate destroy")
    }

    @Test fun `out of window mutation retires its exact owned platform ad`() = runTest(dispatcher) {
        val retained = FakeAd(7)
        val platform = fakePlatform { _, _, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(retained), null))
        }
        val coord = coordinator(platform = platform)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        advanceUntilIdle()

        assertEquals(listOf(retained), platform.destroyed, "the retired record must destroy its owned ad")
        assertEquals(0, coord.schedulerCount(), "scheduler is removed after its final record retires")
    }

    @Test fun `memory eviction clears session ownership and a later window reloads the slot`() = runTest(dispatcher) {
        var nextAd = 0
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(nextAd++) }, null))
        }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2),
            platform = platform,
        )
        val session = coord.session(
            "s1",
            NativeAdSessionPolicy(maxRetainedAds = 2, retainBehind = 0, prefetchAhead = 0),
        )
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()

        coord.onMemoryPressure(dev.avinya.ads.internal.NativeMemoryPressure.Moderate)
        val evictedKey = session.state.value.slots.entries.single { it.value == NativeAdSlotState.Empty }.key
        assertEquals(1, session.state.value.slots.values.count { it is NativeAdSlotState.Ready })

        coord.updateWindow("s1", windowWith(evictedKey))
        advanceUntilIdle()
        assertTrue(session.state.value.slots[evictedKey] is NativeAdSlotState.Ready)
        assertEquals(2, platform.loadCalls.size)
    }

    // --- Test 2: partial batch admission admits only the resolved ads -------

    @Test fun `partial batch admission admits only the resolved ads`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            val ads = (0 until count / 2).map { FakeAd(it) }
            AdAttemptResult.Success(NativeAdPlatformBatch(ads, null))
        }
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b", "c"))
        advanceUntilIdle()
        assertEquals(1, platform.loadCalls.size, "exactly one load for the batch")
        val (_, requestedCount, _) = platform.loadCalls.single()
        assertEquals(3, requestedCount, "platform called with full demand")
        val state = session.state.value
        val readyCount = state.slots.values.count {
            it is NativeAdSlotState.Ready || it is NativeAdSlotState.Mounted
        }
        assertEquals(1, readyCount, "one record admitted from the partial fill")
        assertEquals(2, state.slots.values.count { it is NativeAdSlotState.Failed }, "unmatched reservations are terminally failed")
    }

    @Test fun `governor cancellation of first batch reservation still admits second slot`() = runTest(dispatcher) {
        val firstBatchGate = CompletableDeferred<Unit>()
        val firstPlacement = nativePlacement.copy(id = "first")
        val visiblePlacement = nativePlacement.copy(id = "visible")
        val cancelledAd = FakeAd(1)
        val survivingAd = FakeAd(2)
        val platform = fakePlatform { placement, _, _ ->
            if (placement.id == firstPlacement.id) {
                firstBatchGate.await()
                AdAttemptResult.Success(NativeAdPlatformBatch(listOf(cancelledAd, survivingAd), null))
            } else {
                AdAttemptResult.Success(NativeAdPlatformBatch(listOf(FakeAd(3)), null))
            }
        }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2),
            platform = platform,
        )
        val firstSession = coord.session(
            "first",
            NativeAdSessionPolicy(maxRetainedAds = 2, retainBehind = 0, prefetchAhead = 1),
        )
        coord.updateWindow(
            "first",
            NativeAdWindow(
                visible = emptyList(),
                prefetchAhead = listOf(NativeAdSlot("a", firstPlacement), NativeAdSlot("b", firstPlacement)),
            ),
        )
        runCurrent()

        coord.session("visible")
        coord.updateWindow("visible", NativeAdWindow(visible = listOf(NativeAdSlot("c", visiblePlacement))))
        runCurrent()

        firstBatchGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(firstSession.state.value.slots["a"] is NativeAdSlotState.Empty)
        assertTrue(
            firstSession.state.value.slots["b"] is NativeAdSlotState.Retained,
            "second slot must retain its reservation-to-slot pairing: ${firstSession.state.value.slots}",
        )
        assertTrue(cancelledAd in platform.destroyed, "the cancelled pair's ad must not be reassigned")
        assertTrue(survivingAd !in platform.destroyed, "the second reservation keeps its own returned ad")
    }

    @Test fun `cancellation after callback binding destroys cancelled ad without remapping second identity`() = runTest(dispatcher) {
        val firstPlacement = nativePlacement.copy(id = "first")
        val visiblePlacement = nativePlacement.copy(id = "visible")
        val cancelledAd = FakeAd(11)
        val survivingAd = FakeAd(12)
        val bindGate = CompletableDeferred<Unit>()
        val emitted = mutableListOf<AdEvent>()
        val platform = fakePlatform { placement, _, _ ->
            if (placement.id == firstPlacement.id) {
                AdAttemptResult.Success(NativeAdPlatformBatch(listOf(cancelledAd, survivingAd), null))
            } else {
                AdAttemptResult.Success(NativeAdPlatformBatch(listOf(FakeAd(13)), null))
            }
        }
        platform.bindGate = bindGate
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2),
            platform = platform,
            eventSink = emitted::add,
        )
        val firstSession = coord.session(
            "first",
            NativeAdSessionPolicy(maxRetainedAds = 2, retainBehind = 0, prefetchAhead = 1),
        )
        val firstGeneration = coord.sessionGeneration("first")!!
        coord.updateWindow(
            "first",
            NativeAdWindow(
                visible = emptyList(),
                prefetchAhead = listOf(NativeAdSlot("a", firstPlacement), NativeAdSlot("b", firstPlacement)),
            ),
        )
        runCurrent()
        assertTrue(platform.bindStarted.isCompleted, "first callback must attach before the cancellation")

        coord.session("visible")
        coord.updateWindow("visible", NativeAdWindow(visible = listOf(NativeAdSlot("c", visiblePlacement))))
        runCurrent()
        bindGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(firstSession.state.value.slots["a"] is NativeAdSlotState.Empty)
        assertTrue(firstSession.state.value.slots["b"] is NativeAdSlotState.Retained)
        assertTrue(cancelledAd in platform.destroyed, "a callback-bound but cancelled ad must be destroyed")
        assertTrue(survivingAd !in platform.destroyed, "B must keep the ad returned at B's launch index")
        assertEquals(
            survivingAd,
            coord.acquireForRender("first", firstGeneration, "b", firstPlacement, "renderer")?.ad,
        )
        val event = AdEvent.Impression("first")
        platform.emit(cancelledAd, event)
        assertTrue(emitted.isEmpty(), "the cancelled ad's old callback identity is stale")
        platform.emit(survivingAd, event)
        assertEquals(listOf<AdEvent>(event), emitted)
    }

    @Test fun `google only demand twelve is scheduled as five five two`() = runTest(dispatcher) {
        val googlePlacement = nativePlacement.copy(
            nativeOptions = NativeAdOptions(batching = NativeAdBatching.GoogleOnly),
        )
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 12, hardLimit = 12),
            platform = platform,
        )
        coord.session("s1", NativeAdSessionPolicy(maxRetainedAds = 12))
        coord.updateWindow("s1", NativeAdWindow(visible = (0 until 12).map { NativeAdSlot("slot-$it", googlePlacement) }))
        advanceUntilIdle()

        assertEquals(listOf(5, 5, 2), platform.loadCalls.map { it.second })
    }

    @Test fun `non retryable failure makes one attempt`() = runTest(dispatcher) {
        val placement = nativePlacement.copy(retryPolicy = AdRetryPolicy(maxAttempts = 3, initialDelay = 1.milliseconds))
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Failure(AdError.sdkNotReady()) }
        val coord = coordinator(platform = platform)
        coord.session("s1")
        coord.updateWindow("s1", NativeAdWindow(visible = listOf(NativeAdSlot("slot", placement))))
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size)
    }

    @Test fun `unexpected platform throwable settles reservations and leaves scheduler usable`() = runTest(dispatcher) {
        var shouldThrow = true
        val platform = fakePlatform { _, count, _ ->
            if (shouldThrow) throw IllegalStateException("platform exploded")
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val coord = coordinator(platform = platform)
        val first = coord.session("first")
        coord.updateWindow("first", windowWith("a"))
        advanceUntilIdle()

        assertTrue(first.state.value.slots["a"] is NativeAdSlotState.Failed)
        assertEquals(0, coord.managerState().reservedLoads)
        assertEquals(0, coord.schedulerCount())

        shouldThrow = false
        val second = coord.session("second")
        coord.updateWindow("second", windowWith("b"))
        advanceUntilIdle()
        assertTrue(second.state.value.slots["b"] is NativeAdSlotState.Ready)
    }

    @Test fun `cancelling current batch does not corrupt an already queued batch`() = runTest(dispatcher) {
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        var call = 0
        val platform = fakePlatform { _, _, _ ->
            call += 1
            if (call == 1) firstGate.await()
            if (call == 2) secondGate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(FakeAd(call)), null))
        }
        val coord = coordinator(platform = platform)
        coord.session("first")
        coord.updateWindow("first", windowWith("a"))
        runCurrent()

        val second = coord.session("second")
        coord.updateWindow("second", windowWith("b"))
        assertEquals(1, platform.loadCalls.size, "second batch is queued behind the current job")

        coord.updateWindow("first", NativeAdWindow(visible = emptyList()))
        runCurrent()
        assertEquals(2, platform.loadCalls.size)
        secondGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(second.state.value.slots["b"] is NativeAdSlotState.Ready)
        assertEquals(0, coord.managerState().reservedLoads)
    }

    @Test fun `cancellation during backoff releases reservations and settles slots`() = runTest(dispatcher) {
        val placement = nativePlacement.copy(retryPolicy = AdRetryPolicy(maxAttempts = 3, initialDelay = 1.minutes, maxDelay = 1.minutes))
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Failure(AdError(code = "NETWORK_ERROR", message = "retry")) }
        val coord = coordinator(memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 1), platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", NativeAdWindow(visible = listOf(NativeAdSlot("slot", placement))))
        runCurrent()
        coord.closeSession("s1")
        advanceTimeBy(2.minutes)
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size, "cancelled backoff must not make another request")
        assertTrue(session.state.value.slots.isEmpty(), "closing during backoff settles the in-flight slot")
        assertEquals(0, coord.schedulerCount(), "cancelled reservation must not retain its scheduler")
    }

    @Test fun `consent revocation during backoff makes no later platform request`() = runTest(dispatcher) {
        var consent = true
        val placement = nativePlacement.copy(retryPolicy = AdRetryPolicy(maxAttempts = 3, initialDelay = 1.minutes, maxDelay = 1.minutes))
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Failure(AdError(code = "NETWORK_ERROR", message = "retry")) }
        val coord = coordinator(platform = platform, canRequestAds = { consent })
        coord.session("s1")
        coord.updateWindow("s1", NativeAdWindow(visible = listOf(NativeAdSlot("slot", placement))))
        runCurrent()
        consent = false
        coord.onConsentRevoked()
        advanceTimeBy(2.minutes)
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size)
    }

    @Test fun `bind failure destroys ad and settles its slot`() = runTest(dispatcher) {
        val ad = FakeAd(9)
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null)) }
        platform.bindFailure = IllegalStateException("binding failed")
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("slot"))
        advanceUntilIdle()

        assertEquals(listOf(ad), platform.destroyed)
        assertTrue(session.state.value.slots["slot"] is NativeAdSlotState.Failed)
        assertEquals(0, coord.schedulerCount())
    }

    @Test fun `current events emit and retired instance events are dropped`() = runTest(dispatcher) {
        val ad = FakeAd(10)
        val emitted = mutableListOf<AdEvent>()
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null)) }
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("slot"))
        advanceUntilIdle()
        val event = AdEvent.Impression("p")
        platform.emit(ad, event)
        assertEquals(1, emitted.size, "current record event emits after admission")
        assertEquals(event, emitted.single())

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        platform.emit(ad, event)
        assertEquals(1, emitted.size, "retired instance callback is stale and dropped")
    }

    // --- Test 3: clear during load destroys late callbacks -------------------

    @Test fun `clear during load destroys late callbacks from a stale generation`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(it) }, null))
        }
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        coord.clear()
        advanceUntilIdle()
        assertEquals(2, platform.destroyed.size, "late ads destroyed after clear")
        val state = session.state.value
        for ((_, slotState) in state.slots) {
            assertTrue(
                slotState is NativeAdSlotState.Empty || slotState is NativeAdSlotState.Loading,
                "slots reset after clear, got $slotState",
            )
        }
    }

    @Test fun `clear preserves inactive session tracking for ttl reaping`() = runTest(dispatcher) {
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), null)) }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(inactiveSessionTtl = 30.minutes),
            platform = platform,
        )
        coord.session("inactive")
        val generation = coord.sessionGeneration("inactive")
        coord.deactivateSession("inactive")

        coord.clear()
        coord.tickForTest(31.minutes)

        assertEquals(null, coord.sessionGeneration("inactive"))
        coord.session("inactive")
        assertTrue(coord.sessionGeneration("inactive") != generation)
    }

    // --- Test 4: cleanup of idle per-placement schedulers --------------------

    @Test fun `cleanup of idle per-placement schedulers`() = runTest(dispatcher) {
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), null)) }
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        assertTrue(coord.schedulerCount() == 0, "no idle schedulers should remain")
    }

    // --- Test 5: one-hour expiry expires a loaded record -------------------

    @Test fun `one-hour expiry expires a loaded record`() = runTest(dispatcher) {
        val ads = listOf(FakeAd(0))
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(ads, null)) }
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        val before = session.state.value
        assertTrue(before.slots["a"] is NativeAdSlotState.Ready, "admitted and ready")
        coord.tickForTest(61.minutes)
        val after = session.state.value
        val slotAfter = after.slots["a"]
        assertTrue(
            slotAfter is NativeAdSlotState.Empty || slotAfter is NativeAdSlotState.Loading,
            "expected Empty or Loading after TTL, got $slotAfter",
        )
    }

    // --- Test 6: inactive session TTL cleanup -------------------------------

    @Test fun `inactive session TTL cleanup reaps after 30 minutes`() = runTest(dispatcher) {
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), null)) }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(inactiveSessionTtl = 30.minutes),
            platform = platform,
        )
        val session = coord.session("s1")
        session.deactivate()
        coord.tickForTest(31.minutes)
        assertTrue(session.state.value.slots.isEmpty(), "reaped session has no slots")
    }

    // --- Test 7: 32-inactive-record LRU eviction -----------------------------

    @Test fun `32-inactive-record LRU evicts the oldest inactive session`() = runTest(dispatcher) {
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), null)) }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(maxInactiveSessions = 2, maxSessionRecords = 8),
            platform = platform,
        )
        val s1 = coord.session("s1")
        coord.tickForTest(1.minutes)
        s1.deactivate()
        val s2 = coord.session("s2")
        coord.tickForTest(1.minutes)
        s2.deactivate()
        coord.session("s3")  // pushes s1 out
        assertTrue(s1.state.value.slots.isEmpty(), "s1 reaped by LRU")
        assertTrue(s2.state.value.slots.isEmpty(), "s2 still inactive but tracked")
    }

    // --- Test 8: failed top-up preserves existing inventory ----------------

    @Test fun `failed top-up preserves existing inventory`() = runTest(dispatcher) {
        var first = true
        val platform = fakePlatform { _, count, _ ->
            if (first) {
                first = false
                AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(it) }, null))
            } else {
                AdAttemptResult.Failure(AdError.sdkNotReady())
            }
        }
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        val firstState = session.state.value
        assertTrue(firstState.slots["a"] is NativeAdSlotState.Ready)
        assertTrue(firstState.slots["b"] is NativeAdSlotState.Ready)
        coord.updateWindow("s1", windowWith("a", "b", "c"))
        advanceUntilIdle()
        val secondState = session.state.value
        assertTrue(secondState.slots["a"] is NativeAdSlotState.Ready, "a still ready")
        assertTrue(secondState.slots["b"] is NativeAdSlotState.Ready, "b still ready")
        assertTrue(secondState.slots["c"] is NativeAdSlotState.Failed, "c failed")
    }

    // Task 4C: a render lease must be tied to the exact session generation
    // and record identity. Removing either validation would let a stale view
    // unmount a replacement ad.
    @Test fun `render lease rejects stale generation second renderer and stale release`() = runTest(dispatcher) {
        val first = FakeAd(101)
        val replacement = FakeAd(102)
        var next = first
        val platform = fakePlatform { _, _, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(next), null))
        }
        val coord = coordinator(platform = platform)
        coord.session("feed")
        val generation = coord.sessionGeneration("feed")!!
        coord.updateWindow("feed", generation, windowWith("slot"))
        advanceUntilIdle()

        val lease = coord.acquireForRender("feed", generation, "slot", nativePlacement, "renderer-a")
        assertEquals(first, lease?.ad, "the current record is leased to its renderer")
        assertEquals(null, coord.acquireForRender("feed", generation, "slot", nativePlacement, "renderer-b"))
        assertEquals(null, coord.acquireForRender("feed", generation + 1, "slot", nativePlacement, "renderer-a"))

        coord.closeSession("feed", generation)
        next = replacement
        coord.session("feed")
        val replacementGeneration = coord.sessionGeneration("feed")!!
        coord.updateWindow("feed", replacementGeneration, windowWith("slot"))
        advanceUntilIdle()
        val replacementLease = coord.acquireForRender("feed", replacementGeneration, "slot", nativePlacement, "renderer-a")!!
        coord.releaseRenderer("feed", generation, "slot", nativePlacement, lease!!.recordId, "renderer-a")
        assertEquals(null, coord.acquireForRender("feed", replacementGeneration, "slot", nativePlacement, "renderer-b"), "stale release cannot unmount the replacement")
        coord.releaseRenderer("feed", replacementGeneration, "slot", nativePlacement, replacementLease.recordId, "renderer-a")
        assertEquals(replacement, coord.acquireForRender("feed", replacementGeneration, "slot", nativePlacement, "renderer-b")?.ad)
    }

    @Test fun `placement native ttl destroys and reloads an eligible active slot`() = runTest(dispatcher) {
        var load = 0
        val first = FakeAd(1)
        val placement = nativePlacement.copy(cachePolicy = AdCachePolicy(expirationPolicy = AdExpirationPolicy(nativeTtl = 1.seconds)))
        val platform = fakePlatform { _, _, _ ->
            load += 1
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(if (load == 1) first else FakeAd(load)), null))
        }
        val coord = coordinator(platform = platform)
        coord.session("feed")
        val generation = coord.sessionGeneration("feed")!!
        coord.updateWindow("feed", generation, NativeAdWindow(visible = listOf(NativeAdSlot("slot", placement))))
        advanceUntilIdle()
        coord.tickForTest(2.seconds)
        advanceUntilIdle()

        assertEquals(2, load, "the placement snapshot TTL reloads an eligible active slot")
        assertEquals(listOf(first), platform.destroyed, "the expired object is retired exactly once")
    }

    // --- NATIVE-02: mixed-batch invalidation -------------------------------------------
    // Demand is grouped by PLACEMENT only, so one window over slots a/b/c on the same
    // placement is a single batch of three entries. Treating a batch as indivisible caused
    // three distinct defects.

    @Test fun `invalidating one slot does not send its stale entry to the platform`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var call = 0
        val platform = fakePlatform { _, count, _ ->
            call += 1
            if (call == 1) gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(call * 100 + it) }, null))
        }
        // Default capacity, so reservations are actually GRANTED and the platform really is called
        // with the batch size. An earlier version of this test used hardLimit = 1, which denied every
        // reservation -- the platform was never called, and the assertion below could not fail.
        // Queueing is driven by currentJob being busy, not by capacity.
        val coord = coordinator(platform = platform)
        val session = coord.session("s")
        coord.updateWindow("s", windowWith("a"))
        runCurrent()
        assertEquals(1, platform.loadCalls.size, "the first load must be in flight to force queueing")

        coord.updateWindow("s", windowWith("a", "x", "y", "z"))
        runCurrent()
        assertEquals(1, platform.loadCalls.size, "the x/y/z demand must be queued behind the in-flight load")

        // Drop only y. Under the old `entries.all { … }` predicate the batch matched nothing, so y
        // stayed queued, won a permit, inflated the requested count, and had its ad destroyed on
        // arrival at recordAdmitted -- a wasted network load and a wasted ad, with hard-cap capacity
        // burned while live slots sat deferred.
        coord.updateWindow("s", windowWith("a", "x", "z"))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        // The precise symptom of a retained stale entry: an ad is fetched for it and then thrown
        // away at recordAdmitted, because the deferral sweep already cleared its inFlight marker.
        // Asserting on the requested COUNT is not enough -- how many entries a window yields depends
        // on maxRetainedAds, so a count bound can pass with the stale entry still present.
        assertTrue(
            platform.destroyed.isEmpty(),
            "no ad should be loaded and discarded; wasted ${platform.destroyed} " +
                "for requests ${platform.loadCalls.map { it.second }}"
        )
        assertTrue(session.state.value.slots["x"] is NativeAdSlotState.Ready)
        assertTrue(session.state.value.slots["z"] is NativeAdSlotState.Ready)
    }

    @Test fun `invalidating a slot in one session leaves the same slot key in another alone`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var call = 0
        val platform = fakePlatform { _, count, _ ->
            call += 1
            if (call == 1) gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(call * 100 + it) }, null))
        }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 1),
            platform = platform,
        )
        // Both sessions use the SAME slot key. Slot generations are per-session counters, so both
        // legitimately hold ("item-0", 1) at once.
        coord.session("blocker")
        coord.updateWindow("blocker", windowWith("item-0"))
        runCurrent()

        val victim = coord.session("victim")
        coord.updateWindow("victim", windowWith("item-0"))
        runCurrent()

        // Invalidate the BLOCKER's item-0. Pins that the sweep matches on session as well as
        // (slotKey, generation): matching on the pair alone clears inFlight on the VICTIM's
        // queued slot, whose batch then survives session-scoped removal, loads, and is rejected
        // at recordAdmitted -- stuck Empty.
        coord.updateWindow("blocker", NativeAdWindow(visible = emptyList()))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            victim.state.value.slots["item-0"] is NativeAdSlotState.Ready,
            "the other session's slot must still fill; was ${victim.state.value.slots["item-0"]}"
        )
    }

    @Test fun `a sibling of an invalidated slot keeps its in-flight load`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var call = 0
        val platform = fakePlatform { _, count, _ ->
            call += 1
            // The first load must still be IN FLIGHT when the invalidation arrives, or there is no
            // job whose cancellation could take the sibling down with it.
            if (call == 1) gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(call * 100 + it) }, null))
        }
        val coord = coordinator(platform = platform)
        val session = coord.session("s")
        coord.updateWindow("s", windowWith("a", "b"))
        runCurrent()
        assertEquals(1, platform.loadCalls.size, "one batch covering both slots must be in flight")

        // Pins: dropping `a` must NOT cancel the whole in-flight batch. Cancelling costs `b` a
        // load through no fault of its own -- deferred, then resubmitted, spending a second
        // network request for a slot that never left the viewport. The batch runs to completion:
        // `a`'s ad is discarded on arrival because its reservation is no longer live, and `b` is
        // filled from the load already paid for.
        // NOTE there is deliberately no second updateWindow for `b` below.
        // runCurrent, not advanceUntilIdle: the batch is still gated, and advancing virtual time
        // here would run it past the placement's 30s load timeout before the ad could arrive.
        coord.updateWindow("s", windowWith("b"))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, call, "the surviving sibling must not trigger a second platform load")
        assertTrue(
            session.state.value.slots["b"] is NativeAdSlotState.Ready,
            "the surviving sibling must be filled from the original load; was ${session.state.value.slots["b"]}"
        )
        assertEquals(0, coord.managerState().reservedLoads, "no reservation may be left dangling")
    }

}

internal class FakePlatform(
    private val loadFn: suspend (AdPlacement, Int, Long) -> AdAttemptResult<NativeAdPlatformBatch<FakeAd>>,
) : NativeAdPlatform<FakeAd> {
    val destroyed = mutableListOf<FakeAd>()
    val loadCalls = mutableListOf<Triple<AdPlacement, Int, Long>>()
    var bindFailure: Throwable? = null
    var bindGate: CompletableDeferred<Unit>? = null
    val bindStarted = CompletableDeferred<FakeAd>()
    private val callbacks = mutableMapOf<FakeAd, (AdEvent) -> Unit>()
    override suspend fun load(placement: AdPlacement, count: Int, generation: Long): AdAttemptResult<NativeAdPlatformBatch<FakeAd>> {
        loadCalls.add(Triple(placement, count, generation))
        return loadFn(placement, count, generation)
    }
    override suspend fun bindEvents(ad: FakeAd, adInstanceId: String, emit: (AdEvent) -> Unit) {
        bindFailure?.let { throw it }
        callbacks[ad] = emit
        bindGate?.let { gate ->
            bindStarted.complete(ad)
            gate.await()
        }
    }
    fun emit(ad: FakeAd, event: AdEvent) { callbacks[ad]?.invoke(event) }
    override fun destroy(ad: FakeAd) { destroyed.add(ad) }
    override fun responseInfo(ad: FakeAd) = null
    override fun mediaInfo(ad: FakeAd) = null
}
