package dev.avinya.ads

import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.awaitCondition
import dev.avinya.ads.internal.awaitHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * Pins the behaviour that closes the "no Activity / no root view controller" class of
 * consent failure.
 *
 * The host is transiently absent in normal operation — between an ad Activity stopping and
 * the app Activity restarting, across a configuration change, and while an iOS view
 * controller is mid-presentation. Treating the first `null` as final is what left the SDK
 * uninitialized for a whole session; [awaitHost] gives the host a bounded moment to appear.
 */
class HostAvailabilityTest {

    @Test
    fun returnsImmediatelyWhenTheHostIsAlreadyPresent() = runTest {
        var probes = 0
        val host = awaitHost(2.seconds) { probes++; "activity" }

        assertEquals("activity", host)
        assertEquals(1, probes, "an available host must not cost a single poll delay")
    }

    @Test
    fun waitsForAHostThatArrivesShortlyAfterTheFirstProbe() = runTest {
        var probes = 0
        // Absent for the first three probes, exactly like an Activity handoff in flight.
        val host = awaitHost(2.seconds) {
            probes++
            if (probes > 3) "activity" else null
        }

        assertEquals("activity", host)
        assertEquals(4, probes)
    }

    @Test
    fun givesUpAfterTheTimeoutWhenTheHostNeverArrives() = runTest {
        val host = awaitHost(200.milliseconds, pollInterval = InitializationTimeouts.hostPoll) { null }

        assertNull(host, "a genuinely absent host must still fail, just not instantly")
    }

    @Test
    fun stopsProbingOnceTheHostIsFound() = runTest {
        var probes = 0
        awaitHost(2.seconds) {
            probes++
            if (probes >= 2) "activity" else null
        }

        assertEquals(2, probes, "probing must stop at the first non-null result")
    }

    @Test
    fun awaitConditionPollsUntilTheConditionHolds() = runTest {
        var checks = 0
        val became = awaitCondition(2.seconds) { checks++; checks > 2 }

        assertTrue(became)
        assertEquals(3, checks)
    }

    @Test
    fun awaitConditionGivesUpWhenTheConditionNeverHolds() = runTest {
        // This is the ATT foreground gate's failure mode: an app launched into the background
        // never becomes active, and the prompt must be skipped rather than waited on forever.
        assertFalse(awaitCondition(200.milliseconds) { false })
    }
}
