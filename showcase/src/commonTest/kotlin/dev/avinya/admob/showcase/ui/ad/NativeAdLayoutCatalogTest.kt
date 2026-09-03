package dev.avinya.admob.showcase.ui.ad

import androidx.compose.ui.text.font.FontFamily
import dev.avinya.admob.showcase.ui.theme.ShowcaseDarkPalette
import dev.avinya.admob.showcase.ui.theme.ShowcaseLightPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers every layout in [ShowcaseNativeAdLayout], including the three Fieldnotes ones whose
 * `@Preview`s live beside their own composables. Policy compliance is asserted here rather than
 * per-file precisely because a preview cannot assert anything.
 */
class NativeAdLayoutCatalogTest {

    @Test
    fun `catalog lists every native layout the showcase can render`() {
        assertEquals(
            listOf("Compact", "Medium", "Feed card", "Fieldnotes feed", "Fieldnotes row", "Fieldnotes inline"),
            ShowcaseNativeAdLayout.entries.map { it.label },
        )
    }

    @Test
    fun `every catalogued layout is policy compliant in both themes`() {
        listOf(ShowcaseLightPalette, ShowcaseDarkPalette).forEach { palette ->
            ShowcaseNativeAdLayout.entries.forEach { layoutCase ->
                val layout = layoutCase.layout(palette, FontFamily.Serif)

                assertTrue(
                    layout.validation.errors.isEmpty(),
                    "${layoutCase.label} has validation errors: ${layout.validation.errors}",
                )
            }
        }
    }
}
