package dev.avinya.ads.internal

import dev.avinya.ads.AdLoadOrigin
import dev.avinya.ads.AdLoadTag
import dev.avinya.ads.AdLoadTelemetry
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext

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
