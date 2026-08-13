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
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Obtains a named native-ad session and keeps it synchronized with [listState]'s measured
 * viewport. Leaving composition only deactivates the session, allowing its bounded inactive
 * anchor to survive a tab switch; the logical feed owner closes it when it is genuinely done.
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
        snapshotFlow {
            val viewport = viewportState.measuredViewport()
            NativeAdViewportInput(
                indexes = viewport.indexes,
                firstIndex = viewport.firstIndex,
                firstOffset = viewport.firstOffset,
                itemCount = currentItemCount,
                slotAt = currentSlotAt,
            )
        }
            .distinctUntilChanged()
            .collect { input ->
                binding.update(
                    visibleIndexes = input.indexes,
                    firstVisibleIndex = input.firstIndex,
                    firstVisibleOffset = input.firstOffset,
                    itemCount = input.itemCount,
                    slotAt = input.slotAt,
                )
            }
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

internal class NativeAdViewportSessionBinding(
    private val session: NativeAdSession,
    private val policy: NativeAdSessionPolicy,
) {
    private var previousFirstIndex: Int? = null
    private var previousFirstOffset: Int? = null
    private var previousViewport: MeasuredNativeAdViewport? = null

    fun update(
        visibleIndexes: List<Int>,
        firstVisibleIndex: Int,
        firstVisibleOffset: Int,
        itemCount: Int,
        slotAt: (Int) -> NativeAdSlot?,
    ) {
        val direction = when {
            previousFirstIndex == null -> NativeAdScrollDirection.Forward
            firstVisibleIndex > previousFirstIndex!! ||
                (firstVisibleIndex == previousFirstIndex && firstVisibleOffset > previousFirstOffset!!) ->
                NativeAdScrollDirection.Forward
            else -> NativeAdScrollDirection.Reverse
        }
        previousFirstIndex = firstVisibleIndex
        previousFirstOffset = firstVisibleOffset

        val viewport = MeasuredNativeAdViewport(visibleIndexes, itemCount, slotAt, direction)
        if (viewport == previousViewport) return
        previousViewport = viewport
        nativeAdWindowForViewport(
            visibleIndexes = viewport.indexes,
            itemCount = viewport.itemCount,
            direction = viewport.direction,
            policy = policy,
            slotAt = viewport.slotAt,
        )?.let(session::updateWindow)
    }
}

private data class MeasuredNativeAdViewport(
    val indexes: List<Int>,
    val itemCount: Int,
    val slotAt: (Int) -> NativeAdSlot?,
    val direction: NativeAdScrollDirection,
)

private data class NativeAdViewportMeasurement(
    val indexes: List<Int>,
    val firstIndex: Int,
    val firstOffset: Int,
)

private data class NativeAdViewportInput(
    val indexes: List<Int>,
    val firstIndex: Int,
    val firstOffset: Int,
    val itemCount: Int,
    val slotAt: (Int) -> NativeAdSlot?,
)
