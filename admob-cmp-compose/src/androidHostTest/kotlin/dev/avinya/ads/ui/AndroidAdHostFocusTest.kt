package dev.avinya.ads.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Pins why the SDK's Android ad hosts never hold input focus.
 *
 * When Compose removes an `AndroidView` whose subtree holds input focus, `ViewGroup.removeViewInternal`
 * immediately calls `rootViewRequestFocus()`. That lands in `AndroidComposeView.requestFocus`, which runs a
 * synchronous Compose focus search; inside a lazy layout the search lays out beyond-bounds items while
 * Compose is still applying or measuring the change that removed the ad, and Compose aborts. It reached
 * production from a native ad in a `LazyVerticalGrid`. The scenario here is the device reproduction: the
 * ad's `key` changes during measure (there, a theme change producing a new `AdLayout` identity) while a View
 * inside the ad holds focus.
 *
 * Scrolling a focused ad away is not a trigger and is deliberately not tested: Compose pins a lazy item
 * while it holds focus, so the ad stays composed and attached off-screen instead of being removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAdHostFocusTest {

    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var frame: Canvas
    private var adSlotWidthDp by mutableIntStateOf(300)
    private val focusTargets = mutableListOf<View>()

    @BeforeTest
    fun setUp() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    // Observed without this: every test after the first stopped recomposing after a state write (its
    // swap never ran), while the same test passed alone. Compose's main-thread dispatcher is shared by
    // every test in the sandbox and Robolectric resets the main looper between tests; running the frames
    // still pending before that reset keeps each test independent of the ones before it.
    @AfterTest
    fun tearDown() {
        controller.pause().stop().destroy()
        repeat(FRAMES) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS)) }
    }

    // The control proves the harness reproduces the defect. If it starts failing, Compose no longer
    // crashes when a focused AndroidView is removed mid-measure — the upstream fix (gated behind
    // ComposeUiFlags.isViewFocusFixEnabled in Compose UI 1.12.1) is on by default, and keeping ad hosts
    // out of input focus can be revisited.
    @Test
    fun `control - swapping a focused raw AndroidView in a lazy list crashes Compose`() {
        showLazyListWithAd { factory -> AndroidView(factory = factory) }
        assertTrue(focusTargets.single().requestFocus(), "the control must be able to take focus")

        adSlotWidthDp = 280

        assertCrashedOnRefocus(assertFails { advanceFrames() })
    }


    @Test
    fun `swapping an ad host in a lazy list after its content asked for focus does not crash`() {
        showLazyListWithAd { factory -> AndroidAdHost(factory = factory) }
        focusTargets.single().requestFocus()

        adSlotWidthDp = 280
        advanceFrames()

        assertEquals(2, focusTargets.size, "the replacement ad host was not composed")
        assertFalse(focusTargets.last().hasFocus())
    }


    @Test
    fun `nothing inside an ad host can take input focus`() {
        showLazyListWithAd { factory -> AndroidAdHost(factory = factory) }
        val target = focusTargets.single()

        assertFalse(target.requestFocus(), "a View inside an ad host took input focus")
        assertFalse(target.hasFocus())
    }

    // Banner hosts reparent GMA's AdView after the factory has run, and GMA can add views of its own
    // at any time, so the guarantee has to hold for descendants added later, not only initial ones.
    @Test
    fun `a view added to an ad host after it is attached cannot take input focus`() {
        var adView: ViewGroup? = null
        showLazyListWithAd { factory ->
            AndroidAdHost(factory = { context -> factory(context).also { adView = it } })
        }
        val late = focusableView(adView!!.context)
        adView!!.addView(late)
        advanceFrames()

        assertFalse(late.requestFocus(), "a View added to an ad host later took input focus")
    }

    // The native ad view is GMA's NativeAdView, which the SDK does not control. GMA 1.4.0 never touches
    // focusability, but the guarantee must not depend on that: an ad view that re-enables focus for itself
    // and its children after attaching still cannot take it.
    @Test
    fun `an ad view that re-enables its own focus still cannot take input focus`() {
        var adView: ViewGroup? = null
        showLazyListWithAd { factory ->
            AndroidAdHost(factory = { context -> factory(context).also { adView = it } })
        }
        adView!!.apply {
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = true
            isFocusableInTouchMode = true
        }

        assertFalse(adView!!.requestFocus(), "the ad view itself took input focus")
        assertFalse(focusTargets.single().requestFocus(), "a View inside the ad view took input focus")
    }

    // AdHostFrame sits between Compose's AndroidViewHolder and the ad view, so it must hand the ad view
    // exactly what the holder used to: the same measure specs, bounds at the origin, zero size when GONE.
    @Test
    fun `the host frame measures and lays out the ad view exactly as the holder would`() {
        val context = controller.get()
        val adView = SpecRecordingView(context, reportedWidth = 123, reportedHeight = 45)
        val host = AdHostFrame(context, adView)

        for ((width, height) in listOf(
            exactly(300) to atMost(500),
            atMost(300) to unspecified(),
            unspecified() to exactly(90),
        )) {
            host.measure(width, height)
            assertEquals(width to height, adView.lastSpecs, "the ad view did not receive the holder's specs")
            assertEquals(123 to 45, host.measuredWidth to host.measuredHeight)
        }

        host.layout(10, 20, 310, 120)
        assertEquals(listOf(0, 0, 300, 100), listOf(adView.left, adView.top, adView.right, adView.bottom))

        adView.visibility = View.GONE
        host.measure(exactly(300), exactly(100))
        assertEquals(0 to 0, host.measuredWidth to host.measuredHeight)
    }

    @Test
    fun `the host frame keeps the ad view's layout params for the holder`() {
        val context = controller.get()
        val adView = View(context).apply {
            layoutParams = ViewGroup.LayoutParams(200, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val host = AdHostFrame(context, adView)

        assertEquals(200 to ViewGroup.LayoutParams.MATCH_PARENT, host.layoutParams.width to host.layoutParams.height)
    }

    // Every View the SDK hands to Compose must go through AndroidAdHost; a new ad format that embeds a
    // view any other way would silently bring the crash back.
    @Test
    fun `the SDK embeds Android views only through AndroidAdHost`() {
        val sources = File("src/androidMain/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "androidMain sources not found from ${File(".").absolutePath}")

        val offenders = sources
            .filter { it.name != "AndroidAdHost.kt" && referencesAndroidView(it.readText()) }
            .map { it.name }

        assertEquals(emptyList(), offenders, "these files use AndroidView directly instead of AndroidAdHost")
    }

    @Test
    fun `the AndroidView guard catches every way of reaching it and ignores comments`() {
        val bypasses = listOf(
            "AndroidView(factory = ::make)",
            "AndroidView<FrameLayout>(factory = ::make)",
            "androidx.compose.ui.viewinterop.AndroidView(factory = ::make)",
            "import androidx.compose.ui.viewinterop.AndroidView as Host",
            "val host = ::AndroidView",
        )
        bypasses.forEach { assertTrue(referencesAndroidView(it), "the guard missed: $it") }

        val harmless = listOf(
            "// AndroidView(factory = ::make)",
            "/** Compose `AndroidView`'s transient zero-width pass */",
            "/* AndroidView<FrameLayout>(\n factory = ::make) */",
            "class AndroidViewHolderSpy",
        )
        harmless.forEach { assertFalse(referencesAndroidView(it), "the guard flagged: $it") }
    }

    private fun showLazyListWithAd(host: @Composable (factory: (Context) -> FrameLayout) -> Unit) {
        controller.get().setContent {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    // The ad's identity is derived during measure, so changing it swaps the host
                    // inside the lazy layout's measure pass — as on the device, where a theme change
                    // re-subcomposed the grid's items mid-measure with a new AdLayout identity.
                    BoxWithConstraints(Modifier.width(adSlotWidthDp.dp)) {
                        key(maxWidth) {
                            host { context ->
                                FrameLayout(context).apply {
                                    addView(focusableView(context).also(focusTargets::add))
                                }
                            }
                        }
                    }
                }
                // Nothing else in the list is focusable, so a focus search has to look beyond the
                // visible items — the beyond-bounds layout that crashes mid-measure.
                items(FILLER_ROWS) { Box(Modifier.fillMaxWidth().height(200.dp)) }
            }
        }
        advanceFrames()
    }

    // Focusable in touch mode, like the WebView inside a banner or a media view: it takes focus on a
    // touch, not only from a keyboard.
    private fun focusableView(context: Context) = View(context).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        layoutParams = ViewGroup.LayoutParams(100, 100)
    }

    // Compose measures pending changes when the window draws (AndroidComposeView.dispatchDraw), and
    // Robolectric never draws on its own. Draw each frame so a recomposition is measured the way it is
    // on a device; without it the lazy item's new constraints are never measured and the swap never runs.
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

    private fun assertCrashedOnRefocus(crash: Throwable) {
        assertTrue(
            crash.stackTrace.any { it.methodName == "rootViewRequestFocus" },
            "expected the crash to come from Android re-focusing the root on removal, got: $crash",
        )
    }

    private class SpecRecordingView(context: Context, val reportedWidth: Int, val reportedHeight: Int) :
        View(context) {
        var lastSpecs: Pair<Int, Int>? = null

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            lastSpecs = widthMeasureSpec to heightMeasureSpec
            setMeasuredDimension(reportedWidth, reportedHeight)
        }
    }

    private companion object {
        const val FILLER_ROWS = 50
        const val FRAMES = 5
        const val FRAME_MS = 20L

        fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        fun atMost(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.AT_MOST)
        fun unspecified() = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        // Any code reference to Compose's AndroidView: a call (with or without type arguments), a
        // qualified call, an import (aliased or not) or a callable reference. Comments are stripped
        // first, and AndroidView must be the whole identifier, so AndroidViewHolder does not count.
        private val ANDROID_VIEW = Regex("""(?<![\w$])AndroidView(?![\w$])""")
        private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        private val LINE_COMMENT = Regex("""//[^\n]*""")

        fun referencesAndroidView(source: String): Boolean =
            ANDROID_VIEW.containsMatchIn(source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, ""))
    }
}
