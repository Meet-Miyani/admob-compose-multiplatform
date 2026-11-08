@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.avinya.ads

import dev.avinya.ads.appopen.AppOpenAdCoordinator
import dev.avinya.ads.appopen.AppOpenConfig
import dev.avinya.ads.internal.FullScreenLoadTelemetrySink
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.FullScreenPresentationHandle
import dev.avinya.ads.internal.FullScreenSlotCore
import dev.avinya.ads.internal.LoadOriginElement
import dev.avinya.ads.internal.RewardDelivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Slot subclass that accepts a [TimeSource] for request durations. [FakeFullScreenSlot] does not
 * forward `requestTimeSource` to the core, and exact virtual-time durations need it.
 */
private class TelemetrySlot(
    placement: AdPlacement,
    globalEvents: MutableSharedFlow<AdEvent>,
    clock: () -> Instant,
    requestTimeSource: TimeSource,
    adRequestBlockedError: () -> AdError?,
    private val loadHandler: (suspend (AdRequestOptions) -> AdAttemptResult<String>)?,
) : FullScreenSlotCore<String>(
    placement = placement,
    globalEvents = globalEvents,
    adRequestBlockedError = adRequestBlockedError,
    clock = clock,
    onPresentationChanged = {},
    arbiter = FullScreenPresentationArbiter(),
    requestTimeSource = requestTimeSource,
) {
    var loadCalls = 0

    override suspend fun loadAd(requestOptions: AdRequestOptions): AdAttemptResult<String> {
        loadCalls++
        loadHandler?.let { return it(requestOptions) }
        return AdAttemptResult.Success("ad-$loadCalls")
    }

    override suspend fun presentAd(
        loaded: String,
        options: FullScreenAdOptions,
        presentation: FullScreenPresentationHandle,
        rewardDelivery: RewardDelivery?,
    ): AdShowResult {
        presentation.tryHandOffToCallbacks()
        presentation.close(true)
        return AdShowResult.Shown
    }

    override fun destroyAd(ad: String) {}

    override fun getResponseInfo(ad: String): AdResponseInfo? = null

    override fun canPresent(): AdError? = null
}

/** Advances with the test scheduler, so a duration measured across suspension is exact. */
private class TelemetryVirtualTimeSource(private val scheduler: TestCoroutineScheduler) : TimeSource {
    override fun markNow(): TimeMark = TelemetryVirtualMark(scheduler, scheduler.currentTime)

    private class TelemetryVirtualMark(
        private val scheduler: TestCoroutineScheduler,
        private val startMillis: Long,
    ) : TimeMark {
        override fun elapsedNow(): Duration = (scheduler.currentTime - startMillis).milliseconds
        override fun plus(duration: Duration): TimeMark =
            TelemetryVirtualMark(scheduler, startMillis + duration.inWholeMilliseconds)
    }
}

/** Records the origin each app-open load runs under, as the coordinator's context sets it. */
private class OriginRecordingAppOpenController(
    ready: Boolean,
    val recordedOrigins: MutableList<AdLoadOrigin?>,
) : AppOpenAdController {
    private val _loadState = MutableStateFlow<AdLoadState>(
        if (ready) AdLoadState.Loaded() else AdLoadState.Idle
    )
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 8)
    override val loadState: StateFlow<AdLoadState> = _loadState
    override val events: SharedFlow<AdEvent> = _events
    override val placement: AdPlacement = AdPlacement("test_app_open", AdFormat.AppOpen, "android", "ios")
    private var ready: Boolean = ready

    override fun availability(): AdAvailability = AdAvailability(isReady = ready)
    override fun isReady(): Boolean = ready

    override suspend fun load(requestOptions: AdRequestOptions): AdLoadState {
        recordedOrigins += currentCoroutineContext()[LoadOriginElement]?.origin
        ready = true
        _loadState.value = AdLoadState.Loaded()
        return _loadState.value
    }

    override suspend fun show(options: FullScreenAdOptions): AdShowResult {
        ready = false
        _loadState.value = AdLoadState.Idle
        return AdShowResult.Shown
    }

    override suspend fun showIfAvailable(options: FullScreenAdOptions): AdShowResult =
        if (isReady()) show(options) else AdShowResult.NotReady

    override fun clear() {}
}

private fun placementWith(
    retry: AdRetryPolicy = AdRetryPolicy(),
    timeout: AdTimeoutPolicy = AdTimeoutPolicy(),
    cache: AdCachePolicy = AdCachePolicy(),
): AdPlacement = testPlacement.copy(retryPolicy = retry, timeoutPolicy = timeout, cachePolicy = cache)

/** A slot wired to a recording sink; the sink's request ids count up from 0. */
private fun TestScope.newTelemetrySlot(
    placement: AdPlacement = testPlacement,
    clock: () -> Instant = { Instant.fromEpochSeconds(1000) },
    blocked: () -> AdError? = unblockedAdRequestError(),
    loadHandler: (suspend (AdRequestOptions) -> AdAttemptResult<String>)? = null,
): Pair<TelemetrySlot, MutableList<AdLoadTelemetry>> {
    val slot = TelemetrySlot(
        placement = placement,
        globalEvents = testGlobalEvents(),
        clock = clock,
        requestTimeSource = TelemetryVirtualTimeSource(testScheduler),
        adRequestBlockedError = blocked,
        loadHandler = loadHandler,
    )
    val events = mutableListOf<AdLoadTelemetry>()
    var nextId = 0L
    slot.telemetry = FullScreenLoadTelemetrySink(
        emit = { events += it },
        nextRequestId = { nextId++ },
    )
    return slot to events
}

private fun List<AdLoadTelemetry>.started(): List<AdLoadTelemetry.RequestStarted> =
    filterIsInstance<AdLoadTelemetry.RequestStarted>()

private fun List<AdLoadTelemetry>.finished(): List<AdLoadTelemetry.RequestFinished> =
    filterIsInstance<AdLoadTelemetry.RequestFinished>()

private fun List<AdLoadTelemetry>.expiries(): List<AdLoadTelemetry.CachedAdExpired> =
    filterIsInstance<AdLoadTelemetry.CachedAdExpired>()

class FullScreenLoadTelemetryTest {

    @Test
    fun `a successful load reports a started and a loaded request with its virtual duration`() = runSlotTest {
        val (slot, events) = newTelemetrySlot(
            loadHandler = { _ ->
                delay(300.milliseconds)
                AdAttemptResult.Success("ad")
            },
        )

        slot.load()

        assertEquals(2, events.size, "one request, one start and one finish")
        assertEquals(
            AdLoadTelemetry.RequestStarted(testPlacement.id, AdFormat.Interstitial, 0L, AdLoadOrigin.App(null), 1),
            events[0],
        )
        val finished = assertIs<AdLoadTelemetry.RequestFinished>(events[1])
        assertEquals(0L, finished.requestId)
        assertEquals(AdLoadOrigin.App(null), finished.origin)
        assertEquals(1, finished.attempt)
        assertEquals(300.milliseconds, finished.duration)
        assertIs<AdRequestOutcome.Loaded>(finished.outcome)
    }

    @Test
    fun `a non-retryable failure reports the SDK error as the outcome`() = runSlotTest {
        val error = AdError(code = "fatal", message = "fatal error")
        val (slot, events) = newTelemetrySlot(
            loadHandler = { _ -> AdAttemptResult.Failure(error) },
        )

        val result = slot.load()

        assertIs<AdLoadState.Failed>(result)
        assertEquals(2, events.size)
        val finished = assertIs<AdLoadTelemetry.RequestFinished>(events[1])
        assertEquals(AdRequestOutcome.Failed(error), finished.outcome)
        assertEquals(1, finished.attempt)
    }

    @Test
    fun `a retryable failure then success reports two requests with distinct ids`() = runSlotTest {
        val script = ArrayDeque<AdAttemptResult<String>>(
            listOf(
                AdAttemptResult.Failure(AdError(code = "NETWORK_ERROR", message = "network down")),
                AdAttemptResult.Success("ad"),
            )
        )
        val (slot, events) = newTelemetrySlot(
            placement = placementWith(retry = AdRetryPolicy(maxAttempts = 2)),
            loadHandler = { _ -> script.removeFirst() },
        )

        val result = slot.load()

        assertIs<AdLoadState.Loaded>(result)
        assertEquals(4, events.size, "two requests, each with a start and a finish")
        val starts = events.started()
        val finishes = events.finished()
        assertEquals(2, starts.size)
        assertEquals(listOf(1, 2), starts.map { it.attempt })
        assertEquals(2, starts.map { it.requestId }.toSet().size, "each retry is its own request id")
        assertEquals(starts.map { it.requestId }, finishes.map { it.requestId })
        assertIs<AdRequestOutcome.Failed>(finishes[0].outcome)
        assertIs<AdRequestOutcome.Loaded>(finishes[1].outcome)
    }

    @Test
    fun `a load that never answers reports TimedOut at the load timeout`() = runSlotTest {
        val (slot, events) = newTelemetrySlot(
            placement = placementWith(timeout = AdTimeoutPolicy(loadTimeout = 2.seconds)),
            loadHandler = { _ -> awaitCancellation() },
        )

        val result = slot.load()

        assertIs<AdLoadState.Failed>(result)
        assertEquals(2, events.size)
        val finished = assertIs<AdLoadTelemetry.RequestFinished>(events[1])
        assertEquals(AdRequestOutcome.TimedOut, finished.outcome)
        assertEquals(2.seconds, finished.duration)
    }

    @Test
    fun `a caller cancelled while the SDK is loading reports Cancelled`() = runSlotTest {
        val (slot, events) = newTelemetrySlot(
            loadHandler = { _ -> awaitCancellation() },
        )

        val loadJob = launch { slot.load() }
        runCurrent()
        assertEquals(1, events.started().size, "precondition: the request is in flight")

        loadJob.cancelAndJoin()

        assertEquals(2, events.size)
        val finished = assertIs<AdLoadTelemetry.RequestFinished>(events[1])
        assertEquals(AdRequestOutcome.Cancelled, finished.outcome)
    }

    @Test
    fun `a load answered from a full cache adds no telemetry`() = runSlotTest {
        val (slot, events) = newTelemetrySlot(
            placement = placementWith(cache = AdCachePolicy(maxSize = 1)),
        )

        slot.load()
        val afterFirstLoad = events.size
        assertEquals(2, afterFirstLoad, "precondition: the first load filled the cache")

        val second = slot.load()

        assertIs<AdLoadState.Loaded>(second)
        assertEquals(afterFirstLoad, events.size, "a cache-answered load must report nothing")
        assertEquals(1, slot.loadCalls, "precondition: the second load never reached the SDK")
    }

    @Test
    fun `a consent-blocked load adds no telemetry`() = runSlotTest {
        val (slot, events) = newTelemetrySlot(blocked = blockedAdRequestError())

        val result = slot.load()

        val failed = assertIs<AdLoadState.Failed>(result)
        assertEquals(AdErrorCode.CONSENT_REQUIRED, failed.error.code)
        assertTrue(events.isEmpty(), "a consent-refused load sends nothing and reports nothing")
        assertEquals(0, slot.loadCalls)
    }

    @Test
    fun `a load under AdLoadTag is reported with the app's tag`() = runSlotTest {
        val (slot, events) = newTelemetrySlot()

        withContext(AdLoadTag("cold_start")) { slot.load() }

        assertEquals(
            AdLoadOrigin.App("cold_start"),
            events.started().single().origin,
        )
    }

    @Test
    fun `a load under LoadOriginElement is reported with the SDK origin`() = runSlotTest {
        val (slot, events) = newTelemetrySlot()

        withContext(LoadOriginElement(AdLoadOrigin.AppOpenStart)) { slot.load() }

        assertEquals(AdLoadOrigin.AppOpenStart, events.started().single().origin)
    }

    @Test
    fun `the reload after a show is reported as ReloadAfterShow`() = runSlotTest {
        val (slot, events) = newTelemetrySlot(
            placement = placementWith(cache = AdCachePolicy(maxSize = 1, reloadAfterShow = true)),
        )

        slot.load()
        val result = slot.show()
        advanceUntilIdle()

        assertEquals(AdShowResult.Shown, result)
        val starts = events.started()
        assertEquals(2, starts.size, "the first load and the reload after the show")
        assertEquals(AdLoadOrigin.App(null), starts[0].origin)
        assertEquals(AdLoadOrigin.ReloadAfterShow, starts[1].origin)
        assertEquals(2, slot.loadCalls)
    }

    @Test
    fun `availability reports an expired cached ad once with its load instant and expiry`() = runSlotTest {
        var now = Instant.fromEpochSeconds(1000)
        val (slot, events) = newTelemetrySlot(clock = { now })

        slot.load()
        now = Instant.fromEpochSeconds(1000 + 3601) // past the 1h full-screen TTL
        assertFalse(slot.availability().isReady)

        val expiry = events.expiries().single()
        val loadedAt = Instant.fromEpochSeconds(1000)
        assertEquals(loadedAt, expiry.loadedAt)
        assertEquals(loadedAt + 1.hours, expiry.expiredAt)

        slot.availability()
        assertEquals(1, events.expiries().size, "a second availability() must not report the same ad again")
    }

    @Test
    fun `load reports an expired cached ad before its own request`() = runSlotTest {
        var now = Instant.fromEpochSeconds(1000)
        val (slot, events) = newTelemetrySlot(clock = { now })

        slot.load()
        now = Instant.fromEpochSeconds(1000 + 3601)
        val result = slot.load()

        assertIs<AdLoadState.Loaded>(result)
        assertEquals(1, events.expiries().size, "exactly one expiry for the stale ad")
        assertEquals(2, events.started().size, "the expiry does not replace the new request")
        assertEquals(2, events.finished().size)
    }

    @Test
    fun `show reports an expired cached ad and does not present it`() = runSlotTest {
        var now = Instant.fromEpochSeconds(1000)
        val (slot, events) = newTelemetrySlot(clock = { now })

        slot.load()
        now = Instant.fromEpochSeconds(1000 + 3601)
        val result = slot.show()

        assertEquals(AdShowResult.NotReady, result)
        assertEquals(1, events.expiries().size, "exactly one expiry for the stale ad")
    }

    @Test
    fun `clear reports no expiry for an ad still within its TTL`() = runSlotTest {
        var now = Instant.fromEpochSeconds(1000)
        val (slot, events) = newTelemetrySlot(clock = { now })

        slot.load()
        slot.clear()
        now = Instant.fromEpochSeconds(1000 + 3601)
        slot.availability()

        assertTrue(events.expiries().isEmpty(), "an ad cleared before its TTL was discarded, not expired")
    }

    @Test
    fun `a load that throws reports the request as Failed rather than Discarded`() = runSlotTest {
        val (slot, events) = newTelemetrySlot(
            loadHandler = { _ -> throw IllegalStateException("mapper exploded") },
        )

        assertFailsWith<IllegalStateException> { slot.load() }

        assertEquals(1, events.started().size, "one request started")
        assertEquals(1, events.finished().size, "one request finished, exactly once")
        assertEquals(
            events.started().single().requestId,
            events.finished().single().requestId,
            "the finish must belong to the request that started",
        )
        val failed = assertIs<AdRequestOutcome.Failed>(
            events.finished().single().outcome,
            "a load that threw delivered no ad, so it is a failure rather than a discarded ad",
        )
        assertTrue(
            failed.error.message.contains("mapper exploded"),
            "the failure must carry the thrown message; saw ${failed.error.message}",
        )
        assertFalse(slot.loadState.value is AdLoadState.Loading, "a throwing load must not leave the slot Loading")
    }

    @Test
    fun `clear reports an ad that was already past its TTL as expired`() = runSlotTest {
        var now = Instant.fromEpochSeconds(1000)
        val (slot, events) = newTelemetrySlot(clock = { now })

        slot.load()
        now = Instant.fromEpochSeconds(1000 + 3601)
        slot.clear()

        val expiries = events.expiries()
        assertEquals(1, expiries.size, "clear() must report the expired ad once")
        assertEquals(Instant.fromEpochSeconds(1000), expiries[0].loadedAt)
        assertEquals(expiries[0].loadedAt + 1.hours, expiries[0].expiredAt, "expiry is load time plus the one-hour TTL")

        slot.availability()

        assertEquals(1, events.expiries().size, "the ad was already removed, so a later read must not report it again")
    }

    @Test
    fun `an ad arriving after clear is reported as Discarded`() = runSlotTest {
        val gate = CompletableDeferred<AdAttemptResult<String>>()
        val (slot, events) = newTelemetrySlot(
            loadHandler = { _ -> gate.await() },
        )

        val loadJob = launch { slot.load() }
        runCurrent()
        assertEquals(1, events.started().size, "precondition: the request is in flight")

        slot.clear()
        gate.complete(AdAttemptResult.Success("late-ad"))
        loadJob.join()

        assertEquals(2, events.size)
        val finished = assertIs<AdLoadTelemetry.RequestFinished>(events[1])
        assertEquals(AdRequestOutcome.Discarded, finished.outcome)
        assertEquals(events.started().single().requestId, finished.requestId)
    }

    @Test
    fun `the no-op manager replays no load telemetry`() {
        assertTrue(NoOpAdManager.loadTelemetry.replayCache.isEmpty())
    }

    // ---------------------------------------------------------------------------------
    // App-open coordinator origins.
    // ---------------------------------------------------------------------------------

    @Test
    fun `the app-open start-up preload is reported as AppOpenStart`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(true)
        val origins = mutableListOf<AdLoadOrigin?>()
        val controller = OriginRecordingAppOpenController(ready = false, recordedOrigins = origins)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(preloadOnStart = true),
            foregroundEvents = foreground,
            clock = { Instant.fromEpochSeconds(1000) },
        )

        coordinator.start(scope)
        advanceUntilIdle()

        assertEquals(listOf<AdLoadOrigin?>(AdLoadOrigin.AppOpenStart), origins)
        coordinator.stop()
    }

    @Test
    fun `the app-open load after a real return is reported as AppOpenAfterReturn`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val origins = mutableListOf<AdLoadOrigin?>()
        val controller = OriginRecordingAppOpenController(ready = false, recordedOrigins = origins)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )

        coordinator.start(scope)
        advanceUntilIdle() // the collector records the background first
        now = Instant.fromEpochSeconds(1005) // 5s > 4s minBackgroundDuration
        foreground.value = true
        advanceUntilIdle()

        assertEquals(listOf<AdLoadOrigin?>(AdLoadOrigin.AppOpenAfterReturn), origins)
        coordinator.stop()
    }
}
