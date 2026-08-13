package dev.avinya.ads.nativead.rendering

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdContentScale
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdInsets
import dev.avinya.ads.nativead.layout.AdTextAlign

internal fun AdInsets.isZero(): Boolean = startDp == 0f && topDp == 0f && endDp == 0f && bottomDp == 0f

internal fun roundedDrawable(
    density: Float,
    colorArgb: Long,
    radiusDp: Float,
    borderWidthDp: Float? = null,
    borderColorArgb: Long? = null
): GradientDrawable =
    GradientDrawable().apply {
        setColor(colorArgb.toAndroidColor())
        cornerRadius = dp(density, radiusDp).toFloat()
        if (borderWidthDp != null && borderColorArgb != null) {
            setStroke(dp(density, borderWidthDp), borderColorArgb.toAndroidColor())
        }
    }

internal fun Long.toAndroidColor(): Int = Color.argb(
    ((this shr 24) and 0xFF).toInt(),
    ((this shr 16) and 0xFF).toInt(),
    ((this shr 8) and 0xFF).toInt(),
    (this and 0xFF).toInt()
)

internal fun AdFontWeight.toStyle(): Int = when (this) {
    AdFontWeight.Normal -> Typeface.NORMAL
    AdFontWeight.Medium, AdFontWeight.Bold -> Typeface.BOLD
}

internal fun AdFontFamily.toTypeface(
    weight: AdFontWeight,
    resolvedComposeFonts: ResolvedComposeFonts = ResolvedComposeFonts.Empty,
): Typeface {
    val style = weight.toStyle()
    return when (this) {
        AdFontFamily.Default, AdFontFamily.SansSerif -> Typeface.create(Typeface.SANS_SERIF, style)
        AdFontFamily.Serif -> Typeface.create(Typeface.SERIF, style)
        AdFontFamily.Monospace -> Typeface.create(Typeface.MONOSPACE, style)
        is AdFontFamily.Named -> Typeface.create(name, style) ?: Typeface.create(Typeface.DEFAULT, style)
        is AdFontFamily.FromCompose -> {
            resolvedComposeValueOrNull(weight, resolvedComposeFonts) as? Typeface
                ?: Typeface.create(Typeface.DEFAULT, style)
        }
    }
}

internal fun AdFontWeight.toTypeface(): Typeface = AdFontFamily.Default.toTypeface(this)

internal fun AdTextAlign.toAndroidGravity(): Int = when (this) {
    AdTextAlign.Start -> Gravity.START
    AdTextAlign.Center -> Gravity.CENTER
    AdTextAlign.End -> Gravity.END
}

internal fun AdAlignment.Horizontal.toAndroidGravity(): Int = when (this) {
    AdAlignment.Horizontal.Start -> Gravity.START
    AdAlignment.Horizontal.CenterHorizontally -> Gravity.CENTER_HORIZONTAL
    AdAlignment.Horizontal.End -> Gravity.END
}

internal fun AdAlignment.Vertical.toAndroidGravity(): Int = when (this) {
    AdAlignment.Vertical.Top -> Gravity.TOP
    AdAlignment.Vertical.CenterVertically -> Gravity.CENTER_VERTICAL
    AdAlignment.Vertical.Bottom -> Gravity.BOTTOM
}

internal fun AdAlignment.Box.toAndroidGravity(): Int = when (this) {
    AdAlignment.Box.TopStart -> Gravity.TOP or Gravity.START
    AdAlignment.Box.TopCenter -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
    AdAlignment.Box.TopEnd -> Gravity.TOP or Gravity.END
    AdAlignment.Box.CenterStart -> Gravity.CENTER_VERTICAL or Gravity.START
    AdAlignment.Box.Center -> Gravity.CENTER
    AdAlignment.Box.CenterEnd -> Gravity.CENTER_VERTICAL or Gravity.END
    AdAlignment.Box.BottomStart -> Gravity.BOTTOM or Gravity.START
    AdAlignment.Box.BottomCenter -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    AdAlignment.Box.BottomEnd -> Gravity.BOTTOM or Gravity.END
}

internal fun AdContentScale.toAndroidScaleType(): ImageView.ScaleType = when (this) {
    AdContentScale.Fit -> ImageView.ScaleType.FIT_CENTER
    AdContentScale.Crop -> ImageView.ScaleType.CENTER_CROP
    AdContentScale.FillBounds -> ImageView.ScaleType.FIT_XY
}
