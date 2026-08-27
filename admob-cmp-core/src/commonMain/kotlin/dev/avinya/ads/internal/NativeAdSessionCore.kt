@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdError
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSessionState
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import dev.avinya.ads.nativead.NativeMediaInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class NativeAdBand { Visible, PrefetchAhead, RetainBehind, Out }

internal data class SlotGeneration(val slotKey: String, val generation: Long)

internal data class SlotDemandEntry(
    val key: String,
    val placement: AdPlacement,
    val generation: Long,
    val band: NativeAdBand,
    val demandClass: NativeAdDemandClass,
    val admittedPriority: NativeAdPriority,
)

internal data class RecordReclassification(
    val recordId: NativeAdRecordId,
    val priority: NativeAdPriority,
)

/** All effects which the coordinator must apply after mutating a session. */
internal data class NativeAdSessionMutation(
    val demands: List<SlotDemandEntry> = emptyList(),
    val retireRecordIds: List<NativeAdRecordId> = emptyList(),
    val invalidateLoads: List<SlotGeneration> = emptyList(),
    val reclassifications: List<RecordReclassification> = emptyList(),
) : List<NativeAdRecordId> by retireRecordIds {
}

/**
 * Pure per-session state machine. The coordinator serializes calls and owns
 * governor/platform side effects; this class only records ownership and emits
 * the work needed to reconcile it.
 */
internal class NativeAdSessionCore(
    val key: String,
    val policy: NativeAdSessionPolicy,
    private val inactiveRetentionLimit: Int,
    private val published: MutableStateFlow<NativeAdSessionState> =
        MutableStateFlow(NativeAdSessionState(active = false, slots = emptyMap())),
) {
    private class SlotEntry(val placement: AdPlacement) {
        var band = NativeAdBand.Out
        var viewportRank = -1
        var generation = 0L
        var inFlight: Long? = null
        var recordId: NativeAdRecordId? = null
        var mediaInfo: NativeMediaInfo? = null
        var mounted = false
        var lastAccess = 0L
        var lastError: AdError? = null
        var desired = false
    }

    private val slots = mutableMapOf<String, SlotEntry>()
    private var active = false
    private var closed = false
    private var nextGeneration = 1L
    private var nextAccess = 1L
    val state: StateFlow<NativeAdSessionState> = published.asStateFlow()

    init {
        publish()
    }

    internal fun publishesInto(flow: MutableStateFlow<NativeAdSessionState>): Boolean = published === flow

    fun updateWindow(window: NativeAdWindow): NativeAdSessionMutation {
        if (closed) return NativeAdSessionMutation()
        validatePlacements(window)
        active = true
        slots.values.forEach { it.desired = false; it.viewportRank = -1 }
        val ranked = rank(window).take(policy.maxRetainedAds)
        val reclassified = mutableListOf<RecordReclassification>()
        ranked.forEachIndexed { rank, (band, slot) ->
            val entry = slots.getOrPut(slot.key) { SlotEntry(slot.placement) }
            val oldBand = entry.band
            entry.band = band; entry.viewportRank = rank; entry.desired = true
            if (band == NativeAdBand.Visible) touch(entry)
            entry.recordId?.takeIf { oldBand != band }?.let { reclassified += RecordReclassification(it, priorityFor(band)) }
        }
        val retire = mutableListOf<NativeAdRecordId>()
        val invalidate = mutableListOf<SlotGeneration>()
        for ((slotKey, entry) in slots.toMap()) if (!entry.desired) {
            entry.band = NativeAdBand.Out
            when {
                entry.recordId != null && entry.mounted -> Unit
                entry.recordId != null -> { retire += entry.recordId!!; slots.remove(slotKey) }
                entry.inFlight != null -> { invalidate += SlotGeneration(slotKey, entry.inFlight!!); slots.remove(slotKey) }
                else -> slots.remove(slotKey)
            }
        }
        val demands = reconcileDemands()
        publish()
        return NativeAdSessionMutation(demands, retire, invalidate, reclassified)
    }

    fun recordAdmitted(slotKey: String, recordId: NativeAdRecordId, mediaInfo: NativeMediaInfo?, generation: Long): Boolean {
        if (closed) return false
        val entry = slots[slotKey] ?: return false
        if (entry.inFlight != generation || !entry.desired || !active) return false
        entry.recordId = recordId; entry.inFlight = null; entry.mediaInfo = mediaInfo; entry.lastError = null; touch(entry)
        publish(); return true
    }

    fun recordAdmitted(slotKey: String, recordId: NativeAdRecordId, generation: Long): Boolean =
        recordAdmitted(slotKey, recordId, null, generation)

    /**
     * Repairs session ownership after the coordinator retires a record for a
     * process-wide reason such as memory pressure or capacity eviction.
     * Reload is intentionally deferred until the next window reconciliation;
     * immediately reloading here would fight the memory governor that evicted it.
     */
    fun recordEvicted(slotKey: String, recordId: NativeAdRecordId): Boolean {
        if (closed) return false
        val entry = slots[slotKey] ?: return false
        if (entry.recordId != recordId) return false
        entry.recordId = null
        entry.mediaInfo = null
        entry.mounted = false
        entry.lastError = null
        if (!active || !entry.desired) slots.remove(slotKey)
        publish()
        return true
    }

    fun recordDeferred(slotKey: String, generation: Long): Boolean = settle(slotKey, generation, null)

    fun recordFailed(slotKey: String, error: AdError, generation: Long): Boolean =
        settle(slotKey, generation, error)

    private fun settle(slotKey: String, generation: Long, error: AdError?): Boolean {
        if (closed) return false
        val entry = slots[slotKey] ?: return false
        if (entry.inFlight != generation) return false
        entry.inFlight = null; entry.lastError = error; publish(); return true
    }

    fun setMounted(slotKey: String, recordId: NativeAdRecordId, mounted: Boolean): NativeAdSessionMutation {
        if (closed) return NativeAdSessionMutation()
        val entry = slots[slotKey] ?: return NativeAdSessionMutation()
        if (entry.recordId != recordId) return NativeAdSessionMutation()
        entry.mounted = mounted
        if (mounted) { touch(entry); publish(); return NativeAdSessionMutation() }
        if (entry.band != NativeAdBand.Out) { publish(); return NativeAdSessionMutation() }
        slots.remove(slotKey)
        val demands = if (active) reconcileDemands() else emptyList()
        publish()
        return NativeAdSessionMutation(demands = demands, retireRecordIds = listOf(recordId))
    }

    fun deactivate(): NativeAdSessionMutation {
        if (closed) return NativeAdSessionMutation()
        active = false
        val recordEntries = slots.entries.filter { it.value.recordId != null }
        val limit = minOf(inactiveRetentionLimit, policy.maxRetainedAds)
        val visible = recordEntries.filter { it.value.band == NativeAdBand.Visible }
        val ordered = buildList {
            visible.maxByOrNull { it.value.viewportRank }?.let(::add)
            visible.filter { it.value.mounted }.sortedByDescending { it.value.viewportRank }.forEach { if (it !in this) add(it) }
            visible.sortedByDescending { it.value.viewportRank }.forEach { if (it !in this) add(it) }
            recordEntries.sortedByDescending { it.value.lastAccess }.forEach { if (it !in this) add(it) }
        }
        val anchors = ordered.take(limit).map { it.key }.toSet()
        val retire = mutableListOf<NativeAdRecordId>(); val invalidate = mutableListOf<SlotGeneration>(); val reclass = mutableListOf<RecordReclassification>()
        for ((slotKey, entry) in slots.toMap()) {
            if (entry.inFlight != null) { invalidate += SlotGeneration(slotKey, entry.inFlight!!); entry.inFlight = null }
            val id = entry.recordId
            when {
                id != null && slotKey in anchors -> { entry.band = NativeAdBand.Out; entry.mounted = false; reclass += RecordReclassification(id, NativeAdPriority.InactiveAnchor) }
                id != null -> { retire += id; slots.remove(slotKey) }
                else -> slots.remove(slotKey)
            }
        }
        publish(); return NativeAdSessionMutation(retireRecordIds = retire, invalidateLoads = invalidate, reclassifications = reclass)
    }

    fun expireSlot(slotKey: String): NativeAdSessionMutation {
        if (closed) return NativeAdSessionMutation()
        val entry = slots[slotKey] ?: return NativeAdSessionMutation()
        val retire = entry.recordId?.let(::listOf).orEmpty()
        entry.recordId = null; entry.mediaInfo = null; entry.mounted = false; entry.lastError = null
        val demand = if (active && entry.desired) reconcileDemands() else emptyList()
        if (!active || !entry.desired) slots.remove(slotKey)
        publish(); return NativeAdSessionMutation(demands = demand, retireRecordIds = retire)
    }

    fun close(): NativeAdSessionMutation {
        if (closed) return NativeAdSessionMutation()
        closed = true; active = false
        val retire = slots.values.mapNotNull { it.recordId }
        val invalidate = slots.mapNotNull { (key, entry) -> entry.inFlight?.let { SlotGeneration(key, it) } }
        slots.clear(); publish(); return NativeAdSessionMutation(retireRecordIds = retire, invalidateLoads = invalidate)
    }

    /** Drops current inventory without retiring this logical session. */
    fun clear(): NativeAdSessionMutation {
        if (closed) return NativeAdSessionMutation()
        val retire = slots.values.mapNotNull { it.recordId }
        val invalidate = slots.mapNotNull { (key, entry) -> entry.inFlight?.let { SlotGeneration(key, it) } }
        slots.clear()
        publish()
        return NativeAdSessionMutation(retireRecordIds = retire, invalidateLoads = invalidate)
    }

    fun recordIdFor(slotKey: String): NativeAdRecordId? = slots[slotKey]?.recordId

    /** Current slot generation for coordinator identity validation. */
    fun slotGenerationFor(slotKey: String): Long? = slots[slotKey]?.generation

    /**
     * Issues load demand for every desired slot that has neither a record nor a load in flight,
     * up to [NativeAdSessionPolicy.maxRetainedAds].
     *
     * A slot whose last attempt **failed** is deliberately skipped. Window updates arrive once per
     * viewport change — during a scroll, once per frame — and a failed slot otherwise satisfies
     * every condition here on the very next one, so it would be re-requested at frame rate for as
     * long as it stayed on screen. Offline, or against a placement that simply is not filling,
     * that is a request loop rather than a retry.
     *
     * Skipping does not strand the slot, because the states that deserve another attempt do not
     * set [SlotEntry.lastError] in the first place:
     *
     * - a **deferred** slot (the governor had no capacity) settles with a null error, so it is
     *   retried on the next reconciliation, which is exactly what should happen;
     * - an **evicted** record (memory pressure) clears the error and reloads;
     * - and a genuinely failed slot recovers as soon as it leaves the window and comes back, at
     *   which point it is pruned and re-created fresh.
     *
     * Transient failures are already handled a layer down: the coordinator retries retryable load
     * errors under the placement's own [dev.avinya.ads.AdRetryPolicy] before ever reporting one
     * here, so by the time an error reaches this entry those attempts are spent.
     */
    private fun reconcileDemands(): List<SlotDemandEntry> {
        if (!active) return emptyList()
        var occupied = slots.values.count { it.recordId != null || it.inFlight != null }
        val demands = mutableListOf<SlotDemandEntry>()
        for ((slotKey, entry) in slots.entries.sortedBy { it.value.viewportRank }) {
            if (!entry.desired || entry.lastError != null || entry.recordId != null ||
                entry.inFlight != null || occupied >= policy.maxRetainedAds
            ) continue
            val generation = nextGeneration++
            entry.generation = generation; entry.inFlight = generation; entry.lastError = null; occupied++
            demands += SlotDemandEntry(slotKey, entry.placement, generation, entry.band, demandFor(entry.band), priorityFor(entry.band))
        }
        return demands
    }

    private fun rank(window: NativeAdWindow): List<Pair<NativeAdBand, NativeAdSlot>> {
        val seen = mutableSetOf<String>(); val result = mutableListOf<Pair<NativeAdBand, NativeAdSlot>>()
        listOf(NativeAdBand.Visible to window.visible, NativeAdBand.PrefetchAhead to window.prefetchAhead, NativeAdBand.RetainBehind to window.retainBehind).forEach { (band, source) -> source.forEach { if (seen.add(it.key)) result += band to it } }
        return result
    }

    private fun validatePlacements(window: NativeAdWindow) {
        val reported = mutableMapOf<String, AdPlacement>()
        listOf(window.visible, window.prefetchAhead, window.retainBehind).forEach { band ->
            band.forEach { slot ->
                reported[slot.key]?.let { previous ->
                    require(previous == slot.placement) {
                        "NativeAdSession '$key': slot '${slot.key}' was reported with conflicting placements."
                    }
                }
                reported[slot.key] = slot.placement
                slots[slot.key]?.let { existing ->
                    require(existing.placement == slot.placement) {
                        "NativeAdSession '$key': slot '${slot.key}' placement changed."
                    }
                }
            }
        }
    }
    private fun touch(entry: SlotEntry) { entry.lastAccess = nextAccess++ }
    private fun demandFor(band: NativeAdBand) = if (band == NativeAdBand.Visible) NativeAdDemandClass.Visible else NativeAdDemandClass.Speculative
    private fun priorityFor(band: NativeAdBand) = when (band) { NativeAdBand.Visible -> NativeAdPriority.ActiveReadyAhead; NativeAdBand.PrefetchAhead -> NativeAdPriority.Speculative; NativeAdBand.RetainBehind -> NativeAdPriority.ActiveRetainedBehind; NativeAdBand.Out -> NativeAdPriority.InactiveAnchor }
    private fun publish() { published.value = NativeAdSessionState(active, slots.mapValues { (_, entry) -> stateFor(entry) }) }
    private fun stateFor(entry: SlotEntry): NativeAdSlotState = when {
        entry.lastError != null -> NativeAdSlotState.Failed(entry.lastError!!)
        entry.inFlight != null -> NativeAdSlotState.Loading
        entry.recordId == null -> NativeAdSlotState.Empty
        entry.band == NativeAdBand.Visible && entry.mounted -> NativeAdSlotState.Mounted(entry.mediaInfo)
        entry.band == NativeAdBand.Visible -> NativeAdSlotState.Ready(entry.mediaInfo)
        else -> NativeAdSlotState.Retained(entry.mediaInfo)
    }
}
