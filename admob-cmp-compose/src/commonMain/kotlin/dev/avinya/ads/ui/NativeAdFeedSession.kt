package dev.avinya.ads.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Obtains a named native-ad session and keeps it synchronized with [listState]'s measured
 * viewport. Leaving composition only deactivates the session, allowing its bounded inactive
 * anchor to survive a tab switch; the logical feed owner closes it when it is genuinely done.
 *
 * [slotAt] is invoked inside a Compose snapshot observer, so any snapshot state it reads —
 * the backing list, a `LazyPagingItems`, a remote slot config — is tracked. A feed whose
 * contents change without changing [itemCount], such as a pull-to-refresh, therefore still
 * republishes the window. It is called only for measured indexes and for a bounded range on
 * either side of them, never across the whole feed, so it must stay cheap and side-effect free.
 */
@Composable
public fun rememberNativeAdFeedSession(
    sessionKey: String,
    listState: LazyListState,
    itemCount: Int,
    slotAt: (index: Int) -> NativeAdSlot?,
    policy: NativeAdSessionPolicy = NativeAdSessionPolicy(),
): NativeAdSession {
    return rememberNativeAdFeedSessionForViewport(
        sessionKey = sessionKey,
        viewportState = listState,
        itemCount = itemCount,
        slotAt = slotAt,
        policy = policy,
        measuredViewport = {
            NativeAdViewportMeasurement(
                indexes = layoutInfo.visibleItemsInfo.map { it.index },
                firstIndex = firstVisibleItemIndex,
                firstOffset = firstVisibleItemScrollOffset,
            )
        },
    )
}

/**
 * Obtains a named native-ad session and keeps it synchronized with [gridState]'s measured
 * viewport. Grid items use the same bounded native-slot scanning and session lifecycle as lists.
 *
 * [slotAt] is invoked inside a Compose snapshot observer, so any snapshot state it reads —
 * the backing list, a `LazyPagingItems`, a remote slot config — is tracked. A feed whose
 * contents change without changing [itemCount], such as a pull-to-refresh, therefore still
 * republishes the window. It is called only for measured indexes and for a bounded range on
 * either side of them, never across the whole feed, so it must stay cheap and side-effect free.
 */
@Composable
public fun rememberNativeAdFeedSession(
    sessionKey: String,
    gridState: LazyGridState,
    itemCount: Int,
    slotAt: (index: Int) -> NativeAdSlot?,
    policy: NativeAdSessionPolicy = NativeAdSessionPolicy(),
): NativeAdSession {
    return rememberNativeAdFeedSessionForViewport(
        sessionKey = sessionKey,
        viewportState = gridState,
        itemCount = itemCount,
        slotAt = slotAt,
        policy = policy,
        measuredViewport = {
            NativeAdViewportMeasurement(
                indexes = layoutInfo.visibleItemsInfo.map { it.index },
                firstIndex = firstVisibleItemIndex,
                firstOffset = firstVisibleItemScrollOffset,
            )
        },
    )
}

@Composable
private fun <S : Any> rememberNativeAdFeedSessionForViewport(
    sessionKey: String,
    viewportState: S,
    itemCount: Int,
    slotAt: (index: Int) -> NativeAdSlot?,
    policy: NativeAdSessionPolicy,
    measuredViewport: S.() -> NativeAdViewportMeasurement,
): NativeAdSession {
    val manager = LocalAdManager.current
    val session = remember(manager, sessionKey, policy) { manager.nativeAds.session(sessionKey, policy) }
    val currentItemCount by rememberUpdatedState(itemCount)
    val currentSlotAt by rememberUpdatedState(slotAt)

    LaunchedEffect(session, viewportState) {
        val binding = NativeAdViewportSessionBinding(session, policy)
        nativeAdViewportInputs(
            policy = policy,
            measure = { viewportState.measuredViewport() },
            itemCount = { currentItemCount },
            slotAt = { currentSlotAt },
        ).collect(binding::update)
    }

    DisposableEffect(session) {
        onDispose(session::deactivate)
    }
    return session
}

/**
 * Creates a one-slot session for an inline native placement that is not part of a lazy list.
 * It uses the same manager and admission governor as feed sessions.
 */
@Composable
public fun rememberNativeAdSlotSession(
    sessionKey: String,
    slot: NativeAdSlot,
    policy: NativeAdSessionPolicy = NativeAdSessionPolicy(
        maxRetainedAds = 1,
        retainBehind = 0,
        prefetchAhead = 0,
    ),
): NativeAdSession {
    val manager = LocalAdManager.current
    val session = remember(manager, sessionKey, policy) { manager.nativeAds.session(sessionKey, policy) }
    val binding = remember(session) { NativeAdSlotSessionBinding(session) }

    LaunchedEffect(binding, slot) { binding.update(slot) }
    DisposableEffect(session) {
        onDispose(binding::deactivate)
    }
    return session
}

/** Testable part of the one-slot effect; production uses it from [rememberNativeAdSlotSession]. */
internal class NativeAdSlotSessionBinding(private val session: NativeAdSession) {
    fun update(slot: NativeAdSlot) {
        session.updateWindow(NativeAdWindow(visible = listOf(slot)))
    }

    fun deactivate() {
        session.deactivate()
    }
}

/**
 * The measured-viewport pipeline, extracted from the composable so it can be driven by a test.
 *
 * Everything happens inside ONE snapshot observer, and that is the whole point.
 * [slotAt] is host code that reads the feed's own Compose state; performing those reads here,
 * rather than in the collector, is what makes a content change with an unchanged item count —
 * a pull-to-refresh, an async slot config resolving — reach the session at all. Resolve the
 * mapping outside this block and the window silently follows scroll geometry and nothing else.
 *
 * [itemCount] and [slotAt] are read through lambdas for the same reason: they come from
 * `rememberUpdatedState`, and the read has to land inside the observer to be tracked.
 */
internal fun nativeAdViewportInputs(
    policy: NativeAdSessionPolicy,
    measure: () -> NativeAdViewportMeasurement,
    itemCount: () -> Int,
    slotAt: () -> (Int) -> NativeAdSlot?,
): Flow<NativeAdViewportInput> = snapshotFlow {
    val viewport = measure()
    NativeAdViewportInput(
        slots = resolveNativeAdViewport(
            visibleIndexes = viewport.indexes,
            itemCount = itemCount(),
            policy = policy,
            slotAt = slotAt(),
        ),
        firstIndex = viewport.firstIndex,
        firstOffset = viewport.firstOffset,
    )
}.distinctUntilChanged()

internal class NativeAdViewportSessionBinding(
    private val session: NativeAdSession,
    private val policy: NativeAdSessionPolicy,
) {
    private var previousFirstIndex: Int? = null
    private var previousFirstOffset: Int? = null
    private var previousViewport: MeasuredNativeAdViewport? = null

    fun update(input: NativeAdViewportInput) {
        val direction = when {
            previousFirstIndex == null -> NativeAdScrollDirection.Forward
            input.firstIndex > previousFirstIndex!! ||
                (input.firstIndex == previousFirstIndex && input.firstOffset > previousFirstOffset!!) ->
                NativeAdScrollDirection.Forward
            else -> NativeAdScrollDirection.Reverse
        }
        previousFirstIndex = input.firstIndex
        previousFirstOffset = input.firstOffset

        // Scroll offset is deliberately NOT part of this key: dragging within one row moves the
        // offset without changing a single band, and republishing there would churn the session
        // for nothing. The resolved slots ARE part of it, so a feed whose contents changed under
        // a stationary viewport still gets through.
        val viewport = MeasuredNativeAdViewport(input.slots, direction)
        if (viewport == previousViewport) return
        previousViewport = viewport
        nativeAdWindowForViewport(viewport.slots, viewport.direction, policy)?.let(session::updateWindow)
    }
}

/** One measured frame: the resolved slot mapping plus the geometry the direction machine reads. */
internal data class NativeAdViewportInput(
    val slots: ResolvedNativeAdViewport,
    val firstIndex: Int,
    val firstOffset: Int,
)

private data class MeasuredNativeAdViewport(
    val slots: ResolvedNativeAdViewport,
    val direction: NativeAdScrollDirection,
)

internal data class NativeAdViewportMeasurement(
    val indexes: List<Int>,
    val firstIndex: Int,
    val firstOffset: Int,
)
