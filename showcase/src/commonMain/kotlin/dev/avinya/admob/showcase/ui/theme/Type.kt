package dev.avinya.admob.showcase.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Locked Fieldnotes editorial typography scale.
 * Uses platform default serif for editorial titles and sans-serif for interface/body.
 */
object FieldnotesTypography {
    val displayExpanded = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Normal,
    )
    val displayCompact = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        fontWeight = FontWeight.Normal,
    )
    val sectionHeadline = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val cardTitle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Medium,
    )
    val body = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 17.sp,
        lineHeight = 27.sp,
        fontWeight = FontWeight.Normal,
    )
    val metadataLabel = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    )
}

internal val ShowcaseTypography = Typography(
    displayLarge = FieldnotesTypography.displayExpanded,
    displayMedium = FieldnotesTypography.displayCompact,
    headlineLarge = FieldnotesTypography.sectionHeadline,
    headlineMedium = FieldnotesTypography.sectionHeadline.copy(fontSize = 26.sp, lineHeight = 30.sp),
    titleLarge = FieldnotesTypography.cardTitle,
    titleMedium = FieldnotesTypography.cardTitle.copy(fontSize = 18.sp, lineHeight = 22.sp),
    bodyLarge = FieldnotesTypography.body,
    bodyMedium = FieldnotesTypography.body.copy(fontSize = 15.sp, lineHeight = 23.sp),
    labelLarge = FieldnotesTypography.metadataLabel.copy(fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = FieldnotesTypography.metadataLabel,
    labelSmall = FieldnotesTypography.metadataLabel,
)


