package dev.avinya.admob.showcase.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class FieldnotesThemeTest {

    @Test
    fun fieldnotesLightColors_matchApprovedPalette() {
        assertEquals(Color(0xFFF5F0E6), FieldnotesLight.page)
        assertEquals(Color(0xFFFCFAF5), FieldnotesLight.raisedSurface)
        assertEquals(Color(0xFF181713), FieldnotesLight.ink)
        assertEquals(Color(0xFF6D685F), FieldnotesLight.mutedInk)
        assertEquals(Color(0xFFD8D0C2), FieldnotesLight.rule)
        assertEquals(Color(0xFFC6452D), FieldnotesLight.accent)
        assertEquals(Color(0xFFF3D7CF), FieldnotesLight.accentContainer)
    }

    @Test
    fun fieldnotesDarkColors_matchApprovedPalette() {
        assertEquals(Color(0xFF151411), FieldnotesDark.page)
        assertEquals(Color(0xFF201E1A), FieldnotesDark.raisedSurface)
        assertEquals(Color(0xFFF3EEE4), FieldnotesDark.ink)
        assertEquals(Color(0xFFAAA397), FieldnotesDark.mutedInk)
        assertEquals(Color(0xFF3A3630), FieldnotesDark.rule)
        assertEquals(Color(0xFFF0785F), FieldnotesDark.accent)
        assertEquals(Color(0xFF5A281F), FieldnotesDark.accentContainer)
    }

    @Test
    fun lightColorScheme_mapsToFieldnotesSemanticRoles() {
        assertEquals(FieldnotesLight.page, ShowcaseLightColors.background)
        assertEquals(FieldnotesLight.raisedSurface, ShowcaseLightColors.surface)
        assertEquals(FieldnotesLight.ink, ShowcaseLightColors.onBackground)
        assertEquals(FieldnotesLight.ink, ShowcaseLightColors.onSurface)
        assertEquals(FieldnotesLight.mutedInk, ShowcaseLightColors.onSurfaceVariant)
        assertEquals(FieldnotesLight.accent, ShowcaseLightColors.primary)
        assertEquals(FieldnotesLight.accentContainer, ShowcaseLightColors.primaryContainer)
        assertEquals(FieldnotesLight.rule, ShowcaseLightColors.outlineVariant)
    }

    @Test
    fun darkColorScheme_mapsToFieldnotesSemanticRoles() {
        assertEquals(FieldnotesDark.page, ShowcaseDarkColors.background)
        assertEquals(FieldnotesDark.raisedSurface, ShowcaseDarkColors.surface)
        assertEquals(FieldnotesDark.ink, ShowcaseDarkColors.onBackground)
        assertEquals(FieldnotesDark.ink, ShowcaseDarkColors.onSurface)
        assertEquals(FieldnotesDark.mutedInk, ShowcaseDarkColors.onSurfaceVariant)
        assertEquals(FieldnotesDark.accent, ShowcaseDarkColors.primary)
        assertEquals(FieldnotesDark.accentContainer, ShowcaseDarkColors.primaryContainer)
        assertEquals(FieldnotesDark.rule, ShowcaseDarkColors.outlineVariant)
    }

    @Test
    fun compactNavigationBreakpoint_is840Dp() {
        assertEquals(840.dp, FieldnotesTokens.navigationRailBreakpoint)
    }

    @Test
    fun adRevealMillis_is180() {
        assertEquals(180, FieldnotesTokens.adRevealMillis)
    }

    @Test
    fun tokensScale_matchesDesignSpec() {
        assertEquals(0.dp, FieldnotesTokens.Radii.structure)
        assertEquals(8.dp, FieldnotesTokens.Radii.image)
        assertEquals(12.dp, FieldnotesTokens.Radii.card)
        assertEquals(20.dp, FieldnotesTokens.Spacing.gutterCompact)
        assertEquals(32.dp, FieldnotesTokens.Spacing.gutterExpanded)
    }

    @Test
    fun typographyScale_usesCorrectFontFamiliesAndSizes() {
        assertEquals(44.sp, FieldnotesTypography.displayExpanded.fontSize)
        assertEquals(48.sp, FieldnotesTypography.displayExpanded.lineHeight)
        assertEquals(FontFamily.Serif, FieldnotesTypography.displayExpanded.fontFamily)

        assertEquals(38.sp, FieldnotesTypography.displayCompact.fontSize)
        assertEquals(42.sp, FieldnotesTypography.displayCompact.lineHeight)
        assertEquals(FontFamily.Serif, FieldnotesTypography.displayCompact.fontFamily)

        assertEquals(30.sp, FieldnotesTypography.sectionHeadline.fontSize)
        assertEquals(34.sp, FieldnotesTypography.sectionHeadline.lineHeight)
        assertEquals(FontFamily.Serif, FieldnotesTypography.sectionHeadline.fontFamily)

        assertEquals(22.sp, FieldnotesTypography.cardTitle.fontSize)
        assertEquals(26.sp, FieldnotesTypography.cardTitle.lineHeight)
        assertEquals(FontFamily.Serif, FieldnotesTypography.cardTitle.fontFamily)

        assertEquals(17.sp, FieldnotesTypography.body.fontSize)
        assertEquals(27.sp, FieldnotesTypography.body.lineHeight)
        assertEquals(FontFamily.SansSerif, FieldnotesTypography.body.fontFamily)

        assertEquals(12.sp, FieldnotesTypography.metadataLabel.fontSize)
        assertEquals(16.sp, FieldnotesTypography.metadataLabel.lineHeight)
        assertEquals(FontFamily.SansSerif, FieldnotesTypography.metadataLabel.fontFamily)
    }
}
