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

    // A prefetch band deeper than the retain band, as a consumer that tunes for fast flings uses.
    private val deepPrefetch = NativeAdSessionPolicy(maxRetainedAds = 5, retainBehind = 1, prefetchAhead = 3)
    private val everyThirdRow = slotsAt(2, 5, 8, 11, 14, 17, 20)

    @Test
    fun `grid viewport publishes bounded windows in measured item order and scroll direction`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, NativeAdSessionPolicy())
        val slotAt = slotsAt(1, 5, 8, 12)

        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotAt,
            )
        )
        binding.update(
            input(
                visibleIndexes = listOf(7, 8, 9, 10),
                firstVisibleLine = 7,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotAt,
            )
        )
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
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
                firstVisibleLine = 0,
                firstVisibleOffset = 0,
                itemCount = 8,
                slotAt = slotsAt(2),
            )
        )
        assertEquals(emptyList<NativeAdWindow>(), session.windows)

        binding.update(
            input(
                visibleIndexes = emptyList(),
                firstVisibleLine = 0,
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

        // The second frame repeats the first exactly, which is not a scroll, so it publishes
        // nothing and the direction stays settled; the mapping change below therefore has to
        // get through on its own rather than riding a direction flip.
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotsAt(5),
            )
        )
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = slotsAt(5),
            )
        )
        // A refresh replaced the rows: same item count, same measured viewport, new slot key.
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 0,
                itemCount = 15,
                slotAt = { index -> if (index == 5) NativeAdSlot("refreshed-5", placement) else null },
            )
        )

        assertEquals(
            listOf(listOf("ad-5"), listOf("refreshed-5")),
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
                measure = { NativeAdViewportMeasurement(listOf(0, 1, 2), firstLine = 0, firstOffset = 0) },
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

    @Test
    fun `a feed content change under a stationary viewport keeps the prefetch direction`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // Same measured viewport; a Paging append grew the feed from 30 to 40 items while the user was parked.
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 100,
                itemCount = 40,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8", "ad-11", "ad-14"), behind = listOf("ad-2")),
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8", "ad-11", "ad-14"), behind = listOf("ad-2")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `a reverse nudge within one row keeps the prefetch direction`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // 40px back inside the same row: not a reversal, so nothing is republished.
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 60,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8", "ad-11", "ad-14"), behind = listOf("ad-2")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `a reverse nudge that only reveals the previous row keeps the prefetch direction`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 0,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // 50px back with 300px rows: row 3 peeks in at the top, a row index change that is still not a full row of travel.
        binding.update(
            input(
                visibleIndexes = listOf(3, 4, 5, 6, 7),
                firstVisibleLine = 3,
                firstVisibleOffset = 250,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8", "ad-11", "ad-14"), behind = listOf("ad-2")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `scrolling back a full row moves the prefetch behind the viewport`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // Exactly one row back, which is the threshold: the prefetch band moves to the rows above.
        binding.update(
            input(
                visibleIndexes = listOf(3, 4, 5, 6),
                firstVisibleLine = 3,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8", "ad-11", "ad-14"), behind = listOf("ad-2")),
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-2"), behind = listOf("ad-8")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `after reversing a forward nudge keeps the reverse prefetch until a full row forward`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(10, 11, 12, 13),
                firstVisibleLine = 10,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // One full row back: turns to reverse.
        binding.update(
            input(
                visibleIndexes = listOf(9, 10, 11, 12),
                firstVisibleLine = 9,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // 40px forward nudge: still reverse, so nothing is republished.
        binding.update(
            input(
                visibleIndexes = listOf(9, 10, 11, 12),
                firstVisibleLine = 9,
                firstVisibleOffset = 140,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-14", "ad-17", "ad-20"), behind = listOf("ad-8")),
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-8", "ad-5", "ad-2"), behind = listOf("ad-14")),
            ),
            session.windows.map { it.toWindowKeys() },
        )

        // One full row forward from the furthest point reached in reverse: turns forward again.
        binding.update(
            input(
                visibleIndexes = listOf(10, 11, 12, 13),
                firstVisibleLine = 10,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-14", "ad-17", "ad-20"), behind = listOf("ad-8")),
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-8", "ad-5", "ad-2"), behind = listOf("ad-14")),
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-14", "ad-17", "ad-20"), behind = listOf("ad-8")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `a forward jump while reversed turns forward at once`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(10, 11, 12, 13),
                firstVisibleLine = 10,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // one full line back: turns to reverse
        binding.update(
            input(
                visibleIndexes = listOf(9, 10, 11, 12),
                firstVisibleLine = 9,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // further back while reversed
        binding.update(
            input(
                visibleIndexes = listOf(3, 4, 5, 6),
                firstVisibleLine = 3,
                firstVisibleOffset = 120,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // a jump such as scrollToItem far past the turn point: turns forward on this frame
        binding.update(
            input(
                visibleIndexes = listOf(16, 17, 18, 19),
                firstVisibleLine = 16,
                firstVisibleOffset = 0,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-14", "ad-17", "ad-20"), behind = listOf("ad-8")),
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-8", "ad-5", "ad-2"), behind = listOf("ad-14")),
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-2"), behind = listOf("ad-8")),
                WindowKeys(visible = listOf("ad-17"), ahead = listOf("ad-20"), behind = listOf("ad-14")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `a backward jump while forward turns reverse at once`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(16, 17, 18, 19),
                firstVisibleLine = 16,
                firstVisibleOffset = 0,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // a jump back that lands deeper into its line than the furthest offset: turns to reverse on this frame
        binding.update(
            input(
                visibleIndexes = listOf(3, 4, 5, 6),
                firstVisibleLine = 3,
                firstVisibleOffset = 250,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-17"), ahead = listOf("ad-20"), behind = listOf("ad-14")),
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-2"), behind = listOf("ad-8")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `travelling further back while reversed moves the turn point with it`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(10, 11, 12, 13),
                firstVisibleLine = 10,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // one full line back: turns to reverse
        binding.update(
            input(
                visibleIndexes = listOf(9, 10, 11, 12),
                firstVisibleLine = 9,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // further back: the turn point moves to line 7 offset 50
        binding.update(
            input(
                visibleIndexes = listOf(6, 7, 8, 9),
                firstVisibleLine = 6,
                firstVisibleOffset = 50,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // a 250px forward nudge stays short of the turn point, so nothing is republished
        binding.update(
            input(
                visibleIndexes = listOf(6, 7, 8, 9),
                firstVisibleLine = 6,
                firstVisibleOffset = 300,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // past the moved turn point: turns forward
        binding.update(
            input(
                visibleIndexes = listOf(8, 9, 10, 11),
                firstVisibleLine = 8,
                firstVisibleOffset = 0,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-14", "ad-17", "ad-20"), behind = listOf("ad-8")),
                WindowKeys(visible = listOf("ad-11"), ahead = listOf("ad-8", "ad-5", "ad-2"), behind = listOf("ad-14")),
                WindowKeys(visible = listOf("ad-8"), ahead = listOf("ad-5", "ad-2"), behind = listOf("ad-11")),
                WindowKeys(visible = listOf("ad-8", "ad-11"), ahead = listOf("ad-14", "ad-17", "ad-20"), behind = listOf()),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    @Test
    fun `a frame without a known first line keeps the direction and the turn point`() {
        val session = RecordingSession()
        val binding = NativeAdViewportSessionBinding(session, deepPrefetch)

        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = 4,
                firstVisibleOffset = 100,
                itemCount = 30,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // no line is known, as for a grid before its first layout, while the feed grew
        binding.update(
            input(
                visibleIndexes = listOf(4, 5, 6, 7),
                firstVisibleLine = null,
                firstVisibleOffset = 0,
                itemCount = 40,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )
        // one full line back from the first frame: turns to reverse
        binding.update(
            input(
                visibleIndexes = listOf(3, 4, 5, 6),
                firstVisibleLine = 3,
                firstVisibleOffset = 100,
                itemCount = 40,
                policy = deepPrefetch,
                slotAt = everyThirdRow,
            )
        )

        assertEquals(
            listOf(
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8", "ad-11", "ad-14"), behind = listOf("ad-2")),
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-8", "ad-11", "ad-14"), behind = listOf("ad-2")),
                WindowKeys(visible = listOf("ad-5"), ahead = listOf("ad-2"), behind = listOf("ad-8")),
            ),
            session.windows.map { it.toWindowKeys() },
        )
    }

    /**
     * Builds one measured frame exactly as the composable does — the host mapping is resolved
     * up front, so the binding only ever sees values.
     */
    private fun input(
        visibleIndexes: List<Int>,
        firstVisibleLine: Int?,
        firstVisibleOffset: Int,
        itemCount: Int,
        policy: NativeAdSessionPolicy = NativeAdSessionPolicy(),
        slotAt: (Int) -> NativeAdSlot?,
    ) = NativeAdViewportInput(
        slots = resolveNativeAdViewport(visibleIndexes, itemCount, policy, slotAt),
        firstLine = firstVisibleLine,
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
