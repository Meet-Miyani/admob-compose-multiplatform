package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy

/**
 * Ready no longer waits for mediation adapters beyond [AdManager.adapterStartupWait]. These tests
 * drive virtual time so every boundary (wait, watchdog, callback) is asserted at its exact millisecond.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdapterStartupWaitTest {

    private fun config() = AdConfig(androidAppId = "ca-app-pub-A", iosAppId = "ca-app-pub-A")

    @Test
    fun `adapters that never report publish Ready at the default wait and then NotReported at the watchdog`() = runSlotTest {
        val manager = FakeGoogleAdManager(
            adapterCompletion = CompletableDeferred(),
            timeSource = VirtualTimeSource(testScheduler),
        )
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.SkipConsent) }
        runCurrent()

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(AdManagerStatus.Initializing, manager.status.value, "Ready must wait for the 5 s default")
        assertEquals(null, result, "initialize must not return before the wait has passed")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(AdapterStartup.InProgress, manager.adapterStartup.value)

        advanceTimeBy(35_000)
        runCurrent()
        assertEquals(
            AdapterStartup.NotReported(40.seconds),
            manager.adapterStartup.value,
            "the detached tail must report NotReported at the 40 s watchdog after accept",
        )
        assertEquals(AdManagerStatus.Ready, manager.status.value, "NotReported must not change Ready")
    }

    @Test
    fun `adapters that complete inside the wait publish Ready at their completion`() = runSlotTest {
        val adapters = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            adapterCompletion = adapters,
            timeSource = VirtualTimeSource(testScheduler),
        )
        backgroundScope.launch { manager.initialize(config(), ConsentMode.SkipConsent) }
        backgroundScope.launch {
            delay(2_000)
            adapters.complete(Unit)
        }
        runCurrent()

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(AdManagerStatus.Initializing, manager.status.value, "Ready must not precede adapter completion")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(testScheduler.currentTime, 2_000L)
        assertEquals(
            AdapterStartup.Finished(emptyList(), 2.seconds),
            manager.adapterStartup.value,
            "elapsed must be measured from accept to the adapter callback",
        )
    }

    @Test
    fun `adapters that complete after the wait leave Ready at the wait and report Finished later`() = runSlotTest {
        val adapters = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            adapterCompletion = adapters,
            timeSource = VirtualTimeSource(testScheduler),
        )
        backgroundScope.launch { manager.initialize(config(), ConsentMode.SkipConsent) }
        backgroundScope.launch {
            delay(10_000)
            adapters.complete(Unit)
        }
        runCurrent()

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(AdManagerStatus.Initializing, manager.status.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(AdapterStartup.InProgress, manager.adapterStartup.value)

        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(AdapterStartup.InProgress, manager.adapterStartup.value, "still waiting at 9999 ms")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(AdapterStartup.Finished(emptyList(), 10.seconds), manager.adapterStartup.value)
        assertEquals(AdManagerStatus.Ready, manager.status.value, "a late adapter callback must not regress Ready")
    }

    @Test
    fun `adapters already complete make initialize return Ready without advancing virtual time`() = runSlotTest {
        val manager = FakeGoogleAdManager(timeSource = VirtualTimeSource(testScheduler))

        val status = manager.initialize(config(), ConsentMode.SkipConsent)

        assertEquals(AdManagerStatus.Ready, status)
        assertEquals(0L, testScheduler.currentTime, "no adapter wait may be spent when adapters are already done")
        assertEquals(AdapterStartup.Finished(emptyList(), Duration.ZERO), manager.adapterStartup.value)
    }

    @Test
    fun `a zero adapter wait publishes Ready immediately while adapters are still in progress`() = runSlotTest {
        val manager = FakeGoogleAdManager(
            adapterCompletion = CompletableDeferred(),
            timeSource = VirtualTimeSource(testScheduler),
        )
        manager.adapterStartupWait = Duration.ZERO

        val status = manager.initialize(config(), ConsentMode.SkipConsent)

        assertEquals(AdManagerStatus.Ready, status)
        assertEquals(0L, testScheduler.currentTime, "a zero wait must not sleep")
        assertEquals(AdapterStartup.InProgress, manager.adapterStartup.value)

        // Drain the detached watchdog inside the test body: it must not outlive the Main reset.
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(AdapterStartup.NotReported(40.seconds), manager.adapterStartup.value)
    }

    @Test
    fun `a custom adapter wait is honoured exactly`() = runSlotTest {
        val manager = FakeGoogleAdManager(
            adapterCompletion = CompletableDeferred(),
            timeSource = VirtualTimeSource(testScheduler),
        )
        manager.adapterStartupWait = 1.seconds
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.SkipConsent) }
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertEquals(AdManagerStatus.Initializing, manager.status.value)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(AdapterStartup.InProgress, manager.adapterStartup.value)

        // The watchdog still measures from accept, not from the end of the custom wait.
        advanceTimeBy(39_000)
        runCurrent()
        assertEquals(AdapterStartup.NotReported(40.seconds), manager.adapterStartup.value)
    }

    @Test
    fun `an adapter wait set after native initialization started but before the SDK accepts requests is the one used`() = runSlotTest {
        val accept = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            adapterCompletion = CompletableDeferred(),
            timeSource = VirtualTimeSource(testScheduler),
            nativeInitialize = { _, _ -> accept.await() },
        )
        var result: AdManagerStatus? = null
        backgroundScope.launch { result = manager.initialize(config(), ConsentMode.SkipConsent) }
        runCurrent()

        manager.adapterStartupWait = 1.seconds
        accept.complete(Unit)
        runCurrent()
        val acceptedAt = testScheduler.currentTime

        advanceTimeBy(999)
        runCurrent()
        assertEquals(AdManagerStatus.Initializing, manager.status.value, "the 1 s wait set before accept must still be running at 999 ms")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(AdManagerStatus.Ready, result)
        assertEquals(acceptedAt + 1_000L, testScheduler.currentTime, "the wait is measured from accept")

        // Drain the detached watchdog inside the test body: it must not outlive the Main reset.
        advanceTimeBy(40_000)
        runCurrent()
    }

    @Test
    fun `adapterStartupWait accepts the bounds and rejects anything outside them`() = runSlotTest {
        val manager = FakeGoogleAdManager(timeSource = VirtualTimeSource(testScheduler))

        assertEquals(AdManager.DEFAULT_ADAPTER_STARTUP_WAIT, manager.adapterStartupWait, "default getter value")

        assertFailsWith<IllegalArgumentException> { manager.adapterStartupWait = (-1).milliseconds }
        assertFailsWith<IllegalArgumentException> { manager.adapterStartupWait = 31.seconds }
        assertFailsWith<IllegalArgumentException> { manager.adapterStartupWait = INFINITE }
        assertEquals(
            AdManager.DEFAULT_ADAPTER_STARTUP_WAIT,
            manager.adapterStartupWait,
            "a rejected value must not replace the previous one",
        )

        manager.adapterStartupWait = 0.seconds
        assertEquals(0.seconds, manager.adapterStartupWait)
        manager.adapterStartupWait = 30.seconds
        assertEquals(30.seconds, manager.adapterStartupWait)
    }

    @Test
    fun `a failed accept reports a retryable failure and never starts adapter tracking`() = runSlotTest {
        val manager = FakeGoogleAdManager(
            nativeInitialize = { _, _ -> throw IllegalStateException("boom") },
            timeSource = VirtualTimeSource(testScheduler),
        )

        val status = manager.initialize(config(), ConsentMode.SkipConsent)

        val failed = assertIs<AdManagerStatus.Failed>(status)
        assertTrue(failed.retryable, "a failure before accept must leave a retry open")
        assertEquals(AdapterStartup.NotStarted, manager.adapterStartup.value)
    }

    @Test
    fun `the After hook runs exactly once whether or not adapters ever report`() = runSlotTest {
        var afterCount = 0
        val hook = object : AdInitializationHook {
            override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
                if (phase == AdInitializationPhase.AfterMobileAdsInitialize) afterCount++
            }
        }
        val manager = FakeGoogleAdManager(
            adapterCompletion = CompletableDeferred(),
            timeSource = VirtualTimeSource(testScheduler),
        )
        val hookedConfig = AdConfig(
            androidAppId = "ca-app-pub-A",
            iosAppId = "ca-app-pub-A",
            initializationHooks = listOf(hook),
        )
        backgroundScope.launch { manager.initialize(hookedConfig, ConsentMode.SkipConsent) }
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, afterCount, "the After hook must have run once by the Ready publication at 5 s")

        advanceTimeBy(35_000)
        runCurrent()
        assertEquals(1, afterCount, "the NotReported tail must not re-run the After hook")
    }

    @Test
    fun `NoOpAdManager reports Unavailable and ignores the adapter wait setting`() {
        assertEquals(AdapterStartup.Unavailable, NoOpAdManager.adapterStartup.value)
        assertEquals(AdManager.DEFAULT_ADAPTER_STARTUP_WAIT, NoOpAdManager.adapterStartupWait)

        NoOpAdManager.adapterStartupWait = 1.seconds

        assertEquals(
            AdManager.DEFAULT_ADAPTER_STARTUP_WAIT,
            NoOpAdManager.adapterStartupWait,
            "the default implementation reports the default and ignores sets",
        )
    }

    @Test
    fun `status never regresses from Ready once adapters report late`() = runSlotTest {
        val adapters = CompletableDeferred<Unit>()
        val manager = FakeGoogleAdManager(
            adapterCompletion = adapters,
            timeSource = VirtualTimeSource(testScheduler),
        )
        val seen = mutableListOf<AdManagerStatus>()
        backgroundScope.launch { manager.status.collect { seen += it } }
        backgroundScope.launch { manager.initialize(config(), ConsentMode.SkipConsent) }
        backgroundScope.launch {
            delay(10_000)
            adapters.complete(Unit)
        }
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        advanceTimeBy(30_000)
        runCurrent()

        val firstReady = seen.indexOf(AdManagerStatus.Ready)
        assertTrue(firstReady >= 0, "Ready must be observed; saw $seen")
        assertTrue(
            seen.take(firstReady).contains(AdManagerStatus.Initializing),
            "the collector must have seen Initializing before Ready; saw $seen",
        )
        val afterReady = seen.drop(firstReady)
        assertTrue(
            afterReady.none { it == AdManagerStatus.Initializing || it is AdManagerStatus.Failed },
            "status must never regress from Ready; saw $seen",
        )
        assertEquals(AdManagerStatus.Ready, manager.status.value)
        assertEquals(AdapterStartup.Finished(emptyList(), 10.seconds), manager.adapterStartup.value)
    }
}

/** Reads [TestCoroutineScheduler.currentTime] so adapter-wait durations follow virtual time. */
private class VirtualTimeSource(private val scheduler: TestCoroutineScheduler) : TimeSource {
    override fun markNow(): TimeMark = VirtualMark(scheduler, scheduler.currentTime)

    private class VirtualMark(
        private val scheduler: TestCoroutineScheduler,
        private val startMillis: Long,
    ) : TimeMark {
        override fun elapsedNow(): Duration = (scheduler.currentTime - startMillis).milliseconds
        override fun plus(duration: Duration): TimeMark =
            VirtualMark(scheduler, startMillis + duration.inWholeMilliseconds)
    }
}
