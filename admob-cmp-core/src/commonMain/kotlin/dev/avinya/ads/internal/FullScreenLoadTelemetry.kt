@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdLogger
import dev.avinya.ads.AdLoadOrigin
import dev.avinya.ads.AdLoadTag
import dev.avinya.ads.AdLoadTelemetry
import dev.avinya.ads.FullScreenAdController
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Where a [FullScreenSlotCore] reports [AdLoadTelemetry]: the manager's stream, and the
 * manager's process-wide request ids. Attached by the manager when it registers the slot.
 */
internal class FullScreenLoadTelemetrySink(
    val emit: (AdLoadTelemetry) -> Unit,
    val nextRequestId: () -> Long,
) {
    companion object {
        /** Reports nothing. A slot keeps this until a manager attaches its own. */
        val None: FullScreenLoadTelemetrySink = FullScreenLoadTelemetrySink(emit = {}, nextRequestId = { 0L })
    }
}

/**
 * Names what started a load, for the SDK's own callers: the app-open coordinator. App code uses
 * the public [AdLoadTag] instead and is reported as [AdLoadOrigin.App].
 */
internal class LoadOriginElement(val origin: AdLoadOrigin) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<LoadOriginElement>
}

/** The origin of a `load()` made from the current coroutine. */
internal suspend fun currentLoadOrigin(): AdLoadOrigin {
    val context = currentCoroutineContext()
    return context[LoadOriginElement]?.origin ?: AdLoadOrigin.App(context[AdLoadTag]?.value)
}

/**
 * A manager's side of [AdLoadTelemetry]: the stream, and the sink every full-screen slot it
 * registers reports to. Request ids are unique for the process, not per slot, so one analytics
 * table can key on them.
 */
internal class LoadTelemetryHub {
    private val _signals = MutableSharedFlow<AdLoadTelemetry>(extraBufferCapacity = 128)
    val signals: SharedFlow<AdLoadTelemetry> = _signals.asSharedFlow()

    private val requestIdLock = FullScreenStateLock()
    private var lastRequestId = 0L

    private val sink = FullScreenLoadTelemetrySink(
        emit = { signal ->
            if (!_signals.tryEmit(signal)) {
                AdLogger.w(
                    "Load telemetry DROPPED — the buffer was full. placement=${signal.placementId} " +
                        "signal=${signal::class.simpleName}"
                )
            }
        },
        nextRequestId = { requestIdLock.withLock { ++lastRequestId } },
    )

    /** Points a newly registered slot at this hub, before it is handed to anyone. */
    fun attachTo(controller: FullScreenAdController) {
        (controller as? FullScreenSlotCore<*>)?.telemetry = sink
    }
}
