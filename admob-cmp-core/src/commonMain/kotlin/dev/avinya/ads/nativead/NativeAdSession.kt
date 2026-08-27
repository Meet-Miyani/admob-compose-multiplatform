package dev.avinya.ads.nativead

import dev.avinya.ads.AdError
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import kotlinx.coroutines.flow.StateFlow


/**
 * A single feed slot inside a [NativeAdSession].
 *
 * The [key] must be stable for the lifetime of the slot — a feed item is
 * re-keyed by index on every recomposition, so passing an index here will
 * re-load the slot whenever an item is recycled. Stable keys (article id,
 * placement-prefixed slug) are what allow the session to reuse the same
 * loaded ad across detach/reattach, tab changes, and configuration changes.
 *
 * The [placement] must be a [AdFormat.Native] placement. Using a non-native
 * placement with a [NativeAdSession] throws — native sessions are responsible
 * for the platform-specific GMA request shape, and reusing an interstitial
 * placement here would silently produce the wrong ad type.
 */
public data class NativeAdSlot(
    val key: String,
    val placement: AdPlacement,
) {
    init {
        require(key.isNotBlank()) { "NativeAdSlot.key must not be blank." }
        require(placement.format == AdFormat.Native) {
            "NativeAdSlot '${key}' has placement '${placement.id}' with format ${placement.format} " +
                "but NativeAdSession only supports Native placements."
        }
    }
}

/**
 * Snapshot of which slots a [NativeAdSession] should care about right now.
 *
 * The session ranks slots in order: every entry of [visible] first, then
 * [prefetchAhead], then [retainBehind]. Only the first
 * [NativeAdSessionPolicy.maxRetainedAds] ranked slots are admitted for
 * loading; the rest stay [NativeAdSlotState.Empty] until they enter the
 * window.
 *
 * A slot key may appear in more than one band — for example, the same key
 * in both [visible] and [prefetchAhead] as the viewport is updated. The
 * session core dedupes on first occurrence (visible wins over prefetchAhead,
 * which wins over retainBehind) so the consumer can report its raw viewport
 * without pre-collapsing the lists. The window **does** require that any key
 * reported in multiple bands use the same [NativeAdSlot.placement] —
 * reporting the same key with two different placements is a misconfiguration
 * and throws.
 *
 * Use [prefetchAhead] to warm the next item the user is about to scroll
 * into, and [retainBehind] to keep a small ring of recently-visible ads
 * alive for fast scroll-back. The session is free to evict anything outside
 * [visible] when memory pressure arrives.
 */
public data class NativeAdWindow(
    val visible: List<NativeAdSlot>,
    val retainBehind: List<NativeAdSlot> = emptyList(),
    val prefetchAhead: List<NativeAdSlot> = emptyList(),
) {
    init {
        require(visible.none { it.key.isBlank() }) { "NativeAdWindow.visible contains a blank slot key." }
        require(retainBehind.none { it.key.isBlank() }) { "NativeAdWindow.retainBehind contains a blank slot key." }
        require(prefetchAhead.none { it.key.isBlank() }) { "NativeAdWindow.prefetchAhead contains a blank slot key." }
        val seen = mutableMapOf<String, NativeAdSlot>()
        for (band in listOf(visible, prefetchAhead, retainBehind)) {
            for (slot in band) {
                val prior = seen.put(slot.key, slot)
                if (prior != null && prior.placement != slot.placement) {
                    throw IllegalArgumentException(
                        "NativeAdWindow reports slot key '${slot.key}' with two different placements: " +
                            "'${prior.placement.id}' and '${slot.placement.id}'. " +
                            "Reuse the same AdPlacement instance for the same key across viewport updates."
                    )
                }
            }
        }
    }
}

/**
 * State of a single slot inside a [NativeAdSession].
 *
 * The transition graph is governed by the session core, not by callers:
 *
 * - [Empty] — the session is unaware of the slot, or the slot has been
 *   fully retired. Compose should render nothing.
 * - [Loading] — a load is in flight. Compose should render a placeholder.
 * - [Ready] — a loaded ad is available but not currently attached to a
 *   renderer. Compose may render the ad; the session will mark the slot
 *   [Mounted] once it observes the attach.
 * - [Mounted] — the ad is attached to a live renderer. It will not be
 *   evicted under any memory policy short of destruction.
 * - [Retained] — the slot has a live ad but it is currently outside the
 *   viewport. The session may evict it under memory pressure.
 * - [Failed] — the last load attempt for this slot failed. The session
 *   will **not** retry while the slot stays in the window, however many
 *   window updates arrive: a viewport reports once per scroll frame, so
 *   retrying on window update alone is a request loop rather than a
 *   retry. The slot is requested again once it leaves the window and
 *   comes back. Retryable transport failures have already been retried,
 *   under the placement's [dev.avinya.ads.AdRetryPolicy], before the
 *   failure ever reaches this state.
 *
 * [Ready], [Mounted], and [Retained] carry the snapshot of [NativeMediaInfo]
 * for the current ad, which Compose uses to lay out the asset region. It
 * may be `null` if the SDK has not yet resolved media for the ad.
 */
public sealed interface NativeAdSlotState {
    public data object Empty : NativeAdSlotState
    public data object Loading : NativeAdSlotState
    public data class Ready(val mediaInfo: NativeMediaInfo?) : NativeAdSlotState
    public data class Mounted(val mediaInfo: NativeMediaInfo?) : NativeAdSlotState
    public data class Retained(val mediaInfo: NativeMediaInfo?) : NativeAdSlotState
    public data class Failed(val error: AdError) : NativeAdSlotState
}

/**
 * Snapshot of a [NativeAdSession]'s slot map.
 *
 * [active] is true between the first [NativeAdSession.updateWindow] call
 * and [NativeAdSession.deactivate] / [NativeAdSession.close]. While inactive,
 * the session retains up to `min(NativeAdMemoryPolicy.inactiveSessionLimit,
 * policy.maxRetainedAds)` ranked anchor ads and discards the rest; the
 * default policy keeps one anchor, so most consumers see a single-record
 * inactive session unless they raise the cap deliberately.
 *
 * [slots] is the live map of slot key to state. The session removes entries
 * for slots that have left the window and have no retained/loaded record,
 * so consumers that key their composables off [slots] will not see unbounded
 * growth as the user scrolls.
 */
public data class NativeAdSessionState(
    val active: Boolean,
    val slots: Map<String, NativeAdSlotState>,
)

/**
 * Process-wide snapshot of the [NativeAdManager].
 *
 * [loadedAds] is the number of native ad objects currently alive in the
 * manager (across all sessions and platforms). [reservedLoads] is the number
 * of in-flight reservations that have not yet been admitted; together they
 * are bounded by [hardLimit] under all races, partial batches, and
 * cancellation. [activeSessions] and [inactiveSessions] report the
 * composition of the session registry.
 */
public data class NativeAdManagerState(
    val loadedAds: Int,
    val reservedLoads: Int,
    val activeSessions: Int,
    val inactiveSessions: Int,
    val hardLimit: Int,
)

/**
 * A named, viewport-aware native-ad session owned by the [NativeAdManager].
 *
 * One session represents one feed (or one screen of feed-shaped content).
 * The session owns the slot map, the priority ranking of [NativeAdWindow]
 * updates, the lifecycle of each slot's [NativeAdSlotState], and the
 * coordination with the manager's global admission governor. The session
 * does **not** own the platform ad object — the manager does — and does
 * **not** own the Compose renderer; it merely publishes state for the
 * renderer to observe.
 *
 * Sessions are obtained from [NativeAdManager.session]. Re-using a [key]
 * returns the existing session, but the [policy] argument is verified on
 * every call: a session that already exists for [key] with a different
 * policy causes [NativeAdManager.session] to throw. Closing a session via
 * [close] retires every record it owns; subsequent calls to [close] are
 * no-ops.
 *
 * Declared stable in `admob-cmp-compose/compose_compiler_config.conf` (this type is compiled
 * without the Compose plugin and would otherwise carry no stability metadata at all). An
 * implementation must honor that promise: [key] and [policy] never change identity after
 * construction, and every other observable change — slot admission, activation, closure —
 * must be visible only through [state]. Adding a plain `var` here, or a method whose effect
 * isn't reflected in [state], would silently break recomposition for anything holding this
 * session across a `remember`.
 */
public interface NativeAdSession {
    /** Stable session key. Identifies the session inside the [NativeAdManager]. */
    public val key: String

    /** Policy this session was created with. Immutable for the session's lifetime. */
    public val policy: NativeAdSessionPolicy

    /** Current slot map and active flag. */
    public val state: StateFlow<NativeAdSessionState>

    /**
     * Reports the new viewport window to the session. The session ranks the
     * contained slots, admits demand for the highest-priority ones under
     * [policy.maxRetainedAds], and starts loading. Calling with the same
     * window repeatedly is a no-op apart from slot-state publication.
     *
     * @throws IllegalArgumentException if the window contains a slot key
     *   that was previously admitted with a different [AdPlacement].
     */
    public fun updateWindow(window: NativeAdWindow)

    /**
     * Marks the session as inactive: the session retains up to
     * `min(NativeAdMemoryPolicy.inactiveSessionLimit, policy.maxRetainedAds)`
     * ranked anchors, preferring mounted and visible slots, falling back to
     * the most recently accessed ready record if there is no visible slot.
     * All other records are retired. Compose typically calls this from a
     * `DisposableEffect` when the host screen leaves composition. The
     * default policy retains a single anchor; consumers that want a wider
     * inactive ring should raise [NativeAdMemoryPolicy.inactiveSessionLimit]
     * deliberately.
     */
    public fun deactivate()

    /**
     * Retires every record the session owns and removes it from the
     * manager's registry. Idempotent — calling [close] on an already-closed
     * session is a no-op. After [close] the session must not be reused.
     */
    public fun close()
}

/**
 * Process-wide native-ad coordinator.
 *
 * The manager owns exactly one admission governor and one set of per-placement
 * load schedulers. It serializes work per placement, reserves capacity before
 * invoking the platform, retries failed loads under the placement's
 * [AdRetryPolicy], enforces the 1-hour native-ad TTL, evicts under memory
 * pressure in the documented order (speculative prefetch → inactive anchor →
 * active retained-behind → active ready-ahead), and reaps inactive sessions
 * after [NativeAdMemoryPolicy.inactiveSessionTtl]. Mounted ads are never
 * eviction candidates under any policy.
 *
 * Sessions are obtained via [session] and may be closed via [closeSession]
 * or collectively via [clear]. [clear] also destroys every ad the manager
 * owns; the consumer is responsible for re-priming their feeds.
 */
public interface NativeAdManager {
    /**
     * Memory policy the manager is operating under. Before initialization
     * succeeds, reports the default [NativeAdMemoryPolicy].
     */
    public val policy: NativeAdMemoryPolicy

    /** Live process-wide state snapshot. */
    public val state: StateFlow<NativeAdManagerState>

    /**
     * Returns the session for [key], creating it on first access.
     *
     * Before [dev.avinya.ads.AdManager.initialize] succeeds, returns an inert handle
     * that records its viewport window and materialises once initialization
     * completes.
     *
     * Re-using a key returns the existing session. The [policy] argument is
     * verified on every call: if a session already exists for [key] and the
     * supplied [policy] differs from the one in force, this method throws
     * rather than silently letting the consumer's later call change the
     * session's retention contract under it. Use [closeSession] to free the
     * key before creating a new session with a different policy.
     *
     * @throws IllegalArgumentException if [key] is blank.
     * @throws IllegalStateException if the session registry already holds
     *   [NativeAdMemoryPolicy.maxSessionRecords] live sessions, or if a
     *   session already exists for [key] with a different [policy].
     */
    public fun session(
        key: String,
        policy: NativeAdSessionPolicy = NativeAdSessionPolicy(),
    ): NativeAdSession

    /**
     * Retires every record the session owns and removes it from the
     * registry. No-op if no session is registered under [key].
     */
    public fun closeSession(key: String)

    /**
     * Closes every session, destroys every loaded ad, and releases every
     * reservation. Safe to call repeatedly. Compose typically calls this
     * from a process-lifecycle effect or a debug reset.
     */
    public fun clear()
}
