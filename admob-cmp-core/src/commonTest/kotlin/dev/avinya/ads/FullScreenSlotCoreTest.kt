package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FullScreenSlotCoreTest {

    @Test
    fun `load happy path drives Idle to Loading to Loaded and emits AdEvent Loaded`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val globalEvents = testGlobalEvents()
            val slot = FakeFullScreenSlot(testPlacement, globalEvents, unblockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))

            val result = slot.load()
            assertIs<AdLoadState.Loaded>(result)
            assertTrue(globalEvents.replayCache.any { it is AdEvent.Loaded })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `load failure transitions to Failed and emits LoadFailed`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val globalEvents = testGlobalEvents()
            val slot = FakeFullScreenSlot(testPlacement, globalEvents, unblockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Failure(AdError.message("no fill")))

            val result = slot.load()
            assertIs<AdLoadState.Failed>(result)
            assertTrue(globalEvents.replayCache.any { it is AdEvent.LoadFailed })
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `consent gate closed returns Failed with consent_required without calling loadAd`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val slot = FakeFullScreenSlot(testPlacement, testGlobalEvents(), blockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Success("should-not-be-called"))

            val result = slot.load()
            val failed = assertIs<AdLoadState.Failed>(result)
            assertEquals(AdErrorCode.CONSENT_REQUIRED, failed.error.code)
            assertEquals(0, slot.loadCalls.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `TTL expiry makes show return NotReady`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            // Settable clock: advance wall time explicitly instead of scripting one tick
            // per internal clock() read, so the test survives implementation changes in
            // how often the core samples the clock.
            var now = Instant.fromEpochSeconds(1000)
            val clock: () -> Instant = { now }

            val slot = FakeFullScreenSlot(testPlacement, testGlobalEvents(), unblockedAdRequestError(), clock)
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))

            slot.load()
            assertTrue(slot.availability().isReady)

            now = Instant.fromEpochSeconds(1000 + 3601) // 3601s gap > 1h TTL → expired
            val showResult = slot.show()
            assertIs<AdShowResult.NotReady>(showResult)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelled show keeps presentation marked in flight`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val presentationDeltas = mutableListOf<Int>()
            val terminalResult = CompletableDeferred<AdShowResult>()
            val slot = FakeFullScreenSlot(
                placement = testPlacement,
                globalEvents = testGlobalEvents(),
                adRequestBlockedError = unblockedAdRequestError(),
                clock = tickClock(),
                onPresentationChanged = { presentationDeltas += it },
                presentHandler = { _, _, _ -> terminalResult.await() }
            )
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))

            slot.load()
            val showJob = launch { slot.show() }
            runCurrent()

            assertEquals(listOf(1), presentationDeltas)

            showJob.cancelAndJoin()

            assertEquals(listOf(1), presentationDeltas)
            assertTrue(showJob.isCancelled)
            assertTrue(slot.destroyedAds.isEmpty())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `clear during in-flight load destroys the late ad and load reports cleared`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loadGate = CompletableDeferred<Unit>()
            val slot = FakeFullScreenSlot(
                placement = testPlacement,
                globalEvents = testGlobalEvents(),
                adRequestBlockedError = unblockedAdRequestError(),
                clock = tickClock(),
                loadHandler = {
                    loadGate.await()
                    AdAttemptResult.Success("late-ad")
                }
            )

            val loadJob = launch { slot.load() }
            runCurrent()
            assertEquals(1, slot.loadCalls.size)

            // Clear while loadAd is still suspended: the generation bump must make the
            // in-flight attempt's eventual result a no-op rather than repopulating the
            // cleared cache.
            slot.clear()
            loadGate.complete(Unit)
            loadJob.join()

            assertEquals(listOf("late-ad"), slot.destroyedAds)
            assertFalse(slot.availability().isReady)
            assertEquals(AdLoadState.Idle, slot.loadState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `clear during active presentation does not affect the in-flight show`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val terminalResult = CompletableDeferred<AdShowResult>()
            val slot = FakeFullScreenSlot(
                placement = testPlacement,
                globalEvents = testGlobalEvents(),
                adRequestBlockedError = unblockedAdRequestError(),
                clock = tickClock(),
                presentHandler = { _, _, _ -> terminalResult.await() }
            )
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()

            val showJob = launch { slot.show() }
            runCurrent()

            // clear() only reaches the FIFO cache (already empty: the ad was removed from
            // the deque the moment show() selected it); it must not reach into an active
            // presentation and must not double-destroy the presenting ad.
            slot.clear()
            assertTrue(slot.destroyedAds.isEmpty())

            terminalResult.complete(AdShowResult.Shown)
            showJob.join()

            assertEquals(listOf("ad1"), slot.destroyedAds)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelled show after SDK hand-off leaves presence to the terminal callback`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val presentationDeltas = mutableListOf<Int>()
            // Captured from outside the show() coroutine, mirroring a real retained platform
            // delegate: the terminal callback fires independently of whether the coroutine
            // that originally called presentAd() is still alive.
            var capturedPresentation: dev.avinya.ads.internal.FullScreenPresentationHandle? = null
            val slot = FakeFullScreenSlot(
                placement = testPlacement,
                globalEvents = testGlobalEvents(),
                adRequestBlockedError = unblockedAdRequestError(),
                clock = tickClock(),
                onPresentationChanged = { presentationDeltas += it },
                onPresentationHandOff = { capturedPresentation = it }
            )
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()

            val showJob = launch { slot.show() }
            runCurrent()
            assertEquals(listOf(1), presentationDeltas)

            // presentAd() has already handed the presentation off to (simulated) SDK callbacks
            // by this point, so cancelling the caller must NOT release presence itself — only
            // the terminal callback below may.
            showJob.cancelAndJoin()
            assertEquals(listOf(1), presentationDeltas)
            assertTrue(slot.destroyedAds.isEmpty())

            capturedPresentation!!.close(wasShown = true)

            assertEquals(listOf(1, -1), presentationDeltas)
            assertEquals(listOf("ad1"), slot.destroyedAds)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `reloadAfterShow reload is dropped when a clear supersedes its generation`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val reloadingPlacement = testPlacement.copy(cachePolicy = AdCachePolicy(reloadAfterShow = true))
            val slot = FakeFullScreenSlot(reloadingPlacement, testGlobalEvents(), unblockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()

            slot.enqueueLoadResult(AdAttemptResult.Success("reload-ad"))
            slot.show()

            // show() schedules the reload lazily (reloadScope.launch(LAZY) then job.start());
            // clear() immediately after must bump the generation before that reload's
            // loadForGeneration call observes it, so the reload's own result never
            // repopulates a cache the caller just asked to be empty.
            slot.clear()
            advanceUntilIdle()

            assertFalse(slot.availability().isReady)
            assertEquals(AdLoadState.Idle, slot.loadState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `FIFO multi-ad cache loads maxSize ads and shows oldest first`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val multiPlacement = testPlacement.copy(cachePolicy = AdCachePolicy(maxSize = 3))
            val slot = FakeFullScreenSlot(multiPlacement, testGlobalEvents(), unblockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.enqueueLoadResult(AdAttemptResult.Success("ad2"))
            slot.enqueueLoadResult(AdAttemptResult.Success("ad3"))

            val result = slot.load()
            assertIs<AdLoadState.Loaded>(result)
            assertEquals(3, slot.availability().cachedCount)

            slot.show()
            assertEquals("ad1", slot.presentedAds[0])
            slot.show()
            assertEquals("ad2", slot.presentedAds[1])
            slot.show()
            assertEquals("ad3", slot.presentedAds[2])
            assertIs<AdShowResult.NotReady>(slot.show())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `concurrent load calls serialize to single loadAd invocation when cache already fresh`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val slot = FakeFullScreenSlot(testPlacement, testGlobalEvents(), unblockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))

            slot.load()
            assertEquals(1, slot.loadCalls.size)

            slot.load()
            assertEquals(1, slot.loadCalls.size)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `show proceeds while a background load sits in retry backoff`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val retryingPlacement = testPlacement.copy(
                retryPolicy = AdRetryPolicy(maxAttempts = 3, initialDelay = 10.seconds)
            )
            var loadAttempts = 0
            val slot = FakeFullScreenSlot(
                placement = retryingPlacement,
                globalEvents = testGlobalEvents(),
                adRequestBlockedError = unblockedAdRequestError(),
                clock = tickClock(),
                loadHandler = {
                    loadAttempts++
                    AdAttemptResult.Failure(AdError(code = "NETWORK_ERROR", message = "offline"))
                }
            )

            // A load that keeps failing enters retry backoff (maxAttempts = 3, so the first
            // failure schedules a delayed retry instead of completing).
            val backgroundLoad = launch { slot.load() }
            runCurrent()
            assertEquals(1, loadAttempts, "first attempt should have run and entered backoff")

            // While that load sits in backoff, a concurrent show() must not be blocked behind
            // loadMutex for the remaining delay — it must resolve (here, NotReady since nothing
            // is cached) well within the backoff window.
            val showResult = withTimeoutOrNull(1_000) { slot.show() }
            assertNotNull(showResult, "show() must not block behind a load in retry backoff")
            assertIs<AdShowResult.NotReady>(showResult)

            backgroundLoad.cancel()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a throwing presence callback does not strand the presentation token`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val arbiter = FullScreenPresentationArbiter()
                val throwingSlot = FakeFullScreenSlot(
                    testPlacement,
                    testGlobalEvents(),
                    unblockedAdRequestError(),
                    tickClock(),
                    onPresentationChanged = { error("presence accounting exploded") },
                    arbiter = arbiter
                )
                throwingSlot.enqueueLoadResult(AdAttemptResult.Success("ad-1"))
                throwingSlot.load()

                val result = throwingSlot.show()
                advanceUntilIdle()

                assertIs<AdShowResult.Shown>(
                    result,
                    "a throwing presence callback must not fail the show"
                )
                assertFalse(
                    arbiter.isHeld,
                    "the token must be released even when the presence callback throws"
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `canPresent evaluated exactly once per show`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val slot = FakeFullScreenSlot(testPlacement, testGlobalEvents(), unblockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()
            slot.canPresentInvocations = 0

            slot.show()

            assertEquals(
                1,
                slot.canPresentInvocations,
                "canPresent() must be evaluated once per show(), on the main dispatcher"
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `canPresent error still blocks show`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val slot = FakeFullScreenSlot(testPlacement, testGlobalEvents(), unblockedAdRequestError(), tickClock())
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()
            slot.canPresentResult = AdError.message("No root view controller.")

            val result = slot.show()

            assertTrue(
                result is AdShowResult.Failed,
                "a canPresent() error must still fail show(), got $result"
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an unexpected throwable during load does not strand the slot in Loading`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val globalEvents = testGlobalEvents()
            val slot = FakeFullScreenSlot(
                testPlacement,
                globalEvents,
                unblockedAdRequestError(),
                tickClock(),
                loadHandler = { throw IllegalStateException("beta SDK mapper blew up") }
            )

            runCatching { slot.load() }

            // Pins: an arbitrary Throwable escaping the load path before completeLoad() runs
            // must not strand loadState at Loading with no live operation behind it — every
            // later load would coalesce onto that dead state.
            assertTrue(
                slot.loadState.value !is AdLoadState.Loading,
                "an unexpected throwable must not strand the slot in Loading; was ${slot.loadState.value}"
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `expired inventory does not leave loadState Loaded over an empty cache`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                var now = Instant.fromEpochSeconds(1000)
                val clock: () -> Instant = { now }
                val slot = FakeFullScreenSlot(testPlacement, testGlobalEvents(), unblockedAdRequestError(), clock)
                slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
                slot.load()
                assertIs<AdLoadState.Loaded>(slot.loadState.value)

                now = Instant.fromEpochSeconds(1000 + 3601) // > 1h TTL

                // Pins: cache and loadState must agree once inventory expires. With the sole ad
                // expired nothing is selected, so a loadState derived from selection rather than
                // from what remains would keep claiming Loaded over an empty cache —
                // isReady == false and show() == NotReady while loadState still said Loaded.
                assertTrue(!slot.availability().isReady, "expired inventory must not report ready")
                assertEquals(
                    AdLoadState.Idle,
                    slot.loadState.value,
                    "loadState must not claim Loaded when every cached ad has expired"
                )
                assertTrue(
                    slot.destroyedAds.contains("ad1"),
                    "the expired ad must be retired, not retained until some later mutation"
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `show with only expired inventory also settles loadState to Idle`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                var now = Instant.fromEpochSeconds(1000)
                val clock: () -> Instant = { now }
                val slot = FakeFullScreenSlot(testPlacement, testGlobalEvents(), unblockedAdRequestError(), clock)
                slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
                slot.load()

                now = Instant.fromEpochSeconds(1000 + 3601)
                assertIs<AdShowResult.NotReady>(slot.show())

                assertEquals(AdLoadState.Idle, slot.loadState.value)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a presentation that fails before hand-off still emits exactly one ShowFailed`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val globalEvents = testGlobalEvents()
                val error = AdError.message("no root view controller")
                val slot = FakeFullScreenSlot(
                    testPlacement,
                    globalEvents,
                    unblockedAdRequestError(),
                    tickClock(),
                    failBeforeHandOff = AdShowResult.Failed(error)
                )
                slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
                slot.load()
                val events = mutableListOf<AdEvent>()
                val collector = launch { slot.events.collect { events.add(it) } }
                advanceUntilIdle()

                val result = slot.show()
                advanceUntilIdle()

                // Pins: a RETURNED Failed must emit ShowFailed, not just preparation errors and
                // THROWN exceptions. Activity/rootViewController resolution failing before SDK
                // callbacks are installed returns Failed without throwing, and must not hand the
                // host a failed suspend result with no corresponding event.
                assertIs<AdShowResult.Failed>(result)
                assertEquals(
                    1,
                    events.count { it is AdEvent.ShowFailed },
                    "a returned Failed with no platform callback must emit exactly one ShowFailed; got $events"
                )
                collector.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a presentation failure already reported by the platform is not double-emitted`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val globalEvents = testGlobalEvents()
                val slot = FakeFullScreenSlot(testPlacement, globalEvents, unblockedAdRequestError(), tickClock())
                slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
                slot.load()
                // The fake hands off and closes the handle itself, standing in for a platform
                // whose SDK callback already owns the terminal event.
                slot.enqueueShowResult(AdShowResult.Failed(AdError.message("sdk said no")))
                val events = mutableListOf<AdEvent>()
                val collector = launch { slot.events.collect { events.add(it) } }
                advanceUntilIdle()

                slot.show()
                advanceUntilIdle()

                assertEquals(
                    0,
                    events.count { it is AdEvent.ShowFailed },
                    "the core must not duplicate a terminal event the platform owns; got $events"
                )
                collector.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a presentation that never reaches the SDK releases the process-wide token`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val arbiter = FullScreenPresentationArbiter()
                val stalling = FakeFullScreenSlot(
                    testPlacement,
                    testGlobalEvents(),
                    unblockedAdRequestError(),
                    tickClock(),
                    arbiter = arbiter,
                    stallBeforeHandOff = true
                )
                stalling.enqueueLoadResult(AdAttemptResult.Success("ad1"))
                stalling.load()

                assertIs<AdShowResult.Failed>(
                    stalling.show(),
                    "a stalled pre-hand-off show must fail, not hang"
                )

                // The real damage was never the one hung call — it was that the process-wide
                // token stayed held, blocking every other full-screen slot.
                val other = FakeFullScreenSlot(
                    testPlacement.copy(id = "other_slot"),
                    testGlobalEvents(),
                    unblockedAdRequestError(),
                    tickClock(),
                    arbiter = arbiter
                )
                other.enqueueLoadResult(AdAttemptResult.Success("ad2"))
                other.load()
                assertIs<AdShowResult.Shown>(
                    other.show(),
                    "a second slot must present after the stalled one timed out"
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a stalled pre-hand-off show emits exactly one ShowFailed for the stalled slot`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val arbiter = FullScreenPresentationArbiter()
                val stalling = FakeFullScreenSlot(
                    testPlacement,
                    testGlobalEvents(),
                    unblockedAdRequestError(),
                    tickClock(),
                    arbiter = arbiter,
                    stallBeforeHandOff = true
                )
                stalling.enqueueLoadResult(AdAttemptResult.Success("ad1"))
                stalling.load()

                val events = mutableListOf<AdEvent>()
                val collector = launch { stalling.events.collect { events.add(it) } }
                advanceUntilIdle()

                val result = stalling.show()
                advanceUntilIdle()

                // The watchdog itself performs the terminal close via closeIfCoreOwned() when
                // it fires, so the plain `handle.close(...)` call immediately after
                // presentAd() returns in show() finds the handle already CLOSED and returns
                // false. If ShowFailed emission were gated on THAT return value alone (the
                // brief's original Step 4 snippet), this event would silently never fire for
                // exactly this failure mode — the one this watchdog exists to handle.
                assertIs<AdShowResult.Failed>(result)
                assertEquals(
                    1,
                    events.count { it is AdEvent.ShowFailed },
                    "a stalled pre-hand-off show must emit exactly one ShowFailed; got $events"
                )
                collector.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `a full-screen load that never calls back times out`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val slot = FakeFullScreenSlot(
                testPlacement,
                testGlobalEvents(),
                unblockedAdRequestError(),
                tickClock(),
                loadHandler = { kotlinx.coroutines.awaitCancellation() }
            )

            assertIs<AdLoadState.Failed>(slot.load())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `rewarded presentation delivers reward before dismiss`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val globalEvents = testGlobalEvents()
            val placement = testPlacement.copy(format = AdFormat.Rewarded)
            val slot = FakeFullScreenSlot(
                placement,
                globalEvents,
                unblockedAdRequestError(),
                tickClock(),
                presentHandler = { _, _, delivery ->
                    requireNotNull(delivery).deliver(AdReward(1_000L, "coin"))
                    AdShowResult.Shown
                }
            )
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()

            val callbacks = mutableListOf<AdReward>()
            val result = slot.showRewardedForTest(testPlacement.fullScreenOptions) { callbacks.add(it) }

            assertIs<AdShowResult.Shown>(result)
            assertEquals(1, callbacks.size)
            assertTrue(globalEvents.replayCache.any { it is AdEvent.RewardEarned })
            assertFalse(slot.destroyedAds.contains("ad1"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `rewarded presentation delivers reward after dismiss`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val globalEvents = testGlobalEvents()
            val placement = testPlacement.copy(format = AdFormat.Rewarded)
            val slot = FakeFullScreenSlot(
                placement,
                globalEvents,
                unblockedAdRequestError(),
                tickClock(),
                presentHandler = { _, _, _ -> AdShowResult.Shown }
            )
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()

            val callbacks = mutableListOf<AdReward>()
            val result = slot.showRewardedForTest(testPlacement.fullScreenOptions) { callbacks.add(it) }

            val delivery = requireNotNull(slot.rewardDelivery)
            delivery.deliver(AdReward(1_000L, "coin"))

            assertIs<AdShowResult.Shown>(result)
            assertEquals(1, callbacks.size)
            assertTrue(globalEvents.replayCache.any { it is AdEvent.RewardEarned })
            assertFalse(slot.destroyedAds.contains("ad1"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `failed rewarded presentation destroys ad`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val placement = testPlacement.copy(format = AdFormat.Rewarded)
            val slot = FakeFullScreenSlot(
                placement,
                testGlobalEvents(),
                unblockedAdRequestError(),
                tickClock(),
                presentHandler = { _, _, _ -> AdShowResult.Failed(AdError.message("err")) }
            )
            slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
            slot.load()

            val result = slot.showRewardedForTest(testPlacement.fullScreenOptions) {}

            assertIs<AdShowResult.Failed>(result)
            assertTrue(slot.destroyedAds.contains("ad1"))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `show with audio options applies overrides and restores on success`() = runSlotTest {
        val audioController = RecordingAudioController()
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            audioController = audioController
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val options = FullScreenAdOptions(audioMuted = true, audioVolume = 0.5f)
        val result = slot.show(options)

        assertIs<AdShowResult.Shown>(result)
        assertEquals(true, audioController.appliedMuted)
        assertEquals(0.5f, audioController.appliedVolume)
        assertEquals(1, audioController.restoreCount)
    }

    @Test
    fun `show with audio options restores on presentation failure`() = runSlotTest {
        val audioController = RecordingAudioController()
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            audioController = audioController,
            presentHandler = { _, _, _ -> AdShowResult.Failed(AdError.message("show failed")) }
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val options = FullScreenAdOptions(audioMuted = true)
        val result = slot.show(options)

        assertIs<AdShowResult.Failed>(result)
        assertEquals(1, audioController.restoreCount)
    }

    // ---------------------------------------------------------------------------------
    // Audio override ownership. The override used to be applied outside showInternal's
    // try and restored in its finally, which leaked the process-wide presentation gate,
    // restored audio mid-ad on caller cancellation, and let a failing restore replace the
    // primary result. FullScreenPresentationHandle now owns the whole lifetime.
    // ---------------------------------------------------------------------------------

    @Test
    fun `audio apply failure retires the ad and releases the process-wide token`() = runSlotTest {
        val arbiter = FullScreenPresentationArbiter()
        val events = testGlobalEvents()
        val failures = mutableListOf<AdEvent.ShowFailed>()
        val collector = launch {
            events.collect { if (it is AdEvent.ShowFailed) failures += it }
        }
        val audioController = RecordingAudioController(failOnApply = RuntimeException("audio exploded"))
        val slot = FakeFullScreenSlot(
            testPlacement,
            events,
            unblockedAdRequestError(),
            tickClock(),
            arbiter = arbiter,
            audioController = audioController
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val result = slot.show(FullScreenAdOptions(audioMuted = true))
        advanceUntilIdle()

        assertIs<AdShowResult.Failed>(result)
        // The ad was already out of the cache when the override threw, so the handle's terminal
        // close is the only thing that can retire it.
        assertEquals(listOf("ad1"), slot.destroyedAds)
        // The headline: a stranded token here blocks EVERY later full-screen ad for the process.
        assertFalse(arbiter.isHeld, "the arbiter token must not survive an audio apply failure")
        assertEquals(1, failures.size, "exactly one ShowFailed must be emitted")
        assertTrue(
            failures.single().error.message.contains("audio", ignoreCase = true),
            "the error should name audio, not report a generic presentation failure"
        )

        // And the slot is genuinely reusable, which is what a leaked token would prevent.
        slot.enqueueLoadResult(AdAttemptResult.Success("ad2"))
        slot.load()
        assertIs<AdShowResult.Shown>(slot.show())
        collector.cancel()
    }

    @Test
    fun `presence accounting returns to zero when the audio apply fails`() = runSlotTest {
        val deltas = mutableListOf<Int>()
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            onPresentationChanged = { deltas += it },
            audioController = RecordingAudioController(failOnApply = RuntimeException("nope"))
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        slot.show(FullScreenAdOptions(audioVolume = 0.25f))
        advanceUntilIdle()

        assertEquals(listOf(1, -1), deltas, "presence must be balanced even when the override throws")
    }

    @Test
    fun `cancelling show after hand-off defers the audio restore to the platform close`() = runSlotTest {
        val audioController = RecordingAudioController()
        var captured: dev.avinya.ads.internal.FullScreenPresentationHandle? = null
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            onPresentationHandOff = { captured = it },
            audioController = audioController
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val job = launch { slot.show(FullScreenAdOptions(audioMuted = true)) }
        advanceUntilIdle()
        assertNotNull(captured, "the presentation should have reached hand-off")

        job.cancelAndJoin()
        advanceUntilIdle()
        // The ad is STILL ON SCREEN: the SDK owns the presentation after hand-off. Restoring here
        // would end the documented per-presentation override while the user is watching.
        assertEquals(0, audioController.restoreCount, "caller cancellation must not restore audio")

        captured!!.close(wasShown = true)
        advanceUntilIdle()
        assertEquals(1, audioController.restoreCount, "the terminal platform close restores exactly once")
    }

    @Test
    fun `hand-off timeout before the SDK is reached restores audio exactly once`() = runSlotTest {
        val audioController = RecordingAudioController()
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            audioController = audioController,
            stallBeforeHandOff = true
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val result = slot.show(FullScreenAdOptions(audioMuted = true))
        advanceUntilIdle()

        assertIs<AdShowResult.Failed>(result)
        // Pre-hand-off the core still owns the presentation, so the watchdog's close must restore.
        assertEquals(1, audioController.restoreCount)
    }

    @Test
    fun `a throwing audio restore does not change a successful show result`() = runSlotTest {
        val arbiter = FullScreenPresentationArbiter()
        val audioController = RecordingAudioController(failOnRestore = RuntimeException("restore exploded"))
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            arbiter = arbiter,
            audioController = audioController
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val result = slot.show(FullScreenAdOptions(audioMuted = true))
        advanceUntilIdle()

        assertIs<AdShowResult.Shown>(result)
        assertEquals(1, audioController.restoreCount)
        // A throw escaping runAudioRestore would abort the rest of the terminal close, which is
        // what actually releases the arbiter and retires the ad.
        assertFalse(arbiter.isHeld, "cleanup failure must not strand the presentation token")
        assertEquals(listOf("ad1"), slot.destroyedAds)
    }

    @Test
    fun `a throwing audio restore does not mask a typed presentation failure`() = runSlotTest {
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            audioController = RecordingAudioController(failOnRestore = RuntimeException("restore exploded")),
            presentHandler = { _, _, _ -> AdShowResult.Failed(AdError.message("the real failure")) }
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val result = slot.show(FullScreenAdOptions(audioMuted = true))
        advanceUntilIdle()

        val failed = assertIs<AdShowResult.Failed>(result)
        assertEquals("the real failure", failed.error.message, "the primary outcome must survive")
    }

    @Test
    fun `caller cancellation before hand-off is preserved and still restores audio`() = runSlotTest {
        val audioController = RecordingAudioController()
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            audioController = audioController,
            stallBeforeHandOff = true
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        val job = launch { slot.show(FullScreenAdOptions(audioMuted = true)) }
        advanceUntilIdle()
        job.cancelAndJoin()
        advanceUntilIdle()

        // Pre-hand-off there is no SDK callback to close the token, so closeIfCoreOwned() must
        // both release the presentation and revert the override.
        assertEquals(1, audioController.restoreCount)
    }

    @Test
    fun `applies audio overrides on the main dispatcher`() = runSlotTest {
        val audioController = RecordingAudioController()
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            audioController = audioController
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        // Invoke from a worker dispatcher: the caller's context must not decide where GMA is
        // touched. There is no Looper/NSThread equivalent in commonTest, so compare the
        // ContinuationInterceptor identity against Main's.
        val mainInterceptor = withContext(Dispatchers.Main.immediate) {
            currentCoroutineContext()[ContinuationInterceptor]
        }
        withContext(StandardTestDispatcher(testScheduler)) {
            slot.show(FullScreenAdOptions(audioMuted = true))
        }
        advanceUntilIdle()

        assertNotNull(audioController.applyInterceptor)
        assertSame(
            mainInterceptor,
            audioController.applyInterceptor,
            "applyOverrides reaches GMA and must observe Dispatchers.Main"
        )
    }

    @Test
    fun `a null audio restore handle is tolerated`() = runSlotTest {
        val audioController = RecordingAudioController(returnNullHandle = true)
        val slot = FakeFullScreenSlot(
            testPlacement,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            audioController = audioController
        )
        slot.enqueueLoadResult(AdAttemptResult.Success("ad1"))
        slot.load()

        assertIs<AdShowResult.Shown>(slot.show(FullScreenAdOptions(audioMuted = true)))
        assertEquals(0, audioController.restoreCount)
    }

    // ---------------------------------------------------------------------------------
    // Detached reload after show. loadForGeneration deliberately rethrows unexpected
    // mapper / onAdLoaded / getResponseInfo failures so a foreground caller sees them,
    // but the reload is launched into a scope nobody awaits — so that rethrow reached a
    // Main coroutine's uncaught handler and could kill the host process.
    // ---------------------------------------------------------------------------------

    @Test
    fun `a throwing automatic reload does not escape as an uncaught exception`() = runSlotTest {
        val reloading = testPlacement.copy(cachePolicy = AdCachePolicy(reloadAfterShow = true))
        var loads = 0
        val slot = FakeFullScreenSlot(
            reloading,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            loadHandler = { _ ->
                loads++
                // First load succeeds and is shown; the reload it triggers blows up the way a
                // publisher mapper or getResponseInfo can.
                if (loads == 1) AdAttemptResult.Success("ad1")
                else throw IllegalStateException("mapper exploded during reload")
            }
        )
        slot.load()
        assertIs<AdShowResult.Shown>(slot.show())
        advanceUntilIdle()

        // Reaching here at all is the assertion: an uncaught throw on the Main test dispatcher
        // fails runTest. Belt and braces on the observable state:
        assertEquals(2, loads, "the reload should have been attempted")
        assertFalse(slot.loadState.value is AdLoadState.Loading, "state must not be stuck Loading")
    }

    @Test
    fun `the slot still works after a failed automatic reload`() = runSlotTest {
        val reloading = testPlacement.copy(cachePolicy = AdCachePolicy(reloadAfterShow = true))
        var loads = 0
        val slot = FakeFullScreenSlot(
            reloading,
            testGlobalEvents(),
            unblockedAdRequestError(),
            tickClock(),
            loadHandler = { _ ->
                loads++
                if (loads == 2) throw IllegalStateException("mapper exploded during reload")
                AdAttemptResult.Success("ad$loads")
            }
        )
        slot.load()
        slot.show()
        advanceUntilIdle()

        // The supervisor must still be alive and the slot usable: a manual load succeeds and the
        // ad can be presented.
        slot.load()
        advanceUntilIdle()
        assertIs<AdShowResult.Shown>(slot.show())
    }
}
