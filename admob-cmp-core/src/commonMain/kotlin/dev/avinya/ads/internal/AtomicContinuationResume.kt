package dev.avinya.ads.internal

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi

/**
 * Atomically resumes this continuation at most once — whoever wins, everyone else gets `false`.
 *
 * ## Why this exists (a real production crash, not a hypothetical)
 *
 * Every native/SDK callback in this library used to restore its coroutine with the
 * check-then-act shape `if (continuation.isActive) { continuation.resume(value) }`. That is
 * **not** atomic: when an ad SDK delivers its terminal callback on two threads at once, both
 * calls pass the `isActive` check and the second `resume()` throws
 * `IllegalStateException: Already resumed, but proposed with update ...` on the SDK's own
 * background thread, where no caller-side `try/catch` can reach it. The process dies.
 *
 * Observed on a real device (crash buffer, Pixel-class PKR110, 2026-09-18 21:42:04):
 *
 * ```
 * FATAL EXCEPTION: GMA(BG) 7
 * Process: com.touchspan.kmp, PID: 8585
 * java.lang.IllegalStateException: Already resumed, but proposed with update
 *   Failure(error=AdError(code=NO_FILL, message=No fill., ...))
 *     at dev.avinya.ads.AndroidBannerAdController$loadBanner$2$1$2.onAdFailedToLoad(AndroidBannerAdController.kt:148)
 *     at <GMA next-gen SDK bridge>.a(LoadAdError)
 *     ...
 *     at java.util.concurrent.ThreadPoolExecutor.runWorker
 * ```
 *
 * Reproduced by repeatedly opening a banner-bearing screen while the ad unit answers `NO_FILL`.
 *
 * ## The invariant, stated correctly
 *
 * An `isActive` check before `resume()` covers exactly one race — **resume vs. cancel**: if
 * cancellation lands between the check and the call, `resume()` on a cancelled continuation is a
 * documented no-op. It does **not** cover **resume vs. resume**: two terminal callbacks can both
 * observe `isActive == true`, and the loser's `resume()` throws. `isActive` is a snapshot, not a
 * claim; only [tryResume] claims the continuation, and it claims it atomically (CAS), so exactly
 * one caller can ever hold the token. [completeResume] must then be called with that token.
 *
 * Use this helper for *every* resume that a native or system callback can trigger. Do not
 * reintroduce `if (isActive) resume(...)` — it is the bug.
 *
 * ## Cleanup contract for the losing callers
 *
 * Returns `true` when this call delivered the value (it won the race), `false` otherwise —
 * without throwing, whether the continuation was already resumed by another callback or was
 * already cancelled.
 *
 * The `onCancellation` overload's handler is invoked **only** when the value cannot be delivered
 * because cancellation raced in *during* the dispatch of a resume this call already won
 * (kotlinx documents it as "called if and only if the value is not delivered to the caller
 * because of the dispatch in the process"). It is **not** invoked when this call returns `false`
 * — for an already-resumed or already-cancelled continuation `tryResume` simply returns `null`.
 * A caller that owns a resource (a loaded ad, a native batch) must therefore clean it up itself
 * when this returns `false`; that is the only path in which nothing else will.
 *
 * @return `true` if this call resumed the continuation, `false` if it had already been resumed or
 *   cancelled. Never throws.
 */
@OptIn(InternalCoroutinesApi::class)
internal fun <T> CancellableContinuation<T>.tryResumeOnce(value: T): Boolean {
    val token = tryResume(value) ?: return false
    completeResume(token)
    return true
}

/**
 * [tryResumeOnce] with an [onCancellation] handler, for call sites that previously wrote
 * `continuation.resume(value) { _, _, _ -> cleanup() }`.
 *
 * See the single-argument overload for the full rationale, the production crash it prevents, and
 * the exact conditions under which [onCancellation] runs (only for a resume that won and was then
 * cancelled in flight — never when this returns `false`, so the caller must still free the value
 * itself on `false`).
 */
@OptIn(InternalCoroutinesApi::class)
internal fun <T> CancellableContinuation<T>.tryResumeOnce(
    value: T,
    onCancellation: ((cause: Throwable, value: T, context: CoroutineContext) -> Unit)?,
): Boolean {
    val token = tryResume(value, null, onCancellation) ?: return false
    completeResume(token)
    return true
}
