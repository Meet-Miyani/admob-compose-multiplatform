package dev.avinya.admob.showcase.ui.ad

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the one piece of ad gating that has no other coverage.
 *
 * A leaked suppression silently disables app-open ads for the rest of the
 * session — there is no error, no log, and nothing on screen to notice. That
 * makes it exactly the kind of defect a test has to catch, and the depth
 * counting is plain enough to assert on values alone.
 */
class AppOpenSuppressorTest {

    @Test
    fun startsUnsuppressed() {
        assertFalse(AppOpenSuppressor().isBlocked)
    }

    @Test
    fun enteringSuppresses() {
        val suppressor = AppOpenSuppressor()

        suppressor.enter()

        assertTrue(suppressor.isBlocked)
    }

    @Test
    fun aBalancedEnterExitLeavesNothingSuppressed() {
        val suppressor = AppOpenSuppressor()

        suppressor.enter()
        suppressor.exit()

        assertFalse(suppressor.isBlocked)
    }

    @Test
    fun overlappingFlowsDoNotUnsuppressEachOther() {
        // The reason this is depth-counted rather than a boolean: an unlock
        // finishing must not re-enable app-open ads while onboarding is still
        // on screen.
        val suppressor = AppOpenSuppressor()

        suppressor.enter()
        suppressor.enter()
        suppressor.exit()

        assertTrue(suppressor.isBlocked, "outer flow is still active")

        suppressor.exit()

        assertFalse(suppressor.isBlocked)
    }

    @Test
    fun exitNeverDrivesDepthNegative() {
        // An unbalanced exit must not leave the suppressor unable to suppress:
        // if depth went to -1, the next enter() would land on 0 and silently
        // fail to block.
        val suppressor = AppOpenSuppressor()

        suppressor.exit()
        suppressor.exit()
        suppressor.enter()

        assertTrue(suppressor.isBlocked)
    }

    @Test
    fun suppressingRestoresAfterASuccessfulBlock() = runTest {
        val suppressor = AppOpenSuppressor()

        val result = suppressor.suppressing {
            assertTrue(suppressor.isBlocked, "suppressed for the duration of the block")
            "unlocked"
        }

        assertEquals("unlocked", result)
        assertFalse(suppressor.isBlocked)
    }

    @Test
    fun suppressingRestoresWhenTheBlockThrows() = runTest {
        // The mid-transaction failure case. A wallet debit that throws must not
        // leave app-open ads disabled for the rest of the session.
        val suppressor = AppOpenSuppressor()

        assertFailsWith<IllegalStateException> {
            suppressor.suppressing { error("debit failed") }
        }

        assertFalse(suppressor.isBlocked)
    }

    @Test
    fun suppressingRestoresOnCancellation() = runTest {
        // Navigating away mid-unlock cancels the coroutine. The finally still
        // runs, so this must not leak either.
        val suppressor = AppOpenSuppressor()

        assertFailsWith<CancellationException> {
            suppressor.suppressing { throw CancellationException("navigated away") }
        }

        assertFalse(suppressor.isBlocked)
    }

    @Test
    fun nestedSuppressingCallsStaySuppressedUntilTheOutermostReturns() = runTest {
        val suppressor = AppOpenSuppressor()

        suppressor.suppressing {
            suppressor.suppressing {
                assertTrue(suppressor.isBlocked)
            }
            assertTrue(suppressor.isBlocked, "inner completion must not un-suppress the outer")
        }

        assertFalse(suppressor.isBlocked)
    }

    @Test
    fun startsWithNoAdScreen() {
        assertFalse(AppOpenSuppressor().isOnAdScreen)
    }

    @Test
    fun enteringAnAdScreenMarksIt() {
        val suppressor = AppOpenSuppressor()

        suppressor.enterAdScreen()

        assertTrue(suppressor.isOnAdScreen)
    }

    @Test
    fun overlappingAdScreensAreDepthCounted() {
        // During a navigation transition the outgoing and incoming screens are both composed, so
        // the incoming one enters before the outgoing one exits.
        val suppressor = AppOpenSuppressor()

        suppressor.enterAdScreen()
        suppressor.enterAdScreen()
        suppressor.exitAdScreen()

        assertTrue(suppressor.isOnAdScreen, "the incoming ad screen is still visible")

        suppressor.exitAdScreen()

        assertFalse(suppressor.isOnAdScreen)
    }

    @Test
    fun anUnbalancedAdScreenExitDoesNotGoNegative() {
        val suppressor = AppOpenSuppressor()

        suppressor.exitAdScreen()
        suppressor.enterAdScreen()

        assertTrue(suppressor.isOnAdScreen, "a stray exit must not cancel the next enter")
    }

    @Test
    fun adScreensAndSensitiveFlowsAreIndependent() {
        val suppressor = AppOpenSuppressor()

        suppressor.enterAdScreen()
        assertFalse(suppressor.isBlocked, "an ad screen is not a sensitive flow")

        suppressor.exitAdScreen()
        suppressor.enter()
        assertFalse(suppressor.isOnAdScreen, "a sensitive flow is not an ad screen")
    }
}
