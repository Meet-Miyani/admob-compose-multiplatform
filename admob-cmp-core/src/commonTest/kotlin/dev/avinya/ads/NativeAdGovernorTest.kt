package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdDemandClass
import dev.avinya.ads.internal.NativeAdGovernor
import dev.avinya.ads.internal.NativeAdLoadReservation
import dev.avinya.ads.internal.NativeAdPriority
import dev.avinya.ads.internal.NativeAdRecordId
import dev.avinya.ads.internal.NativeMemoryPressure
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAdGovernorTest {

    private fun governor(policy: NativeAdMemoryPolicy = NativeAdMemoryPolicy()) =
        NativeAdGovernor(policy)

    private fun reserveVisible(
        gov: NativeAdGovernor,
        priority: NativeAdPriority,
        count: Int,
        allowPartial: Boolean = true,
    ) = gov.reserve(NativeAdDemandClass.Visible, priority, count, allowPartial)

    private fun reserveSpeculative(
        gov: NativeAdGovernor,
        priority: NativeAdPriority,
        count: Int,
        allowPartial: Boolean = true,
    ) = gov.reserve(NativeAdDemandClass.Speculative, priority, count, allowPartial)

    private fun admitAll(
        gov: NativeAdGovernor,
        reservations: List<NativeAdLoadReservation>,
    ): List<NativeAdRecordId> = reservations.map { gov.admit(it) }

    private fun admitOne(gov: NativeAdGovernor, reservation: NativeAdLoadReservation): NativeAdRecordId =
        gov.admit(reservation)

    // --- 1 ---------------------------------------------------------------------

    @Test fun `reservations count against the hard limit`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 4, hardLimit = 6))
        val first = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 6)
        assertEquals(6, first.reservations.size, "expected 6 visible reservations")
        assertEquals(6, gov.state().reservedLoads)
        assertEquals(0, gov.state().loadedRecords)
        // A seventh request is denied outright because 6 + 1 > hardLimit.
        val denied = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        assertTrue(denied.reservations.isEmpty(), "seventh visible reservation should be denied")
        assertTrue(denied.retiredRecordIds.isEmpty(), "denied reservation must not retire anyone")
    }

    // --- 2 ---------------------------------------------------------------------

    @Test fun `speculative reservation stops at soft limit`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 4, hardLimit = 6))
        val first = reserveSpeculative(gov, NativeAdPriority.Speculative, 4)
        assertEquals(4, first.reservations.size)
        // 4 + 1 > softLimit (4) — even with hardLimit headroom, a fifth speculative is denied.
        val denied = reserveSpeculative(gov, NativeAdPriority.Speculative, 1)
        assertTrue(denied.reservations.isEmpty(), "fifth speculative must be denied at soft limit")
    }

    // --- 3 ---------------------------------------------------------------------

    @Test fun `visible reservation may exceed soft limit up to hard limit`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 4, hardLimit = 6))
        // Fill the speculative bucket to soft limit first.
        val speculative = reserveSpeculative(gov, NativeAdPriority.Speculative, 4)
        assertEquals(4, speculative.reservations.size)
        // Visible can climb past softLimit up to hardLimit.
        val fifthAndSixth = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 2)
        assertEquals(2, fifthAndSixth.reservations.size)
        // The 6 slots are full: 4 speculative pending + 2 visible pending. A seventh
        // visible still wins capacity by cancelling a pending speculative permit
        // (cheaper than retiring a record) — the documented "replace pending
        // speculative capacity" behaviour. The governor stays at hardLimit.
        val seventh = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        assertEquals(1, seventh.reservations.size, "seventh visible granted by cancelling a pending speculative")
        assertTrue(seventh.retiredRecordIds.isEmpty(), "no record retired when a speculative permit can be cancelled")
        assertEquals(6, gov.state().loadedRecords + gov.state().reservedLoads)
    }

    // --- 4 ---------------------------------------------------------------------

    @Test fun `visible demand at hard limit retires eligible speculative victim atomically`() {
        // softLimit=2 so the two speculative records fit, hardLimit=2 so the next visible
        // request must evict one to make room.
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2))
        val spek = reserveVisible(gov, NativeAdPriority.Speculative, 2)
        val speculativeIds = admitAll(gov, spek.reservations)
        // One visible reservation arrives; the governor evicts the oldest speculative
        // and grants the visible slot in the same locked mutation.
        val visible = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        assertEquals(1, visible.reservations.size, "visible reservation must be granted")
        assertEquals(1, visible.retiredRecordIds.size, "exactly one speculative must be retired")
        assertTrue(
            visible.retiredRecordIds.single() in speculativeIds,
            "retired id must be one of the speculative records",
        )
        // Admit the visible so the post-admit state matches the documented invariant:
        // 2 loaded (the surviving speculative + the visible) + 0 reserved <= hardLimit (2).
        admitAll(gov, visible.reservations)
        assertEquals(2, gov.state().loadedRecords)
        assertEquals(0, gov.state().reservedLoads)
    }

    // --- 5 ---------------------------------------------------------------------

    @Test fun `mounted ads are never eviction candidates`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 3))
        // All three records use the visible demand class so they all fit under
        // hardLimit; their eviction priority is the property under test.
        val mountedRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1).reservations.single(),
        )
        val retainedRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.ActiveRetainedBehind, 1).reservations.single(),
        )
        val specRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        gov.setMounted(mountedRecord, true)
        // Critical trim evicts everything non-mounted.
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertEquals(setOf(retainedRecord, specRecord), result.retiredRecordIds.toSet())
        assertTrue(result.cancelledReservations.isEmpty())
        assertEquals(1, gov.state().loadedRecords, "mounted record must remain")
    }

    // --- 6 ---------------------------------------------------------------------

    @Test fun `speculative ads evict before inactive anchors`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        val specRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        val inactiveRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.InactiveAnchor, 1).reservations.single(),
        )
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertEquals(listOf(specRecord, inactiveRecord), result.retiredRecordIds)
    }

    // --- 7 ---------------------------------------------------------------------

    @Test fun `inactive anchors evict before active retained ads`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        val inactiveRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.InactiveAnchor, 1).reservations.single(),
        )
        val retainedRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.ActiveRetainedBehind, 1).reservations.single(),
        )
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertEquals(listOf(inactiveRecord, retainedRecord), result.retiredRecordIds)
    }

    // --- 8 ---------------------------------------------------------------------

    @Test fun `reclassifying a record changes its later eviction order`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        val initiallySpeculative = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        val inactiveRecord = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.InactiveAnchor, 1).reservations.single(),
        )
        // The slot moved into the active ready-ahead band — reclassify to reflect that.
        gov.reclassify(initiallySpeculative, NativeAdPriority.ActiveReadyAhead)
        // Critical trim now evicts by *current* priority: the inactive anchor goes first.
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertEquals(listOf(inactiveRecord, initiallySpeculative), result.retiredRecordIds)
    }

    // --- 9 ---------------------------------------------------------------------

    @Test fun `touch uses deterministic monotonic access order for LRU`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 3))
        val a = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        val b = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        val c = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        // Touching A makes it the most recently used. Eviction order on critical trim
        // is B (oldest), C, A (most recent).
        gov.touch(a)
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertEquals(listOf(b, c, a), result.retiredRecordIds)
    }

    // --- 10 --------------------------------------------------------------------

    @Test fun `moderate trim returns non-mounted victims until soft limit`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 6))
        // 4 visible records so they all fit; softLimit governs the trim target, not
        // the speculative cap here.
        val records = admitAll(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 4).reservations,
        )
        val result = gov.trim(NativeMemoryPressure.Moderate)
        assertEquals(2, result.retiredRecordIds.size, "moderate trim evicts down to softLimit")
        assertTrue(result.cancelledReservations.isEmpty(), "no pending reservations to cancel")
        assertEquals(2, gov.state().loadedRecords, "two records remain after moderate trim")
    }

    // --- 11 --------------------------------------------------------------------

    @Test fun `critical trim returns only non-mounted records`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 3))
        val mounted = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1).reservations.single(),
        )
        val a = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        val b = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.ActiveRetainedBehind, 1).reservations.single(),
        )
        gov.setMounted(mounted, true)
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertFalse(mounted in result.retiredRecordIds, "critical trim must never return mounted ids")
        assertEquals(setOf(a, b), result.retiredRecordIds.toSet())
    }

    // --- 12 --------------------------------------------------------------------

    @Test fun `critical trim cancels every in-flight reservation`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 6))
        val reservations = reserveVisible(gov, NativeAdPriority.Speculative, 2).reservations
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertEquals(reservations.toSet(), result.cancelledReservations.toSet())
        assertTrue(result.retiredRecordIds.isEmpty(), "no records to retire")
        // Late admit of a cancelled reservation must fail because the registry no longer
        // holds the token — the coordinator's generation check is the outer guard.
        assertFailsWith<IllegalStateException> {
            gov.admit(reservations[0])
        }
    }

    // --- 13 --------------------------------------------------------------------

    @Test fun `moderate trim cancels speculative reservations before loaded records`() {
        // softLimit=2 so the speculative reservation can be granted; hardLimit=4 so
        // the visible reservation fits alongside the 1 loaded record + 1 spec reservation.
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 4))
        val loadedSpeculative = admitOne(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        val speculativeReservation = reserveSpeculative(gov, NativeAdPriority.Speculative, 1)
            .reservations.single()
        val visibleReservation = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
            .reservations.single()
        // Total: 1 loaded + 2 reserved = 3, softLimit=2, excess=1.
        // The cheapest way to drop to 2 is to cancel the speculative reservation,
        // not to retire the loaded record.
        val result = gov.trim(NativeMemoryPressure.Moderate)
        assertEquals(listOf(speculativeReservation), result.cancelledReservations)
        assertTrue(result.retiredRecordIds.isEmpty(), "no record retired when speculation cancels")
        assertEquals(1, gov.state().loadedRecords, "loaded record preserved")
        assertEquals(1, gov.state().reservedLoads, "only the visible reservation remains")
    }

    // --- 14 --------------------------------------------------------------------

    @Test fun `releasing a partial batch frees unused reservations`() {
        // softLimit=1, hardLimit=4; use visible reservations so the speculative cap
        // doesn't bite. The behaviour under test is reservation accounting, not
        // demand class behaviour.
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 4))
        val reservations = reserveVisible(gov, NativeAdPriority.Speculative, 3).reservations
        assertEquals(3, reservations.size)
        assertEquals(3, gov.state().reservedLoads)
        gov.admit(reservations[0])
        assertEquals(1, gov.state().loadedRecords)
        assertEquals(2, gov.state().reservedLoads)
        gov.releaseReservation(reservations[1])
        gov.releaseReservation(reservations[2])
        assertEquals(1, gov.state().loadedRecords)
        assertEquals(0, gov.state().reservedLoads)
        val second = reserveVisible(gov, NativeAdPriority.Speculative, 3)
        assertEquals(3, second.reservations.size)
    }

    // --- 15 --------------------------------------------------------------------

    @Test fun `all-or-nothing reservation leaves state unchanged when full count cannot fit`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 4))
        // Occupy 3 of the 4 slots with visible reservations (the speculative cap
        // does not constrain visible demand).
        val pre = admitAll(
            gov,
            reserveVisible(gov, NativeAdPriority.Speculative, 3).reservations,
        )
        assertEquals(3, gov.state().loadedRecords)
        // Only 1 slot is free; request 5 with allowPartial=false. Must be denied entirely.
        val denied = reserveSpeculative(gov, NativeAdPriority.Speculative, 5, allowPartial = false)
        assertTrue(denied.reservations.isEmpty())
        assertTrue(denied.retiredRecordIds.isEmpty())
        // State must be exactly what it was before the call.
        assertEquals(3, gov.state().loadedRecords)
        assertEquals(0, gov.state().reservedLoads)
        // And the original records are still present (not retired by the denied call).
        val result = gov.trim(NativeMemoryPressure.Critical)
        assertEquals(pre.toSet(), result.retiredRecordIds.toSet())
    }

    // --- 16 --------------------------------------------------------------------

    @Test fun `admit rejects a forged or already resolved reservation token`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 4))
        val real = reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single()
        // A forged token with the same id but a different priority and instance.
        val forged = NativeAdLoadReservation(
            id = real.id,
            demandClass = NativeAdDemandClass.Visible,
            priority = NativeAdPriority.ActiveReadyAhead,
        )
        assertFailsWith<IllegalStateException> {
            gov.admit(forged)
        }
        // The real token still admits cleanly.
        val admittedId = gov.admit(real)
        assertEquals(real.id, admittedId)
        // Re-admitting the same token fails because the registry no longer holds it.
        assertFailsWith<IllegalStateException> {
            gov.admit(real)
        }
    }

    // --- 17 --------------------------------------------------------------------

    @Test fun `mixed visible and speculative races never exceed hard limit`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 4, hardLimit = 6))
        // Fill the speculative bucket.
        val speculative = reserveSpeculative(gov, NativeAdPriority.Speculative, 4)
        assertEquals(4, speculative.reservations.size)
        assertEquals(0 + 4, gov.state().loadedRecords + gov.state().reservedLoads)
        // Visible climbs past softLimit.
        val visible1 = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        assertEquals(1, visible1.reservations.size)
        assertEquals(0 + 5, gov.state().loadedRecords + gov.state().reservedLoads)
        val visible2 = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        assertEquals(1, visible2.reservations.size)
        assertEquals(0 + 6, gov.state().loadedRecords + gov.state().reservedLoads)
        // A seventh visible is granted by cancelling a pending speculative permit;
        // a fifth speculative is denied (speculative cap is the soft limit and
        // we are already at it). The total stays at hardLimit.
        val seventhVisible = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        val deniedSpeculative = reserveSpeculative(gov, NativeAdPriority.Speculative, 1)
        assertEquals(1, seventhVisible.reservations.size)
        assertTrue(deniedSpeculative.reservations.isEmpty())
        // Invariant holds: total at hardLimit, not over.
        assertEquals(6, gov.state().loadedRecords + gov.state().reservedLoads)
    }

    // --- Task 2 corrections -----------------------------------------------------

    @Test fun `visible demand at hard limit cancels pending speculative reservations before retiring records`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2))
        // 1 speculative record admitted, 1 speculative reservation pending. The
        // soft cap is full, the hard cap is full. A visible request must free
        // capacity by cancelling the pending reservation, not by retiring the
        // already-admitted record.
        admitOne(
            gov,
            reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single(),
        )
        val speculativeReservation = reserveSpeculative(gov, NativeAdPriority.Speculative, 1)
            .reservations.single()
        val visible = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        assertEquals(1, visible.reservations.size, "visible reservation must be granted")
        assertTrue(
            visible.retiredRecordIds.isEmpty(),
            "no record should be retired while a pending speculative reservation can be cancelled",
        )
        assertEquals(
            listOf(speculativeReservation),
            visible.cancelledReservations,
            "the cancelled speculative permit must be returned in the decision",
        )
        // The cancelled reservation is no longer known to the governor; the visible
        // permit is the only outstanding reservation.
        assertFailsWith<IllegalStateException> {
            gov.admit(speculativeReservation)
        }
        // Admit the visible and confirm the final state: the surviving speculative
        // record plus the new visible record fill the hard cap.
        admitOne(gov, visible.reservations.single())
        assertEquals(2, gov.state().loadedRecords)
        assertEquals(0, gov.state().reservedLoads)
    }

    @Test fun `free capacity prevents unnecessary cancellation of speculative permits`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 4))
        // 1 speculative permit outstanding, 0 records. Hard cap has 3 free slots.
        val speculative = reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single()
        // Visible reserves 2 — fully satisfied by free capacity; no cancellation,
        // no retirement.
        val visible = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 2)
        assertEquals(2, visible.reservations.size)
        assertTrue(visible.cancelledReservations.isEmpty(),
            "no speculative permit should be cancelled when free capacity covers the request")
        assertTrue(visible.retiredRecordIds.isEmpty())
        // State: 1 speculative + 2 visible = 3 reserved.
        assertEquals(3, gov.state().reservedLoads)
        // The speculative permit is still alive.
        admitOne(gov, speculative)
        assertEquals(1, gov.state().loadedRecords)
    }

    @Test fun `partial admission returns every cancelled permit`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2))
        // 2 speculative permits, no records. The system is at the hard cap.
        val spec1 = reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single()
        val spec2 = reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single()
        // Visible reserves 5 with allowPartial=true: free=0, need 5, can cancel 2.
        // Partial fill: grant 2 (free 0 + cancellations 2), retire 0.
        val visible = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 5, allowPartial = true)
        assertEquals(2, visible.reservations.size, "partial fill grants free + cancellations")
        assertEquals(setOf(spec1, spec2), visible.cancelledReservations.toSet(),
            "every cancelled speculative permit is returned so the coordinator can drop late callbacks")
        assertTrue(visible.retiredRecordIds.isEmpty(), "no records to retire")
        // Final state: 2 visible permits, 0 speculative permits.
        assertEquals(2, gov.state().reservedLoads)
        assertEquals(0, gov.state().loadedRecords)
    }

    @Test fun `visible demand at hard limit returns the exact cancelled reservation`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2))
        // 2 speculative permits, no records.
        val spec1 = reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single()
        val spec2 = reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single()
        val visible = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)
        assertEquals(1, visible.reservations.size)
        assertEquals(1, visible.cancelledReservations.size, "exactly one speculative permit cancelled")
        // The cancelled permit is one of the two speculative ones; identity matters
        // so the coordinator can later admit-or-discard the platform callback.
        val cancelled = visible.cancelledReservations.single()
        assertTrue(cancelled === spec1 || cancelled === spec2)
    }

    @Test fun `releaseReservation rejects a forged token`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 4))
        val real = reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single()
        val forged = NativeAdLoadReservation(
            id = real.id,
            demandClass = NativeAdDemandClass.Visible,
            priority = NativeAdPriority.ActiveReadyAhead,
        )
        assertFailsWith<IllegalStateException> {
            gov.releaseReservation(forged)
        }
        // The genuine token still releases cleanly.
        gov.releaseReservation(real)
        assertEquals(0, gov.state().reservedLoads)
    }

    @Test fun `reserve rejects a negative count`() {
        val gov = governor(NativeAdMemoryPolicy())
        assertFailsWith<IllegalArgumentException> {
            gov.reserve(NativeAdDemandClass.Visible, NativeAdPriority.Speculative, -1, allowPartial = true)
        }
    }

    @Test fun `concurrent reserve wave grants exactly hardLimit permits`() = runBlocking {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 4, hardLimit = 6))
        val coroutineCount = 30
        val errors = Channel<Throwable>(Channel.UNLIMITED)
        val grants = Channel<Unit>(Channel.UNLIMITED)

        val jobs = (1..coroutineCount).map {
            launch(Dispatchers.Default) {
                try {
                    val decision = gov.reserve(
                        NativeAdDemandClass.Visible,
                        NativeAdPriority.Speculative,
                        1,
                        allowPartial = true,
                    )
                    if (decision.reservations.isNotEmpty()) {
                        grants.send(Unit)
                    }
                } catch (e: Throwable) {
                    errors.send(e)
                }
            }
        }
        jobs.joinAll()
        grants.close()
        errors.close()

        val collectedErrors = mutableListOf<Throwable>()
        for (err in errors) collectedErrors.add(err)
        val grantedCount = mutableListOf<Unit>()
        for (u in grants) grantedCount.add(u)

        assertTrue(
            collectedErrors.isEmpty(),
            "no coroutine should throw during concurrent access; got: ${collectedErrors.map { it.message }}",
        )
        // Exactly hardLimit coroutines got a permit; the rest were denied.
        assertEquals(
            6,
            grantedCount.size,
            "exactly hardLimit (6) permits should be granted under a simultaneous reserve wave",
        )
        val state = gov.state()
        assertEquals(0, state.loadedRecords, "no records admitted in a reserve-only wave")
        assertEquals(6, state.reservedLoads, "the governor retains exactly hardLimit reservations")
    }

    // --- Spare priority (unshown-ad reuse) --------------------------------------

    @Test fun `moderate trim retires a spare before a speculative record`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 4))
        val ids = admitAll(gov, reserveVisible(gov, NativeAdPriority.Speculative, 2).reservations)
        gov.reclassify(ids[1], NativeAdPriority.Spare)

        val result = gov.trim(NativeMemoryPressure.Moderate)

        assertEquals(listOf(ids[1]), result.retiredRecordIds, "the spare goes first even though it is more recent")
    }

    @Test fun `speculative reservation at the soft limit retires the least recently used spare`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 4))
        val ids = admitAll(gov, reserveSpeculative(gov, NativeAdPriority.Speculative, 2).reservations)
        gov.reclassify(ids[0], NativeAdPriority.Spare)
        gov.reclassify(ids[1], NativeAdPriority.Spare)
        gov.touch(ids[0])

        val decision = reserveSpeculative(gov, NativeAdPriority.Speculative, 1, allowPartial = false)

        assertEquals(1, decision.reservations.size)
        assertEquals(listOf(ids[1]), decision.retiredRecordIds)
        assertEquals(2, gov.state().loadedRecords + gov.state().reservedLoads)
    }

    @Test fun `speculative reservation at the soft limit never retires a record that is not a spare`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 4))
        admitAll(gov, reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations)

        val decision = reserveSpeculative(gov, NativeAdPriority.Speculative, 1)

        assertTrue(decision.reservations.isEmpty())
        assertTrue(decision.retiredRecordIds.isEmpty())
    }

    @Test fun `a spare priority reservation never retires another spare`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 4))
        val id = admitOne(gov, reserveSpeculative(gov, NativeAdPriority.Speculative, 1).reservations.single())
        gov.reclassify(id, NativeAdPriority.Spare)

        val decision = reserveSpeculative(gov, NativeAdPriority.Spare, 1)

        assertTrue(decision.reservations.isEmpty())
        assertTrue(decision.retiredRecordIds.isEmpty())
        assertEquals(1, gov.state().loadedRecords)
    }

    @Test fun `visible reservation at the hard limit retires a spare before a speculative record`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2))
        val ids = admitAll(gov, reserveSpeculative(gov, NativeAdPriority.Speculative, 2).reservations)
        gov.reclassify(ids[1], NativeAdPriority.Spare)

        val decision = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)

        assertEquals(1, decision.reservations.size)
        assertEquals(listOf(ids[1]), decision.retiredRecordIds)
    }

    @Test fun `moderate trim retires a spare before cancelling a pending speculative reservation`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 4))
        val speculative = reserveSpeculative(gov, NativeAdPriority.Speculative, 2).reservations
        val spare = admitOne(gov, speculative[0])
        gov.reclassify(spare, NativeAdPriority.Spare)
        admitAll(gov, reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1).reservations)

        val result = gov.trim(NativeMemoryPressure.Moderate)

        assertEquals(listOf(spare), result.retiredRecordIds)
        assertTrue(result.cancelledReservations.isEmpty(), "a wanted pending load outranks an idle spare")
    }

    @Test fun `visible demand at the hard limit retires a spare before cancelling a pending speculative reservation`() {
        val gov = governor(NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2))
        val speculative = reserveSpeculative(gov, NativeAdPriority.Speculative, 2).reservations
        val spare = admitOne(gov, speculative[0])
        gov.reclassify(spare, NativeAdPriority.Spare)

        val decision = reserveVisible(gov, NativeAdPriority.ActiveReadyAhead, 1)

        assertEquals(1, decision.reservations.size)
        assertEquals(listOf(spare), decision.retiredRecordIds)
        assertTrue(decision.cancelledReservations.isEmpty())
    }
}
