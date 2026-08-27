package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdBand
import dev.avinya.ads.internal.NativeAdPriority
import dev.avinya.ads.internal.NativeAdRecordId
import dev.avinya.ads.internal.NativeAdSessionCore
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import dev.avinya.ads.nativead.NativeMediaInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeAdSessionCoreTest {
    private val placement = AdPlacement(
        id = "native",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds("ca-app-pub-3940256099942544/2247696110", "ca-app-pub-3940256099942544/3986624511"),
    )

    private fun session(max: Int = 3, inactive: Int = 1) = NativeAdSessionCore(
        key = "feed", policy = NativeAdSessionPolicy(maxRetainedAds = max, retainBehind = 0, prefetchAhead = 0), inactiveRetentionLimit = inactive,
    )
    private fun slot(key: String) = NativeAdSlot(key, placement)
    private fun window(vararg keys: String) = NativeAdWindow(visible = keys.map(::slot))
    private fun admit(core: NativeAdSessionCore, key: String, mutation: dev.avinya.ads.internal.NativeAdSessionMutation, id: Long) {
        val demand = mutation.demands.first { it.key == key }
        assertTrue(core.recordAdmitted(key, NativeAdRecordId(id), null, demand.generation))
    }

    @Test fun `new session is inactive until its first window`() {
        val core = session()
        assertFalse(core.state.value.active)
        assertTrue(core.state.value.slots.isEmpty())
    }

    @Test fun `window ranking and demand classification are visible then ahead then behind`() {
        val core = session()
        val mutation = core.updateWindow(NativeAdWindow(
            visible = listOf(slot("visible")), prefetchAhead = listOf(slot("ahead")), retainBehind = listOf(slot("behind")),
        ))
        assertEquals(listOf("visible", "ahead", "behind"), mutation.demands.map { it.key })
        assertEquals(listOf(NativeAdBand.Visible, NativeAdBand.PrefetchAhead, NativeAdBand.RetainBehind), mutation.demands.map { it.band })
        assertEquals(listOf(NativeAdPriority.ActiveReadyAhead, NativeAdPriority.Speculative, NativeAdPriority.ActiveRetainedBehind), mutation.demands.map { it.admittedPriority })
    }

    @Test fun `conflicting placements across window bands are rejected before deduplication`() {
        val core = session()
        val conflicting = placement.copy(id = "other-native")

        assertFailsWith<IllegalArgumentException> {
            core.updateWindow(
                NativeAdWindow(
                    visible = listOf(NativeAdSlot("shared", placement)),
                    prefetchAhead = listOf(NativeAdSlot("shared", conflicting)),
                ),
            )
        }
    }

    @Test fun `same in-flight generation is not emitted twice`() {
        val core = session()
        val first = core.updateWindow(window("a"))
        assertTrue(core.updateWindow(window("a")).demands.isEmpty())
        assertEquals(NativeAdSlotState.Loading, core.state.value.slots["a"])
        assertEquals(1, first.demands.size)
    }

    @Test fun `existing records are reclassified when their band changes`() {
        val core = session()
        val first = core.updateWindow(window("a")); admit(core, "a", first, 1)
        val mutation = core.updateWindow(NativeAdWindow(prefetchAhead = listOf(slot("a")), visible = emptyList()))
        assertEquals(listOf(NativeAdPriority.Speculative), mutation.reclassifications.map { it.priority })
        assertIs<NativeAdSlotState.Retained>(core.state.value.slots["a"])
    }

    @Test fun `session cap counts existing records plus in-flight demand`() {
        val core = session(max = 1)
        val first = core.updateWindow(window("a")); admit(core, "a", first, 1)
        val moved = core.updateWindow(window("b"))
        assertEquals("b", moved.demands.single().key)
        assertEquals(setOf(NativeAdRecordId(1)), moved.retireRecordIds.toSet())
        assertTrue(core.state.value.slots.size <= 1)
    }

    @Test fun `out-of-window detached records return explicit retirement actions`() {
        val core = session()
        val first = core.updateWindow(window("a")); admit(core, "a", first, 1)
        val mutation = core.updateWindow(window("b"))
        assertEquals(listOf(NativeAdRecordId(1)), mutation.retireRecordIds)
        assertNull(core.recordIdFor("a"))
    }

    @Test fun `mounted out-of-window record delays replacement demand until detachment`() {
        val core = session(max = 1)
        val first = core.updateWindow(window("a")); admit(core, "a", first, 1)
        core.setMounted("a", NativeAdRecordId(1), true)
        val moved = core.updateWindow(window("b"))
        assertTrue(moved.demands.isEmpty())
        val detached = core.setMounted("a", NativeAdRecordId(1), false)
        assertEquals(listOf(NativeAdRecordId(1)), detached.retireRecordIds)
        assertEquals(listOf("b"), detached.demands.map { it.key })
    }

    @Test fun `out-of-window in-flight loads return explicit invalidations`() {
        val core = session()
        val first = core.updateWindow(window("a"))
        val moved = core.updateWindow(window("b"))
        assertEquals(first.demands.map { it.generation }, moved.invalidateLoads.map { it.generation })
        assertNull(core.state.value.slots["a"])
    }

    @Test fun `out-of-window failed and empty entries are pruned`() {
        val core = session()
        val first = core.updateWindow(window("a"))
        core.recordFailed("a", AdError("failed", "failed", null), first.demands.single().generation)
        core.updateWindow(window("b"))
        assertNull(core.state.value.slots["a"])
    }

    @Test fun `stale admit is rejected without taking ownership`() {
        val core = session()
        val first = core.updateWindow(window("a")); core.updateWindow(window("b"))
        assertFalse(core.recordAdmitted("a", NativeAdRecordId(1), null, first.demands.single().generation))
        assertNull(core.recordIdFor("a"))
    }

    @Test fun `accepted admit publishes the supplied media info`() {
        val core = session(); val load = core.updateWindow(window("a"))
        val media = NativeMediaInfo(1.5f, true, 10.0)
        assertTrue(core.recordAdmitted("a", NativeAdRecordId(1), media, load.demands.single().generation))
        assertEquals(NativeAdSlotState.Ready(media), core.state.value.slots["a"])
    }

    @Test fun `coordinator eviction clears matching record ownership and permits later reload`() {
        val core = session()
        val first = core.updateWindow(window("a"))
        admit(core, "a", first, 1)

        assertTrue(core.recordEvicted("a", NativeAdRecordId(1)))
        assertEquals(NativeAdSlotState.Empty, core.state.value.slots["a"])

        val reload = core.updateWindow(window("a"))
        assertEquals(listOf("a"), reload.demands.map { it.key })
    }

    @Test fun `deactivate keeps the last visible anchor in current viewport order`() {
        val core = session()
        val loads = core.updateWindow(window("a", "b", "c")); loads.demands.forEachIndexed { i, d -> assertTrue(core.recordAdmitted(d.key, NativeAdRecordId((i + 1).toLong()), null, d.generation)) }
        val mutation = core.deactivate()
        assertEquals(setOf(NativeAdRecordId(1), NativeAdRecordId(2)), mutation.retireRecordIds.toSet())
        assertEquals(NativeAdRecordId(3), core.recordIdFor("c"))
    }

    @Test fun `deactivate invalidates every non-anchor in-flight generation`() {
        val core = session()
        val loads = core.updateWindow(window("a", "b", "c")); admit(core, "c", loads, 3)
        val mutation = core.deactivate()
        assertEquals(setOf("a", "b"), mutation.invalidateLoads.map { it.slotKey }.toSet())
    }

    @Test fun `inactive session rejects a late non-anchor admit`() {
        val core = session()
        val loads = core.updateWindow(window("a", "b")); admit(core, "b", loads, 2); val a = loads.demands.first { it.key == "a" }
        core.deactivate()
        assertFalse(core.recordAdmitted("a", NativeAdRecordId(1), null, a.generation))
    }

    @Test fun `reactivation preserves the retained anchor record`() {
        val core = session(); val loads = core.updateWindow(window("a")); admit(core, "a", loads, 1)
        core.deactivate(); assertTrue(core.updateWindow(window("a")).demands.isEmpty())
        assertEquals(NativeAdRecordId(1), core.recordIdFor("a"))
    }

    @Test fun `expiry reloads only an active slot still inside its retained window`() {
        val core = session(); val loads = core.updateWindow(window("a")); admit(core, "a", loads, 1)
        val expiry = core.expireSlot("a")
        assertEquals(listOf(NativeAdRecordId(1)), expiry.retireRecordIds)
        assertEquals(listOf("a"), expiry.demands.map { it.key })
        core.deactivate()
        assertTrue(core.expireSlot("a").demands.isEmpty())
    }

    @Test fun `close is idempotent and permanently rejects later mutation`() {
        val core = session(); val loads = core.updateWindow(window("a")); admit(core, "a", loads, 1)
        assertEquals(listOf(NativeAdRecordId(1)), core.close().retireRecordIds)
        assertTrue(core.close().retireRecordIds.isEmpty())
        assertTrue(core.updateWindow(window("b")).demands.isEmpty())
        assertFalse(core.recordAdmitted("b", NativeAdRecordId(2), null, 1))
    }

    @Test fun `clear drops inventory without changing active lifecycle state`() {
        val activeCore = session()
        val activeLoad = activeCore.updateWindow(window("a"))
        admit(activeCore, "a", activeLoad, 1)
        activeCore.clear()
        assertTrue(activeCore.state.value.active)
        assertTrue(activeCore.state.value.slots.isEmpty())

        val inactiveCore = session()
        inactiveCore.clear()
        assertFalse(inactiveCore.state.value.active)
    }

    @Test fun `walking one thousand successful failed and in-flight keys remains bounded`() {
        val core = session()
        repeat(1_000) { i ->
            val load = core.updateWindow(window("k-$i"))
            when (i % 3) {
                0 -> admit(core, "k-$i", load, i.toLong())
                1 -> core.recordFailed("k-$i", AdError("failed", "failed", null), load.demands.single().generation)
            }
        }
        assertTrue(core.state.value.slots.size <= 3, "found ${core.state.value.slots.size} retained entries")
    }

    @Test fun `failed slot is not re-requested while it stays in the window`() {
        val core = session()
        val first = core.updateWindow(window("a"))
        core.recordFailed("a", AdError("no-fill", "no fill", null), first.demands.single().generation)

        // A real viewport republishes its window on every scroll frame. None of them may spend a
        // request on a slot that has already failed.
        repeat(20) { assertTrue(core.updateWindow(window("a")).demands.isEmpty()) }
        assertIs<NativeAdSlotState.Failed>(core.state.value.slots["a"])
    }

    @Test fun `failed slot is requested again after leaving and re-entering the window`() {
        val core = session()
        val first = core.updateWindow(window("a"))
        core.recordFailed("a", AdError("no-fill", "no fill", null), first.demands.single().generation)

        core.updateWindow(window("b"))
        assertNull(core.state.value.slots["a"])
        val reentry = core.updateWindow(window("a"))
        assertEquals(listOf("a"), reentry.demands.map { it.key })
    }

    @Test fun `deferred slot is retried on the next window update`() {
        val core = session()
        val first = core.updateWindow(window("a"))
        // The governor had no capacity — nothing failed, so this must not be treated as a failure.
        core.recordDeferred("a", first.demands.single().generation)

        val retry = core.updateWindow(window("a"))
        assertEquals(listOf("a"), retry.demands.map { it.key })
    }





    @Test fun `session core publishes into a supplied flow`() {
        val externalFlow = kotlinx.coroutines.flow.MutableStateFlow(
            dev.avinya.ads.nativead.NativeAdSessionState(active = true, slots = mapOf("stale" to NativeAdSlotState.Loading)),
        )
        val core = NativeAdSessionCore(
            key = "feed",
            policy = NativeAdSessionPolicy(maxRetainedAds = 3, retainBehind = 0, prefetchAhead = 0),
            inactiveRetentionLimit = 1,
            published = externalFlow,
        )
        assertTrue(core.publishesInto(externalFlow))
        assertFalse(externalFlow.value.active)
        assertTrue(externalFlow.value.slots.isEmpty())

        core.updateWindow(window("a"))
        assertTrue(externalFlow.value.active)
        assertEquals(NativeAdSlotState.Loading, externalFlow.value.slots["a"])
    }
}
