package dev.avinya.ads.ui

import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSessionState
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
            visibleIndexes = listOf(4, 5, 6, 7),
            firstVisibleIndex = 4,
            firstVisibleOffset = 0,
            itemCount = 15,
            slotAt = slotAt,
        )
        binding.update(
            visibleIndexes = listOf(7, 8, 9, 10),
            firstVisibleIndex = 7,
            firstVisibleOffset = 0,
            itemCount = 15,
            slotAt = slotAt,
        )
        binding.update(
            visibleIndexes = listOf(4, 5, 6, 7),
            firstVisibleIndex = 4,
            firstVisibleOffset = 0,
            itemCount = 15,
            slotAt = slotAt,
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
            visibleIndexes = emptyList(),
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            itemCount = 8,
            slotAt = slotsAt(2),
        )
        assertEquals(emptyList<NativeAdWindow>(), session.windows)

        binding.update(
            visibleIndexes = emptyList(),
            firstVisibleIndex = 0,
            firstVisibleOffset = 0,
            itemCount = 0,
            slotAt = slotsAt(2),
        )

        assertEquals(
            listOf(WindowKeys(emptyList(), emptyList(), emptyList())),
            session.windows.map { it.toWindowKeys() },
        )
    }

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
