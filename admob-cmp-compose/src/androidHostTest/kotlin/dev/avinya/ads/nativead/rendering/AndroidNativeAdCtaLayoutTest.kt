package dev.avinya.ads.nativead.rendering

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.dp
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.adLayout
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-measurement checks of the Android call-to-action, run against the renderer's actual views.
 *
 * `GraphicsMode.NATIVE` is required: the legacy Robolectric graphics shadows fake text metrics, so
 * a label's width and line height — the two numbers every assertion here is about — would be
 * fiction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidNativeAdCtaLayoutTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val density = context.resources.displayMetrics.density

    private fun px(dp: Float): Int = (dp * density).roundToInt()

    private fun render(layout: AdLayout, widthDp: Float, callToAction: String = "Install"): NativeAdView {
        val nativeAd = mock(NativeAd::class.java)
        `when`(nativeAd.callToAction).thenReturn(callToAction)
        val view = AndroidNativeAdLayoutRenderer(context = context, nativeAd = nativeAd).render(layout)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(px(widthDp), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun NativeAdView.cta(): FrameLayout = callToActionView as FrameLayout

    private fun FrameLayout.label(): TextView = getChildAt(0) as TextView

    /** The label's natural one-line height, independent of whatever box it was given. */
    private fun TextView.naturalHeight(): Int {
        measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return measuredHeight
    }

    private fun handoffRow(ctaStyle: AdButtonStyle = AdButtonStyle.filled) = adLayout {
        row(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
            column(modifier = AdModifier.weight(1f)) {
                text(text = "Weekend trails, offline", style = AdTextStyle(fontSizeSp = 16f), maxLines = 2)
                text(text = "Fieldnotes · 4.8 stars", style = AdTextStyle(fontSizeSp = 12f), maxLines = 1)
            }
            callToAction(
                modifier = AdModifier.wrapContentWidth().height(36.dp).padding(horizontal = 16.dp),
                style = ctaStyle,
            )
        }
    }

    @Test
    fun `a wrap-content call to action beside a weighted column keeps its label width`() {
        val root = render(handoffRow(), widthDp = 358f)
        val cta = root.cta()
        val label = cta.label()

        assertEquals(label.measuredWidth + 2 * px(16f), cta.width, "the button must be its label plus padding")
        assertEquals(px(36f), cta.height)
        val column = (cta.parent as ViewGroup).getChildAt(0)
        assertEquals(px(358f) - px(12f) - cta.width, column.width, "the weighted column takes the rest of the row")
    }

    @Test
    fun `a fixed-height call to action keeps the author's height and overflows its label evenly`() {
        // 40sp needs a line well over 36dp, so the overflow is certain rather than dependent on
        // Android 14's non-linear font scaling.
        val style = AdButtonStyle.filled.copy(textStyle = AdButtonStyle.filled.textStyle.copy(fontSizeSp = 40f))
        val root = render(handoffRow(style), widthDp = 358f)
        val cta = root.cta()
        val label = cta.label()
        val natural = label.naturalHeight()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)

        assertTrue(natural > px(36f), "precondition: the line ($natural px) must be taller than the box")
        assertEquals(px(36f), cta.height, "the SDK must never change the author's fixed height")
        assertEquals(natural, label.height, "the label must keep its full line height and overflow instead")
        val overTop = -label.top
        val overBottom = label.bottom - cta.height
        assertTrue(
            abs(overTop - overBottom) <= 1,
            "overflow must be split evenly: top $overTop px, bottom $overBottom px",
        )
    }

    @Test
    fun `a call to action with no size or padding is its label plus the default insets`() {
        val layout = adLayout { callToAction() }
        val root = render(layout, widthDp = 358f)
        val cta = root.cta()
        val label = cta.label()

        assertEquals(label.measuredHeight + 2 * px(10f), cta.height)
        assertEquals(label.measuredWidth + 2 * px(AdButtonStyle.filled.horizontalPaddingDp), cta.width)
    }
}
