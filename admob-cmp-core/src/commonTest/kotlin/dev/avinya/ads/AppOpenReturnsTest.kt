package dev.avinya.ads

import dev.avinya.ads.appopen.AppOpenAdCoordinator
import dev.avinya.ads.appopen.AppOpenConfig
import dev.avinya.ads.appopen.AppOpenReturn
import dev.avinya.ads.appopen.AppOpenReturnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Wires a coordinator to fakes with a mutable clock and a foreground flow, and collects
 * [AppOpenAdCoordinator.returns] into [entries]. The collector is launched by [collectReturns]
 * before the test calls `coordinator.start(...)`, so no entry can be missed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class ReturnHarness(
    val controller: FakeAppOpenAdController = FakeAppOpenAdController(),
    val manager: FakeAdManager = FakeAdManager(),
    config: AppOpenConfig = AppOpenConfig(preloadOnStart = false),
    initiallyForeground: Boolean = false,
) {
    val foreground = MutableStateFlow(initiallyForeground)
    var now = Instant.fromEpochSeconds(1000)
    val coordinator = AppOpenAdCoordinator(
        manager = manager,
        controller = controller,
        config = config,
        foregroundEvents = foreground,
        clock = { now },
    )
    val entries = mutableListOf<AppOpenReturn>()

    fun collectReturns(scope: TestScope) {
        scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
            coordinator.returns.toList(entries)
        }
    }

    fun background(atEpochSeconds: Long) {
        now = Instant.fromEpochSeconds(atEpochSeconds)
        foreground.value = false
    }

    fun returnToForeground(atEpochSeconds: Long) {
        now = Instant.fromEpochSeconds(atEpochSeconds)
        foreground.value = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AppOpenReturnsTest {

    @Test
    fun `a return after 2s away emits AwayTooShort with the time away`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeAppOpenAdController()
        val h = ReturnHarness(controller = controller)
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1002) // backgrounded at 1000 when the lifecycle started

        val entry = h.entries.single()
        assertEquals(AppOpenReturnOutcome.AwayTooShort, entry.outcome)
        assertEquals(2.seconds, entry.awayFor)
        assertEquals(controller.placement.id, entry.placementId)
        assertFalse(controller.showCalled, "a return under minBackgroundDuration must not show")
        h.coordinator.stop()
    }

    @Test
    fun `the first return after an ad click emits ReturnedFromAdClick`() = runTest(UnconfinedTestDispatcher()) {
        val h = ReturnHarness()
        h.collectReturns(this)
        h.coordinator.skipAfterAdClick = true
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.manager.emitEvent(AdEvent.Clicked(placementId = "feed_native"))
        h.returnToForeground(1005) // 5s away, past minBackgroundDuration

        val entry = h.entries.single()
        assertEquals(AppOpenReturnOutcome.ReturnedFromAdClick, entry.outcome)
        assertEquals(5.seconds, entry.awayFor)
        assertFalse(h.controller.showCalled, "a return from an ad click must not show")
        h.coordinator.stop()
    }

    @Test
    fun `a return while isBlocked is set emits Blocked`() = runTest(UnconfinedTestDispatcher()) {
        val h = ReturnHarness()
        h.collectReturns(this)
        h.coordinator.isBlocked = true
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)

        assertEquals(AppOpenReturnOutcome.Blocked, h.entries.single().outcome)
        assertFalse(h.controller.showCalled, "a blocked coordinator must not show")
        h.coordinator.stop()
    }

    @Test
    fun `a return while the SDK is initializing emits SdkNotReady with that status`() = runTest(UnconfinedTestDispatcher()) {
        val h = ReturnHarness()
        h.collectReturns(this)
        h.manager.setStatus(AdManagerStatus.Initializing)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)

        assertEquals(
            AppOpenReturnOutcome.SdkNotReady(AdManagerStatus.Initializing),
            h.entries.single().outcome,
        )
        assertFalse(h.controller.showCalled, "an SDK that is not Ready must not show")
        h.coordinator.stop()
    }

    @Test
    fun `a return inside the cooldown emits CoolingDown with the time left`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeAppOpenAdController()
        val h = ReturnHarness(
            controller = controller,
            config = AppOpenConfig(preloadOnStart = false, cooldownBetweenShows = 1.hours),
        )
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005) // first show at t=1005; the controller reloads afterwards
        assertTrue(controller.showCalled, "precondition: the first return shows")
        controller.showCalled = false

        h.background(1010)
        h.returnToForeground(1015) // 10s after the first show, 5s away

        assertEquals(
            listOf(
                AppOpenReturnOutcome.ShowAttempted(AdShowResult.Shown),
                AppOpenReturnOutcome.CoolingDown(1.hours - 10.seconds),
            ),
            h.entries.map { it.outcome },
        )
        assertFalse(controller.showCalled, "the second return must not show inside the cooldown")
        h.coordinator.stop()
    }

    @Test
    fun `a return with no ad loaded emits NoAdLoaded and loads for the next return`() = runTest(UnconfinedTestDispatcher()) {
        val controller = FakeAppOpenAdController(simulatedLoadState = AdLoadState.Idle)
        val h = ReturnHarness(controller = controller)
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)

        assertEquals(AppOpenReturnOutcome.NoAdLoaded, h.entries.single().outcome)
        assertFalse(controller.showCalled, "no ad was loaded, so nothing may show")
        assertTrue(controller.loadCalled, "the coordinator must load for the next return")
        h.coordinator.stop()
    }

    @Test
    fun `a return while another full-screen ad holds the token emits AnotherAdShowing`() = runTest(UnconfinedTestDispatcher()) {
        val h = ReturnHarness()
        h.collectReturns(this)
        h.manager.holdFullScreenToken()
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)

        assertEquals(AppOpenReturnOutcome.AnotherAdShowing, h.entries.single().outcome)
        assertFalse(h.controller.showCalled, "app-open must not stack on another full-screen ad")
        h.coordinator.stop()
    }

    @Test
    fun `ShowAttempted is emitted only after the show returns`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var showStarted = false
        val controller = FakeAppOpenAdController(beforeShow = {
            showStarted = true
            gate.await()
        })
        val h = ReturnHarness(controller = controller)
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)
        runCurrent()

        assertTrue(showStarted, "precondition: the show is suspended in the controller")
        assertTrue(h.entries.isEmpty(), "no outcome may be reported while the ad is still showing")

        gate.complete(Unit) // the ad is dismissed
        runCurrent()

        assertEquals(
            listOf(AppOpenReturnOutcome.ShowAttempted(AdShowResult.Shown)),
            h.entries.map { it.outcome },
        )
        h.coordinator.stop()
    }

    @Test
    fun `ShowAttempted carries a failed show result`() = runTest(UnconfinedTestDispatcher()) {
        val failure = AdShowResult.Failed(AdError.message("x"))
        val controller = FakeAppOpenAdController(simulatedShowResult = failure)
        val h = ReturnHarness(controller = controller)
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)

        assertEquals(AppOpenReturnOutcome.ShowAttempted(failure), h.entries.single().outcome)
        h.coordinator.stop()
    }

    @Test
    fun `the start-up foreground emits nothing and a later real return still does`() = runTest(StandardTestDispatcher()) {
        val controller = FakeAppOpenAdController()
        val h = ReturnHarness(controller = controller, initiallyForeground = true)
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(StandardTestDispatcher(testScheduler)))
        advanceUntilIdle()

        assertTrue(h.entries.isEmpty(), "the start-up foreground is not a return and must emit nothing")
        assertFalse(controller.showCalled, "the start-up foreground must not show")

        // Proves the collector is live, so the empty list above is not a dead subscription.
        h.background(1000)
        runCurrent()
        h.returnToForeground(1005)
        advanceUntilIdle()

        assertEquals(
            listOf(AppOpenReturnOutcome.ShowAttempted(AdShowResult.Shown)),
            h.entries.map { it.outcome },
        )
        h.coordinator.stop()
    }

    @Test
    fun `stop during a show emits nothing`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var showStarted = false
        val controller = FakeAppOpenAdController(beforeShow = {
            showStarted = true
            gate.await()
        })
        val h = ReturnHarness(controller = controller)
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)
        runCurrent()
        assertTrue(showStarted, "precondition: the show is suspended in the controller")

        h.coordinator.stop()
        gate.complete(Unit)
        runCurrent()

        assertFalse(controller.showCalled, "the cancelled show must not complete")
        assertTrue(h.entries.isEmpty(), "a show cancelled by stop() must emit no outcome")
    }

    @Test
    fun `each qualifying return emits exactly one entry in order`() = runTest(UnconfinedTestDispatcher()) {
        val h = ReturnHarness()
        h.collectReturns(this)
        h.coordinator.start(CoroutineScope(UnconfinedTestDispatcher()))

        h.returnToForeground(1005)
        h.background(1010)
        h.returnToForeground(1015)

        assertEquals(
            listOf(
                AppOpenReturnOutcome.ShowAttempted(AdShowResult.Shown),
                AppOpenReturnOutcome.ShowAttempted(AdShowResult.Shown),
            ),
            h.entries.map { it.outcome },
        )
        assertEquals(listOf(5.seconds, 5.seconds), h.entries.map { it.awayFor })
        h.coordinator.stop()
    }
}
