package dev.avinya.ads

import dev.avinya.ads.internal.awaitForegroundForPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundPresentationWaitTest {

    @Test
    fun `an app already in the foreground is not delayed`() = runTest {
        var checks = 0
        val start = currentTime

        val result = awaitForegroundForPresentation { checks++; true }

        assertTrue(result)
        assertEquals(1, checks)
        assertEquals(0L, currentTime - start, "no wait when the app is already foreground")
    }

    @Test
    fun `waits until the app becomes foreground`() = runTest {
        var checks = 0
        val start = currentTime

        // False on the first two checks, true on the third.
        val result = awaitForegroundForPresentation { checks++; checks >= 3 }

        assertTrue(result)
        assertEquals(3, checks)
        assertEquals(100L, currentTime - start, "two 50 ms polls before the third check")
    }

    @Test
    fun `gives up after the timeout when the app never becomes foreground`() = runTest {
        val start = currentTime

        val result = awaitForegroundForPresentation { false }

        assertFalse(result)
        assertTrue(currentTime - start <= 1000L, "bounded by the 1 s timeout, was ${currentTime - start} ms")
        assertTrue(currentTime - start >= 950L, "must actually wait close to the timeout, was ${currentTime - start} ms")
    }
}
