package dev.avinya.admob.showcase.ui.ad

import androidx.compose.ui.text.font.FontFamily
import dev.avinya.admob.showcase.ui.theme.ShowcaseLightPalette
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AdLayoutsTypographyTest {
    @Test
    fun `all showcase ad headlines use the supplied compose family`() {
        val family = FontFamily.Cursive
        val layouts = listOf(
            feedAdLayout(ShowcaseLightPalette, family, ShowcaseLightPalette.surface) to AdFontWeight.Bold,
            feedRowAdLayout(ShowcaseLightPalette, family, ShowcaseLightPalette.canvas) to AdFontWeight.Medium,
            inlineAdLayout(ShowcaseLightPalette, family, ShowcaseLightPalette.surface) to AdFontWeight.Bold,
        )

        layouts.forEach { (layout, expectedWeight) ->
            val headline = layout.root.descendants().filterIsInstance<AdAssetNode.Headline>().single()
            val fromCompose = assertIs<AdFontFamily.FromCompose>(headline.style.fontFamily)
            assertEquals(family, fromCompose.fontFamily)
            assertEquals(expectedWeight, headline.style.fontWeight)
        }
    }

    @Test
    fun `non-headline native ad text remains on platform fonts`() {
        val textStyles = listOf(
            feedAdLayout(ShowcaseLightPalette, FontFamily.Cursive, ShowcaseLightPalette.surface),
            feedRowAdLayout(ShowcaseLightPalette, FontFamily.Cursive, ShowcaseLightPalette.canvas),
            inlineAdLayout(ShowcaseLightPalette, FontFamily.Cursive, ShowcaseLightPalette.surface),
        ).flatMap { layout ->
            layout.root.descendants().mapNotNull { node ->
                when (node) {
                    is AdAssetNode.Body -> node.style
                    is AdAssetNode.Advertiser -> node.style
                    is AdAssetNode.Price -> node.style
                    is AdAssetNode.Store -> node.style
                    is AdAssetNode.StarRating -> node.style
                    is AdAssetNode.AdBadge -> node.style
                    is AdAssetNode.CallToAction -> node.style.textStyle
                    else -> null
                }
            }.toList()
        }

        assertFalse(textStyles.isEmpty())
        assertFalse(textStyles.any { it.fontFamily is AdFontFamily.FromCompose })
    }
}

private fun AdNode.descendants(): Sequence<AdNode> = sequence {
    yield(this@descendants)
    if (this@descendants is AdContainerNode) {
        children.forEach { yieldAll(it.descendants()) }
    }
}
