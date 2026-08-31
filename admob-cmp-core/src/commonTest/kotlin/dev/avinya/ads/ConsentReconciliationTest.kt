package dev.avinya.ads

import dev.avinya.ads.internal.reconcileThenResumeIfActive
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsentReconciliationTest {

    @Test
    fun `reconciles and resumes an active waiter with the given value`() = runTest {
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val result = async {
            suspendCancellableCoroutine<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        var reconciled = false

        reconcileThenResumeIfActive(continuation, "granted") { reconciled = true }

        assertEquals("granted", result.await())
        assertTrue(reconciled, "reconcile must run for an active waiter")
    }

    @Test
    fun `still reconciles when the waiter was already cancelled without resuming it`() = runTest {
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val job = launch {
            suspendCancellableCoroutine<String> { continuation -> captured.complete(continuation) }
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
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val result = async {
            suspendCancellableCoroutine<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        val order = mutableListOf<String>()

        reconcileThenResumeIfActive(continuation, "granted") { order += "reconcile" }
        order += "resumed"

        assertEquals("granted", result.await())
        assertEquals(listOf("reconcile", "resumed"), order)
    }

    // Documents the invariant reconcileThenResumeIfActive's isActive-check-then-resume shape
    // relies on: kotlinx.coroutines deliberately makes Continuation.resume() on an
    // already-cancelled CancellableContinuation a safe no-op (see CancellableContinuationImpl's
    // handling of the CancelledContinuation state) rather than throwing -- specifically so that
    // "the loser of a resume/cancel race is safe to call resume anyway" is a supported pattern.
    // That is what makes the isActive check here (and at every other native-callback site in
    // this codebase) race-safe without needing tryResume/completeResume: even if cancellation
    // lands between the isActive check and the resume() call, resume() itself tolerates it.
    // If a future kotlinx.coroutines version ever changes this, this test fails loudly instead
    // of the change silently becoming unsafe.
    @Test
    fun `resume on an already-cancelled continuation does not throw`() = runTest {
        val captured = CompletableDeferred<CancellableContinuation<String>>()
        val job = launch {
            suspendCancellableCoroutine<String> { continuation -> captured.complete(continuation) }
        }
        val continuation = captured.await()
        job.cancelAndJoin()
        advanceUntilIdle()

        // No isActive check at all -- this is the exact call the TOCTOU claim says is unsafe.
        continuation.resume("late value")
    }
}
