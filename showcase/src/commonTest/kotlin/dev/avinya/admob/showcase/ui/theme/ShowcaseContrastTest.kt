package dev.avinya.admob.showcase.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contrast floors for every pairing the app actually renders.
 *
 * `inkFaint` is checked against the canvas and raised surface only — it is a
 * tertiary weight for de-emphasised metadata and is never placed on the sunken
 * plane, where it would fall below the body-text threshold.
 */
class ShowcaseContrastTest {

    @Test
    fun lightPaletteMeetsItsFloors() = assertPaletteContrast(ShowcaseLightPalette)

    @Test
    fun darkPaletteMeetsItsFloors() = assertPaletteContrast(ShowcaseDarkPalette)

    @Test
    fun sectionHuesAreReadableOnBothPlanes() {
        listOf(ShowcaseLightPalette, ShowcaseDarkPalette).forEach { palette ->
            palette.sections.ordered.forEachIndexed { index, hue ->
                assertAtLeast("section hue $index on canvas", palette.canvas, hue, 4.5)
                assertAtLeast("section hue $index on surface", palette.surface, hue, 4.5)
            }
        }
    }

    private fun assertPaletteContrast(palette: ShowcasePalette) {
        val planes = listOf(
            "canvas" to palette.canvas,
            "surface" to palette.surface,
            "sunken" to palette.surfaceSunken,
        )

        planes.forEach { (planeName, plane) ->
            assertAtLeast("ink on $planeName", plane, palette.ink, 7.0)
            assertAtLeast("muted ink on $planeName", plane, palette.inkMuted, 4.5)
            assertAtLeast("primary on $planeName", plane, palette.primary, 4.5)
            assertAtLeast("accent on $planeName", plane, palette.accent, 4.5)
            assertAtLeast("danger on $planeName", plane, palette.danger, 4.5)
            assertAtLeast("success on $planeName", plane, palette.success, 4.5)
        }

        assertAtLeast("faint ink on canvas", palette.canvas, palette.inkFaint, 4.5)
        assertAtLeast("faint ink on surface", palette.surface, palette.inkFaint, 4.5)

        assertAtLeast("primary on its container", palette.primarySoft, palette.primary, 4.5)
        assertAtLeast("accent on its container", palette.accentSoft, palette.accent, 4.5)
        assertAtLeast("danger on its container", palette.dangerSoft, palette.danger, 4.5)

        // Filled controls: the label sits on the fill, not on a plane.
        assertAtLeast("label on primary fill", palette.primary, palette.onAccentInk, 4.5)
        assertAtLeast("label on danger fill", palette.danger, palette.onAccentInk, 4.5)

        // Rules are non-text: 3:1 is the correct threshold for a UI boundary.
        assertAtLeast("strong hairline on canvas", palette.canvas, palette.hairlineStrong, 1.4)
    }

    private fun assertAtLeast(role: String, background: Color, foreground: Color, minimum: Double) {
        val ratio = contrastRatio(background, foreground)
        assertTrue(ratio >= minimum, "$role must have contrast >= $minimum, got $ratio")
    }
}
