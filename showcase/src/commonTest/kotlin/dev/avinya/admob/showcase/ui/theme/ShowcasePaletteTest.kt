package dev.avinya.admob.showcase.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Structural checks over the palette.
 *
 * These pin the properties the design language depends on rather than every
 * hex value, so a deliberate colour tweak does not fail the suite while a
 * structural mistake — a duplicated section hue, an error colour that has
 * drifted back into the brand accent — still does.
 */
class ShowcasePaletteTest {

    @Test
    fun bothThemesDeclareTheirMode() {
        assertEquals(false, ShowcaseLightPalette.isDark)
        assertEquals(true, ShowcaseDarkPalette.isDark)
    }

    @Test
    fun dangerIsDistinctFromBothAccents() {
        // The previous palette mapped `error` to the brand accent, so a failed
        // ad load looked identical to a section label. It must not regress.
        listOf(ShowcaseLightPalette, ShowcaseDarkPalette).forEach { palette ->
            assertNotEquals(palette.danger, palette.primary)
            assertNotEquals(palette.danger, palette.accent)
        }
    }

    @Test
    fun interactiveAndEditorialAccentsAreDistinct() {
        listOf(ShowcaseLightPalette, ShowcaseDarkPalette).forEach { palette ->
            assertNotEquals(palette.primary, palette.accent)
        }
    }

    @Test
    fun sectionHuesAreSixAndAllDistinct() {
        listOf(ShowcaseLightPalette, ShowcaseDarkPalette).forEach { palette ->
            val hues = palette.sections.ordered
            assertEquals(6, hues.size)
            assertEquals(6, hues.toSet().size, "section hues must be distinct")
        }
    }

    @Test
    fun materialSchemesMapToTheAppPalette() {
        assertEquals(ShowcaseLightPalette.canvas, ShowcaseLightColors.background)
        assertEquals(ShowcaseLightPalette.surface, ShowcaseLightColors.surface)
        assertEquals(ShowcaseLightPalette.ink, ShowcaseLightColors.onSurface)
        assertEquals(ShowcaseLightPalette.inkMuted, ShowcaseLightColors.onSurfaceVariant)
        assertEquals(ShowcaseLightPalette.primary, ShowcaseLightColors.primary)
        assertEquals(ShowcaseLightPalette.danger, ShowcaseLightColors.error)
        assertEquals(ShowcaseLightPalette.hairline, ShowcaseLightColors.outlineVariant)

        assertEquals(ShowcaseDarkPalette.canvas, ShowcaseDarkColors.background)
        assertEquals(ShowcaseDarkPalette.surface, ShowcaseDarkColors.surface)
        assertEquals(ShowcaseDarkPalette.ink, ShowcaseDarkColors.onSurface)
        assertEquals(ShowcaseDarkPalette.inkMuted, ShowcaseDarkColors.onSurfaceVariant)
        assertEquals(ShowcaseDarkPalette.primary, ShowcaseDarkColors.primary)
        assertEquals(ShowcaseDarkPalette.danger, ShowcaseDarkColors.error)
        assertEquals(ShowcaseDarkPalette.hairline, ShowcaseDarkColors.outlineVariant)
    }

    @Test
    fun tonalElevationIsDisabled() {
        // The design language separates planes with a shadow and a hairline.
        // A non-transparent surface tint would add Material's tonal wash on
        // top of that and muddy every card.
        assertEquals(Color.Transparent, ShowcaseLightColors.surfaceTint)
        assertEquals(Color.Transparent, ShowcaseDarkColors.surfaceTint)
    }

    @Test
    fun hairlineIsLighterThanItsStrongCounterpart() {
        assertTrue(
            luminance(ShowcaseLightPalette.hairline) > luminance(ShowcaseLightPalette.hairlineStrong),
            "light hairline must be the subtler of the two",
        )
        assertTrue(
            luminance(ShowcaseDarkPalette.hairline) < luminance(ShowcaseDarkPalette.hairlineStrong),
            "dark hairline must be the subtler of the two",
        )
    }
}
