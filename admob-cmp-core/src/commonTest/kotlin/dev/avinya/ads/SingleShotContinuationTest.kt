package dev.avinya.ads

import dev.avinya.ads.internal.SingleShotContinuation
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
 * Regression tests for [SingleShotContinuation] — the atomic single-shot resume every native/SDK
 * callback in this library goes through.
 *
 * Ported from @yangwuan55's `ContinuationResumeTest` in #45, which pinned the same contract for
 * the `tryResume`-based helper this replaced. The cases and their reasoning are his; two of them
 * now assert the opposite outcome, because the cleanup semantics deliberately changed (see the
 * last two tests).
 *
 * The defect these pin down comes from a real device crash (`FATAL EXCEPTION: GMA(BG) 7`,
 * 2026-09-18): the previous check-then-act shape
 * `if (continuation.isActive) { continuation.resume(value) }` is not atomic. Two AdMob terminal
 * callbacks arriving on two threads both pass the `isActive` check, both call `resume()`, and the
 * second one throws `IllegalStateException: Already resumed, but proposed with update ...` on the
 * SDK's own thread, where no caller-side `try/catch` can reach it — the whole process dies.
 * Reproduction was simply reopening a banner-bearing screen while the ad unit answered `NO_FILL`.
 *
 * The bare-double-resume test is deliberately a *negative control*: without it, the other cases
 * would keep passing even if `resume()` became tolerant of a second call, and this file would
 * silently stop protecting anything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SingleShotContinuationTest {

    @Test
    fun `resume delivers once and the second call returns false without throwing`() = runTest {
        val (continuation, result) = captured<String>()

        assertTrue(continuation.resume("first"), "the first call must win the claim")
        // Must NOT throw: a losing caller is a normal outcome (a second SDK callback, or a
        // cancellation that landed first), never an exception on the SDK's callback thread.
        assertFalse(continuation.resume("second"), "the second call must lose, not throw")

        assertEquals("first", result.await(), "the winner's value is the one delivered")
    }

    @Test
    fun `control - a bare second resume on the same continuation throws Already resumed`() = runTest {
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val result = async { suspendCancellableCoroutine<String> { captured.complete(it) } }
        val raw = captured.await()

        raw.resume("first")
        val failure = assertFailsWith<IllegalStateException> {
            raw.resume("second")
        }

        assertTrue(
            failure.message?.contains("Already resumed") == true,
            "expected kotlinx's `Already resumed` message, got: ${failure.message}"
        )
        assertEquals("first", result.await())
    }

    @Test
    fun `eight threads racing - exactly one call wins and none throws`() = runTest {
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
                    List(CALLS_PER_THREAD) { continuation.resume(1) }
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

    /**
     * Inverted from the ported original, and this is the point of the migration.
     *
     * With `tryResumeOnce` the handler did NOT run for a losing caller — kotlinx invokes
     * `onCancellation` only when a resume that already won is then cancelled in flight — so
     * every call site owning an ad had to remember a second `if (!delivered) destroy()` branch,
     * and forgetting it leaked a native ad for the process lifetime.
     *
     * `onUndelivered` covers every path by which the value fails to reach the waiter, so cleanup
     * is stated once and the extra branch is gone.
     */
    @Test
    fun `a losing call runs its cleanup exactly once so the call site needs no second branch`() = runTest {
        val (continuation, result) = captured<String>()
        val cleaned = mutableListOf<String>()

        assertTrue(continuation.resume("first") { cleaned += it })
        assertFalse(continuation.resume("second") { cleaned += it })
        assertEquals("first", result.await())

        assertEquals(
            listOf("second"),
            cleaned,
            "the loser's value must be freed for it, and the winner's must not be touched",
        )
    }

    /**
     * Cancel-before-resume: the late value is freed rather than stranded.
     *
     * Note what the return value means, because it is easy to get backwards. This claim is a
     * CAS owned by [SingleShotContinuation], and on an already-cancelled continuation nothing
     * else has claimed it — so `resume` returns TRUE here. True means "this caller won the
     * claim", not "the waiter received the value". Resume-versus-cancel is left to kotlinx,
     * where resuming a cancelled continuation is a documented no-op that runs the
     * onUndelivered handler.
     *
     * What matters for a leak is the handler, not the Boolean: the ad is freed on this path
     * either way, and no call site branches on the return value to decide cleanup.
     */
    @Test
    fun `on an already-cancelled continuation the value is still cleaned up`() = runTest {
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val job = launch {
            suspendCancellableCoroutine<String> { continuation -> captured.complete(continuation) }
        }
        val raw = captured.await()
        job.cancelAndJoin()
        advanceUntilIdle()
        val single = SingleShotContinuation(raw)
        val cleaned = mutableListOf<String>()

        single.resume("late value") { cleaned += it }

        assertEquals(
            listOf("late value"),
            cleaned,
            "an ad that arrives after cancellation has no other owner; it must be freed here",
        )
    }

    @Test
    fun `isActive reports false once the continuation has been claimed`() = runTest {
        val (continuation, result) = captured<String>()

        assertTrue(continuation.isActive)
        continuation.resume("value")

        // Used by call sites to skip work nobody will receive — installing a paid-event handler
        // on an ad about to be dropped, for instance. Never used as a guard in front of resume().
        assertFalse(continuation.isActive)
        assertEquals("value", result.await())
    }

    /**
     * Suspends a coroutine and hands back both its [SingleShotContinuation] and the [Deferred]
     * that completes when something resumes it — the exact shape a native callback sees at a
     * `suspendSingleShot` site.
     */
    private suspend fun <T> TestScope.captured(): Pair<SingleShotContinuation<T>, Deferred<T>> {
        val captured = CompletableDeferred<SingleShotContinuation<T>>()
        val result = async {
            suspendCancellableCoroutine<T> { captured.complete(SingleShotContinuation(it)) }
        }
        return captured.await() to result
    }

    private companion object {
        const val THREADS = 8
        const val CALLS_PER_THREAD = 8
    }
}
