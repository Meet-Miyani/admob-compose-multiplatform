package dev.avinya.ads.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSessionState
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeAdFeedSessionBindingTest {
    private val placement = AdPlacement("native", AdFormat.Native, "android-native", "ios-native")

    @Test
    fun `grid viewport publishes bounded windows in measured item order and scroll direction`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, NativeAdSessionPolicy())
        val slotAt = slotsAt(1, 5, 8, 12)

        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleIndex = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotAt,
            )
        )
        binding.update(
            input(
                visibleIndexes = listOf(7, 8, 9, 10),
                firstVisibleIndex = 7,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotAt,
            )
        )
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleIndex = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotAt,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8"), behind = listOf("ad-1")),
                WindowKeys(visible = listOf("ad-8"), ahead = listOf("ad-12"), behind = listOf("ad-5")),
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-1"), behind = listOf("ad-8")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `grid binding ignores an unmeasured viewport but clears an emptied feed`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, NativeAdSessionPolicy())

        binding.update(
            input(
                visibleIndexes = emptyList(),
                firstVisibleIndex = 0,
                firstVisibleOffset = 0,
                itemCount = 8,
                slotAt = slotsAt(2),
            )
        )
        assertEquals(emptyList<NativeAdWindow>(), session.windows)

        binding.update(
            input(
                visibleIndexes = emptyList(),
                firstVisibleIndex = 0,
                firstVisibleOffset = 0,
                itemCount = 0,
                slotAt = slotsAt(2),
            )
        )

        assertEquals(
            listOf(WindowKeys(emptyList(), emptyList(), emptyList())),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `a slot mapping change under an unchanged viewport republishes the window`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, NativeAdSessionPolicy())

        // Two identical frames first. The direction machine reports Forward for the very first
        // measurement and Reverse for any repeat of it, so the mapping change below has to
        // survive a SETTLED direction rather than riding a direction flip that would have
        // republished the window anyway.
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleIndex = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotsAt(5),
            )
        )
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleIndex = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotsAt(5),
            )
        )
        // A refresh replaced the rows: same item count, same measured viewport, new slot key.
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleIndex = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = { index -> if (index == 5) NativeAdSlot("refreshed-5", placement) else null },
            )
        )

        assertEquals(
            listOf(listOf("ad-5"), listOf("ad-5"), listOf("refreshed-5")),
            session.windows.map { window -> window.visible.map(NativeAdSlot::key) },
        )
    }

    @Test
    fun `a feed content change under a stationary viewport re-emits`() = runTest {
        // The ONLY snapshot state this pipeline can see is `feed`, and it is reachable solely
        // through slotAt — `measure` reads nothing. So this passes if and only if the host's
        // slot mapping is applied inside the snapshot observer.
        val feed = mutableStateOf(listOf("a", "b", "c"))
        val emitted = mutableListOf<List<String>>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            nativeAdViewportInputs(
                policy = NativeAdSessionPolicy(),
                measure = { NativeAdViewportMeasurement(listOf(0, 1, 2), firstIndex = 0, firstOffset = 0) },
                itemCount = { 3 },
                slotAt = { { index -> NativeAdSlot(feed.value[index], placement) } },
            ).collect { input -> emitted += input.slots.visible.map(NativeAdSlot::key) }
        }
        runCurrent()

        // A refresh: same row count, same measured viewport, different rows.
        feed.value = listOf("x", "y", "z")
        Snapshot.sendApplyNotifications()
        runCurrent()
        collector.cancel()

        assertEquals(listOf(listOf("a", "b", "c"), listOf("x", "y", "z")), emitted)
    }

    /**
     * Builds one measured frame exactly as the composable does — the host mapping is resolved
     * up front, so the binding only ever sees values.
     */
    private fun input(
        visibleIndexes: List<Int>,
        firstVisibleIndex: Int,
        firstVisibleOffset: Int,
        itemCount: Int,
        policy: NativeAdSessionPolicy = NativeAdSessionPolicy(),
        slotAt: (Int) -> NativeAdSlot?,
    ) = NativeAdViewportInput(
        slots = resolveNativeAdViewport(visibleIndexes, itemCount, policy, slotAt),
        firstIndex = firstVisibleIndex,
        firstOffset = firstVisibleOffset,
    )

    private fun slotsAt(vararg indexes: Int): (Int) -> NativeAdSlot? = { index ->
        index.takeIf { it in indexes }?.let { NativeAdSlot("ad-$it", placement) }
    }

    private data class WindowKeys(
        val visible: List<String>,
        val ahead: List<String>,
        val behind: List<String>,
    )

    private fun NativeAdWindow.toWindowKeys(): WindowKeys = WindowKeys(
        visible = visible.map(NativeAdSlot::key),
        ahead = prefetchAhead.map(NativeAdSlot::key),
        behind = retainBehind.map(NativeAdSlot::key),
    )

    private class RecordingSession : NativeAdSession {
        override val key: String = "recording"
        override val policy: NativeAdSessionPolicy = NativeAdSessionPolicy()
        override val state: StateFlow<NativeAdSessionState> = MutableStateFlow(NativeAdSessionState(false, emptyMap()))
        val windows = mutableListOf<NativeAdWindow>()

        override fun updateWindow(window: NativeAdWindow) {
            windows += window
        }

        override fun deactivate() = Unit
        override fun close() = Unit
    }
}
