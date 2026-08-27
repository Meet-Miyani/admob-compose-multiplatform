@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.INTERNAL_LOAD_ERROR_CODE
import dev.avinya.ads.isRetryableLoadFailure
import dev.avinya.ads.retryAdLoad
import dev.avinya.ads.nativead.NativeAdBatching
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSessionState
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant


/**
 * Process-wide coordinator that drives native-ad loads across every active
 * session, owns placement-level load scheduling, and bridges the
 * platform-specific [NativeAdPlatform] to the public
 * [dev.avinya.ads.nativead.NativeAdManager] / [dev.avinya.ads.nativead.NativeAdSession] surface.
 *
 * **Ownership model.** The coordinator is the **sole owner of every
 * admitted platform ad**. The [NativeAdGovernor] only tracks record ids
 * and reservation counts; the platform-side object lives in the
 * coordinator's [records] map and is destroyed exactly once via
 * [destroyRecord] on any of the invalidation paths:
 *  - [closeSession] / [closeAll] / [clear] / [onConsentRevoked]
 *  - per-record 1-hour native TTL (the [tickLocked] pass)
 *  - inactive-session reap at [NativeAdMemoryPolicy.inactiveSessionTtl]
 *  - inactive-session LRU eviction at [NativeAdMemoryPolicy.maxInactiveSessions]
 *
 * **Generation model.** Each placement has a generation counter. [clear]
 * and [onConsentRevoked] bump every placement's generation. A late
 * platform callback that arrives under an older generation is destroyed
 * on arrival — it never reaches a session. Per-slot generation is owned
 * by [NativeAdSessionCore]; the coordinator threads it through admit /
 * fail callbacks so a stale admit for a since-superseded slot is
 * dropped at the session.
 *
 * **Scheduling.** One [PlacementScheduler] per placement that has ever
 * had demand. The scheduler reserves capacity via the governor (using
 * the **granted** reservation count for the platform.load call, never
 * the originally requested count), serialises per-placement work, and
 * removes itself once it has no records, no reservations, no
 * in-flight work, and no queued requests.
 *
 * **TTL.**
 *  - 1-hour native ad TTL is enforced by [tickLocked] on every public
 *    mutator. Expired records destroy their platform ad, retire the
 *    governor accounting, and submit the [NativeAdSessionCore.expireSlot]
 *    reload demand to the right scheduler.
 *  - Inactive-session TTL is [NativeAdMemoryPolicy.inactiveSessionTtl]
 *    (default 30 minutes). The coordinator tracks the inactive set in
 *    insertion order (LinkedHashMap) so eviction is LRU.
 *  - [NativeAdMemoryPolicy.maxSessionRecords] is the hard cap on
 *    live + inactive sessions; the 65th call to [session] throws.
 *
 * **Locking.** One [FullScreenStateLock] per coordinator instance. The
 * lock is held across every mutator; per-placement work is launched on
 * [scope] so platform calls and `platform.destroy` happen outside the
 * lock.
 */
internal class NativeAdCoordinatorCore<A : Any>(
    private val memoryPolicy: NativeAdMemoryPolicy,
    private val platform: NativeAdPlatform<A>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Instant = { Clock.System.now() },
    private val canRequestAds: () -> Boolean = { true },
    private val eventSink: (AdEvent) -> Unit = {},
) {
    private val lock = FullScreenStateLock()
    private val governor = NativeAdGovernor(memoryPolicy)
    private val sessions = mutableMapOf<String, SessionHolder>()
    private val inactiveOrder = LinkedHashMap<String, Instant>()
    private val schedulers = mutableMapOf<String, PlacementScheduler>()
    // Sole record of every admitted platform ad. Destroyed exactly once.
    private val records = mutableMapOf<NativeAdRecordId, RecordEntry>()
    private val reservationOwners = mutableMapOf<NativeAdRecordId, ReservationOwner>()
    private var nextSessionGeneration = 1L
    // Test-only override for "now". Production uses the real clock.
    private var testNow: Instant? = null
    private var stateListener: () -> Unit = {}

    private inner class RecordEntry(
        val ad: A,
        val placementId: String,
        val sessionKey: String,
        val slotKey: String,
        val generation: Long,
        val placement: AdPlacement,
        val mediaInfo: dev.avinya.ads.nativead.NativeMediaInfo?,
        val adInstanceId: String,
        val loadedAt: Instant,
        var rendererId: String? = null,
    )

    private inner class SessionHolder(
        val core: NativeAdSessionCore,
        val generation: Long,
        var lastActive: Instant,
        var active: Boolean = true,
    )

    private inner class ReservationOwner(
        val placementId: String,
        val sessionKey: String,
        val slotKey: String,
        val slotGeneration: Long,
        val reservation: NativeAdLoadReservation,
    )

    /** A launched platform load's immutable reservation-to-slot association. */
    private inner class ReservationSlotPair(
        val reservation: NativeAdLoadReservation,
        val entry: SlotDemandEntry,
    )

    private inner class Effects {
        val destroy = mutableListOf<A>()
        val cancel = mutableListOf<Job>()
        fun run() {
            cancel.distinct().forEach { it.cancel() }
            destroy.distinct().forEach(platform::destroy)
            // Platform loads settle asynchronously. Notify only after all work that
            // escaped the coordinator lock, so observers can safely obtain the
            // authoritative governor/session snapshot without lock recursion.
            stateListener()
        }
    }

    // -----------------------------------------------------------------------
    // Public surface
    // -----------------------------------------------------------------------

    /** Installed once by the public facade; always invoked outside [lock]. */
    fun setStateListener(listener: () -> Unit) {
        stateListener = listener
    }

    fun session(
        key: String,
        policy: NativeAdSessionPolicy = NativeAdSessionPolicy(),
        published: MutableStateFlow<NativeAdSessionState>? = null,
    ): NativeAdSessionCore {
        val (core, effects) = lock.withLock {
            val effects = Effects()
            require(key.isNotBlank()) { "session key must not be blank" }
            tickLocked(effects)
            sessions[key]?.let { holder ->
                // Policy mismatch is rejected (the original plan contract).
                if (holder.core.policy != policy) {
                    throw IllegalStateException(
                        "NativeAdCoordinatorCore: session '$key' already exists with " +
                            "a different policy (maxRetainedAds=${holder.core.policy.maxRetainedAds}, " +
                            "retainBehind=${holder.core.policy.retainBehind}, " +
                            "prefetchAhead=${holder.core.policy.prefetchAhead}); " +
                            "close the existing session before reusing the key with a new policy."
                    )
                }
                holder.lastActive = nowLocked()
                if (!holder.active) {
                    holder.active = true
                    inactiveOrder.remove(key)
                }
                return@withLock holder.core to effects
            }
            if (sessions.size >= memoryPolicy.maxSessionRecords) {
                throw IllegalStateException(
                    "NativeAdCoordinatorCore: maxSessionRecords (${memoryPolicy.maxSessionRecords}) " +
                        "reached; cannot create session '$key'."
                )
            }
            val holder = SessionHolder(
                core = if (published != null) {
                    NativeAdSessionCore(key, policy, memoryPolicy.inactiveSessionLimit, published)
                } else {
                    NativeAdSessionCore(key, policy, memoryPolicy.inactiveSessionLimit)
                },
                generation = nextSessionGeneration++,
                lastActive = nowLocked(),
            )
            sessions[key] = holder
            holder.core to effects
        }
        effects.run()
        return core
    }

    /** Coordinator-issued identity for session-scoped operations. */
    fun sessionGeneration(key: String): Long? = lock.withLock { sessions[key]?.generation }

    private fun currentHolderLocked(key: String, generation: Long): SessionHolder? =
        sessions[key]?.takeIf { it.generation == generation }

    fun closeSession(key: String) {
        val effects = lock.withLock {
            val effects = Effects()
            tickLocked(effects)
            val holder = sessions.remove(key) ?: return@withLock effects
            inactiveOrder.remove(key)
            applySessionMutationLocked(holder, holder.core.close(), effects)
            schedulers.values.toList().forEach { it.cancelForSessionLocked(key, effects) }
            cleanupSchedulersLocked()
            effects
        }
        effects.run()
    }

    fun closeSession(key: String, sessionGeneration: Long) {
        if (lock.withLock { currentHolderLocked(key, sessionGeneration) == null }) return
        closeSession(key)
    }

    fun clear() {
        val effects = lock.withLock {
        val effects = Effects()
        tickLocked(effects)
        placementGenBumpAll()
        // Destroy inventory but retain live session definitions. A following
        // window update is fresh demand on the same generation.
        destroyAllRecordsLocked(effects)
        for (holder in sessions.values) {
            holder.core.clear()
        }
        schedulers.values.toList().forEach { it.clearQueuedLocked() }
        cleanupSchedulersLocked()
        effects
        }
        effects.run()
    }

    fun onConsentRevoked() {
        val effects = lock.withLock {
        val effects = Effects()
        tickLocked(effects)
        placementGenBumpAll()
        destroyAllRecordsLocked(effects)
        for (holder in sessions.values) holder.core.close()
        sessions.clear()
        inactiveOrder.clear()
        schedulers.values.toList().forEach { it.clearQueuedLocked() }
        cleanupSchedulersLocked()
        effects
        }
        effects.run()
    }

    fun updateWindow(sessionKey: String, window: NativeAdWindow) {
        val effects = lock.withLock {
            val effects = Effects()
            tickLocked(effects)
            val holder = sessions[sessionKey] ?: return@withLock effects
            holder.lastActive = nowLocked()
            if (!holder.active) {
                holder.active = true
                inactiveOrder.remove(sessionKey)
            }
            applySessionMutationLocked(holder, holder.core.updateWindow(window), effects)
            effects
        }
        effects.run()
    }

    fun updateWindow(sessionKey: String, sessionGeneration: Long, window: NativeAdWindow) {
        val effects = lock.withLock {
            val effects = Effects()
            tickLocked(effects)
            val holder = currentHolderLocked(sessionKey, sessionGeneration) ?: return@withLock effects
            holder.lastActive = nowLocked()
            if (!holder.active) {
                holder.active = true
                inactiveOrder.remove(sessionKey)
            }
            applySessionMutationLocked(holder, holder.core.updateWindow(window), effects)
            effects
        }
        effects.run()
    }

    fun setMounted(sessionKey: String, slotKey: String, mounted: Boolean) {
        val effects = lock.withLock {
            val effects = Effects()
            tickLocked(effects)
            sessions[sessionKey]?.let { holder ->
                holder.core.recordIdFor(slotKey)?.let { recordId ->
                    applySessionMutationLocked(holder, holder.core.setMounted(slotKey, recordId, mounted), effects)
                    governor.setMounted(recordId, mounted)
                }
                holder.lastActive = nowLocked()
            }
            effects
        }
        effects.run()
    }

    fun deactivateSession(sessionKey: String) {
        val effects = lock.withLock {
            val effects = Effects()
            tickLocked(effects)
            sessions[sessionKey]?.let { holder ->
                applySessionMutationLocked(holder, holder.core.deactivate(), effects)
                holder.active = false
                inactiveOrder.remove(sessionKey)
                inactiveOrder[sessionKey] = nowLocked()
                tickLocked(effects)
            }
            effects
        }
        effects.run()
    }

    fun deactivateSession(sessionKey: String, sessionGeneration: Long) {
        val effects = lock.withLock {
            val effects = Effects()
            tickLocked(effects)
            currentHolderLocked(sessionKey, sessionGeneration)?.let { holder ->
                applySessionMutationLocked(holder, holder.core.deactivate(), effects)
                holder.active = false
                inactiveOrder.remove(sessionKey)
                inactiveOrder[sessionKey] = nowLocked()
                tickLocked(effects)
            }
            effects
        }
        effects.run()
    }

    fun acquireForRender(
        sessionKey: String,
        sessionGeneration: Long,
        slotKey: String,
        placement: AdPlacement,
        rendererId: String,
    ): NativeAdRenderRecord<A>? = lock.withLock {
        val holder = currentHolderLocked(sessionKey, sessionGeneration) ?: return@withLock null
        val recordId = holder.core.recordIdFor(slotKey) ?: return@withLock null
        val entry = records[recordId] ?: return@withLock null
        if (entry.sessionKey != sessionKey || entry.slotKey != slotKey || entry.generation != holder.core.slotGenerationFor(slotKey) || entry.placement != placement) return@withLock null
        if (entry.rendererId != null && entry.rendererId != rendererId) return@withLock null
        entry.rendererId = rendererId
        applySessionMutationLocked(holder, holder.core.setMounted(slotKey, recordId, true), Effects())
        governor.setMounted(recordId, true)
        NativeAdRenderRecord(recordId, entry.adInstanceId, entry.ad, entry.mediaInfo)
    }

    fun releaseRenderer(
        sessionKey: String,
        sessionGeneration: Long,
        slotKey: String,
        placement: AdPlacement,
        recordId: NativeAdRecordId,
        rendererId: String,
    ) {
        val effects = lock.withLock {
            val effects = Effects()
            val holder = currentHolderLocked(sessionKey, sessionGeneration) ?: return@withLock effects
            val entry = records[recordId] ?: return@withLock effects
            if (entry.sessionKey != sessionKey || entry.slotKey != slotKey || entry.placement != placement || entry.rendererId != rendererId) return@withLock effects
            entry.rendererId = null
            applySessionMutationLocked(holder, holder.core.setMounted(slotKey, recordId, false), effects)
            governor.setMounted(recordId, false)
            effects
        }
        effects.run()
    }

    fun onMemoryPressure(pressure: NativeMemoryPressure) {
        val effects = lock.withLock {
            val effects = Effects()
            val result = governor.trim(pressure)
            result.cancelledReservations.forEach { reservation ->
                reservationOwners.remove(reservation.id)?.let { owner ->
                    schedulers[owner.placementId]?.cancelSlotLocked(
                        owner.sessionKey,
                        SlotGeneration(owner.slotKey, owner.slotGeneration),
                        effects,
                    )
                }
            }
            result.retiredRecordIds.forEach { removeRecordLocked(it, effects) }
            effects
        }
        effects.run()
    }

    fun schedulerCount(): Int = lock.withLock { schedulers.size }

    fun tickForTest(duration: Duration) {
        val effects = lock.withLock {
            val effects = Effects()
            testNow = nowLocked() + duration
            tickLocked(effects)
            effects
        }
        effects.run()
    }

    fun managerState(): dev.avinya.ads.nativead.NativeAdManagerState = lock.withLock {
        val governorState = governor.state()
        dev.avinya.ads.nativead.NativeAdManagerState(
            loadedAds = governorState.loadedRecords,
            reservedLoads = governorState.reservedLoads,
            activeSessions = sessions.values.count { it.active },
            inactiveSessions = sessions.values.count { !it.active },
            hardLimit = governorState.hardLimit,
        )
    }

    // -----------------------------------------------------------------------
    // Internal helpers (must be called under `lock`)
    // -----------------------------------------------------------------------

    private fun nowLocked(): Instant = testNow ?: clock()

    private fun tickLocked(effects: Effects = Effects()) {
        val now = nowLocked()

        // Reap inactive sessions past the TTL.
        val inactiveCutoff = now - memoryPolicy.inactiveSessionTtl
        val toReap = inactiveOrder.entries.filter { it.value <= inactiveCutoff }.map { it.key }
        for (key in toReap) {
            val holder = sessions.remove(key) ?: continue
            inactiveOrder.remove(key)
            applySessionMutationLocked(holder, holder.core.close(), effects)
            schedulers.values.toList().forEach { it.cancelForSessionLocked(key, effects) }
        }
        // Enforce the LRU cap on inactive sessions.
        while (inactiveOrder.size > memoryPolicy.maxInactiveSessions) {
            val oldest = inactiveOrder.entries.firstOrNull()?.key ?: break
            val holder = sessions.remove(oldest) ?: break
            inactiveOrder.remove(oldest)
            applySessionMutationLocked(holder, holder.core.close(), effects)
            schedulers.values.toList().forEach { it.cancelForSessionLocked(oldest, effects) }
        }

        // Expire records past the 1-hour native-ad TTL.
        val expiredRecordIds = records.entries
            .filter { (_, meta) -> meta.loadedAt <= now - meta.placement.cachePolicy.expirationPolicy.nativeTtl }
            .map { (id, _) -> id }
            .toList()
        for (recordId in expiredRecordIds) {
            val entry = records[recordId] ?: continue
            val holder = sessions[entry.sessionKey]
            val demand = holder?.core?.expireSlot(entry.slotKey)
            removeRecordLocked(recordId, effects)
            // The reload demand is submitted to the right placement
            // scheduler so the platform call is reissued.
            if (holder != null && demand != null && demand.demands.isNotEmpty()) {
                applySessionMutationLocked(holder, demand, effects)
            }
        }
    }

    private fun applySessionMutationLocked(
        holder: SessionHolder,
        mutation: NativeAdSessionMutation,
        effects: Effects,
    ) {
        mutation.invalidateLoads.forEach { invalidation ->
            schedulers.values.forEach { scheduler ->
                scheduler.cancelSlotLocked(holder.core.key, invalidation, effects)
            }
        }
        mutation.reclassifications.forEach { governor.reclassify(it.recordId, it.priority) }
        mutation.retireRecordIds.forEach { removeRecordLocked(it, effects) }
        submitDemand(holder, mutation, effects)
    }

    private fun submitDemand(holder: SessionHolder, demand: NativeAdSessionMutation, effects: Effects) {
        if (demand.demands.isEmpty()) return
        val byPlacement = demand.demands.groupBy { it.placement.id }
        for ((placementId, entries) in byPlacement) {
            val scheduler = schedulers.getOrPut(placementId) { PlacementScheduler(placementId) }
            scheduler.submit(holder, entries, effects)
        }
    }

    private fun placementGenBumpAll() {
        if (schedulers.isEmpty()) return
        for (placementId in schedulers.keys.toList()) {
            schedulers[placementId]?.bumpGeneration()
        }
    }

    /**
     * Destroy every record the holder currently owns, then remove the
     * corresponding metadata. Called from [clear], [onConsentRevoked],
     * and the inactive-session reap path in [tickLocked].
     */
    private fun destroyAllRecordsLocked(effects: Effects) {
        records.keys.toList().forEach { removeRecordLocked(it, effects) }
    }

    /**
     * Destroy and remove the records for [retiredRecordIds] belonging
     * to [sessionKey]. Safe to call when the records are not present
     * (e.g. an already-reaped record). Used by [closeSession] and the
     * inactive-session reap path.
     */
    private fun removeRecordLocked(recordId: NativeAdRecordId, effects: Effects) {
        val entry = records.remove(recordId) ?: return
        entry.rendererId = null
        sessions[entry.sessionKey]?.core?.recordEvicted(entry.slotKey, recordId)
        schedulers[entry.placementId]?.activeRecordIds?.remove(recordId)
        governor.retire(recordId)
        effects.destroy += entry.ad
        cleanupSchedulersLocked()
    }

    private fun cleanupSchedulersLocked() {
        schedulers.entries.removeAll { (_, scheduler) -> scheduler.isIdleLocked() }
    }

    /** Platform callbacks are accepted only while their owned record is current. */
    private fun routeEvent(recordId: NativeAdRecordId, instanceId: String, event: AdEvent) {
        val current = lock.withLock { records.containsKey(recordId) && recordId.value.toString() == instanceId }
        if (current) eventSink(event)
    }

    private inner class PlacementScheduler(val placementId: String) {
        private val queue = mutableListOf<Batch>()
        private var currentJob: Job? = null
        val activeRecordIds = mutableSetOf<NativeAdRecordId>()
        private val activeReservations = mutableListOf<ReservationSlotPair>()
        private var generation: Long = 0L

        private inner class Batch(val holder: SessionHolder, val entries: List<SlotDemandEntry>)

        fun submit(holder: SessionHolder, entries: List<SlotDemandEntry>, effects: Effects) {
            queue.add(Batch(holder, entries))
            if (currentJob == null) startNextLocked(effects)
        }

        fun clearQueuedLocked() {
            queue.clear()
            releaseReservationsLocked()
        }

        fun cancelForSessionLocked(sessionKey: String, effects: Effects) {
            val it = queue.iterator()
            while (it.hasNext()) {
                if (it.next().holder.core.key == sessionKey) it.remove()
            }
            // We do not cancel an in-flight load here: the late result is
            // still subject to the generation check.
            processNextOrCleanupLocked(effects)
        }

        fun bumpGeneration() {
            generation++
        }

        fun cancelSlotLocked(sessionKey: String, invalidation: SlotGeneration, effects: Effects) {
            // Demand is grouped by placement only, so one window update over slots a, b and c on
            // the same placement becomes a SINGLE batch of three entries. Two bugs followed from
            // treating a batch as indivisible:
            //
            //  - Removal required `entries.all { … }` to match the invalidation, so a batch of
            //    [a@1, b@1, c@1] with only a@1 invalidated satisfied no predicate. The stale entry
            //    stayed queued, later won a governor permit, inflated the requested count, and its
            //    ad was loaded and then destroyed on arrival at recordAdmitted — a wasted network
            //    load and a wasted ad, with hard-cap capacity burned while live slots sat deferred.
            //  - The recordDeferred sweep matched on (slotKey, generation) with no session guard,
            //    but `generation` is a PER-SESSION counter. Session B legitimately holds
            //    ("item-0", 1) at the same time as session A, so invalidating A's item-0@1 cleared
            //    inFlight on B's queued slot. B's batch then survived removal on the session-key
            //    mismatch, loaded, and was rejected at recordAdmitted — leaving B's slot Empty.
            //
            // Rebuilding per entry, scoped to the session, fixes both and drops emptied batches.
            val surviving = mutableListOf<Batch>()
            queue.forEach { batch ->
                if (batch.holder.core.key != sessionKey) {
                    surviving += batch
                    return@forEach
                }
                val (invalidated, live) = batch.entries.partition { entry ->
                    entry.key == invalidation.slotKey && entry.generation == invalidation.generation
                }
                invalidated.forEach { batch.holder.core.recordDeferred(it.key, it.generation) }
                if (live.isNotEmpty()) surviving += Batch(batch.holder, live)
            }
            queue.clear()
            queue.addAll(surviving)
            val owners = reservationOwners.values.filter {
                it.sessionKey == sessionKey && it.slotKey == invalidation.slotKey && it.slotGeneration == invalidation.generation
            }
            owners.forEach { owner ->
                reservationOwners.remove(owner.reservation.id)
                activeReservations.removeAll { it.reservation === owner.reservation }
                governor.releaseReservation(owner.reservation)
            }
            // Cancel the in-flight job only when nothing it was loading is wanted any more.
            // A batch covers every slot one window update demanded, so cancelling it because ONE
            // of them left the viewport threw away the siblings' loads as well — they were then
            // deferred and resubmitted, spending a second network request each for slots that had
            // done nothing wrong. The dropped slot has already been removed from
            // `activeReservations` above, so its ad is destroyed on arrival (isPairLiveLocked)
            // whether or not the job keeps running; letting the batch finish costs nothing and
            // saves every sibling still in it. This matters more the deeper a consumer prefetches,
            // because deeper prefetch means larger batches.
            if (owners.isNotEmpty() && activeReservations.isEmpty()) currentJob?.let(effects.cancel::add)
            processNextOrCleanupLocked(effects)
        }

        private fun startNextLocked(effects: Effects) {
            val requested = queue.removeAt(0)
            val maxChunk = if (requested.entries.first().placement.nativeOptions.batching == NativeAdBatching.GoogleOnly) 5 else requested.entries.size
            val candidateEntries = requested.entries.take(maxChunk)
            if (candidateEntries.size < requested.entries.size) {
                queue.add(0, Batch(requested.holder, requested.entries.drop(candidateEntries.size)))
            }
            val grantedPairs = mutableListOf<ReservationSlotPair>()
            candidateEntries.forEach { entry ->
                val decision = governor.reserve(
                    demandClass = entry.demandClass,
                    priority = entry.admittedPriority,
                    count = 1,
                    allowPartial = false,
                )
            // Consume both retired records and cancelled reservations
            // from the decision — they are the platform objects /
            // permits the prior call already accounted for.
                decision.retiredRecordIds.forEach { removeRecordLocked(it, effects) }
                decision.cancelledReservations.forEach { reservation ->
                    reservationOwners.remove(reservation.id)?.let { owner ->
                        schedulers[owner.placementId]?.activeReservations?.removeAll {
                            it.reservation === reservation
                        }
                        sessions[owner.sessionKey]?.core?.recordDeferred(owner.slotKey, owner.slotGeneration)
                    }
                }
                val reservation = decision.reservations.singleOrNull()
                if (reservation == null) {
                    requested.holder.core.recordDeferred(entry.key, entry.generation)
                } else {
                    val pair = ReservationSlotPair(reservation, entry)
                    activeReservations += pair
                    grantedPairs += pair
                    reservationOwners[reservation.id] = ReservationOwner(placementId, requested.holder.core.key, entry.key, entry.generation, reservation)
                }
            }
            val genAtSubmit = generation
            val placement = candidateEntries.first().placement
            // Load the **granted** count, not the original demand. When
            // the governor could only reserve some of the requested
            // permits (visible demand at the hard cap with no eviction
            // room), the platform only sees the granted size — never
            // zero, because reserve with allowPartial=true still
            // surfaces whatever fit.
            val grantedCount = grantedPairs.size
            if (grantedCount == 0) {
                processNextOrCleanupLocked(effects)
                return
            }
            // This call's own grants, not the whole activeReservations list. Result binding is
            // positional against grantedCount, so any pair lingering from an earlier launch would
            // shift the ad-to-slot mapping. Serialisation via currentJob keeps the two equal today,
            // which made that an invariant held by accident; deriving it here makes it structural.
            val launchedPairs = grantedPairs.toList()
            currentJob = scope.launch {
                try {
                    var attempted = false
                    val result = withTimeoutOrNull(placement.timeoutPolicy.loadTimeout) {
                        retryAdLoad(placement.retryPolicy, { it.isRetryableLoadFailure() }) {
                            if (!canRequestAds()) AdAttemptResult.Failure(AdError.consentRequired())
                            else if (attempted && !isGenerationCurrent(genAtSubmit)) AdAttemptResult.Failure(AdError.message("Native ad load was invalidated."))
                            else {
                                attempted = true
                                try {
                                    platform.load(placement, grantedCount, genAtSubmit)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    AdAttemptResult.Failure(
                                        AdError.message(
                                            failure.message ?: "Native ad platform load failed unexpectedly.",
                                        ),
                                    )
                                }
                            }
                        }
                    } ?: AdAttemptResult.Failure(AdError.message("Native ad load timed out after ${placement.timeoutPolicy.loadTimeout}."))
                    if (result is AdAttemptResult.Success) {
                        try {
                            val liveAtBinding = lock.withLock {
                                launchedPairs.map(::isPairLiveLocked)
                            }
                            launchedPairs.forEachIndexed { index, pair ->
                                val ad = result.value.ads.getOrNull(index) ?: return@forEachIndexed
                                if (!liveAtBinding[index]) return@forEachIndexed
                                val recordId = pair.reservation.id
                                val instanceId = recordId.value.toString()
                                platform.bindEvents(ad, instanceId) { event -> routeEvent(recordId, instanceId, event) }
                            }
                        } catch (failure: Throwable) {
                            handleBindingFailure(launchedPairs, genAtSubmit, result.value.ads, failure)
                            return@launch
                        }
                    }
                    handleResult(launchedPairs, genAtSubmit, result)
                } catch (cancelled: CancellationException) {
                    handleCancelled(launchedPairs, genAtSubmit)
                    throw cancelled
                }
            }
        }

        private fun isGenerationCurrent(submittedGen: Long): Boolean = lock.withLock {
            generation == submittedGen && schedulers[placementId] === this
        }

        private fun handleResult(
            launchedPairs: List<ReservationSlotPair>,
            submittedGen: Long,
            result: AdAttemptResult<NativeAdPlatformBatch<A>>,
        ) {
            val effects = lock.withLock {
                val effects = Effects()
            currentJob = null
            val currentGen = generation
            val stale = submittedGen != currentGen
            if (stale) {
                if (result is AdAttemptResult.Success) {
                    effects.destroy += result.value.ads
                }
                releaseReservationsLocked()
                processNextOrCleanupLocked(effects)
                return@withLock effects
            }
            when (result) {
                is AdAttemptResult.Success -> handleSuccess(launchedPairs, result.value, effects)
                is AdAttemptResult.Failure -> handleFailure(launchedPairs, result.error)
            }
            processNextOrCleanupLocked(effects)
            effects
            }
            effects.run()
        }

        private fun handleCancelled(launchedPairs: List<ReservationSlotPair>, submittedGen: Long) {
            val effects = lock.withLock {
                val effects = Effects()
                // Siblings of an invalidated slot are still wanted. cancelSlotLocked cancels the
                // whole in-flight job to drop ONE slot, so every other slot in that batch loses its
                // load through no fault of its own. Marking them Deferred and stopping there left
                // them Empty until something external re-drove demand (updateWindow / setMounted /
                // expireSlot) — and since a deferred slot holds no record, the TTL sweep never
                // touches it, so an unchanged viewport left them empty indefinitely.
                val resubmit = mutableMapOf<String, MutableList<SlotDemandEntry>>()
                if (generation == submittedGen) {
                    livePairs(launchedPairs).forEach { pair ->
                        val owner = reservationOwners[pair.reservation.id] ?: return@forEach
                        val holder = sessions[owner.sessionKey]
                        // A closed session is already removed from `sessions`, so the null check
                        // covers closure; `active` covers a deactivated one.
                        if (holder != null && holder.active) {
                            // Deliberately NOT recordDeferred here. recordAdmitted requires
                            // entry.inFlight == generation, and recordDeferred nulls it — so
                            // deferring first and re-queueing after would guarantee the ad we just
                            // asked for is rejected on arrival. Leaving the slot in flight is what
                            // makes the resubmission actually deliver.
                            resubmit.getOrPut(owner.sessionKey) { mutableListOf() } += pair.entry
                        } else {
                            // Nothing can carry this demand any more; settle it so the slot is not
                            // left believing a load is still in flight.
                            holder?.core?.recordDeferred(owner.slotKey, owner.slotGeneration)
                        }
                    }
                }
                currentJob = null
                releaseReservationsLocked()
                // Re-queue before processNextOrCleanupLocked, which is what starts the next batch —
                // and which would otherwise delete this scheduler outright once the queue is empty.
                resubmit.forEach { (sessionKey, entries) ->
                    sessions[sessionKey]?.let { holder -> queue.add(Batch(holder, entries)) }
                }
                processNextOrCleanupLocked(effects)
                effects
            }
            effects.run()
        }

        private fun handleBindingFailure(launchedPairs: List<ReservationSlotPair>, submittedGen: Long, ads: List<A>, failure: Throwable) {
            val effects = lock.withLock {
                val effects = Effects()
                if (generation == submittedGen) {
                    livePairs(launchedPairs).forEach { pair ->
                        pair.entry.let { entry ->
                            reservationOwners[pair.reservation.id]?.let { owner ->
                                sessions[owner.sessionKey]?.core?.recordFailed(
                                    owner.slotKey,
                                    AdError.message(failure.message ?: "Native ad event binding failed."),
                                    entry.generation,
                                )
                            }
                        }
                    }
                }
                effects.destroy += ads
                currentJob = null
                releaseReservationsLocked()
                processNextOrCleanupLocked(effects)
                effects
            }
            effects.run()
        }

        private fun handleSuccess(launchedPairs: List<ReservationSlotPair>, result: NativeAdPlatformBatch<A>, effects: Effects) {
            val ads = result.ads
            launchedPairs.forEachIndexed { index, pair ->
                val ad = ads.getOrNull(index)
                if (ad == null) {
                    settleUnfilledPair(pair, result.unfilledError, effects)
                    return@forEachIndexed
                }
                if (!isPairLiveLocked(pair)) {
                    effects.destroy += ad
                    return@forEachIndexed
                }
                val reservation = pair.reservation
                val entry = pair.entry
                val owner = reservationOwners.remove(reservation.id)
                try {
                    val recordId = governor.admit(reservation)
                    activeRecordIds.add(recordId)
                    val admittedOwner = owner?.takeIf {
                        it.reservation === reservation && it.slotGeneration == entry.generation
                    }
                    val admitted = admittedOwner?.let {
                        sessions[it.sessionKey]?.core?.recordAdmitted(
                            it.slotKey,
                            recordId,
                            platform.mediaInfo(ad),
                            entry.generation,
                        )
                    } == true
                    if (!admitted) {
                        governor.retire(recordId)
                        effects.destroy += ad
                        return@forEachIndexed
                    }
                    records[recordId] = RecordEntry(
                        ad = ad,
                        placementId = placementId,
                        sessionKey = requireNotNull(admittedOwner).sessionKey,
                        slotKey = entry.key,
                        generation = entry.generation,
                        placement = entry.placement,
                        mediaInfo = platform.mediaInfo(ad),
                        adInstanceId = recordId.value.toString(),
                        loadedAt = nowLocked(),
                    )
                } catch (e: IllegalStateException) {
                    reservationOwners.remove(reservation.id)
                    effects.destroy += ad
                }
            }
            ads.drop(launchedPairs.size).forEach { effects.destroy += it }
            activeReservations.removeAll { active -> launchedPairs.any { it.reservation === active.reservation } }
        }

        private fun settleUnfilledPair(
            pair: ReservationSlotPair,
            suppliedError: AdError?,
            effects: Effects,
        ) {
            if (!isPairLiveLocked(pair)) return
            val reservation = pair.reservation
            val owner = reservationOwners[reservation.id]
            val error = suppliedError ?: AdError(
                code = INTERNAL_LOAD_ERROR_CODE,
                message = "Native ad batch completed without filling every reserved slot.",
            )
            if (owner?.reservation === reservation && owner.slotGeneration == pair.entry.generation) {
                sessions[owner.sessionKey]?.core?.recordFailed(owner.slotKey, error, pair.entry.generation)
            }
            reservationOwners.remove(reservation.id)
            try {
                governor.releaseReservation(reservation)
            } catch (_: IllegalStateException) {
                // The governor already settled this exact token.
            }
        }

        private fun handleFailure(launchedPairs: List<ReservationSlotPair>, error: AdError) {
            livePairs(launchedPairs).forEach { pair ->
                reservationOwners[pair.reservation.id]?.let { owner ->
                    sessions[owner.sessionKey]?.core?.recordFailed(owner.slotKey, error, pair.entry.generation)
                }
            }
            releaseReservationsLocked()
        }

        private fun releaseReservationsLocked() {
            for (pair in activeReservations) {
                val res = pair.reservation
                reservationOwners.remove(res.id)
                try {
                    governor.releaseReservation(res)
                } catch (_: IllegalStateException) {
                    // Already gone.
                }
            }
            activeReservations.clear()
        }

        /** Only the token identity originally granted to this launched slot may settle it. */
        private fun livePairs(launchedPairs: List<ReservationSlotPair>): List<ReservationSlotPair> =
            launchedPairs.filter(::isPairLiveLocked)

        private fun isPairLiveLocked(pair: ReservationSlotPair): Boolean =
            reservationOwners[pair.reservation.id]?.reservation === pair.reservation &&
                activeReservations.any { it.reservation === pair.reservation }

        private fun processNextOrCleanupLocked(effects: Effects) {
            if (queue.isNotEmpty() && currentJob == null) {
                startNextLocked(effects)
            } else if (activeRecordIds.isEmpty() && activeReservations.isEmpty() && currentJob == null) {
                schedulers.remove(placementId)
            }
        }

        fun isIdleLocked(): Boolean =
            queue.isEmpty() && activeRecordIds.isEmpty() && activeReservations.isEmpty() && currentJob == null
    }
}
