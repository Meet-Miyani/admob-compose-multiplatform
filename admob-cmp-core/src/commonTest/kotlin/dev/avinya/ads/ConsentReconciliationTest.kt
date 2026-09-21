package dev.avinya.ads

import dev.avinya.ads.internal.SingleShotContinuation
import dev.avinya.ads.internal.reconcileThenResumeIfActive
import dev.avinya.ads.internal.suspendSingleShot
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * [reconcileThenResumeIfActive] is the mechanism behind the AndroidConsentController /
 * IosConsentController fix: a UMP native callback (form dismissed, privacy options form
 * dismissed) must always reconcile the SDK's own consent state, even if the coroutine that
 * originally awaited it was cancelled. Only resuming that waiter is conditional.
 *
 * Resuming it is also *atomic*: the helper claims the continuation through
 * [dev.avinya.ads.internal.SingleShotContinuation] rather than through a racy `isActive` read,
 * so a second concurrent callback cannot make it throw. That contract, and the control case
 * proving the old shape really does throw, live in [SingleShotContinuationTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentReconciliationTest {

    @Test
    fun `reconciles and resumes an active waiter with the given value`() = runTest {
        val captured = CompletableDeferred<SingleShotContinuation<String>>()
        val result = async {
            suspendSingleShot<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        var reconciled = false

        reconcileThenResumeIfActive(continuation, "granted") { reconciled = true }

        assertEquals("granted", result.await())
        assertTrue(reconciled, "reconcile must run for an active waiter")
    }

    @Test
    fun `still reconciles when the waiter was already cancelled without resuming it`() = runTest {
        val captured = CompletableDeferred<SingleShotContinuation<String>>()
        val job = launch {
            suspendSingleShot<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        job.cancelAndJoin()
        advanceUntilIdle()
        var reconciled = false

        // Must not throw resuming an already-cancelled continuation, and -- the exact bug this
        // guards against -- must still run reconcile: a real privacy decision persisted by UMP
        // must never be dropped just because the caller that started gatherConsent() /
        // showPrivacyOptions() went away (navigation, rotation, process death) before the
        // native callback fired.
        reconcileThenResumeIfActive(continuation, "granted") { reconciled = true }

        assertTrue(reconciled, "reconcile must still run even though the waiter is gone")
    }

    @Test
    fun `reconcile runs to completion before the resume attempt`() = runTest {
        val captured = CompletableDeferred<SingleShotContinuation<String>>()
        val result = async {
            suspendSingleShot<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        val order = mutableListOf<String>()

        reconcileThenResumeIfActive(continuation, "granted") { order += "reconcile" }
        order += "resumed"

        assertEquals("granted", result.await())
        assertEquals(listOf("reconcile", "resumed"), order)
    }

    // Documents exactly ONE of the two races that exist at a resume site: kotlinx.coroutines
    // deliberately makes Continuation.resume() on an already-cancelled CancellableContinuation a
    // safe no-op (see CancellableContinuationImpl's handling of the CancelledContinuation state),
    // specifically so that "the loser of a resume/cancel race is safe to call resume anyway" is a
    // supported pattern.
    //
    // This does NOT generalise to resume/resume -- the correction that matters. A concurrent
    // second terminal callback is not a cancellation: whichever of the two calls loses that race
    // throws `IllegalStateException: Already resumed, but proposed with update ...` on the SDK's
    // own callback thread, where no caller-side try/catch can reach it, killing the process. That
    // is a real, on-device crash (FATAL EXCEPTION: GMA(BG) 7, 2026-09-18, thrown
    // from AndroidBannerAdController's onAdFailedToLoad while the ad unit answered NO_FILL).
    //
    // Therefore: `if (continuation.isActive) { continuation.resume(value) }` is NOT race-safe,
    // and it is no longer used anywhere in this library. Every native-callback resume goes
    // through
    // `SingleShotContinuation` (internal/SingleShotContinuation.kt), which claims the continuation
    // atomically -- see SingleShotContinuationTest for that contract and for a control case proving
    // bare double-resume really does throw. Do not read this test as an endorsement of the
    // check-then-act shape at any callback site.
    //
    // If a future kotlinx.coroutines version ever changes this cancellation tolerance, this test
    // fails loudly instead of the change silently becoming unsafe.
    @Test
    fun `resume on an already-cancelled continuation does not throw`() = runTest {
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val job = launch {
            suspendCancellableCoroutine<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        job.cancelAndJoin()
        advanceUntilIdle()

        // No isActive check at all -- safe ONLY because this is resume vs. cancel, which kotlinx
        // tolerates. It is not the race the old global idiom claimed to protect against, and it
        // is not the race that crashed the process: two concurrent resumes would throw here.
        continuation.resume("late value")
    }
}
