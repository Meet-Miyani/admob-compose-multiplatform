package dev.avinya.ads.nativead.rendering

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import dev.avinya.ads.nativead.layout.AdLayoutSize
import dev.avinya.ads.nativead.layout.AdModifier
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal fun AdLayoutSize.toLayoutParam(density: Float): Int = when (this) {
    AdLayoutSize.Match -> ViewGroup.LayoutParams.MATCH_PARENT
    AdLayoutSize.Wrap -> ViewGroup.LayoutParams.WRAP_CONTENT
    is AdLayoutSize.Fixed -> dp(density, dp)
}

internal fun dp(density: Float, value: Float): Int = (value * density).roundToInt()

internal class ModifierMeasureFrameLayout(
    context: Context,
    private val density: Float,
    private val modifier: AdModifier
) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cappedWidthSpec = capMeasureSpec(widthMeasureSpec, modifier.maxWidthDp)
        val cappedHeightSpec = capMeasureSpec(heightMeasureSpec, modifier.maxHeightDp)
        super.onMeasure(cappedWidthSpec, cappedHeightSpec)

        var width = measuredWidth
        var height = measuredHeight
        val minWidth = dp(density, modifier.minWidthDp ?: 0f)
        val minHeight = dp(density, modifier.minHeightDp ?: 0f)
        val maxWidth = modifier.maxWidthDp?.let { dp(density, it) }
        val maxHeight = modifier.maxHeightDp?.let { dp(density, it) }

        width = max(width, minWidth)
        height = max(height, minHeight)

        modifier.aspectRatio?.takeIf { it > 0f }?.let { ratio ->
            if (width > 0) {
                height = (width / ratio).roundToInt()
            } else if (height > 0) {
                width = (height * ratio).roundToInt()
            }
        }

        maxWidth?.let { width = min(width, it) }
        maxHeight?.let { height = min(height, it) }
        width = max(width, minWidth)
        height = max(height, minHeight)

        setMeasuredDimension(width, height)
        val childWidth = max(0, width - paddingLeft - paddingRight)
        val childHeight = max(0, height - paddingTop - paddingBottom)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY)
                )
            }
        }
    }

    private fun capMeasureSpec(spec: Int, maxDp: Float?): Int {
        val maxPx = maxDp?.let { dp(density, it) } ?: return spec
        val mode = MeasureSpec.getMode(spec)
        val size = MeasureSpec.getSize(spec)
        return when (mode) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(min(size, maxPx), mode)
        }
    }
}

/**
 * The call-to-action's surface: sized like a plain `FrameLayout`, but its label always keeps its
 * full line height.
 *
 * The SDK never changes a height the layout author fixed, so a line taller than the button —
 * large text, or a large font scale — has to overflow it. A plain `FrameLayout` measures a
 * wrap-content child `AT_MOST` its own content height, which clamps the label to the box; a
 * `TextView` then draws from its top (its vertical gravity only applies to text *shorter* than the
 * view), so the overflow was cut from the bottom alone and the baseline went with it. Re-measuring
 * a clamped label at its natural height lets the `Gravity.CENTER` it is added with centre the whole
 * line — `FrameLayout` places a child taller than itself at a negative offset — and `clipChildren`
 * trims the overflow evenly from both edges, as the preview and iOS do.
 *
 * The container's own size is untouched: it is still the author's fixed height, or the label plus
 * the insets when it wraps.
 */
internal class CallToActionFrameLayout(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val contentHeight = max(0, measuredHeight - paddingTop - paddingBottom)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE || child.measuredHeight < contentHeight) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(child.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
    }
}

internal object RectOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRect(0, 0, view.width, view.height)
    }
}

internal class RoundedOutlineProvider(private val radiusPx: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
    }
}

internal object CircleOutlineProvider : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        val size = min(view.width, view.height)
        val left = (view.width - size) / 2
        val top = (view.height - size) / 2
        outline.setOval(left, top, left + size, top + size)
    }
}
