package dev.avinya.admob.showcase.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class ShowcaseTypographyTest {
    @Test
    fun `editorial family is limited to display headline and large title roles`() {
        val editorial = FontFamily.Cursive
        val typography = buildShowcaseTypography(editorial)

        val editorialRoles = listOf(
            typography.displayLarge,
            typography.displayMedium,
            typography.displaySmall,
            typography.headlineLarge,
            typography.headlineMedium,
            typography.headlineSmall,
            typography.titleLarge,
            typography.titleMedium,
        )
        val interfaceRoles = listOf(
            typography.titleSmall,
            typography.bodyLarge,
            typography.bodyMedium,
            typography.bodySmall,
            typography.labelLarge,
            typography.labelMedium,
            typography.labelSmall,
        )

        editorialRoles.forEach { assertEquals(editorial, it.fontFamily) }
        interfaceRoles.forEach { assertEquals(FontFamily.SansSerif, it.fontFamily) }
    }

    @Test
    fun `custom family preserves every established material metric`() {
        val typography = buildShowcaseTypography(FontFamily.Cursive)
        listOf(
            typography.displayLarge to Metric(44, 48, FontWeight.Normal),
            typography.displayMedium to Metric(38, 42, FontWeight.Normal),
            typography.displaySmall to Metric(32, 38, FontWeight.SemiBold),
            typography.headlineLarge to Metric(30, 34, FontWeight.SemiBold),
            typography.headlineMedium to Metric(26, 31, FontWeight.SemiBold),
            typography.headlineSmall to Metric(23, 28, FontWeight.SemiBold),
            typography.titleLarge to Metric(21, 26, FontWeight.Medium),
            typography.titleMedium to Metric(18, 23, FontWeight.Medium),
            typography.titleSmall to Metric(15, 20, FontWeight.SemiBold),
            typography.bodyLarge to Metric(17, 27, FontWeight.Normal),
            typography.bodyMedium to Metric(15, 23, FontWeight.Normal),
            typography.bodySmall to Metric(13, 19, FontWeight.Normal),
            typography.labelLarge to Metric(14, 18, FontWeight.SemiBold),
            typography.labelMedium to Metric(12, 16, FontWeight.Medium),
            typography.labelSmall to Metric(11, 15, FontWeight.Medium),
        ).forEach { (style, expected) ->
            assertEquals(expected.size.sp, style.fontSize)
            assertEquals(expected.lineHeight.sp, style.lineHeight)
            assertEquals(expected.weight, style.fontWeight)
        }
    }

    private data class Metric(val size: Int, val lineHeight: Int, val weight: FontWeight)
}
