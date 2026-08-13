package dev.avinya.admob.showcase.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import admobcmp.showcase.generated.resources.Res
import admobcmp.showcase.generated.resources.newsreader_variable
import org.jetbrains.compose.resources.Font

@Composable
internal fun rememberNewsreaderFontFamily(): FontFamily {
    val normal = Font(Res.font.newsreader_variable, FontWeight.Normal)
    val medium = Font(Res.font.newsreader_variable, FontWeight.Medium)
    val semiBold = Font(Res.font.newsreader_variable, FontWeight.SemiBold)
    val bold = Font(Res.font.newsreader_variable, FontWeight.Bold)
    return remember(normal, medium, semiBold, bold) {
        FontFamily(normal, medium, semiBold, bold)
    }
}

internal fun buildShowcaseTypography(editorialFamily: FontFamily): Typography = Typography(
    displayLarge = typeStyle(editorialFamily, FontWeight.Normal, 44, 48),
    displayMedium = typeStyle(editorialFamily, FontWeight.Normal, 38, 42),
    displaySmall = typeStyle(editorialFamily, FontWeight.SemiBold, 32, 38),
    headlineLarge = typeStyle(editorialFamily, FontWeight.SemiBold, 30, 34),
    headlineMedium = typeStyle(editorialFamily, FontWeight.SemiBold, 26, 31),
    headlineSmall = typeStyle(editorialFamily, FontWeight.SemiBold, 23, 28),
    titleLarge = typeStyle(editorialFamily, FontWeight.Medium, 21, 26),
    titleMedium = typeStyle(editorialFamily, FontWeight.Medium, 18, 23),
    titleSmall = typeStyle(FontFamily.SansSerif, FontWeight.SemiBold, 15, 20),
    bodyLarge = typeStyle(FontFamily.SansSerif, FontWeight.Normal, 17, 27),
    bodyMedium = typeStyle(FontFamily.SansSerif, FontWeight.Normal, 15, 23),
    bodySmall = typeStyle(FontFamily.SansSerif, FontWeight.Normal, 13, 19),
    labelLarge = typeStyle(FontFamily.SansSerif, FontWeight.SemiBold, 14, 18),
    labelMedium = typeStyle(FontFamily.SansSerif, FontWeight.Medium, 12, 16),
    labelSmall = typeStyle(FontFamily.SansSerif, FontWeight.Medium, 11, 15),
)

private fun typeStyle(
    family: FontFamily,
    weight: FontWeight,
    size: Int,
    lineHeight: Int,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

/**
 * The editorial type scale.
 *
 * Newsreader carries the editorial display roles through Material typography.
 * The remaining bespoke roles below are intentionally sans-serif UI accents.
 */
object ShowcaseType {

    private val sans = FontFamily.SansSerif

    /**
     * Uppercase taxonomy label — section names, "SPONSORED", "PREMIUM".
     * Tracked out, because short uppercase strings need the air.
     */
    val eyebrow = TextStyle(
        fontFamily = sans,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
    )

    /** The wordmark in the app header. */
    val wordmark = TextStyle(
        fontFamily = sans,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )

    /** Numerals that need to line up in a column — balances, counters. */
    val numeric = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    )
}
