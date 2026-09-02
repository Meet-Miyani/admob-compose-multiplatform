package dev.avinya.ads.internal

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation

/**
 * Runs [reconcile] unconditionally, then resumes [continuation] with [value] only if it is
 * still active.
 *
 * UMP's native form/update callback fires exactly once regardless of whether the Kotlin
 * caller is still listening. Cancelling the coroutine that started `gatherConsent()` or
 * `showPrivacyOptions()` does not cancel the form the user is looking at, and does not undo
 * the consent decision UMP has already persisted natively — a `ConsentController` that only
 * reconciles its own state (`canRequestAds`, consent status) when the waiter is still active
 * would silently drop a real privacy decision the moment a host coroutine dies mid-form
 * (navigation, rotation, process death). [reconcile] is exactly that state and must always
 * run. Only *resuming the waiter* is conditional: resuming an inactive continuation is a
 * no-op at best and an `IllegalStateException` at worst.
 *
 * The `isActive` check is an OPTIMIZATION, not a correctness mechanism.
 * `CancellableContinuation` already resolves cancellation-versus-resume atomically, and
 * resuming a cancelled continuation is a no-op. The check is kept because it makes the intent
 * ("the waiter may be gone; the state must not be") obvious at the call site. Do not read it
 * as the thing that makes this safe — [reconcile] running first is.
 */
internal inline fun <T> reconcileThenResumeIfActive(
    continuation: CancellableContinuation<T>,
    value: T,
    reconcile: () -> Unit,
) {
    reconcile()
    if (continuation.isActive) continuation.resume(value)
}
