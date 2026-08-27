package dev.avinya.ads.nativead

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes


/**
 * Process-wide capacity and retention policy for the native-ad manager.
 *
 * The [softLimit] caps **speculative** loading — prefetch-ahead, retain-behind
 * warm-ups, and backfill that the session core has not yet been asked for. It
 * is the steady-state goal: the manager will not speculatively exceed it.
 * Visible demand, in contrast, may push the manager past the soft limit, up to
 * the [hardLimit], because the user is actually looking at those ads and the
 * cost of failing to render is worse than the cost of holding the record.
 *
 * [hardLimit] is the absolute upper bound — the manager will refuse a new
 * reservation (loading or speculative prefetch) before it is exceeded, even
 * under race conditions, partial-batch admission, or cancellation. [hardLimit]
 * counts loaded ads **plus** in-flight reservations, so reservation bookkeeping
 * must release unused slots before any terminal publish.
 *
 * Trimming under memory pressure follows the [softLimit]:
 * - **Moderate** pressure trims non-mounted records toward the soft limit,
 *   keeping the highest-priority retained and mounted ads.
 * - **Critical** pressure retains mounted ads only.
 *
 * [inactiveSessionLimit] caps how many ads any single inactive session may
 * retain as anchors while the user is on another tab. The session further caps
 * this at its own [NativeAdSessionPolicy.maxRetainedAds]. [maxInactiveSessions]
 * and [maxSessionRecords] bound the size of the session registry; an inactive
 * session whose metadata is older than [inactiveSessionTtl] is reaped first,
 * and the least-recently-used inactive session is evicted when the registry
 * is full.
 *
 * Default values match the SDK's documented behavior. Overriding them is
 * opt-in and must be deliberate: a higher [hardLimit] raises the worst-case
 * memory footprint across the process, and the platform memory-pressure
 * signal still remains authoritative for critical trim.
 */
public data class NativeAdMemoryPolicy(
    val softLimit: Int = 4,
    val hardLimit: Int = DEFAULT_HARD_LIMIT,
    val inactiveSessionLimit: Int = 1,
    val maxInactiveSessions: Int = 32,
    val maxSessionRecords: Int = DEFAULT_MAX_SESSION_RECORDS,
    val inactiveSessionTtl: Duration = 30.minutes,
) {
    public companion object {
        /** [hardLimit]'s default, exposed so callers reading only the default need not allocate a policy. */
        public const val DEFAULT_HARD_LIMIT: Int = 6
        /** [maxSessionRecords]'s default, exposed so callers reading only the default need not allocate a policy. */
        public const val DEFAULT_MAX_SESSION_RECORDS: Int = 64
    }

    init {
        require(softLimit > 0) { "NativeAdMemoryPolicy.softLimit must be positive, was $softLimit." }
        require(hardLimit > 0) { "NativeAdMemoryPolicy.hardLimit must be positive, was $hardLimit." }
        require(softLimit <= hardLimit) {
            "NativeAdMemoryPolicy.softLimit ($softLimit) must be <= hardLimit ($hardLimit)."
        }
        require(inactiveSessionLimit > 0) {
            "NativeAdMemoryPolicy.inactiveSessionLimit must be positive, was $inactiveSessionLimit."
        }
        require(inactiveSessionLimit <= hardLimit) {
            "NativeAdMemoryPolicy.inactiveSessionLimit ($inactiveSessionLimit) must be <= hardLimit ($hardLimit)."
        }
        require(maxInactiveSessions > 0) {
            "NativeAdMemoryPolicy.maxInactiveSessions must be positive, was $maxInactiveSessions."
        }
        require(maxSessionRecords > 0) {
            "NativeAdMemoryPolicy.maxSessionRecords must be positive, was $maxSessionRecords."
        }
        require(maxInactiveSessions <= maxSessionRecords) {
            "NativeAdMemoryPolicy.maxInactiveSessions ($maxInactiveSessions) must be <= maxSessionRecords ($maxSessionRecords)."
        }
        require(inactiveSessionTtl.isFinite() && inactiveSessionTtl.isPositive()) {
            "NativeAdMemoryPolicy.inactiveSessionTtl must be finite and positive, was $inactiveSessionTtl."
        }
    }
}

/**
 * Per-session retention policy.
 *
 * A session can hold at most [maxRetainedAds] native ads at a time: the visible
 * slot, the [retainBehind] slots that have just scrolled out of view, and the
 * [prefetchAhead] slots that are about to scroll into view. The defaults
 * (3 / 1 / 1) implement the documented "previous, current, next" behavior.
 *
 * [retainBehind] and [prefetchAhead] are non-negative viewport distances, not
 * counts. The session dedupes by slot key, so a viewport that reports the same
 * key in multiple bands still occupies a single slot.
 *
 * The speculative footprint — every key in [retainBehind] plus every key in
 * [prefetchAhead] — must be strictly less than [maxRetainedAds], because at
 * least one slot is reserved for the currently-visible band. The arithmetic
 * is done in [Long] to keep the comparison safe at the validation boundary.
 */
public data class NativeAdSessionPolicy(
    val maxRetainedAds: Int = 3,
    val retainBehind: Int = 1,
    val prefetchAhead: Int = 1,
) {
    init {
        require(maxRetainedAds > 0) {
            "NativeAdSessionPolicy.maxRetainedAds must be positive, was $maxRetainedAds."
        }
        require(retainBehind >= 0) {
            "NativeAdSessionPolicy.retainBehind must be non-negative, was $retainBehind."
        }
        require(prefetchAhead >= 0) {
            "NativeAdSessionPolicy.prefetchAhead must be non-negative, was $prefetchAhead."
        }
        val speculative = retainBehind.toLong() + prefetchAhead.toLong()
        require(speculative < maxRetainedAds) {
            "NativeAdSessionPolicy speculative parts (retainBehind=$retainBehind, prefetchAhead=$prefetchAhead) " +
                "do not fit inside maxRetainedAds=$maxRetainedAds once a visible slot is reserved."
        }
    }
}

/**
 * Batching strategy for a native-ad placement.
 *
 * - [Sequential] issues one ad request at a time. This is the safe default and
 *   the **only** strategy that may be used for placements whose inventory is
 *   mediated, server-side configured, or otherwise unknown to the SDK. The SDK
 *   cannot inspect mediation configuration, so any non-Google-inventory request
 *   must default to sequential to preserve per-ad callbacks and per-ad cleanup.
 * - [GoogleOnly] may issue a single multi-ad request for Google-inventory
 *   placements. The count is capped at 5; partial fills are allowed. The consumer
 *   is asserting, by choosing this value, that the placement is direct-sold
 *   Google inventory. Choosing [GoogleOnly] for mediated or unknown inventory is
 *   a misconfiguration that can leak cross-ad callbacks across networks.
 */
public enum class NativeAdBatching { Sequential, GoogleOnly }
