package dev.avinya.admob.showcase.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The editorial type scale.
 *
 * Serif carries the reading voice — display text, headlines, titles, pull
 * quotes. Sans carries the interface — labels, controls, metadata. No remote
 * font is fetched; both families resolve to the platform's own, which is why
 * the app looks native-weight on iOS and Android without shipping binaries.
 *
 * Every Material role is populated below. Leaving roles unmapped is how the
 * previous scale ended up silently falling back to Roboto on `displaySmall`,
 * `headlineSmall`, and `titleSmall`.
 */
object ShowcaseType {

    private val serif = FontFamily.Serif
    private val sans = FontFamily.SansSerif

    val displayLarge = TextStyle(
        fontFamily = serif,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Normal,
    )
    val displayMedium = TextStyle(
        fontFamily = serif,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Normal,
    )
    val displaySmall = TextStyle(
        fontFamily = serif,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
    )

    val headlineLarge = TextStyle(
        fontFamily = serif,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val headlineMedium = TextStyle(
        fontFamily = serif,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val headlineSmall = TextStyle(
        fontFamily = serif,
        fontSize = 23.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    )

    val titleLarge = TextStyle(
        fontFamily = serif,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
    )
    val titleMedium = TextStyle(
        fontFamily = serif,
        fontSize = 18.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Medium,
    )
    val titleSmall = TextStyle(
        fontFamily = sans,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )

    val bodyLarge = TextStyle(
        fontFamily = sans,
        fontSize = 17.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Normal,
    )
    val bodyMedium = TextStyle(
        fontFamily = sans,
        fontSize = 15.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
    )
    val bodySmall = TextStyle(
        fontFamily = sans,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.Normal,
    )

    val labelLarge = TextStyle(
        fontFamily = sans,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val labelMedium = TextStyle(
        fontFamily = sans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    )
    val labelSmall = TextStyle(
        fontFamily = sans,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        fontWeight = FontWeight.Medium,
    )

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

internal val ShowcaseTypography = Typography(
    displayLarge = ShowcaseType.displayLarge,
    displayMedium = ShowcaseType.displayMedium,
    displaySmall = ShowcaseType.displaySmall,
    headlineLarge = ShowcaseType.headlineLarge,
    headlineMedium = ShowcaseType.headlineMedium,
    headlineSmall = ShowcaseType.headlineSmall,
    titleLarge = ShowcaseType.titleLarge,
    titleMedium = ShowcaseType.titleMedium,
    titleSmall = ShowcaseType.titleSmall,
    bodyLarge = ShowcaseType.bodyLarge,
    bodyMedium = ShowcaseType.bodyMedium,
    bodySmall = ShowcaseType.bodySmall,
    labelLarge = ShowcaseType.labelLarge,
    labelMedium = ShowcaseType.labelMedium,
    labelSmall = ShowcaseType.labelSmall,
)
