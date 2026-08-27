package dev.avinya.ads.internal

import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdLogger
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Publishes [event], logging when the buffer refuses it.
 *
 * Every emitter must route through here rather than calling `tryEmit` directly and discarding
 * the Boolean. `AdEvent` flows have no replay and a finite extra buffer, so a burst against a
 * slow collector drops impressions, closes, show failures and paid events with no trace
 * anywhere. The stream is presented as a unified event stream but has best-effort datagram
 * semantics; this at least makes *that* loss observable instead of invisible.
 *
 * **What this does NOT catch:** an emission with no collector attached. For a `replay = 0`
 * flow there is nothing to buffer the value into, so it is dropped and `tryEmit` still
 * returns `true` — the no-subscriber case is structurally invisible to this check, not
 * merely unhandled. Detecting it would mean sampling `subscriptionCount`, which races the
 * emission and, on the per-controller flows most consumers never collect, would log a
 * warning for every single event. The public contract on `AdManager.events` states this
 * limitation instead of pretending to cover it.
 *
 * This is deliberately the minimum. Durable analytics/revenue delivery needs a separate
 * contract — a synchronous sink or bounded channel with explicit overflow behaviour and event
 * IDs for deduplication — which is an API change, not a logging change.
 */
internal fun MutableSharedFlow<AdEvent>.emitOrLogDrop(event: AdEvent, source: String) {
    if (!tryEmit(event)) {
        AdLogger.w(
            "Ad event DROPPED by $source — the event buffer was full and this event is gone. " +
                "event=${event::class.simpleName} placement=${event.placementId}. " +
                "Do not rely on this stream for billing-grade accounting."
        )
    }
}
