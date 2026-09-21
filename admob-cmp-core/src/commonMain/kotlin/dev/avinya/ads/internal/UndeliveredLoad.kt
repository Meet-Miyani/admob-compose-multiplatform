package dev.avinya.ads.internal

import dev.avinya.ads.AdAttemptResult
import kotlin.concurrent.Volatile

/**
 * Holds a loaded ad while it crosses a coroutine boundary that can drop it.
 *
 * A `suspend fun` cannot hand back a resource safely. kotlinx discards a returned value, with
 * no hook to clean it up, in three places:
 *
 *  1. **The dispatch back from [kotlinx.coroutines.withContext].** Documented as the prompt
 *     cancellation guarantee: if the calling context is cancelled by the time its dispatcher
 *     runs the continuation, the result is discarded and `CancellationException` is thrown
 *     instead. Every platform load returns its ad out of a `withContext(Main.immediate)`.
 *  2. **A scoped coroutine that returns while already cancelling.** The completing state is
 *     replaced by the cancellation, so the value never reaches the caller. This covers
 *     `withContext` on all three of its paths and [kotlinx.coroutines.withTimeoutOrNull],
 *     where it surfaces as a plain `null` — indistinguishable from "no ad".
 *  3. **Any further hop the receiving core makes** before it takes ownership.
 *
 * `SingleShotContinuation.onUndelivered` covers only the innermost step, from the SDK callback
 * into the suspension. It cannot see any of the above, because by then the continuation has
 * already been resumed successfully.
 *
 * The kotlinx docs prescribe the shape used here: declare the holder OUTSIDE the block, assign
 * inside it, and clean up in a `finally`. [capture] is called on the producing side, [take] by
 * whoever accepts ownership; anything still held when the caller unwinds was never delivered
 * and must be destroyed.
 *
 * Not an atomic. Each instance belongs to one load, `capture` happens-before the `take` or the
 * `finally` that follows it on the same coroutine, and `@Volatile` covers the dispatcher hop
 * in between.
 */
internal class UndeliveredLoad<T : Any> {
    @Volatile
    private var held: T? = null

    /** Records a successful result's value, then returns the result unchanged. */
    fun capture(result: AdAttemptResult<T>): AdAttemptResult<T> {
        if (result is AdAttemptResult.Success) held = result.value
        return result
    }

    /**
     * Claims the held value, or returns null if there is none.
     *
     * Call it where ownership is accepted, so the `finally` has nothing left to destroy; call
     * it in the `finally` to collect what was never accepted.
     */
    fun take(): T? {
        val value = held
        held = null
        return value
    }
}
