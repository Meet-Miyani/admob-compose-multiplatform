package dev.avinya.ads

import dev.avinya.ads.internal.tryResumeOnce
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Regression tests for [tryResumeOnce] — the atomic single-shot resume every native/SDK callback
 * in this library now goes through.
 *
 * The defect these pin down comes from a real device crash (`FATAL EXCEPTION: GMA(BG) 7`, PKR110,
 * 2026-09-18 21:42:04): the previous check-then-act shape
 * `if (continuation.isActive) { continuation.resume(value) }` is not atomic. Two AdMob terminal
 * callbacks arriving on two threads both pass the
 * `isActive` check, both call `resume()`, and the second one throws
 * `IllegalStateException: Already resumed, but proposed with update ...` on the SDK's own thread,
 * where no caller-side `try/catch` can reach it — the whole process dies. Reproduction was simply
 * reopening a banner-bearing screen while the ad unit answered `NO_FILL`.
 *
 * The bare-double-resume test is deliberately a *negative control*: without it, the other cases
 * would keep passing even if `resume()` became tolerant of a second call, and this file would
 * silently stop protecting anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContinuationResumeTest {

    @Test
    fun `tryResumeOnce delivers once and the second call returns false without throwing`() = runTest {
        val (continuation, result) = captured<String>()

        assertTrue(continuation.tryResumeOnce("first"), "the first call must win the claim")
        // Must NOT throw: a losing caller is a normal outcome (a second SDK callback, or a
        // cancellation that landed first), never an exception on the SDK's callback thread.
        assertFalse(continuation.tryResumeOnce("second"), "the second call must lose, not throw")

        assertEquals("first", result.await(), "the winner's value is the one delivered")
        assertTrue(continuation.isCompleted)
        assertFalse(continuation.isActive)
    }

    @Test
    fun `control - a bare second resume on the same continuation throws Already resumed`() = runTest {
        val (continuation, result) = captured<String>()

        continuation.resume("first")
        val failure = assertFailsWith<IllegalStateException> {
            continuation.resume("second")
        }

        assertTrue(
            failure.message?.contains("Already resumed") == true,
            "expected kotlinx's `Already resumed` message, got: ${failure.message}"
        )
        assertEquals("first", result.await())
    }

    @Test
    fun `eight threads racing, exactly one call wins and none throws`() = runTest {
        val (continuation, result) = captured<Int>()

        // A genuine multi-thread race: Dispatchers.Default runs these on its own worker threads
        // and the gate releases them together. The assertions are timing-independent — atomic
        // single-shot means exactly one `true` however the threads interleave — so this cannot
        // flake. A lost race must never surface as an exception: a throw here fails the test.
        val gate = CompletableDeferred<Unit>()
        val outcomes = withContext(Dispatchers.Default) {
            val racers = List(THREADS) {
                async {
                    gate.await()
                    List(CALLS_PER_THREAD) { continuation.tryResumeOnce(1) }
                }
            }
            gate.complete(Unit)
            racers.awaitAll()
        }.flatten()

        assertEquals(THREADS * CALLS_PER_THREAD, outcomes.size)
        assertEquals(1, outcomes.count { it }, "exactly one caller may claim the continuation")
        assertEquals(outcomes.size - 1, outcomes.count { !it }, "every other call reports the loss")
        assertEquals(1, result.await())
    }

    @Test
    fun `a losing call does not run its cancellation handler - the caller must free the value`() = runTest {
        val (continuation, result) = captured<String>()
        var cleanups = 0

        assertTrue(continuation.tryResumeOnce("first") { _, _, _ -> cleanups++ })
        assertFalse(continuation.tryResumeOnce("second") { _, _, _ -> cleanups++ })
        assertEquals("first", result.await())

        // kotlinx documents onCancellation as "called if and only if the value is not delivered
        // because of the dispatch in the process" — it is NOT invoked for an already-resumed
        // continuation. Call sites that own an ad or a native batch therefore free it themselves
        // when tryResumeOnce returns false (AndroidBannerAdController's onAdLoaded,
        // resumeLoadedAd in AndroidFullScreenSlots, AndroidNativeAdPlatform's
        // onAdLoadingCompleted). This asserts the premise they depend on.
        assertEquals(0, cleanups, "no handler may run after the value was delivered")
    }

    @Test
    fun `on an already-cancelled continuation tryResumeOnce reports the loss and runs no handler`() = runTest {
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val job = launch {
            suspendCancellableCoroutine<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        job.cancelAndJoin()
        advanceUntilIdle()
        var cleanups = 0

        // Cancel-before-resume: this loser also owns a value nothing will ever deliver.
        // `tryResume` returns a null token without invoking onCancellation, so the same "the
        // caller frees it" rule applies — which is why the call sites destroy their own value on
        // `false` instead of relying on the handler. (Contrast `resume(value, onCancellation)`,
        // which DOES invoke the handler for a cancelled continuation; that asymmetry is called
        // out in the helper's KDoc, because getting it backwards is a leak.)
        assertFalse(continuation.tryResumeOnce("late value") { _, _, _ -> cleanups++ })
        assertEquals(0, cleanups)
    }

    /**
     * Suspends a coroutine and hands back both its raw [CancellableContinuation] and the
     * [Deferred] that completes when something resumes it — the exact shape a native callback
     * sees at a `suspendCancellableCoroutine` site.
     */
    private suspend fun <T> TestScope.captured(): Pair<CancellableContinuation<T>, Deferred<T>> {
        val captured = CompletableDeferred<CancellableContinuation<T>>()
        val result = async { suspendCancellableCoroutine<T> { captured.complete(it) } }
        return captured.await() to result
    }

    private companion object {
        const val THREADS = 8
        const val CALLS_PER_THREAD = 8
    }
}
