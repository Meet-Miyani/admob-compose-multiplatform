package dev.avinya.admob.showcase.ui.ad

import androidx.compose.ui.text.font.FontFamily
import dev.avinya.admob.showcase.ui.theme.ShowcaseDarkPalette
import dev.avinya.admob.showcase.ui.theme.ShowcaseLightPalette
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.ads.nativead.layout.AdLayout
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every showcase ad layout must paint its own opaque root.
 *
 * The iOS renderer embeds the ad below Compose's Metal canvas, and Compose clears every pixel it
 * drew under the interop rect. A transparent root therefore composites onto the platform backdrop
 * — `UIColor.whiteColor` under a light system appearance — which is how a dark-themed feed ended up
 * showing a white block behind its sponsored row.
 */
class AdLayoutSurfaceTest {

    private fun layoutsFor(palette: ShowcasePalette): List<Pair<String, AdLayout>> = listOf(
        "feedAdLayout" to feedAdLayout(palette, FontFamily.Serif, palette.surface),
        "feedRowAdLayout" to feedRowAdLayout(palette, FontFamily.Serif, palette.canvas),
        "inlineAdLayout" to inlineAdLayout(palette, FontFamily.Serif, palette.surface),
    )

    private fun assertOpaqueRoots(palette: ShowcasePalette, paletteName: String) {
        layoutsFor(palette).forEach { (name, layout) ->
            assertTrue(
                layout.validation.warnings.none { it.code == "transparent_root_background" },
                "$name has a transparent root under $paletteName; it will show the iOS platform " +
                    "backdrop instead of the app surface.",
            )
        }
    }

    @Test
    fun `showcase ad layouts declare an opaque root in the light palette`() {
        assertOpaqueRoots(ShowcaseLightPalette, "ShowcaseLightPalette")
    }

    @Test
    fun `showcase ad layouts declare an opaque root in the dark palette`() {
        assertOpaqueRoots(ShowcaseDarkPalette, "ShowcaseDarkPalette")
    }
}
