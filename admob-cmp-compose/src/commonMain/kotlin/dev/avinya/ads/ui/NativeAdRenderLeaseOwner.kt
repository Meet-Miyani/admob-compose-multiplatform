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
) : RememberObserver {
    private var currentLease: L? = acquire()
    private var retired: Boolean = false

    fun lease(): L? {
        if (retired) return null
        currentLease?.let { return it }
        return acquire().also { currentLease = it }
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
