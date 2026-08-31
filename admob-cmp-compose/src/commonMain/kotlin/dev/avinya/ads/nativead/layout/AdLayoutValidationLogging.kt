package dev.avinya.ads.nativead.layout

import dev.avinya.ads.AdLogger

/**
 * Upper bound on how many distinct layout identities are remembered for
 * [AdLayout.logValidationWarningsOnce]'s dedup.
 *
 * [AdLayout.identity] includes node content such as [AdStaticText.text], so an app that
 * generates layouts dynamically (locale- or user-data-dependent copy) can produce an unbounded
 * number of distinct identities over a process lifetime. A plain unbounded set would then grow
 * for as long as the process runs. FIFO eviction at this bound keeps memory use flat; the
 * tradeoff is that an evicted identity can log again if it reappears later, which is an
 * acceptable cost for a diagnostic warning, not a correctness-relevant guarantee.
 */
private const val MAX_TRACKED_LAYOUT_IDENTITIES = 64

// Plain, not synchronized: both platform NativeAdView entry points call this only from
// LaunchedEffect (Compose main-thread effects), never from a background dispatcher or a native
// callback thread, so no concurrent mutation is possible.
private val warnedLayoutIdentities = mutableSetOf<String>()
private val warnedLayoutIdentityOrder = ArrayDeque<String>()

/**
 * Logs [AdLayout.validation]'s warnings through [AdLogger] once per distinct
 * [AdLayout.identity] (bounded by [MAX_TRACKED_LAYOUT_IDENTITIES]), the first time this layout
 * is rendered.
 *
 * This is a developer-experience convenience, not a correctness fix: [AdLayoutValidator]
 * already reports every warning through [AdLayout.validation] regardless of whether this
 * function is ever called, and its own KDoc leaves reacting to
 * [AdLayoutValidationReport.warnings] up to the consumer. What this adds is a zero-effort
 * default -- most apps read no diagnostics at all, so surfacing warnings through [AdLogger]
 * automatically means a missing ad badge or AdChoices slot is visible in the log without a
 * developer having to go read `layout.validation` themselves.
 *
 * Deliberately NOT called from [AdLayout]'s constructor: `validation` is recomputed on every
 * construction, including every `copy()`, and both `NativeAdView` implementations rebuild their
 * [AdLayout] on every recomposition that touches the tree — logging there would repeat on every
 * recomposition instead of once per distinct layout. The identity dedup is what keeps this to
 * one log per layout shape, no matter how many times it renders.
 */
internal fun AdLayout.logValidationWarningsOnce() {
    if (validation.warnings.isEmpty()) return
    if (!warnedLayoutIdentities.add(identity)) return
    warnedLayoutIdentityOrder.addLast(identity)
    if (warnedLayoutIdentityOrder.size > MAX_TRACKED_LAYOUT_IDENTITIES) {
        warnedLayoutIdentities.remove(warnedLayoutIdentityOrder.removeFirst())
    }
    validation.warnings.forEach { issue ->
        // identity may contain AdStaticText.text, including locale- or user-derived content.
        // Keep it as the internal dedup key, but never copy it into the host's logs.
        AdLogger.w("Native ad layout: ${issue.message}")
    }
}
