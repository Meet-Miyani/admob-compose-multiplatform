package dev.avinya.ads

import dev.avinya.ads.appopen.AppOpenAdCoordinator
import dev.avinya.ads.appopen.AppOpenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Controls the app-open controller used by the start-up foreground and D4 tests.
 *
 * - [load] optionally suspends on [loadGate]. If the load is cancelled while it waits, it records
 *   [loadCancelled] and rethrows, so a test can prove that stop() reached the in-flight load.
 * - [show] consumes the ad: it counts the call and marks the controller not ready.
 */
private class CountingAppOpenController(
    ready: Boolean = false,
    private val loadGate: CompletableDeferred<Unit>? = null,
) : AppOpenAdController {
    private val _loadState = MutableStateFlow<AdLoadState>(
        if (ready) AdLoadState.Loaded() else AdLoadState.Idle
    )
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 8)
    override val loadState: StateFlow<AdLoadState> = _loadState
    override val events: SharedFlow<AdEvent> = _events
    override val placement: AdPlacement = AdPlacement("test_app_open", AdFormat.AppOpen, "android", "ios")

    var loadCalls = 0
    var showCalls = 0
    var loadCancelled = false
    private var ready: Boolean = ready

    override fun availability(): AdAvailability = AdAvailability(isReady = ready)
    override fun isReady(): Boolean = ready

    override suspend fun load(requestOptions: AdRequestOptions): AdLoadState {
        loadCalls++
        try {
            loadGate?.await()
        } catch (e: CancellationException) {
            loadCancelled = true
            throw e
        }
        ready = true
        _loadState.value = AdLoadState.Loaded()
        return _loadState.value
    }

    override suspend fun preload(requestOptions: AdRequestOptions): AdLoadState = load(requestOptions)

    override suspend fun show(options: FullScreenAdOptions): AdShowResult {
        showCalls++
        // The ad is consumed by a show, so the coordinator must reload afterwards.
        ready = false
        _loadState.value = AdLoadState.Idle
        return AdShowResult.Shown
    }

    override suspend fun showIfAvailable(options: FullScreenAdOptions): AdShowResult =
        if (isReady()) show(options) else AdShowResult.NotReady

    override fun clear() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppOpenStartupForegroundTest {

    // ---------------------------------------------------------------------------------
    // Start-up foreground is not a return.
    // ---------------------------------------------------------------------------------

    @Test
    fun `start-up foreground without preload neither shows nor loads`() = runTest(StandardTestDispatcher()) {
        // The platform replays the current foreground to a new lifecycle. No background was seen
        // by this lifecycle, so this is the start-up foreground, not a return.
        val foreground = MutableStateFlow(true)
        val controller = CountingAppOpenController()
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
        advanceUntilIdle()

        assertEquals(0, controller.loadCalls, "a start-up foreground must not load")
        assertEquals(0, controller.showCalls, "a start-up foreground must not show")
        coordinator.stop()
    }

    @Test
    fun `start-up foreground with preload loads only the preload`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(true)
        val controller = CountingAppOpenController()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(preloadOnStart = true),
            foregroundEvents = foreground,
            clock = { now },
        )

        coordinator.start(scope)
        advanceUntilIdle()

        assertEquals(1, controller.loadCalls, "the preload is the only load before the first return")
        assertEquals(0, controller.showCalls, "the start-up foreground must not show")
        coordinator.stop()
    }

    @Test
    fun `a real return with no ad ready still loads`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = CountingAppOpenController(ready = false)
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

        assertEquals(1, controller.loadCalls, "a qualifying return with no ad ready must load")
        assertEquals(0, controller.showCalls, "no ad was ready, so nothing to show")
        coordinator.stop()
    }

    @Test
    fun `a real return with an ad ready still shows`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = CountingAppOpenController(ready = true)
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

        assertEquals(1, controller.showCalls, "a qualifying return with a ready ad must show")
        coordinator.stop()
    }

    @Test
    fun `a restart does not count a background seen by the earlier lifecycle`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = CountingAppOpenController(ready = true)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )

        // The first lifecycle records a background at t=1000.
        coordinator.start(scope)
        advanceUntilIdle()
        coordinator.stop()

        // The app comes back while no lifecycle is running, 10s after that background.
        now = Instant.fromEpochSeconds(1010)
        foreground.value = true

        // The restarted lifecycle replays `true` as its start-up foreground. The stale background
        // from the earlier lifecycle must not make it look like a 10s return.
        coordinator.start(scope)
        advanceUntilIdle()

        assertEquals(0, controller.showCalls, "a stale background from an earlier lifecycle must not trigger a show")
        coordinator.stop()
    }

    // ---------------------------------------------------------------------------------
    // D4: a late cold-start ad is kept for the next qualifying return, never shown late.
    // ---------------------------------------------------------------------------------

    @Test
    fun `a cold-start load that misses the timeout is kept and shown on the next return`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        val controller = CountingAppOpenController(ready = false, loadGate = gate)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(
                showOnColdStart = true,
                preloadOnStart = false,
                coldStartTimeout = 1.seconds,
            ),
            foregroundEvents = foreground,
            clock = { now },
            appInForeground = { true },
        )

        coordinator.start(scope)
        advanceTimeBy(1_500) // past the 1s coldStartTimeout, with the load still suspended
        runCurrent()

        assertEquals(0, controller.showCalls, "a load that misses the cold-start timeout must not show")
        assertFalse(controller.loadCancelled, "the timeout must not cancel the load")

        gate.complete(Unit) // the load now finishes into the cache
        advanceUntilIdle()

        assertTrue(controller.isReady(), "the late ad must be kept in the cache")
        assertEquals(0, controller.showCalls, "the late ad must never be shown late")
        assertEquals(1, controller.loadCalls)

        // A qualifying return: background observed, then foreground after 5s.
        foreground.value = false
        runCurrent() // the collector records the background at the current clock
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true
        advanceUntilIdle()

        assertEquals(1, controller.showCalls, "the kept ad must show on the next qualifying return")
        coordinator.stop()
    }

    @Test
    fun `a cold-start load that finishes in time shows`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        val controller = CountingAppOpenController(ready = false, loadGate = gate)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(
                showOnColdStart = true,
                preloadOnStart = false,
                coldStartTimeout = 1.seconds,
            ),
            foregroundEvents = foreground,
            clock = { now },
            appInForeground = { true },
        )

        coordinator.start(scope)
        advanceTimeBy(500) // well inside the 1s timeout
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, controller.showCalls, "a cold-start load that finishes in time must show")
        coordinator.stop()
    }

    @Test
    fun `a cold-start load in time does not show when the app is not in the foreground`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        val controller = CountingAppOpenController(ready = false, loadGate = gate)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(
                showOnColdStart = true,
                preloadOnStart = false,
                coldStartTimeout = 1.seconds,
            ),
            foregroundEvents = foreground,
            clock = { now },
            appInForeground = { false },
        )

        coordinator.start(scope)
        advanceTimeBy(500) // in time
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, controller.showCalls, "a background launch must never show a cold-start ad")
        assertTrue(controller.isReady(), "the ad stays cached for the next genuine foreground entry")
        coordinator.stop()
    }

    @Test
    fun `stop cancels a cold-start load that is still running after the timeout`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(true)
        val gate = CompletableDeferred<Unit>()
        val controller = CountingAppOpenController(ready = false, loadGate = gate)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = FakeAdManager(),
            controller = controller,
            config = AppOpenConfig(
                showOnColdStart = true,
                preloadOnStart = false,
                coldStartTimeout = 1.seconds,
            ),
            foregroundEvents = foreground,
            clock = { now },
            appInForeground = { true },
        )

        coordinator.start(scope)
        advanceTimeBy(1_500) // timeout has passed; the load is still suspended on the gate
        runCurrent()

        assertEquals(1, controller.loadCalls, "precondition: the cold-start load is in flight")
        assertFalse(controller.loadCancelled, "precondition: the load has not been cancelled yet")

        coordinator.stop()
        gate.complete(Unit)
        advanceUntilIdle()

        assertTrue(controller.loadCancelled, "stop() must cancel the in-flight cold-start load")
        assertFalse(controller.isReady(), "a cancelled load must not fill the cache")
        assertEquals(0, controller.showCalls)
    }
}
