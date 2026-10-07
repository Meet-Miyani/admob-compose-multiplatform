package dev.avinya.ads.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Pins what the native feed's direction machine is fed: the line a lazy layout's viewport starts in.
 * One row of a multi-column grid spans several item indexes, so reading the index instead made a
 * nudge that revealed the previous row look like a whole row of travel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativeAdViewportMeasurementTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var frame: Canvas

    @BeforeTest
    fun setUp() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @AfterTest
    fun tearDown() {
        controller.pause().stop().destroy()
        repeat(FRAMES) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS)) }
    }

    @Test
    fun `a vertical grid reports the row its viewport starts in rather than the item index`() {
        val state = LazyGridState(firstVisibleItemIndex = 10, firstVisibleItemScrollOffset = 30)
        controller.get().setContent {
            LazyVerticalGrid(columns = GridCells.Fixed(2), state = state, modifier = Modifier.width(200.dp).height(300.dp)) {
                items(40) { Box(Modifier.fillMaxWidth().height(100.dp)) }
            }
        }
        advanceFrames()

        val measurement = state.nativeAdViewportMeasurement()
        assertEquals(10, state.firstVisibleItemIndex)
        assertEquals(5, measurement.firstLine)
        assertEquals(state.firstVisibleItemScrollOffset, measurement.firstOffset)
    }

    @Test
    fun `a horizontal grid reports the column its viewport starts in`() {
        val state = LazyGridState(firstVisibleItemIndex = 10, firstVisibleItemScrollOffset = 30)
        controller.get().setContent {
            LazyHorizontalGrid(rows = GridCells.Fixed(2), state = state, modifier = Modifier.width(300.dp).height(200.dp)) {
                items(40) { Box(Modifier.fillMaxHeight().width(100.dp)) }
            }
        }
        advanceFrames()

        val measurement = state.nativeAdViewportMeasurement()
        assertEquals(10, state.firstVisibleItemIndex)
        assertEquals(5, measurement.firstLine)
        assertEquals(state.firstVisibleItemScrollOffset, measurement.firstOffset)
    }

    @Test
    fun `a list reports the item its viewport starts in`() {
        val state = LazyListState(firstVisibleItemIndex = 10, firstVisibleItemScrollOffset = 30)
        controller.get().setContent {
            LazyColumn(state = state, modifier = Modifier.width(200.dp).height(300.dp)) {
                items(40) { Box(Modifier.fillMaxWidth().height(100.dp)) }
            }
        }
        advanceFrames()

        val measurement = state.nativeAdViewportMeasurement()
        assertEquals(10, measurement.firstLine)
        assertEquals(state.firstVisibleItemScrollOffset, measurement.firstOffset)
    }

    @Test
    fun `a grid before its first layout reports no line`() {
        val state = LazyGridState(firstVisibleItemIndex = 10)
        // Never composed: no layout has run, so no line is known yet.

        val measurement = state.nativeAdViewportMeasurement()
        assertNull(measurement.firstLine)
    }

    private fun advanceFrames() {
        repeat(FRAMES) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            controller.get().window.decorView.draw(windowCanvas())
        }
    }

    private fun windowCanvas(): Canvas {
        if (!::frame.isInitialized) {
            val window = controller.get().window.decorView
            check(window.width > 0 && window.height > 0) { "the window has not been laid out yet" }
            frame = Canvas(Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888))
        }
        return frame
    }

    private companion object {
        const val FRAMES = 5
        const val FRAME_MS = 20L
    }
}
