package dev.avinya.ads.internal

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The only handle a native/SDK callback gets on its waiting coroutine: it can be resumed at most
 * once, however many callbacks arrive and on however many threads.
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
 * Observed on a real consumer device (crash buffer, an Android phone, 2026-09-18), diagnosed and
 * first fixed by @yangwuan55 in #45:
 *
 * ```
 * FATAL EXCEPTION: GMA(BG) 7
 * Process: <consumer app id>, PID: <pid>
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
 * claim.
 *
 * ## Why the claim is a CAS here rather than kotlinx's `tryResume`
 *
 * `tryResume`/`completeResume` solve the same problem and were the original fix, but they are
 * `@InternalCoroutinesApi` — a surface kotlinx documents as having no compatibility guarantee.
 * Gradle resolves a consumer to the *highest* coroutines version on its classpath, so a
 * signature change there would surface in a published artifact as a `NoSuchMethodError`, thrown
 * on that same SDK callback thread: the original crash, in a new costume, and unfixable by the
 * consumer. The claim is therefore owned here as an [AtomicBoolean] CAS — the same
 * claim-then-act shape as [RewardDelivery] and [FullScreenPresentationHandle] — so exactly one
 * `resume` ever reaches kotlinx, and resume-versus-cancel stays with kotlinx, where it is
 * already safe. Only public API is used.
 *
 * Sites obtain this through [suspendSingleShot] and never see the raw continuation, so the racy
 * shape cannot be written again rather than merely being advised against.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SingleShotContinuation<T>(private val continuation: CancellableContinuation<T>) {
    private val claimed = AtomicBoolean(false)

    /**
     * Whether a [resume] from this caller could still be the one that is delivered.
     *
     * For skipping work nobody will receive — installing a paid-event handler on an ad that is
     * about to be dropped, re-reading response info on a banner refresh. It is never needed in
     * front of [resume], which is safe to call unconditionally.
     */
    val isActive: Boolean get() = !claimed.load() && continuation.isActive

    fun invokeOnCancellation(handler: (cause: Throwable?) -> Unit) = continuation.invokeOnCancellation(handler)

    /**
     * Delivers [value] if this is the first resume, and returns whether this call was the one
     * that claimed the continuation.
     *
     * [onUndelivered] receives a value that will never reach the waiter, exactly once, whatever
     * the reason: another callback claimed first, the waiter was already cancelled, or it was
     * cancelled while the value was in flight. A site that owns a resource — a loaded ad, a
     * native batch — therefore states its cleanup once, here, and never frees anything itself.
     * That is the one behavioural difference from `tryResumeOnce`, whose handler did NOT run for
     * a losing caller, so every call site had to remember to free the value on `false`.
     */
    fun resume(value: T, onUndelivered: ((T) -> Unit)? = null): Boolean {
        if (!claimed.compareAndSet(expectedValue = false, newValue = true)) {
            onUndelivered?.invoke(value)
            return false
        }
        continuation.resume(value) { _, undelivered, _ -> onUndelivered?.invoke(undelivered) }
        return true
    }
}

/** [suspendCancellableCoroutine] for a native/SDK callback: hands [block] a [SingleShotContinuation]. */
internal suspend inline fun <T> suspendSingleShot(
    crossinline block: (SingleShotContinuation<T>) -> Unit,
): T = suspendCancellableCoroutine { block(SingleShotContinuation(it)) }
