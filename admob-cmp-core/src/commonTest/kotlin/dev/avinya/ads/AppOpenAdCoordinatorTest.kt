package dev.avinya.ads

import dev.avinya.ads.appopen.AppOpenAdCoordinator
import dev.avinya.ads.appopen.AppOpenConfig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AppOpenAdCoordinatorTest {

    @Test
    fun `foreground event with ready ad and elapsed thresholds triggers show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)
        now = Instant.fromEpochSeconds(1005) // 5s > 4s minBackgroundDuration
        foreground.value = true

        assertTrue(controller.showCalled)
    }

    @Test
    fun `exact minBackgroundDuration threshold triggers show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false, minBackgroundDuration = 4.seconds),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)
        now = Instant.fromEpochSeconds(1004) // exact threshold 4s
        foreground.value = true

        assertTrue(controller.showCalled)
    }

    @Test
    fun `under minBackgroundDuration does not show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false, minBackgroundDuration = 4.seconds),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)
        now += 3.99.seconds // 3.99s < 4s → no show
        foreground.value = true

        assertFalse(controller.showCalled)
    }

    @Test
    fun `long background sleep triggers show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)
        now += 10.hours // 10h sleep
        foreground.value = true

        assertTrue(controller.showCalled)
    }

    @Test
    fun `simulated backward reading is clamped to zero and does not trigger show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false, minBackgroundDuration = 4.seconds),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)
        now = Instant.fromEpochSeconds(500) // time jumped backward: -500s -> clamped to 0s < 4s
        foreground.value = true

        assertFalse(controller.showCalled)
    }

    @Test
    fun `isBlocked suppresses show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.isBlocked = true
        coordinator.start(scope)
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true

        assertFalse(controller.showCalled)
    }

    @Test
    fun `cooldown suppresses second show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(
                preloadOnStart = false,
                minBackgroundDuration = 4.seconds,
                cooldownBetweenShows = 30.seconds,
            ),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)

        now = Instant.fromEpochSeconds(1005)
        foreground.value = true
        assertTrue(controller.showCalled)
        controller.showCalled = false

        now = Instant.fromEpochSeconds(1010)
        foreground.value = false
        now = Instant.fromEpochSeconds(1020) // 15s since last show < 30s cooldown
        foreground.value = true

        assertFalse(controller.showCalled, "Second show suppressed by cooldown")
    }

    @Test
    fun `foreign full-screen ad presenting suppresses app-open show`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)

        manager.holdFullScreenToken()
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true

        assertFalse(controller.showCalled, "App-open must not stack on top of another full-screen ad")
    }

    @Test
    fun `app-open show allowed once no full-screen ad is presenting`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)

        val otherFormatToken = manager.holdFullScreenToken()
        manager.fullScreenArbiter.release(otherFormatToken)
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true

        assertTrue(controller.showCalled, "App-open should show once no other full-screen ad is presenting")
    }

    @Test
    fun `reload triggered after show when not ready`() = runTest(UnconfinedTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController(simulatedShowResult = AdShowResult.Shown)
        val manager = FakeAdManager()
        val scope = CoroutineScope(UnconfinedTestDispatcher())

        var now = Instant.fromEpochSeconds(1000)
        val coordinator = AppOpenAdCoordinator(
            manager = manager,
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false),
            foregroundEvents = foreground,
            clock = { now },
        )
        coordinator.start(scope)
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true

        assertTrue(controller.showCalled)
        assertTrue(controller.loadCalled, "Controller should load because show() set ready=false")
    }

    @Test
    fun `coordinator declines while another format holds the process-wide token`() =
        runTest(UnconfinedTestDispatcher()) {
            val foreground = MutableStateFlow(false)
            val controller = FakeAppOpenAdController()
            val manager = FakeAdManager()
            val scope = CoroutineScope(UnconfinedTestDispatcher())

            manager.holdFullScreenToken()

            var now = Instant.fromEpochSeconds(1000)
            val coordinator = AppOpenAdCoordinator(
                manager = manager,
                controller = controller,
                config = AppOpenConfig(preloadOnStart = false),
                foregroundEvents = foreground,
                clock = { now },
            )
            coordinator.start(scope)
            now = Instant.fromEpochSeconds(1005)
            foreground.value = true

            assertFalse(
                controller.showCalled,
                "app-open must not stack on top of another full-screen ad"
            )
        }

    @Test
    fun `coordinator shows once the other format releases the token`() =
        runTest(UnconfinedTestDispatcher()) {
            val foreground = MutableStateFlow(false)
            val controller = FakeAppOpenAdController()
            val manager = FakeAdManager()
            val scope = CoroutineScope(UnconfinedTestDispatcher())

            val otherFormatToken = manager.holdFullScreenToken()

            var now = Instant.fromEpochSeconds(1000)
            val coordinator = AppOpenAdCoordinator(
                manager = manager,
                controller = controller,
                config = AppOpenConfig(preloadOnStart = false),
                foregroundEvents = foreground,
                clock = { now },
            )
            coordinator.start(scope)
            now = Instant.fromEpochSeconds(1005)
            foreground.value = true
            assertFalse(controller.showCalled, "precondition: blocked while the token is held")

            manager.fullScreenArbiter.release(otherFormatToken)
            now = Instant.fromEpochSeconds(1010)
            foreground.value = false
            now = Instant.fromEpochSeconds(1020)
            foreground.value = true

            assertTrue(
                controller.showCalled,
                "the coordinator must recover once the token is released"
            )
        }

    // ---------------------------------------------------------------------------------
    // stop() ownership. stop() must cancel EVERY coroutine it owns — including show/reload
    // launched from a foreground transition. Those must not go onto the CALLER's scope, or
    // they outlive stop() entirely.
    // ---------------------------------------------------------------------------------

    @Test
    fun `stop cancels an automatic show already in flight`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val showGate = CompletableDeferred<Unit>()
        var showCompleted = false
        // Gate show(), which is what showNow() calls. An earlier version of this test gated
        // showIfAvailable() and therefore proved nothing.
        var showStarted = false
        val controller = FakeAppOpenAdController(beforeShow = {
            showStarted = true
            showGate.await()
            showCompleted = true
        })
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
        // Let the collector observe the initial `false` first: onBackground() records the timestamp
        // that the minBackgroundDuration gate compares against. Without this drain the first
        // emission the collector sees is `true`, backgroundedAtInstant is still null, and the
        // elapsed duration is ZERO -- so no automatic show is attempted and the test proves nothing.
        advanceUntilIdle()
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true
        advanceUntilIdle()               // the show is now suspended on the gate

        // Guards against the test proving nothing: if the automatic show never started, the
        // assertion below would pass no matter what stop() does.
        assertTrue(showStarted, "the automatic show should be suspended mid-flight by now")

        coordinator.stop()
        showGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(showCompleted, "no automatic show may complete after stop()")
        // The host's own scope must survive: the coordinator owns a child, not the parent.
        assertTrue(scope.isActive, "stop() must not cancel the caller's scope")
    }

    @Test
    fun `a restart does not let a stale child act`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val firstGate = CompletableDeferred<Unit>()
        var shows = 0
        val controller = FakeAppOpenAdController(beforeShow = {
            shows++
            if (shows == 1) firstGate.await()
        })
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
        // Let the collector observe the initial `false` first: onBackground() records the timestamp
        // that the minBackgroundDuration gate compares against. Without this drain the first
        // emission the collector sees is `true`, backgroundedAtInstant is still null, and the
        // elapsed duration is ZERO -- so no automatic show is attempted and the test proves nothing.
        advanceUntilIdle()
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true
        advanceUntilIdle()

        // Restart while the first show is still suspended. The stale child must not be able to
        // release the admission it holds and let a second show through behind the new lifecycle.
        coordinator.stop()
        coordinator.start(scope)
        firstGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(shows <= 1, "a stale child from the previous lifecycle acted after restart")
    }

    @Test
    fun `cancelling the host scope stops the coordinator`() = runTest(StandardTestDispatcher()) {
        val foreground = MutableStateFlow(false)
        val controller = FakeAppOpenAdController()
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
        scope.cancel()
        now = Instant.fromEpochSeconds(1005)
        foreground.value = true
        advanceUntilIdle()

        // Parenting the lifecycle to the caller's Job keeps the host in control.
        assertFalse(controller.showCalled, "a cancelled host scope must stop automatic shows")
    }
}
