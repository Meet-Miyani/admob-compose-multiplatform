package dev.avinya.ads.ui

import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NativeAdViewportBindingTest {
    private val firstPlacement = AdPlacement("first", AdFormat.Native, "android-first", "ios-first")
    private val secondPlacement = AdPlacement("second", AdFormat.Native, "android-second", "ios-second")

    @Test
    fun `forward viewport scans native slots rather than content rows`() {
        val window = measuredViewport(
            visibleIndexes = listOf(4, 5),
            itemCount = 12,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(),
            slotAt = slotsAt(2, 5, 8),
        )

        assertEquals(listOf("ad-5"), window.visible.keys())
        assertEquals(listOf("ad-8"), window.prefetchAhead.keys())
        assertEquals(listOf("ad-2"), window.retainBehind.keys())
    }

    @Test
    fun `reverse viewport reverses ahead and behind bands`() {
        val window = measuredViewport(
            visibleIndexes = listOf(4, 5),
            itemCount = 12,
            direction = NativeAdScrollDirection.Reverse,
            policy = NativeAdSessionPolicy(),
            slotAt = slotsAt(2, 4, 8),
        )

        assertEquals(listOf("ad-4"), window.visible.keys())
        assertEquals(listOf("ad-2"), window.prefetchAhead.keys())
        assertEquals(listOf("ad-8"), window.retainBehind.keys())
    }

    @Test
    fun `multiple visible ads preserve lazy list order and consume retention budget first`() {
        val window = measuredViewport(
            visibleIndexes = listOf(2, 4, 6),
            itemCount = 10,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(maxRetainedAds = 2, retainBehind = 0, prefetchAhead = 1),
            slotAt = slotsAt(2, 4, 6, 8),
        )

        assertEquals(listOf("ad-2", "ad-4"), window.visible.keys())
        assertEquals(emptyList(), window.prefetchAhead.keys())
        assertEquals(emptyList(), window.retainBehind.keys())
    }

    @Test
    fun `no visible ad still warms the next nearby native slot`() {
        val window = measuredViewport(
            visibleIndexes = listOf(3, 4),
            itemCount = 10,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(),
            slotAt = slotsAt(6),
        )

        assertEquals(emptyList(), window.visible.keys())
        assertEquals(listOf("ad-6"), window.prefetchAhead.keys())
        assertEquals(emptyList(), window.retainBehind.keys())
    }

    @Test
    fun `first unmeasured layout does not emit a destructive empty window`() {
        assertNull(windowFor(
            visibleIndexes = emptyList(),
            itemCount = 10,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(),
            slotAt = slotsAt(2),
        ))
    }

    @Test
    fun `empty feed emits an empty window after a populated feed shrinks`() {
        val prior = measuredViewport(
            visibleIndexes = listOf(2),
            itemCount = 5,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(),
            slotAt = slotsAt(2),
        )

        val emptied = windowFor(
            visibleIndexes = emptyList(),
            itemCount = 0,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(),
            slotAt = slotsAt(2),
        )

        assertEquals(listOf("ad-2"), prior.visible.keys())
        assertEquals(emptyList(), requireNotNull(emptied).visible.keys())
        assertEquals(emptyList(), emptied.prefetchAhead.keys())
        assertEquals(emptyList(), emptied.retainBehind.keys())
    }

    @Test
    fun `boundaries and paging nulls do not wrap or duplicate slots`() {
        val window = measuredViewport(
            visibleIndexes = listOf(0, 1),
            itemCount = 3,
            direction = NativeAdScrollDirection.Reverse,
            policy = NativeAdSessionPolicy(),
            slotAt = { index -> if (index == 0) slot("ad-0") else null },
        )

        assertEquals(listOf("ad-0"), window.visible.keys())
        assertEquals(emptyList(), window.prefetchAhead.keys())
        assertEquals(emptyList(), window.retainBehind.keys())
    }

    @Test
    fun `scan stops after 128 indexes when an ad free range is malformed`() {
        val inspected = mutableListOf<Int>()
        val window = measuredViewport(
            visibleIndexes = listOf(0),
            itemCount = 1_000_000,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(),
            slotAt = { index -> inspected += index; null },
        )

        assertEquals(emptyList(), window.visible.keys())
        assertEquals(129, inspected.size)
        assertEquals(128, inspected.last())
    }

    @Test
    fun `duplicate keys retain their first occurrence across bands`() {
        val window = measuredViewport(
            visibleIndexes = listOf(4, 5),
            itemCount = 10,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(maxRetainedAds = 3, retainBehind = 1, prefetchAhead = 1),
            slotAt = { index -> if (index == 5 || index == 8 || index == 2) slot("shared") else null },
        )

        assertEquals(listOf("shared"), window.visible.keys())
        assertEquals(emptyList(), window.prefetchAhead.keys())
        assertEquals(emptyList(), window.retainBehind.keys())
    }

    @Test
    fun `conflicting placement for one slot key fails closed`() {
        assertFailsWith<IllegalArgumentException> {
            windowFor(
                visibleIndexes = listOf(4, 5),
                itemCount = 10,
                direction = NativeAdScrollDirection.Forward,
                policy = NativeAdSessionPolicy(maxRetainedAds = 3, retainBehind = 1, prefetchAhead = 1),
                slotAt = { index ->
                    when (index) {
                        5 -> NativeAdSlot("shared", firstPlacement)
                        8 -> NativeAdSlot("shared", secondPlacement)
                        else -> null
                    }
                },
            )
        }
    }

    @Test
    fun `item count shrink ignores stale visible indexes`() {
        val window = measuredViewport(
            visibleIndexes = listOf(5, 6),
            itemCount = 4,
            direction = NativeAdScrollDirection.Forward,
            policy = NativeAdSessionPolicy(),
            slotAt = slotsAt(2),
        )

        assertEquals(emptyList(), window.visible.keys())
        assertEquals(listOf("ad-2"), window.retainBehind.keys())
    }

    private fun slotsAt(vararg indexes: Int): (Int) -> NativeAdSlot? = { index ->
        index.takeIf { it in indexes }?.let { slot("ad-$it") }
    }

    private fun slot(key: String): NativeAdSlot = NativeAdSlot(key, firstPlacement)
    private fun List<NativeAdSlot>.keys(): List<String> = map(NativeAdSlot::key)

    /**
     * Runs the real two-stage pipeline: resolve the host mapping, then rank it. Tests drive both
     * halves together so a change that moves work between them cannot pass unnoticed.
     */
    private fun windowFor(
        visibleIndexes: List<Int>,
        itemCount: Int,
        direction: NativeAdScrollDirection,
        policy: NativeAdSessionPolicy,
        slotAt: (Int) -> NativeAdSlot?,
    ) = nativeAdWindowForViewport(
        resolveNativeAdViewport(visibleIndexes, itemCount, policy, slotAt),
        direction,
        policy,
    )

    private fun measuredViewport(
        visibleIndexes: List<Int>,
        itemCount: Int,
        direction: NativeAdScrollDirection,
        policy: NativeAdSessionPolicy,
        slotAt: (Int) -> NativeAdSlot?,
    ) = requireNotNull(windowFor(visibleIndexes, itemCount, direction, policy, slotAt))
}
