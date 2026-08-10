package dev.avinya.admob.showcase.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * WCAG 2.1 relative luminance and contrast ratio.
 *
 * Both are pure functions of sRGB values, so the palette's contrast can be
 * pinned in a common unit test without an emulator or a screenshot harness.
 */
internal fun luminance(color: Color): Double {
    fun channel(c: Float): Double =
        if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

internal fun contrastRatio(background: Color, foreground: Color): Double {
    val a = luminance(background)
    val b = luminance(foreground)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
