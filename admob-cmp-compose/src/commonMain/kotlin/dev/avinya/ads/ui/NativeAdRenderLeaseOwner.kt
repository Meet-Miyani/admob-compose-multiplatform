package dev.avinya.ads.ui

import androidx.compose.runtime.RememberObserver

/**
 * Owns one renderer lease for the lifetime of a committed remember slot.
 *
 * Acquisition is deliberately synchronous: a renderable composition either mounts the lease
 * immediately or renders contention/loading output. If acquisition returns null, later reads
 * retry without replacing the owner. Compose may abandon a newly created remember value before
 * committing it, so both terminal callbacks retire the lease through the same exact-once path.
 */
internal class NativeAdRenderLeaseOwner<L : Any>(
    private val acquire: () -> L?,
    private val release: (L) -> Unit,
    /**
     * Identity of the ad behind a lease, used to notice that the record was replaced.
     *
     * The published slot states (`Ready`, `Mounted`, `Retained`) carry no record identity, so
     * an unchanged `Ready` can describe a different ad than the one this owner is holding.
     */
    private val identityOf: (L) -> String,
) : RememberObserver {
    private var currentLease: L? = acquire()
    private var retired: Boolean = false

    /**
     * The lease for the CURRENT record, re-validated on every read.
     *
     * The owner is remembered on session, slot, placement and renderer — none of which change
     * when the slot's record is replaced — so a cached lease could outlive the ad it points
     * at and hand back a destroyed one. Re-acquiring is cheap and idempotent for the same
     * renderer id: the coordinator checks record, slot, placement and renderer identity and
     * returns the current record, or null when this renderer no longer owns one. Comparing
     * identities is what distinguishes "same ad, same lease" from "the record moved on".
     *
     * Deliberately not driven by observing an intermediate non-renderable state: `StateFlow`
     * conflates, so there is no guarantee such a state is ever composed.
     */
    fun lease(): L? {
        if (retired) return null
        val cached = currentLease
        val current = acquire()
        if (cached != null && (current == null || identityOf(current) != identityOf(cached))) {
            // The record this owner was rendering is gone or has been replaced. Release the
            // stale lease so the coordinator's mount accounting stays truthful, then adopt
            // whatever is current.
            release(cached)
        }
        currentLease = current
        return current
    }

    override fun onRemembered() = Unit

    override fun onForgotten() = retire()

    override fun onAbandoned() = retire()

    private fun retire() {
        if (retired) return
        retired = true
        currentLease?.let(release)
        currentLease = null
    }
}
