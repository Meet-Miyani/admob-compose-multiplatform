package dev.avinya.ads.nativead.rendering

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.adLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ComposeFontResolutionTest {
    private val firstFamily = AdFontFamily.FromCompose(FontFamily.Serif)
    private val secondFamily = AdFontFamily.FromCompose(FontFamily.Monospace)

    @Test
    fun `collects every text-bearing node and deduplicates equal requests`() {
        val normal = AdTextStyle(fontFamily = firstFamily)
        val medium = AdTextStyle(fontFamily = firstFamily, fontWeight = AdFontWeight.Medium)
        val bold = AdTextStyle(fontFamily = secondFamily, fontWeight = AdFontWeight.Bold)
        val layout = adLayout {
            column {
                text("Static", style = normal)
                headline(style = medium)
                body(style = normal)
                advertiser(style = bold)
                price(style = normal)
                store(style = medium)
                starRating(style = normal)
                adBadge(style = bold)
                callToAction(style = AdButtonStyle(textStyle = medium))
            }
        }

        assertEquals(
            setOf(
                ComposeFontRequest(FontFamily.Serif, FontWeight.Normal),
                ComposeFontRequest(FontFamily.Serif, FontWeight.Medium),
                ComposeFontRequest(FontFamily.Monospace, FontWeight.Bold),
            ),
            layout.composeFontRequests(),
        )
    }

    @Test
    fun `ignores platform font families and non-text nodes`() {
        val layout = adLayout {
            column {
                headline(style = AdTextStyle(fontFamily = AdFontFamily.Named("Installed")))
                body(style = AdTextStyle(fontFamily = AdFontFamily.SansSerif))
                icon()
                media()
                adChoices()
            }
        }

        assertEquals(emptySet(), layout.composeFontRequests())
    }

    @Test
    fun `maps ad font weights to exact compose weights`() {
        assertEquals(FontWeight.Normal, AdFontWeight.Normal.toComposeFontWeight())
        assertEquals(FontWeight.Medium, AdFontWeight.Medium.toComposeFontWeight())
        assertEquals(FontWeight.Bold, AdFontWeight.Bold.toComposeFontWeight())
    }

    @Test
    fun `looks up only the exact compose family and weight request`() {
        val token = Any()
        val fonts = ResolvedComposeFonts(
            mapOf(ComposeFontRequest(FontFamily.Serif, FontWeight.Medium) to token)
        )

        assertSame(token, firstFamily.resolvedComposeValueOrNull(AdFontWeight.Medium, fonts))
        assertNull(firstFamily.resolvedComposeValueOrNull(AdFontWeight.Bold, fonts))
        assertNull(AdFontFamily.Named("Installed").resolvedComposeValueOrNull(AdFontWeight.Medium, fonts))
    }
}
