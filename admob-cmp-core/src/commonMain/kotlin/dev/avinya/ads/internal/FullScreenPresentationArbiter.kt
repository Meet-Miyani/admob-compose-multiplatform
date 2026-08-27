@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdFormat

/**
 * Grants at most one full-screen presentation token **process-wide**.
 *
 * Presence must be decided by an **admission gate**, never recorded observationally. Counting
 * after the fact — a slot removing an ad from its cache and *then* telling the manager to
 * increment a counter — cannot serialize the decision: those are per-slot fields, and there are
 * four full-screen slots per platform, so two slots can each conclude "I may present" and both
 * commit. Acquire a token here first; do not reintroduce a counter as the source of truth.
 *
 * ### Scope
 * The token is **process-wide**, which today coincides with manager-wide because
 * `AdMob.manager()` is a per-process singleton. If that ever changes — more than one
 * [dev.avinya.ads.AdManager] alive at once — this guarantee silently weakens to
 * per-manager unless the arbiter is hoisted to a genuine process singleton. Do not rely on
 * the coincidence without re-reading this note.
 *
 * ### Fairness
 * First-come-first-served with **no queueing**. A caller that loses the race gets `null`
 * immediately and reports not-ready, matching the pre-existing per-slot behavior. Queueing was
 * deliberately rejected: a full-screen ad that presents late — after the user has moved on
 * from the screen that triggered it — is worse UX than one that never presents, and is exactly
 * the pattern app-open suppression exists to prevent.
 *
 * ### Locking
 * The critical section does **no I/O, no suspension, and no callbacks into slot code** — it is
 * a compare-and-set over one owner field. Lock ordering across the module is
 * **slot locks → arbiter, never the reverse**: callers acquire while holding `operationMutex`
 * and `publicationLock`. The arbiter must never call back into a slot while holding its own
 * lock, or that ordering becomes a cycle.
 */
internal class FullScreenPresentationArbiter {

    private val lock = FullScreenStateLock()
    private var owner: PresentationToken? = null

    /** True while some slot holds the presentation token. Diagnostics only — never gate on this. */
    internal val isHeld: Boolean
        get() = lock.withLock { owner != null }

    /**
     * Attempts to claim the process-wide presentation right.
     *
     * @return a [PresentationToken] on success, or `null` if another slot already holds it.
     *   A `null` return is not an error — it means "someone else is presenting", and the caller
     *   must abandon its show attempt **without** consuming its cached ad.
     */
    internal fun tryAcquire(placementId: String, format: AdFormat): PresentationToken? =
        lock.withLock {
            if (owner != null) return@withLock null
            val token = PresentationToken(placementId, format)
            owner = token
            token
        }

    /**
     * Releases [token] if it is the current owner.
     *
     * @return true only for the call that performed the transition. Repeat calls, and calls
     *   with a token from an earlier turn, return false and change nothing. Release-once is
     *   also guaranteed upstream by [FullScreenPresentationHandle]'s CAS loop; this identity
     *   check is defence in depth so a stale token can never free someone else's presentation.
     */
    internal fun release(token: PresentationToken): Boolean = lock.withLock {
        if (owner !== token) return@withLock false
        owner = null
        true
    }

    /** Human-readable description of the current owner, or null when free. Diagnostics only. */
    internal fun currentHolder(): String? = lock.withLock {
        owner?.let { "${it.placementId} (${it.format})" }
    }

    /**
     * Opaque proof that the holder won the admission race. Identity-compared, never
     * value-compared — two tokens for the same placement must not be interchangeable.
     */
    internal class PresentationToken(
        internal val placementId: String,
        internal val format: AdFormat
    )
}
