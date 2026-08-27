package dev.avinya.ads

import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.awaitNativeCallback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class NativeCallbackTimeoutTest {

    @Test
    fun `returns the block result`() = runTest {
        assertEquals("ok", awaitNativeCallback("op", 10.seconds) { "ok" })
    }

    @Test
    fun `a null result is not mistaken for a timeout`() = runTest {
        // The implementation boxes the result precisely so that a callback which legitimately
        // reports null cannot be reported as "the SDK never called back".
        assertNull(awaitNativeCallback<String?>("op", 10.seconds) { null })
    }

    @Test
    fun `a callback that never arrives fails with a named timeout`() = runTest {
        val failure = assertFailsWith<NativeCallbackTimeoutException> {
            awaitNativeCallback("MobileAds.initialize", 30.seconds) { awaitCancellation() }
        }
        assertTrue(
            failure.message!!.contains("MobileAds.initialize"),
            "the operation must be named so the log identifies which call hung"
        )
    }

    @Test
    fun `a timeout is not a CancellationException`() = runTest {
        // The load-bearing property. Both managers wrap initialization in
        // catch (CancellationException) arms that restore the previous status and treat the attempt
        // as abandoned. withTimeout would raise TimeoutCancellationException -- a CancellationException
        // -- so every timeout would be silently swallowed as "the caller walked away".
        val failure = assertFailsWith<NativeCallbackTimeoutException> {
            awaitNativeCallback("op", 5.seconds) { awaitCancellation() }
        }
        assertTrue(failure !is CancellationException, "a timeout must be distinguishable from cancellation")
    }

    @Test
    fun `caller cancellation still propagates as cancellation`() = runTest {
        val started = CompletableDeferred<Unit>()
        var observed: Throwable? = null
        val job = launch {
            try {
                awaitNativeCallback("op", 60.seconds) {
                    started.complete(Unit)
                    awaitCancellation()
                }
            } catch (t: Throwable) {
                observed = t
                throw t
            }
        }
        started.await()
        job.cancelAndJoin()
        advanceUntilIdle()

        assertTrue(
            observed is CancellationException,
            "cancelling the caller must not be reported as an SDK timeout"
        )
    }

    @Test
    fun `a callback that arrives before the deadline wins`() = runTest {
        val gate = CompletableDeferred<String>()
        var result: String? = null
        val job = launch {
            result = awaitNativeCallback("op", 30.seconds) { gate.await() }
        }
        gate.complete("callback value")
        job.join()

        assertEquals("callback value", result)
    }
}
