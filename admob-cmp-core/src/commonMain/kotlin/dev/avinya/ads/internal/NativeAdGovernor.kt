@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.nativead.NativeAdMemoryPolicy


/**
 * Opaque identity of a single native-ad record inside the
 * [NativeAdGovernor]. The coordinator allocates one for every reservation
 * it requests and the same id is reused when the reservation is admitted
 * to a loaded record, so the coordinator can refer to the record across
 * all later operations (touch, setMounted, reclassify, retire) without
 * keeping a separate handle.
 */
internal data class NativeAdRecordId(val value: Long)

/**
 * Demand classification attached to every reservation. The class
 * controls cap behaviour at reserve time, not the record's later
 * eviction order.
 *
 * - [Visible] — the slot is in the reported visible band. May consume
 *   capacity up to [NativeAdMemoryPolicy.hardLimit] and may atomically
 *   retire non-mounted records to make room at the cap.
 * - [Speculative] — covers prefetch-ahead, retain-behind warm-up,
 *   inactive-anchor backfill, and every other load that is not
 *   currently visible. May consume only the capacity below
 *   [NativeAdMemoryPolicy.softLimit] and never evicts to make room.
 *   It may retire [NativeAdPriority.Spare] records, and only those, to make room.
 */
internal enum class NativeAdDemandClass { Visible, Speculative }

/**
 * Eviction priority of a native-ad record. Lower rank means lower value
 * to the manager and therefore an earlier candidate for trim. Mounted
 * records are never eviction candidates regardless of rank.
 *
 * The rank order maps directly to the documented eviction sequence:
 * speculative prefetch → inactive anchor → active retained-behind →
 * active ready-ahead. The class name overlap with [NativeAdDemandClass.Speculative]
 * is intentional but means different things: demand "speculative" describes
 * *why* a slot is being loaded (cap behaviour), while priority "Speculative"
 * describes *what* a record is (eviction order). A record admitted from a
 * speculative reservation may have any priority — most often Speculative,
 * but [reclassify] can move it as the slot changes bands.
 */
internal enum class NativeAdPriority(val rank: Int) {
    /**
     * An unshown ad no slot owns, kept for the next slot of its placement
     * ([dev.avinya.ads.nativead.NativeAdManager.reuseUnshownAds]). Nobody is waiting for it, so it
     * is the first to go.
     */
    Spare(-1),
    /** Prefetch-ahead or retain-behind warm-up. Cheapest to refetch on demand. */
    Speculative(0),
    /** Anchor held by an inactive session. */
    InactiveAnchor(1),
    /** Loaded ad behind the viewport of an active session. */
    ActiveRetainedBehind(2),
    /** Loaded ad in front of the viewport of an active session. */
    ActiveReadyAhead(3),
}

/**
 * Identity-based permit returned by [NativeAdGovernor.reserve]. The
 * governor stores the actual instance it minted; [NativeAdGovernor.admit]
 * rejects any other instance (including a copy with a different
 * [demandClass] or [priority]) by reference comparison, so the caller
 * cannot forge the demand class or priority after the fact.
 *
 * The reservation is consumed by [NativeAdGovernor.admit] (record) or
 * released by [NativeAdGovernor.releaseReservation] (no record) or
 * cancelled by [NativeAdGovernor.trim] (no record). The same reservation
 * must never be passed to more than one of those three paths.
 */
internal class NativeAdLoadReservation(
    val id: NativeAdRecordId,
    val demandClass: NativeAdDemandClass,
    val priority: NativeAdPriority,
)

/**
 * Outcome of a [NativeAdGovernor.reserve] call.
 *
 * - [reservations] are the new permits the coordinator may now call
 *   [NativeAdGovernor.admit] on.
 * - [retiredRecordIds] are the records the governor evicted in the same
 *   locked mutation to make room — the coordinator is responsible for
 *   destroying their platform objects after releasing the governor lock.
 * - [cancelledReservations] are speculative permits the governor
 *   dropped to satisfy visible demand at the hard cap. The platform
 *   may still call back for these permits (the request is in flight);
 *   the coordinator must drop those callbacks at the generation
 *   check, before any record is admitted.
 */
internal data class NativeAdReservationDecision(
    val reservations: List<NativeAdLoadReservation>,
    val retiredRecordIds: List<NativeAdRecordId> = emptyList(),
    val cancelledReservations: List<NativeAdLoadReservation> = emptyList(),
)

/**
 * Outcome of a [NativeAdGovernor.trim] call. [retiredRecordIds] are
 * records the governor removed; [cancelledReservations] are permits
 * the governor cancelled (the platform never gets to call back with
 * an ad, or its callback is dropped at the coordinator's generation
 * check). The coordinator destroys platform objects behind the retired
 * ids and discards any in-flight load behind the cancelled permits.
 */
internal data class NativeAdTrimResult(
    val retiredRecordIds: List<NativeAdRecordId>,
    val cancelledReservations: List<NativeAdLoadReservation>,
)

/**
 * Memory-pressure signal the [NativeAdGovernor] reacts to.
 *
 * - [Moderate] trims toward [NativeAdMemoryPolicy.softLimit], cancelling
 *   speculative reservations first, then retiring non-mounted records,
 *   and only cancelling visible reservations as a last resort.
 * - [Critical] atomically cancels every pending reservation and retires
 *   every non-mounted record, retaining mounted records only.
 */
internal enum class NativeMemoryPressure { Moderate, Critical }

/**
 * Snapshot of the governor's internal state. Distinct from the public
 * [dev.avinya.ads.nativead.NativeAdManagerState]; the manager composes
 * its public snapshot from this plus the session registry.
 */
internal data class NativeAdGovernorState(
    val loadedRecords: Int,
    val reservedLoads: Int,
    val hardLimit: Int,
    val softLimit: Int,
)

/**
 * Process-wide admission governor for native ads. Owns the invariant
 *
 * ```
 * loadedRecordCount + reservedLoadCount <= policy.hardLimit
 * ```
 *
 * under every mutation. All state is protected by a [FullScreenStateLock];
 * every public method is atomic from the caller's perspective, and raw
 * [synchronized] is deliberately not used (it is unavailable in
 * Kotlin/Native common code).
 *
 * Records carry an eviction [NativeAdPriority] and a `mounted` flag. A
 * record can be of any priority class and independently be mounted; the
 * governor never evicts a mounted record regardless of its priority.
 * Within a priority class, LRU breaks ties — the [touch] operation moves
 * a record to the most-recent position. LRU is keyed off an
 * incrementing access ordinal, not [kotlin.time.Clock] wall time, so
 * tests are deterministic.
 *
 * The governor is a pure counter-and-priority object: it does not know
 * about platform ad instances, sessions, slots, or generations. The
 * coordinator is responsible for those concepts and for destroying the
 * ad object the governor hands back to it via [trim] and [retire].
 */
internal class NativeAdGovernor(
    private val policy: NativeAdMemoryPolicy,
) {
    private val lock = FullScreenStateLock()
    private var nextId: Long = 0
    private var accessOrdinal: Long = 0
    private val records = mutableMapOf<NativeAdRecordId, MutableRecord>()
    private val pendingReservations = mutableMapOf<NativeAdRecordId, NativeAdLoadReservation>()
    // FIFO order of pending reservations, oldest first. Used by trim to cancel
    // in insertion order. The map is the lookup; the list is the order.
    private val reservationOrder = mutableListOf<NativeAdRecordId>()

    private class MutableRecord(
        var priority: NativeAdPriority,
        var mounted: Boolean,
        var lastAccessed: Long,
    )

    /**
     * Reserves up to [count] capacity units for in-flight loads.
     *
     * Cap behaviour depends on [demandClass]:
     * - [NativeAdDemandClass.Speculative] may consume only capacity below
     *   [NativeAdMemoryPolicy.softLimit]. If the soft cap is reached the
     *   request is denied even with hard-limit headroom, and the
     *   governor never evicts to satisfy a speculative request.
     * - [NativeAdDemandClass.Visible] may consume capacity above the
     *   soft cap up to [NativeAdMemoryPolicy.hardLimit]. At the hard
     *   cap, the governor may atomically cancel pending speculative
     *   reservations first, then retire non-mounted records, in the
     *   same locked mutation. Retired record ids are returned in
     *   the [NativeAdReservationDecision.retiredRecordIds] field so
     *   the coordinator can destroy the platform objects after
     *   releasing the lock. Mounted records are never victims.
     *
     * [allowPartial] controls partial fill: if true, the governor grants
     * as many as fit (with cancellation and eviction for Visible as
     * needed); if false, a shortfall denies the entire request without
     * mutating records or reservations, so accounting and requested load
     * counts cannot diverge.
     */
    fun reserve(
        demandClass: NativeAdDemandClass,
        priority: NativeAdPriority,
        count: Int,
        allowPartial: Boolean,
    ): NativeAdReservationDecision = lock.withLock {
        require(count >= 0) { "reserve count must be non-negative, was $count" }
        if (count == 0) return@withLock NativeAdReservationDecision(emptyList())

        val currentTotal = records.size + pendingReservations.size

        when (demandClass) {
            NativeAdDemandClass.Speculative -> reserveSpeculative(priority, count, currentTotal, allowPartial)
            NativeAdDemandClass.Visible -> reserveVisible(priority, count, currentTotal, allowPartial)
        }
    }

    private fun reserveSpeculative(
        priority: NativeAdPriority,
        count: Int,
        currentTotal: Int,
        allowPartial: Boolean,
    ): NativeAdReservationDecision {
        val available = policy.softLimit - currentTotal
        if (available >= count) {
            return NativeAdReservationDecision(createReservations(NativeAdDemandClass.Speculative, priority, count))
        }
        // Short of the soft limit. Spares are ads nobody is waiting for, so wanted demand may retire
        // them, least recently used first. A spare may never displace another spare: that would
        // churn the pool without serving anyone.
        val victims = if (priority == NativeAdPriority.Spare) emptyList() else spareVictims()
        val grantable = minOf(count, available + victims.size)
        if (grantable <= 0 || (grantable < count && !allowPartial)) return NativeAdReservationDecision(emptyList())
        val retired = victims.take(grantable - available)
        retired.forEach { records.remove(it) }
        return NativeAdReservationDecision(
            createReservations(NativeAdDemandClass.Speculative, priority, grantable),
            retiredRecordIds = retired,
        )
    }

    private fun spareVictims(): List<NativeAdRecordId> =
        records.entries
            .asSequence()
            .filter { it.value.priority == NativeAdPriority.Spare && !it.value.mounted }
            .sortedWith(compareBy({ it.value.lastAccessed }, { it.key.value }))
            .map { it.key }
            .toList()

    private fun reserveVisible(
        priority: NativeAdPriority,
        count: Int,
        currentTotal: Int,
        allowPartial: Boolean,
    ): NativeAdReservationDecision {
        val cap = policy.hardLimit
        val free = cap - currentTotal
        if (free >= count) {
            return NativeAdReservationDecision(createReservations(NativeAdDemandClass.Visible, priority, count))
        }
        if (free <= 0) {
            val victims = pickNonMountedVictims(count)
            return resolveVisibleEviction(priority, count, 0, victims, allowPartial)
        }
        val needed = count - free
        val victims = pickNonMountedVictims(needed)
        return resolveVisibleEviction(priority, count, free, victims, allowPartial)
    }

    private fun resolveVisibleEviction(
        priority: NativeAdPriority,
        count: Int,
        free: Int,
        victims: List<Pair<NativeAdRecordId, MutableRecord>>,
        allowPartial: Boolean,
    ): NativeAdReservationDecision {
        // Capacity is sourced in this order: free → cancel pending speculative
        // reservations → retire non-mounted records. Cancelling a speculative
        // permit is cheaper than retiring a record (no platform object to
        // destroy), so we always prefer it. Mounted records are never victims.
        val speculatives = cancelableSpeculativeReservations()
        val maxGrantable = free + speculatives.size + victims.size
        return when {
            count <= maxGrantable -> {
                // needed = how many non-free slots we must source by cancellation
                // or retirement; free already covers the rest.
                val needed = count - free
                val cancelCount = minOf(needed, speculatives.size)
                val evictCount = needed - cancelCount
                val toCancel = speculatives.take(cancelCount)
                toCancel.forEach { (id, _) ->
                    pendingReservations.remove(id)
                    reservationOrder.remove(id)
                }
                val toRetire = victims.take(evictCount)
                toRetire.forEach { (id, _) -> records.remove(id) }
                NativeAdReservationDecision(
                    createReservations(NativeAdDemandClass.Visible, priority, count),
                    toRetire.map { it.first },
                    toCancel.map { it.second },
                )
            }
            allowPartial && maxGrantable > 0 -> {
                speculatives.forEach { (id, _) ->
                    pendingReservations.remove(id)
                    reservationOrder.remove(id)
                }
                victims.forEach { (id, _) -> records.remove(id) }
                NativeAdReservationDecision(
                    createReservations(NativeAdDemandClass.Visible, priority, maxGrantable),
                    victims.map { it.first },
                    speculatives.map { it.second },
                )
            }
            else -> NativeAdReservationDecision(emptyList())
        }
    }

    private fun cancelableSpeculativeReservations(): List<Pair<NativeAdRecordId, NativeAdLoadReservation>> =
        reservationOrder.mapNotNull { id ->
            pendingReservations[id]?.takeIf { it.demandClass == NativeAdDemandClass.Speculative }
                ?.let { id to it }
        }

    private fun createReservations(
        demandClass: NativeAdDemandClass,
        priority: NativeAdPriority,
        count: Int,
    ): List<NativeAdLoadReservation> = List(count) {
        val id = NativeAdRecordId(nextId++)
        val reservation = NativeAdLoadReservation(id, demandClass, priority)
        pendingReservations[id] = reservation
        reservationOrder.add(id)
        reservation
    }

    private fun pickNonMountedVictims(count: Int): List<Pair<NativeAdRecordId, MutableRecord>> =
        records.entries
            .asSequence()
            .filter { !it.value.mounted }
            .sortedWith(
                compareBy({ it.value.priority.rank }, { it.value.lastAccessed }, { it.key.value }),
            )
            .take(count)
            .map { it.key to it.value }
            .toList()

    /**
     * Promotes a [reservation] to a loaded record. Returns the record's
     * id (the same id the reservation carried). Throws
     * [IllegalStateException] if the reservation is unknown, has
     * already been resolved, or carries a different instance — the
     * latter prevents a caller from forging a different demand class
     * or priority after the fact.
     */
    fun admit(reservation: NativeAdLoadReservation): NativeAdRecordId = lock.withLock {
        val stored = pendingReservations[reservation.id]
        check(stored != null) {
            "admit: reservation ${reservation.id.value} is unknown or already resolved"
        }
        check(stored === reservation) {
            "admit: reservation ${reservation.id.value} identity mismatch (forged or different instance)"
        }
        pendingReservations.remove(reservation.id)
        reservationOrder.remove(reservation.id)
        records[reservation.id] = MutableRecord(
            priority = stored.priority,
            mounted = false,
            lastAccessed = accessOrdinal++,
        )
        reservation.id
    }

    /**
     * Discards a reservation without admitting it. Use this when the
     * platform returned fewer ads than reserved (partial-batch failure,
     * timeout, cancellation). No-op on an unknown reservation; throws
     * [IllegalStateException] if the supplied [reservation] is a
     * different instance from the one the governor stored (a forged
     * token). The identity check is the same one [admit] applies, so
     * a forged permit cannot be released to make capacity appear free.
     */
    fun releaseReservation(reservation: NativeAdLoadReservation) {
        lock.withLock {
            val stored = pendingReservations[reservation.id] ?: return@withLock
            check(stored === reservation) {
                "releaseReservation: reservation ${reservation.id.value} identity mismatch " +
                    "(forged or different instance)"
            }
            pendingReservations.remove(reservation.id)
            reservationOrder.remove(reservation.id)
        }
    }

    /**
     * Updates the LRU timestamp of a record. No-op if the id is unknown
     * (caller may pass a stale id after a race that already retired it).
     */
    fun touch(id: NativeAdRecordId) {
        lock.withLock {
            records[id]?.lastAccessed = accessOrdinal++
        }
    }

    /**
     * Marks a record as mounted (currently attached to a renderer) or
     * clears the mounted flag. Mounted records are never eviction
     * candidates under any [trim] call. No-op on an unknown id.
     */
    fun setMounted(id: NativeAdRecordId, mounted: Boolean) {
        lock.withLock {
            records[id]?.mounted = mounted
        }
    }

    /**
     * Changes the eviction priority of an already-admitted record. Use
     * this when a slot's band changes (e.g. from prefetched to visible)
     * so the record's later eviction order reflects the new role. The
     * record's LRU position is preserved; only the priority moves.
     * No-op on an unknown id.
     */
    fun reclassify(id: NativeAdRecordId, priority: NativeAdPriority) {
        lock.withLock {
            records[id]?.priority = priority
        }
    }

    /**
     * Retires a single record and returns true if the id was found.
     * Mounted records can be retired — the caller is responsible for
     * also tearing down the rendered view, since the governor does not
     * know about the platform object behind the id.
     */
    fun retire(id: NativeAdRecordId): Boolean = lock.withLock {
        records.remove(id) != null
    }

    /**
     * Trims records and pending reservations under memory pressure.
     * The [NativeAdTrimResult] contains both retired record ids (which
     * the caller destroys) and cancelled reservations (which the
     * coordinator's generation check must drop from any in-flight
     * platform callback).
     *
     * [NativeMemoryPressure.Moderate] trims toward
     * [NativeAdMemoryPolicy.softLimit]:
     *   1. cancel speculative pending reservations, oldest first;
     *   2. retire non-mounted records by (priority rank, LRU);
     *   3. only as a last resort, cancel visible pending reservations.
     * Mounted records may leave the result above the soft limit.
     *
     * [NativeMemoryPressure.Critical] atomically cancels every
     * pending reservation and retires every non-mounted record.
     * Mounted records are preserved. The result may therefore exceed
     * the soft limit if there are more mounted records than the soft
     * limit allows.
     */
    fun trim(pressure: NativeMemoryPressure): NativeAdTrimResult = lock.withLock {
        when (pressure) {
            NativeMemoryPressure.Moderate -> trimModerate()
            NativeMemoryPressure.Critical -> trimCritical()
        }
    }

    private fun trimModerate(): NativeAdTrimResult {
        val target = policy.softLimit
        val currentTotal = records.size + pendingReservations.size
        if (currentTotal <= target) {
            return NativeAdTrimResult(emptyList(), emptyList())
        }
        var excess = currentTotal - target
        val cancelledSpeculative = mutableListOf<NativeAdLoadReservation>()
        val cancelledVisible = mutableListOf<NativeAdLoadReservation>()
        val retiredIds = mutableListOf<NativeAdRecordId>()

        // 1. Cancel speculative pending reservations, oldest first.
        for (id in reservationOrder.toList()) {
            if (excess <= 0) break
            val res = pendingReservations[id] ?: continue
            if (res.demandClass != NativeAdDemandClass.Speculative) continue
            pendingReservations.remove(id)
            reservationOrder.remove(id)
            cancelledSpeculative.add(res)
            excess--
        }

        // 2. Retire non-mounted records by (priority rank, LRU).
        if (excess > 0) {
            val sortedVictims = pickNonMountedVictims(excess)
            for ((id, _) in sortedVictims) {
                if (excess <= 0) break
                records.remove(id)
                retiredIds.add(id)
                excess--
            }
        }

        // 3. Cancel visible pending reservations as a last resort.
        if (excess > 0) {
            for (id in reservationOrder.toList()) {
                if (excess <= 0) break
                val res = pendingReservations[id] ?: continue
                if (res.demandClass != NativeAdDemandClass.Visible) continue
                pendingReservations.remove(id)
                reservationOrder.remove(id)
                cancelledVisible.add(res)
                excess--
            }
        }

        return NativeAdTrimResult(
            retiredRecordIds = retiredIds,
            cancelledReservations = cancelledSpeculative + cancelledVisible,
        )
    }

    private fun trimCritical(): NativeAdTrimResult {
        val retiredIds = records.entries
            .asSequence()
            .filter { !it.value.mounted }
            .sortedWith(
                compareBy({ it.value.priority.rank }, { it.value.lastAccessed }, { it.key.value }),
            )
            .map { it.key }
            .toList()
        retiredIds.forEach { records.remove(it) }
        val cancelled = pendingReservations.values.toList()
        pendingReservations.clear()
        reservationOrder.clear()
        return NativeAdTrimResult(
            retiredRecordIds = retiredIds,
            cancelledReservations = cancelled,
        )
    }

    /**
     * Returns a snapshot of the governor's current counts. Read-only;
     * the snapshot is not guaranteed to remain consistent with later
     * mutations.
     */
    fun state(): NativeAdGovernorState = lock.withLock {
        NativeAdGovernorState(
            loadedRecords = records.size,
            reservedLoads = pendingReservations.size,
            hardLimit = policy.hardLimit,
            softLimit = policy.softLimit,
        )
    }
}
