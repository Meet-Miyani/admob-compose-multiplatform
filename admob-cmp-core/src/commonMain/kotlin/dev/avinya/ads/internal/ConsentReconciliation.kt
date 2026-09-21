package dev.avinya.ads.internal

import kotlinx.coroutines.CancellableContinuation

/**
 * Runs [reconcile] unconditionally, then resumes [continuation] with [value] at most once —
 * only if it has not already been resumed by another callback or cancelled.
 *
 * UMP's native form/update callback fires exactly once regardless of whether the Kotlin
 * caller is still listening. Cancelling the coroutine that started `gatherConsent()` or
 * `showPrivacyOptions()` does not cancel the form the user is looking at, and does not undo
 * the consent decision UMP has already persisted natively — a `ConsentController` that only
 * reconciles its own state (`canRequestAds`, consent status) when the waiter is still active
 * would silently drop a real privacy decision the moment a host coroutine dies mid-form
 * (navigation, rotation, process death). [reconcile] is exactly that state and must always
 * run. Only *resuming the waiter* is conditional.
 *
 * The conditional resume goes through [tryResumeOnce], NOT through
 * `if (continuation.isActive) { continuation.resume(value) }`. That older shape is not atomic,
 * and it is the exact defect this library shipped: an `isActive` check covers only **resume vs.
 * cancel** — cancellation landing between the check and the call is tolerated, because resuming
 * an already-cancelled continuation is a no-op. It does NOT cover **resume vs. resume**: two
 * callbacks that both observe `isActive == true` both call `resume()`, and the loser throws
 * `IllegalStateException: Already resumed, but proposed with update ...` on the SDK's own
 * callback thread, where no caller-side `try/catch` can reach it — the process dies.
 *
 * That is not hypothetical: it was observed on a real consumer device as
 * `FATAL EXCEPTION: GMA(BG) 7` on 2026-09-18, thrown from a concurrent second terminal callback in
 * `AndroidBannerAdController`'s `onAdFailedToLoad` while the ad unit answered `NO_FILL`. An
 * `isActive` read is a snapshot; only `tryResume` makes a claim, and it makes it atomically.
 * See [tryResumeOnce] for the full trace and the correct invariant.
 *
 * The function keeps its name because its *behaviour* is unchanged — it still resumes only a
 * waiter that is still active. What changed is the mechanism: an atomic claim instead of a racy
 * read. Do not reintroduce the racy read here or at any other native-callback site.
 */
internal inline fun <T> reconcileThenResumeIfActive(
    continuation: CancellableContinuation<T>,
    value: T,
    reconcile: () -> Unit,
) {
    reconcile()
    continuation.tryResumeOnce(value)
}
