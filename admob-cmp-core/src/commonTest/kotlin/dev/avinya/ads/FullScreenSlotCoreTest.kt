package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
import kotlinx.coroutines.withTimeoutOrNull
import dev.avinya.ads.internal.FullScreenPresentationArbiter
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

            // P1-1: the load path caught only CancellationException, so an arbitrary Throwable
            // propagated out before completeLoad() ran, leaving loadState at Loading with no
            // live operation behind it. Every later load then coalesced onto that dead state.
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

                // P1-11: availability() filtered expired entries without mutating state, and
                // prepareShow only reset loadState when an ad was actually SELECTED. With the
                // sole ad expired nothing is selected, so Loaded survived over an empty cache:
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

                // P1-12: the core emitted ShowFailed only for preparation errors or THROWN
                // exceptions. An ordinary returned Failed — Activity/rootViewController
                // resolution failing before SDK callbacks are installed — produced a failed
                // suspend result with no corresponding event, so host analytics saw an
                // incomplete lifecycle.
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
    fun `show with audio options applies overrides and restores on success`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var appliedMuted: Boolean? = null
            var appliedVolume: Float? = null
            var restored = false
            val audioController = object : dev.avinya.ads.internal.FullScreenAudioController {
                override fun applyOverrides(options: FullScreenAdOptions): dev.avinya.ads.internal.AudioRestoreHandle? {
                    appliedMuted = options.audioMuted
                    appliedVolume = options.audioVolume
                    return dev.avinya.ads.internal.AudioRestoreHandle { restored = true }
                }
            }
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
            assertEquals(true, appliedMuted)
            assertEquals(0.5f, appliedVolume)
            assertTrue(restored)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `show with audio options restores on presentation failure`() = runTest(StandardTestDispatcher()) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var restored = false
            val audioController = object : dev.avinya.ads.internal.FullScreenAudioController {
                override fun applyOverrides(options: FullScreenAdOptions): dev.avinya.ads.internal.AudioRestoreHandle? {
                    return dev.avinya.ads.internal.AudioRestoreHandle { restored = true }
                }
            }
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
            assertTrue(restored)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
