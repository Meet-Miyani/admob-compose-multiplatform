package dev.avinya.ads.ui

import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdWindow

/** Direction of travel used to rank native-ad slots adjacent to a measured viewport. */
internal enum class NativeAdScrollDirection { Forward, Reverse }

private const val viewportScanBudget = 128

/**
 * A measured viewport with the host's slot mapping already applied — plain values, no lambda.
 *
 * Two properties make this type the unit the pipeline is built around:
 *
 * 1. Producing one calls `slotAt`, so when it is produced inside a snapshot observer every
 *    piece of feed state the host's lambda touches becomes a dependency of that observer.
 *    That is the only thing that makes a content change with an unchanged item count — a
 *    pull-to-refresh, an async slot config resolving — reach the session at all.
 * 2. It compares by value, so it can be the deduplication key. A lambda cannot: its identity
 *    depends on whether Compose memoized it, which depends on the stability of whatever the
 *    host captured, which is not something this SDK gets to assume.
 *
 * [forward] and [backward] are deliberately unlabelled. Scroll direction decides which of them
 * is the prefetch band and which is the retain band, but not which indexes they cover, so the
 * scan does not need to know it — see [nativeAdWindowForViewport].
 */
internal data class ResolvedNativeAdViewport(
    /** Slots on screen, in measured item order. */
    val visible: List<NativeAdSlot>,
    /** Slots found scanning up from the last visible index. */
    val forward: List<NativeAdSlot>,
    /** Slots found scanning down from the first visible index. */
    val backward: List<NativeAdSlot>,
    val itemCount: Int,
    /** False while a LazyList has not laid out yet; distinct from an item count of zero. */
    val measured: Boolean,
)

/**
 * Applies [slotAt] across the measured viewport and the bounded ranges on either side of it.
 *
 * **Call this inside the snapshot observer**, not beside it. Every `slotAt` invocation here is
 * a read of the host's feed state, and those reads are what re-trigger the pipeline when the
 * feed's contents change underneath a viewport that did not move.
 *
 * It knows only list indexes: consumers retain ownership of the feed model through [slotAt],
 * and a long content-only range cannot make this scan walk the whole feed.
 */
internal fun resolveNativeAdViewport(
    visibleIndexes: List<Int>,
    itemCount: Int,
    policy: NativeAdSessionPolicy,
    slotAt: (Int) -> NativeAdSlot?,
): ResolvedNativeAdViewport {
    if (itemCount <= 0 || visibleIndexes.isEmpty()) {
        return ResolvedNativeAdViewport(
            visible = emptyList(),
            forward = emptyList(),
            backward = emptyList(),
            itemCount = itemCount,
            measured = visibleIndexes.isNotEmpty(),
        )
    }

    val visible = visibleIndexes
        .filter { it in 0 until itemCount }
        .mapNotNull(slotAt)

    // During a Paging shrink Compose can briefly report the prior measured indexes. Clamp the
    // scan anchors to the new range, but never call slotAt for those stale indexes.
    val firstVisible = visibleIndexes.min().coerceIn(0, itemCount - 1)
    val lastVisible = visibleIndexes.max().coerceIn(0, itemCount - 1)

    // Both bands are resolved to the larger of the two budgets because direction is not known
    // here and either band may end up playing either role. `scan` still stops at the first
    // `wanted` hits, so the common policy (prefetchAhead = retainBehind = 1) inspects exactly
    // the same indexes it would if direction were known.
    val wanted = maxOf(policy.prefetchAhead, policy.retainBehind)
    return ResolvedNativeAdViewport(
        visible = visible,
        forward = scan(lastVisible + 1, step = 1, wanted = wanted, itemCount = itemCount, slotAt = slotAt),
        backward = scan(firstVisible - 1, step = -1, wanted = wanted, itemCount = itemCount, slotAt = slotAt),
        itemCount = itemCount,
        measured = true,
    )
}

private fun scan(
    start: Int,
    step: Int,
    wanted: Int,
    itemCount: Int,
    slotAt: (Int) -> NativeAdSlot?,
): List<NativeAdSlot> {
    if (wanted == 0) return emptyList()
    val found = mutableListOf<NativeAdSlot>()
    var index = start
    var inspected = 0
    while (index in 0 until itemCount && inspected < viewportScanBudget && found.size < wanted) {
        slotAt(index)?.let(found::add)
        index += step
        inspected++
    }
    return found
}

/**
 * Ranks an already-resolved viewport into the bounded bands a session consumes.
 *
 * Pure over values — it never calls back into the host. That is what keeps the conflicting-key
 * `require` below out of the snapshot observer: a throw there would surface as an unrelated
 * recomposition failure rather than as the caller's own bad feed model.
 */
internal fun nativeAdWindowForViewport(
    resolved: ResolvedNativeAdViewport,
    direction: NativeAdScrollDirection,
    policy: NativeAdSessionPolicy,
): NativeAdWindow? {
    // An empty measurement is transient while a LazyList first lays out, but an empty feed is
    // authoritative: publish it so the session retires any previous visible demand.
    if (resolved.itemCount <= 0) return NativeAdWindow(visible = emptyList())
    if (!resolved.measured) return null

    // Scrolling forward, the band above the viewport is the prefetch; scrolling back it is the
    // retention. The index ranges are the same either way, so only the labels swap here.
    val scrollingForward = direction == NativeAdScrollDirection.Forward
    val ahead = (if (scrollingForward) resolved.forward else resolved.backward).take(policy.prefetchAhead)
    val behind = (if (scrollingForward) resolved.backward else resolved.forward).take(policy.retainBehind)

    val placementsByKey = mutableMapOf<String, AdPlacement>()
    fun unique(slots: List<NativeAdSlot>, remaining: Int): List<NativeAdSlot> = buildList {
        for (slot in slots) {
            val priorPlacement = placementsByKey[slot.key]
            require(priorPlacement == null || priorPlacement == slot.placement) {
                "Native viewport maps slot key '${slot.key}' to conflicting placements " +
                    "'${priorPlacement?.id}' and '${slot.placement.id}'."
            }
            if (priorPlacement == null) {
                placementsByKey[slot.key] = slot.placement
                if (size < remaining) add(slot)
            }
        }
    }

    var remaining = policy.maxRetainedAds
    val uniqueVisible = unique(resolved.visible, remaining)
    remaining -= uniqueVisible.size
    val uniqueAhead = unique(ahead, remaining)
    remaining -= uniqueAhead.size
    val uniqueBehind = unique(behind, remaining)

    return NativeAdWindow(
        visible = uniqueVisible,
        prefetchAhead = uniqueAhead,
        retainBehind = uniqueBehind,
    )
}
