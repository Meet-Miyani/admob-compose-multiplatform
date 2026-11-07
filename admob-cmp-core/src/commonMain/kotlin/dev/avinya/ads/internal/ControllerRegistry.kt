@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdSlotKey
import dev.avinya.ads.BannerAdController
import dev.avinya.ads.FullScreenAdController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The manager's cache of banner and full-screen controllers, one per placement id (per format for
 * full-screen slots), with placement-collision detection.
 *
 * Moved out of `GoogleAdManagerBase` unchanged in behaviour; the base keeps `registerBanner` and
 * `registerFullScreenSlot` as the entry points its platform subclasses call. Guarded by its own
 * lock, which is synchronized(Any()) on Android and an NSRecursiveLock on iOS.
 */
internal class ControllerRegistry(
    /** Runs once for every full-screen slot this registry creates, before it is returned. */
    private val onFullScreenSlotCreated: (FullScreenAdController) -> Unit = {},
) {
    private val lock = FullScreenStateLock()

    // Detects placement-id collisions: the same id used with a different ad unit /
    // format / config silently reusing the first controller is a programming error.
    private val registeredPlacements = mutableMapOf<String, AdPlacement>()
    private val banners = mutableMapOf<String, BannerAdController>()
    private val slots = mutableMapOf<AdSlotKey, FullScreenAdController>()

    /** See `GoogleAdManagerBase.registerBanner`. */
    fun banner(
        placement: AdPlacement,
        create: (AdPlacement) -> BannerAdController,
    ): BannerAdController = lock.withLock {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == AdFormat.Banner) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a Banner factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        banners.getOrPut(ownedPlacement.id) { create(ownedPlacement) }
    }

    /** See `GoogleAdManagerBase.registerFullScreenSlot`. */
    fun fullScreenSlot(
        placement: AdPlacement,
        format: AdFormat,
        create: (AdPlacement) -> FullScreenAdController,
    ): FullScreenAdController = lock.withLock {
        val ownedPlacement = placement.ownedSnapshot()
        require(ownedPlacement.format == format) {
            "AdPlacement '${ownedPlacement.id}' has format ${ownedPlacement.format} but was passed to a $format factory. " +
                "The factory function and placement.format must agree."
        }
        checkPlacementCollision(ownedPlacement)
        slots.getOrPut(AdSlotKey(ownedPlacement.id, ownedPlacement.format)) {
            create(ownedPlacement).also(onFullScreenSlotCreated)
        }
    }

    /** Every controller created so far, copied under the lock, for a purge that runs outside it. */
    fun snapshot(): Pair<List<BannerAdController>, List<FullScreenAdController>> =
        lock.withLock { banners.values.toList() to slots.values.toList() }

    private fun checkPlacementCollision(placement: AdPlacement) {
        val existing = registeredPlacements.getOrPut(placement.id) { placement }
        check(existing == placement) {
            "AdPlacement id '${placement.id}' is already registered with different configuration " +
                "(existing=$existing, requested=$placement). Placement ids must be unique."
        }
    }
}

/**
 * Count of full-screen ads currently presenting across all slots, behind
 * `FullScreenPresenceAware.isFullScreenPresenting`. Observational only — admission is decided by
 * the process-wide `FullScreenPresentationArbiter`, not by this counter. Guarded by its own lock,
 * since shows can settle on different threads.
 */
internal class FullScreenPresenceCounter {
    private val lock = FullScreenStateLock()
    private var count = 0
    private val _presenting = MutableStateFlow(false)
    val presenting: StateFlow<Boolean> = _presenting

    fun onPresentationChanged(delta: Int) {
        lock.withLock {
            count = (count + delta).coerceAtLeast(0)
            _presenting.value = count > 0
        }
    }
}
