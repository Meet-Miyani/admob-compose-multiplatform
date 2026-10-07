package dev.avinya.ads.ui

import androidx.compose.foundation.gestures.Orientation
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
        measuredViewport = { nativeAdViewportMeasurement() },
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
        measuredViewport = { nativeAdViewportMeasurement() },
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
        firstLine = viewport.firstLine,
        firstOffset = viewport.firstOffset,
    )
}.distinctUntilChanged()

internal class NativeAdViewportSessionBinding(
    private val session: NativeAdSession,
    private val policy: NativeAdSessionPolicy,
) {
    private var direction = NativeAdScrollDirection.Forward
    // The furthest point reached in [direction], or null until a frame reports a line. Travel
    // against [direction] is measured from here, not from the previous frame.
    private var furthest: ScrollPosition? = null
    private var previousViewport: MeasuredNativeAdViewport? = null

    fun update(input: NativeAdViewportInput) {
        input.firstLine?.let { line -> track(ScrollPosition(line, input.firstOffset)) }

        // Scroll offset is deliberately NOT part of this key: dragging within one row moves the
        // offset without changing a single band, and republishing there would churn the session
        // for nothing. The resolved slots ARE part of it, so a feed whose contents changed under
        // a stationary viewport still gets through.
        val viewport = MeasuredNativeAdViewport(input.slots, direction)
        if (viewport == previousViewport) return
        previousViewport = viewport
        nativeAdWindowForViewport(viewport.slots, viewport.direction, policy)?.let(session::updateWindow)
    }

    /**
     * Turns [direction] only once the viewport has moved a full line against it.
     *
     * Turning moves the prefetch band to the other side of the viewport. Whenever the two sides of
     * the window hold different numbers of slots — [NativeAdSessionPolicy.prefetchAhead] and
     * [NativeAdSessionPolicy.retainBehind] differ, or [NativeAdSessionPolicy.maxRetainedAds] has
     * room for only one side once the visible slots are counted — a turn pushes loaded ads nobody
     * has seen out of the window, the session retires them, and it requests them again as soon as
     * the user carries on. Turning on every backward pixel made an ordinary nudge do that, and so
     * did a frame that did not move at all, which is what a Paging append or a refresh landing
     * under a parked list looks like here.
     *
     * The turn point is the furthest point reached moved one line back: the same offset in the line
     * before it, or in the line after it while reversed. Anything past the turn point counts, so a
     * jump, or a fling that skips lines between frames, turns on the frame it lands. A line is a
     * row of a grid, not an item, which is what [NativeAdViewportMeasurement.firstLine] reports.
     */
    private fun track(position: ScrollPosition) {
        val reached = furthest
        if (reached == null) {
            furthest = position
            return
        }
        val forward = direction == NativeAdScrollDirection.Forward
        val turnPoint = ScrollPosition(if (forward) reached.line - 1 else reached.line + 1, reached.offset)
        val turned = if (forward) position <= turnPoint else position >= turnPoint
        val travelledFurther = if (forward) position > reached else position < reached
        if (turned) direction = if (forward) NativeAdScrollDirection.Reverse else NativeAdScrollDirection.Forward
        if (turned || travelledFurther) furthest = position
    }
}

/** A scroll position: the line the viewport starts in, then how far into that line it starts. */
private data class ScrollPosition(val line: Int, val offset: Int) : Comparable<ScrollPosition> {
    override fun compareTo(other: ScrollPosition): Int =
        if (line != other.line) line.compareTo(other.line) else offset.compareTo(other.offset)
}

/** One measured frame: the resolved slot mapping plus the geometry the direction machine reads. */
internal data class NativeAdViewportInput(
    val slots: ResolvedNativeAdViewport,
    val firstLine: Int?,
    val firstOffset: Int,
)

private data class MeasuredNativeAdViewport(
    val slots: ResolvedNativeAdViewport,
    val direction: NativeAdScrollDirection,
)

/**
 * What one frame of a lazy layout reports to the native-ad pipeline.
 *
 * [firstLine] is the line, not the item, the viewport starts in: a row of a vertical grid, a column
 * of a horizontal one, an item of a list. It is null while no line is known, as before a lazy grid's
 * first layout. [firstOffset] is how far into that line the viewport starts.
 */
internal data class NativeAdViewportMeasurement(
    val indexes: List<Int>,
    val firstLine: Int?,
    val firstOffset: Int,
)

internal fun LazyListState.nativeAdViewportMeasurement(): NativeAdViewportMeasurement =
    NativeAdViewportMeasurement(
        indexes = layoutInfo.visibleItemsInfo.map { it.index },
        firstLine = firstVisibleItemIndex,
        firstOffset = firstVisibleItemScrollOffset,
    )

/**
 * A grid's line is the row of a vertical grid or the column of a horizontal one. Item indexes
 * would not do: one row of a multi-column grid spans several of them, so stepping back one index
 * is not stepping back one row. The line comes from the first visible item's layout, so it is
 * null until the grid has been laid out; Compose reports -1 for a line it does not know.
 */
internal fun LazyGridState.nativeAdViewportMeasurement(): NativeAdViewportMeasurement {
    val info = layoutInfo
    val first = info.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex }
    val line = first?.let { if (info.orientation == Orientation.Vertical) it.row else it.column }
    return NativeAdViewportMeasurement(
        indexes = info.visibleItemsInfo.map { it.index },
        firstLine = line?.takeIf { it >= 0 },
        firstOffset = firstVisibleItemScrollOffset,
    )
}
