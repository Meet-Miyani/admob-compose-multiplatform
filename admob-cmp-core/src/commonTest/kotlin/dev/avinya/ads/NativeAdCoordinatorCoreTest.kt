package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdCoordinatorCore
import dev.avinya.ads.internal.NativeAdPlatform
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.internal.NativeMemoryPressure
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
import kotlin.time.Instant

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

    // --- Lifecycle settlement -------------------------------------------------

    /**
     * A load job cancelled before its first dispatch must still settle the placement.
     *
     * `cancelSlotLocked` cancels the in-flight job when nothing it was loading is wanted any
     * more, and on a queued dispatcher that can land before the job body has run at all. A
     * DEFAULT-start coroutine cancelled at that point never runs its body, so `handleCancelled`
     * never executed and `currentJob` stayed non-null forever — after which `submit`,
     * `processNextOrCleanupLocked` and `isIdleLocked` all considered the placement busy and
     * every later demand for it sat at Loading for the rest of the process.
     */
    @Test fun `a load cancelled before its job starts does not wedge the placement`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val coord = coordinator(platform = platform)
        val session = coord.session("s")

        // No runCurrent() between these two: the job is submitted and then cancelled while it
        // is still only queued.
        coord.updateWindow("s", windowWith("a"))
        coord.updateWindow("s", NativeAdWindow(visible = emptyList()))
        advanceUntilIdle()

        coord.updateWindow("s", windowWith("a"))
        advanceUntilIdle()

        assertTrue(
            session.state.value.slots["a"] is NativeAdSlotState.Ready,
            "a placement wedged by a never-started job can never serve again; was " +
                "${session.state.value.slots["a"]}",
        )
    }

    /**
     * Memory pressure during an in-flight load must settle the slot, not strand it.
     *
     * `runCurrent()` rather than `advanceUntilIdle()` is the whole point: the load has to be
     * still IN FLIGHT (a reservation, not yet a record) when the trim arrives. The existing
     * memory-pressure test drains first, so it only ever exercised the record path.
     */
    @Test fun `memory pressure during an in-flight load settles the slot`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = fakePlatform { _, count, _ ->
            gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val coord = coordinator(platform = platform)
        val session = coord.session("s")

        coord.updateWindow("s", windowWith("a"))
        runCurrent()
        assertTrue(session.state.value.slots["a"] is NativeAdSlotState.Loading, "precondition: in flight")

        coord.onMemoryPressure(NativeMemoryPressure.Critical)
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            session.state.value.slots["a"] !is NativeAdSlotState.Loading,
            "a trimmed reservation must settle; a slot stuck Loading is skipped by every later " +
                "reconciliation. Was ${session.state.value.slots["a"]}",
        )
        assertEquals(0, coord.managerState().reservedLoads, "the trimmed reservation must be released")
    }

    /**
     * A generation-scoped close must not close a replacement session.
     *
     * The clock hook re-enters the coordinator from inside `tickLocked`, which the lock allows
     * (it is reentrant on both platforms). That is a deterministic stand-in for the real race:
     * the old overload checked the generation, released the lock, then closed by key alone, so
     * whatever occupied the key by then was destroyed — including a session the consumer had
     * just created and still holds.
     */
    @Test fun `a generation-scoped close cannot close the replacement session`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        var reenter: (() -> Unit)? = null
        val coord = NativeAdCoordinatorCore(
            memoryPolicy = NativeAdMemoryPolicy(),
            platform = platform,
            scope = scope,
            clock = {
                reenter?.let { hook -> reenter = null; hook() }
                Instant.fromEpochSeconds(1000)
            },
        )
        coord.session("feed")
        val staleGeneration = coord.sessionGeneration("feed")!!

        // While the stale close is inside the coordinator, the key is closed and re-created.
        reenter = {
            coord.closeSession("feed")
            coord.session("feed")
        }
        coord.closeSession("feed", staleGeneration)
        advanceUntilIdle()

        assertTrue(
            coord.sessionGeneration("feed") != null,
            "a close authorised for an older generation must not remove the current session",
        )
        assertTrue(
            coord.sessionGeneration("feed") != staleGeneration,
            "precondition: the replacement really is a different generation",
        )
    }

    /**
     * Capacity released by one session is offered to demand another session already wanted.
     *
     * A denied reservation is recorded as deferred and dropped. Nothing re-offered it when
     * capacity appeared, and `reconcileDemands` only runs on a window update — so a scrolling
     * feed recovered by accident on the next scroll while a single inline slot, whose window
     * is published exactly once, stayed Empty for the life of the screen.
     */
    @Test fun `freed capacity is offered to a deferred slot without a new window`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 1),
            platform = platform,
        )
        coord.session("holder")
        coord.updateWindow("holder", windowWith("held"))
        advanceUntilIdle()
        // Mounted records are never eviction candidates, so this genuinely occupies the cap.
        coord.setMounted("holder", "held", true)

        val waiting = coord.session("waiting")
        coord.updateWindow("waiting", windowWith("wanted"))
        advanceUntilIdle()
        assertTrue(
            waiting.state.value.slots["wanted"] !is NativeAdSlotState.Ready,
            "precondition: the second slot must be denied at the hard cap",
        )

        // The only event is the holder going away — no window update for `waiting`.
        coord.closeSession("holder")
        advanceUntilIdle()

        assertTrue(
            waiting.state.value.slots["wanted"] is NativeAdSlotState.Ready,
            "freed capacity must reach demand that is still wanted; was " +
                "${waiting.state.value.slots["wanted"]}",
        )
    }

    /** A trim must not immediately refill what it just reclaimed. */
    @Test fun `a memory trim is not refilled by its own event`() = runTest(dispatcher) {
        val platform = fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        val coord = coordinator(
            memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2),
            platform = platform,
        )
        coord.session("s")
        coord.updateWindow("s", windowWith("a", "b"))
        advanceUntilIdle()
        val loadsBeforeTrim = platform.loadCalls.size

        coord.onMemoryPressure(NativeMemoryPressure.Critical)
        advanceUntilIdle()

        assertEquals(
            loadsBeforeTrim,
            platform.loadCalls.size,
            "a trim exists to lower the footprint; re-offering its own freed capacity would undo it",
        )
    }

    // --- Unshown-ad reuse: session drops ---------------------------------------

    private val otherPlacement = nativePlacement.copy(id = "q", adUnitIds = AdUnitIds(android = "x2", ios = "y2"))

    private fun countingPlatform(): FakePlatform {
        var next = 0
        return fakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(next++) }, null))
        }
    }

    @Test fun `with reuse on a slot that leaves the window unseen gives its ad to the next slot`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        advanceUntilIdle()
        assertTrue(platform.destroyed.isEmpty(), "an unseen ad must be kept, not destroyed")

        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size, "the next slot must reuse the kept ad instead of loading")
        assertTrue(session.state.value.slots["b"] is NativeAdSlotState.Ready)
        val generation = coord.sessionGeneration("s1")!!
        assertEquals(0, coord.acquireForRender("s1", generation, "b", nativePlacement, "r")?.ad?.id)
    }

    @Test fun `with reuse on a deactivated session gives its dropped ad to another session`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()

        coord.deactivateSession("s1")
        assertTrue(platform.destroyed.isEmpty(), "the ad dropped past the inactive anchor must be kept")

        val other = coord.session("s2")
        coord.updateWindow("s2", windowWith("c"))
        advanceUntilIdle()
        assertEquals(1, platform.loadCalls.size)
        assertTrue(other.state.value.slots["c"] is NativeAdSlotState.Ready)
    }

    @Test fun `with reuse on a closed session gives its ad to the next session`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        coord.closeSession("s1")
        val next = coord.session("s2")
        coord.updateWindow("s2", windowWith("b"))
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size)
        assertTrue(next.state.value.slots["b"] is NativeAdSlotState.Ready)
        assertTrue(platform.destroyed.isEmpty())
    }

    @Test fun `an ad that reached a renderer is destroyed rather than kept`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        val generation = coord.sessionGeneration("s1")!!
        coord.updateWindow("s1", generation, windowWith("a"))
        advanceUntilIdle()
        val lease = coord.acquireForRender("s1", generation, "a", nativePlacement, "r")!!
        coord.releaseRenderer("s1", generation, "a", nativePlacement, lease.recordId, "r")

        coord.updateWindow("s1", generation, NativeAdWindow(visible = emptyList()))

        assertEquals(listOf(0), platform.destroyed.map { it.id })
        coord.updateWindow("s1", generation, windowWith("b"))
        advanceUntilIdle()
        assertEquals(2, platform.loadCalls.size)
    }

    @Test fun `an ad that reported an impression is destroyed rather than kept`() = runTest(dispatcher) {
        val ad = FakeAd(0)
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null)) }
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        platform.emit(ad, AdEvent.Impression("p"))

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        assertEquals(listOf(ad), platform.destroyed)
    }

    @Test fun `an ad that reported a click is destroyed rather than kept`() = runTest(dispatcher) {
        val ad = FakeAd(0)
        val platform = fakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null)) }
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        platform.emit(ad, AdEvent.Clicked("p"))

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        assertEquals(listOf(ad), platform.destroyed)
    }

    @Test fun `a kept ad is never given to a slot of a different placement`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        coord.session("s2")
        coord.updateWindow("s2", NativeAdWindow(visible = listOf(NativeAdSlot("b", otherPlacement))))
        advanceUntilIdle()
        assertEquals(2, platform.loadCalls.size, "a different ad unit must load its own ad")

        val sameUnitOtherOptions = nativePlacement.copy(nativeOptions = NativeAdOptions(disableImageLoading = true))
        coord.updateWindow("s2", NativeAdWindow(visible = listOf(NativeAdSlot("c", sameUnitOtherOptions))))
        advanceUntilIdle()
        assertEquals(3, platform.loadCalls.size, "the same unit with other options must load its own ad")
        assertTrue(platform.destroyed.isEmpty(), "every unseen ad stays kept for its own placement")
    }

    @Test fun `a third kept ad of one placement destroys the oldest`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.tickForTest(1.minutes)
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        coord.tickForTest(1.minutes)
        coord.updateWindow("s1", windowWith("a", "b", "c"))
        advanceUntilIdle()

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        assertEquals(listOf(0), platform.destroyed.map { it.id }, "the oldest of three kept ads is dropped")
        assertEquals(2, coord.managerState().loadedAds)
    }

    @Test fun `an ad past three quarters of its lifetime is destroyed rather than kept`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.tickForTest(46.minutes)

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        assertEquals(listOf(0), platform.destroyed.map { it.id })
    }

    @Test fun `a reused ad keeps routing its events`() = runTest(dispatcher) {
        val ad = FakeAd(0)
        var loads = 0
        val platform = fakePlatform { _, _, _ ->
            loads += 1
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null))
        }
        val emitted = mutableListOf<AdEvent>()
        val coord = coordinator(platform = platform, eventSink = emitted::add)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        platform.emit(ad, AdEvent.Impression("p"))

        assertEquals(listOf<AdEvent>(AdEvent.Impression("p")), emitted)
        assertEquals(1, loads)
    }

    @Test fun `a kept ad fills a slot that was waiting for capacity`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform, memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 1))
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        val waiting = coord.session("s2")
        coord.updateWindow("s2", NativeAdWindow(visible = emptyList(), prefetchAhead = listOf(NativeAdSlot("x", nativePlacement))))
        advanceUntilIdle()
        assertTrue(waiting.state.value.slots["x"] !is NativeAdSlotState.Retained, "x waits: the soft limit is full")

        coord.closeSession("s1")
        advanceUntilIdle()

        assertTrue(waiting.state.value.slots["x"] is NativeAdSlotState.Retained, "the kept ad must fill the waiting slot")
        assertEquals(1, platform.loadCalls.size)
    }

    @Test fun `a kept ad that reports an impression while kept is never reused`() = runTest(dispatcher) {
        val first = FakeAd(0)
        var next = 1
        val platform = fakePlatform { _, count, _ ->
            val ads = if (next == 1) listOf(first) else (0 until count).map { FakeAd(next + it) }
            next += count
            AdAttemptResult.Success(NativeAdPlatformBatch(ads, null))
        }
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        platform.emit(first, AdEvent.Impression("p"))

        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        assertEquals(2, platform.loadCalls.size, "an impressed spare must not be adopted")
        assertEquals(listOf(first), platform.destroyed)
    }

    // --- Unshown-ad reuse: lifetime, purges and capacity ------------------------

    @Test fun `a kept ad past three quarters of its lifetime is swept and never reused`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        coord.tickForTest(46.minutes)

        assertEquals(listOf(0), platform.destroyed.map { it.id }, "the sweep must destroy an aged-out spare")
        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()
        assertEquals(2, platform.loadCalls.size)
    }

    @Test fun `clear destroys every kept ad exactly once`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        coord.clear()

        assertEquals(listOf(0, 1), platform.destroyed.map { it.id }.sorted())
        coord.updateWindow("s1", windowWith("c"))
        advanceUntilIdle()
        assertEquals(2, platform.loadCalls.size)
    }

    @Test fun `consent revocation destroys every kept ad exactly once`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        coord.onConsentRevoked()

        assertEquals(listOf(0, 1), platform.destroyed.map { it.id }.sorted())
        coord.session("s2")
        coord.updateWindow("s2", windowWith("c"))
        advanceUntilIdle()
        assertEquals(2, platform.loadCalls.size)
    }

    @Test fun `turning reuse off destroys every kept ad`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        coord.setReuseUnshownAds(false)

        assertEquals(listOf(0, 1), platform.destroyed.map { it.id }.sorted())
        assertEquals(0, coord.managerState().loadedAds)
    }

    @Test fun `memory pressure trims a kept ad before an ad a slot owns`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform, memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 3))
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        coord.session("s2")
        coord.updateWindow("s2", NativeAdWindow(visible = listOf(NativeAdSlot("x", otherPlacement))))
        advanceUntilIdle()

        coord.onMemoryPressure(NativeMemoryPressure.Moderate)

        assertEquals(listOf(0), platform.destroyed.map { it.id })
    }

    @Test fun `visible demand at the hard limit evicts a kept ad first`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform, memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        coord.session("s2")
        coord.updateWindow("s2", NativeAdWindow(visible = listOf(NativeAdSlot("x", otherPlacement))))
        advanceUntilIdle()

        coord.updateWindow(
            "s2",
            NativeAdWindow(visible = listOf(NativeAdSlot("x", otherPlacement), NativeAdSlot("y", otherPlacement))),
        )
        advanceUntilIdle()

        assertEquals(listOf(0), platform.destroyed.map { it.id })
        assertEquals(3, platform.loadCalls.size)
    }

    @Test fun `a kept ad does not block prefetch for another placement`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform, memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))

        coord.session("s2")
        coord.updateWindow(
            "s2",
            NativeAdWindow(visible = emptyList(), prefetchAhead = listOf(NativeAdSlot("x", otherPlacement))),
        )
        advanceUntilIdle()

        assertEquals(2, platform.loadCalls.size, "speculative demand must retire the kept ad rather than wait")
        assertEquals(listOf(0), platform.destroyed.map { it.id })
    }

    @Test fun `a reaped inactive session destroys its anchor rather than keeping it`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.deactivateSession("s1")

        coord.tickForTest(31.minutes)

        assertEquals(listOf(0), platform.destroyed.map { it.id })
    }

    // --- Unshown-ad reuse: late arrivals ----------------------------------------

    private fun firstLoadGated(gate: CompletableDeferred<Unit>): FakePlatform {
        var next = 0
        return fakePlatform { _, count, _ ->
            if (next == 0) gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(next++) }, null))
        }
    }

    @Test fun `with reuse on an ad that lands after its slot left is kept and reused`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = firstLoadGated(gate)
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(platform.destroyed.isEmpty(), "the late ad must be kept")

        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()
        assertEquals(1, platform.loadCalls.size)
        assertTrue(session.state.value.slots["b"] is NativeAdSlotState.Ready)
    }

    @Test fun `with reuse off a load whose slot left still makes the next slot load`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = firstLoadGated(gate)
        val coord = coordinator(platform = platform)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        assertEquals(2, platform.loadCalls.size)
    }

    @Test fun `a late ad with no room under the soft limit is destroyed`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var next = 0
        val platform = fakePlatform { placement, count, _ ->
            if (placement == otherPlacement) gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(next++) }, null))
        }
        val coord = coordinator(platform = platform, memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        coord.setReuseUnshownAds(true)
        coord.session("s0")
        coord.updateWindow("s0", windowWith("held"))
        advanceUntilIdle()
        coord.session("s1")
        coord.updateWindow("s1", NativeAdWindow(visible = listOf(NativeAdSlot("late", otherPlacement))))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1), platform.destroyed.map { it.id }, "no spare permit fits, so the late ad is destroyed")
        assertEquals(1, coord.managerState().loadedAds)
        assertEquals(0, coord.managerState().reservedLoads)
    }

    @Test fun `a late ad is destroyed when reuse was turned off before it landed`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = firstLoadGated(gate)
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()
        coord.setReuseUnshownAds(false)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0), platform.destroyed.map { it.id })
        assertEquals(0, coord.managerState().reservedLoads)
    }

    @Test fun `a late ad from before a clear is destroyed`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = firstLoadGated(gate)
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()
        coord.clear()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0), platform.destroyed.map { it.id })
        assertEquals(0, coord.managerState().reservedLoads)
    }

    @Test fun `a late ad whose event binding fails is destroyed and leaves no permit behind`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = firstLoadGated(gate)
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()
        platform.bindFailure = IllegalStateException("bind")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0), platform.destroyed.map { it.id })
        assertEquals(0, coord.managerState().reservedLoads)
        assertEquals(0, coord.managerState().loadedAds)
    }

    @Test fun `a queued slot adopts an ad that landed while it waited`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = firstLoadGated(gate)
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        // `a` leaves while its ad is loading; `b` queues behind that load.
        coord.updateWindow("s1", windowWith("b"))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size, "b must adopt the late ad instead of loading")
        assertTrue(session.state.value.slots["b"] is NativeAdSlotState.Ready)
    }

    @Test fun `a late ad keeps routing its events after it is reused`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val ad = FakeAd(0)
        var loads = 0
        val platform = fakePlatform { _, _, _ ->
            loads += 1
            gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null))
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
        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        platform.emit(ad, AdEvent.Impression("p"))

        assertEquals(listOf<AdEvent>(AdEvent.Impression("p")), emitted)
        assertEquals(1, loads)
    }

    @Test fun `retiring a spare to admit prefetch keeps the new placement's scheduler registered`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var next = 0
        val platform = fakePlatform { placement, count, _ ->
            if (placement == otherPlacement) gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(next++) }, null))
        }
        val coord = coordinator(platform = platform, memoryPolicy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        coord.session("s2")
        coord.updateWindow("s2", NativeAdWindow(visible = emptyList(), prefetchAhead = listOf(NativeAdSlot("x", otherPlacement))))
        runCurrent()

        assertEquals(1, coord.schedulerCount(), "the in-flight placement must stay registered")
        coord.clear()
        assertEquals(0, coord.managerState().reservedLoads, "clear must reach the in-flight placement")
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun `a load cancelled by a memory trim is destroyed rather than kept`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = firstLoadGated(gate)
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList(), prefetchAhead = listOf(NativeAdSlot("x", nativePlacement))))
        runCurrent()
        coord.onMemoryPressure(NativeMemoryPressure.Critical)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(0), platform.destroyed.map { it.id }, "the trim cancelled this load, so its ad must not come back")
        assertEquals(0, coord.managerState().loadedAds)
    }

    @Test fun `with reuse on an abandoned load does not retry`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val platform = fakePlatform { _, _, _ ->
            gate.await()
            AdAttemptResult.Failure(AdError(code = "NETWORK_ERROR", message = "retry"))
        }
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, platform.loadCalls.size, "nobody wants this load any more, so a retryable failure must not retry")
        assertEquals(0, coord.managerState().reservedLoads)
    }

    // Regression guards: with reuse off (the default) every drop destroys, exactly as before.

    @Test fun `with reuse off a slot that leaves the window destroys its ad`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        assertEquals(listOf(0), platform.destroyed.map { it.id })
        assertEquals(2, platform.loadCalls.size)
    }

    @Test fun `with reuse off a deactivation destroys the ad past the anchor`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a", "b"))
        advanceUntilIdle()

        coord.deactivateSession("s1")

        assertEquals(1, platform.destroyed.size)
    }

    @Test fun `with reuse off closing a session destroys its ad`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        coord.closeSession("s1")

        assertEquals(listOf(0), platform.destroyed.map { it.id })
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
