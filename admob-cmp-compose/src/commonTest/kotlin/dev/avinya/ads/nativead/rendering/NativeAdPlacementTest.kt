package dev.avinya.ads.nativead.rendering

import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdLayoutSize
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdNode
import dev.avinya.ads.nativead.layout.AdSpacer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeAdPlacementTest {

    @Test
    fun `row cross axis placement follows vertical alignment`() {
        assertEquals(AdAxisPlacement.Start, rowCrossAxisPlacement(AdAlignment.Vertical.Top))
        assertEquals(AdAxisPlacement.Center, rowCrossAxisPlacement(AdAlignment.Vertical.CenterVertically))
        assertEquals(AdAxisPlacement.End, rowCrossAxisPlacement(AdAlignment.Vertical.Bottom))
    }

    @Test
    fun `column cross axis placement follows horizontal alignment`() {
        assertEquals(AdAxisPlacement.Start, columnCrossAxisPlacement(AdAlignment.Horizontal.Start))
        assertEquals(AdAxisPlacement.Center, columnCrossAxisPlacement(AdAlignment.Horizontal.CenterHorizontally))
        assertEquals(AdAxisPlacement.End, columnCrossAxisPlacement(AdAlignment.Horizontal.End))
    }

    // The main axis is the one iOS ignored outright: `UIStackView` handed any slack to whichever
    // child hugged least, wherever the arrangement actually asked for it to go.
    @Test
    fun `row main axis placement follows horizontal alignment`() {
        assertEquals(AdAxisPlacement.Start, rowMainAxisPlacement(AdAlignment.Horizontal.Start))
        assertEquals(AdAxisPlacement.Center, rowMainAxisPlacement(AdAlignment.Horizontal.CenterHorizontally))
        assertEquals(AdAxisPlacement.End, rowMainAxisPlacement(AdAlignment.Horizontal.End))
    }

    @Test
    fun `column main axis placement follows vertical alignment`() {
        assertEquals(AdAxisPlacement.Start, columnMainAxisPlacement(AdAlignment.Vertical.Top))
        assertEquals(AdAxisPlacement.Center, columnMainAxisPlacement(AdAlignment.Vertical.CenterVertically))
        assertEquals(AdAxisPlacement.End, columnMainAxisPlacement(AdAlignment.Vertical.Bottom))
    }

    // Every one of the nine Box alignments resolves to a distinct pair. iOS pinned all nine to
    // top-start, so a `box(contentAlignment = BottomEnd)` rendered correctly on Android and in the
    // preview, and in the opposite corner on iOS.
    @Test
    fun `every box alignment decomposes into its two axes`() {
        val expected = mapOf(
            AdAlignment.Box.TopStart to (AdAxisPlacement.Start to AdAxisPlacement.Start),
            AdAlignment.Box.TopCenter to (AdAxisPlacement.Center to AdAxisPlacement.Start),
            AdAlignment.Box.TopEnd to (AdAxisPlacement.End to AdAxisPlacement.Start),
            AdAlignment.Box.CenterStart to (AdAxisPlacement.Start to AdAxisPlacement.Center),
            AdAlignment.Box.Center to (AdAxisPlacement.Center to AdAxisPlacement.Center),
            AdAlignment.Box.CenterEnd to (AdAxisPlacement.End to AdAxisPlacement.Center),
            AdAlignment.Box.BottomStart to (AdAxisPlacement.Start to AdAxisPlacement.End),
            AdAlignment.Box.BottomCenter to (AdAxisPlacement.Center to AdAxisPlacement.End),
            AdAlignment.Box.BottomEnd to (AdAxisPlacement.End to AdAxisPlacement.End),
        )
        assertEquals(AdAlignment.Box.entries.size, expected.size, "a Box alignment is unaccounted for")
        expected.forEach { (alignment, axes) ->
            assertEquals(axes.first, alignment.horizontalPlacement(), "$alignment horizontal")
            assertEquals(axes.second, alignment.verticalPlacement(), "$alignment vertical")
        }
    }

    // The reported defect: a 44dp icon inside a column narrowed the whole column to 44dp.
    @Test
    fun `a fixed size child constrains a column's cross axis`() {
        assertTrue(constrainsColumnCrossAxis(AdModifier.empty.size(44.dp)))
    }

    @Test
    fun `a fixed width alone constrains a column's cross axis`() {
        assertTrue(constrainsColumnCrossAxis(AdModifier(width = AdLayoutSize.Fixed(44f))))
    }

    @Test
    fun `a fixed height alone does not constrain a column's cross axis`() {
        assertFalse(constrainsColumnCrossAxis(AdModifier(height = AdLayoutSize.Fixed(44f))))
    }

    @Test
    fun `a max width bound also constrains a column`() {
        assertTrue(constrainsColumnCrossAxis(AdModifier(maxWidthDp = 120f)))
    }

    // Labels and weighted columns must keep stretching, or multi-line text stops wrapping.
    @Test
    fun `an unconstrained child is left to the stack`() {
        assertFalse(constrainsColumnCrossAxis(AdModifier.empty))
        assertFalse(constrainsColumnCrossAxis(AdModifier.empty.weight(1f)))
    }

    @Test
    fun `a match sized child is left to the stack`() {
        assertFalse(constrainsColumnCrossAxis(AdModifier(width = AdLayoutSize.Match)))
    }

    @Test
    fun `unweighted children leave slack for the arrangement`() {
        assertTrue(stackHasMainAxisSlack(listOf(headline(), badge()), stackIsHorizontal = true))
    }

    @Test
    fun `a weighted child claims the slack`() {
        assertFalse(stackHasMainAxisSlack(listOf(headline(AdModifier.empty.weight(1f)), badge()), stackIsHorizontal = true))
    }

    @Test
    fun `a single weighted child needs no ratio`() {
        assertEquals(
            emptyList(),
            resolveWeightRatios(listOf(headline(AdModifier.empty.weight(1f)), badge())),
        )
    }

    @Test
    fun `no weighted children need no ratio`() {
        assertEquals(emptyList(), resolveWeightRatios(listOf(headline(), badge())))
    }

    // The divergence this exists to close: `weight(1f)` beside `weight(2f)` came out 1:1 on iOS,
    // because dropping a hugging priority says a view should absorb slack but not how much.
    @Test
    fun `two weighted children resolve to their ratio`() {
        val ratios = resolveWeightRatios(
            listOf(
                headline(AdModifier.empty.weight(1f)),
                badge(),
                headline(AdModifier.empty.weight(2f)),
            ),
        )
        assertEquals(listOf(AdWeightRatio(childIndex = 2, referenceIndex = 0, multiplier = 2.0)), ratios)
    }

    @Test
    fun `ratios are measured against the first weighted child whatever its index`() {
        val ratios = resolveWeightRatios(
            listOf(
                badge(),
                headline(AdModifier.empty.weight(2f)),
                headline(AdModifier.empty.weight(1f)),
            ),
        )
        assertEquals(listOf(AdWeightRatio(childIndex = 2, referenceIndex = 1, multiplier = 0.5)), ratios)
    }

    // `AdModifier.weight` already discards values <= 0, but `AdModifier` is a public data class, so
    // a directly-constructed modifier can still carry one. Every consumer of a weight has to agree
    // it is not a weight, or the renderers disagree about what the layout means — and Compose's
    // own `Modifier.weight` throws on it.
    @Test
    fun `a non positive weight is ignored rather than divided by`() {
        val ratios = resolveWeightRatios(
            listOf(
                AdSpacer(AdModifier(weight = 0f)),
                headline(AdModifier.empty.weight(3f)),
            ),
        )
        assertEquals(emptyList(), ratios)
    }

    @Test
    fun `a non positive or non finite weight is not a weight`() {
        assertEquals(null, AdModifier(weight = 0f).effectiveWeight)
        assertEquals(null, AdModifier(weight = -1f).effectiveWeight)
        assertEquals(null, AdModifier(weight = Float.NaN).effectiveWeight)
        assertEquals(null, AdModifier.empty.effectiveWeight)
        assertEquals(2f, AdModifier(weight = 2f).effectiveWeight)
    }

    // The same rule has to hold for slack, or a zero-weight child both skips the main-axis
    // arrangement and is skipped by the ratio resolver.
    @Test
    fun `a zero weight child still leaves slack for the arrangement`() {
        assertTrue(stackHasMainAxisSlack(listOf(AdSpacer(AdModifier(weight = 0f)), badge()), stackIsHorizontal = true))
    }

    private fun headline(modifier: AdModifier = AdModifier.empty): AdNode = AdAssetNode.Headline(modifier)
    private fun badge(): AdNode = AdAssetNode.AdBadge()

    // `fillMaxWidth()` on a row child measures against the remaining width in Compose, so it is a
    // claim on leftover space just as a weight is. Reading only `weight` is what left it doing
    // nothing on iOS and starving the siblings on Android.
    @Test
    fun `a main axis Match child claims the space a weight would`() {
        assertTrue(claimsMainAxis(AdModifier(width = AdLayoutSize.Match), stackIsHorizontal = true))
        assertTrue(claimsMainAxis(AdModifier(height = AdLayoutSize.Match), stackIsHorizontal = false))
        assertTrue(claimsMainAxis(AdModifier.empty.weight(2f), stackIsHorizontal = true))
    }

    // Match on the *cross* axis is how a child fills the stack's width/height, not a claim on the
    // main axis, and must not be mistaken for one.
    @Test
    fun `a cross axis Match child claims nothing`() {
        assertFalse(claimsMainAxis(AdModifier(height = AdLayoutSize.Match), stackIsHorizontal = true))
        assertFalse(claimsMainAxis(AdModifier(width = AdLayoutSize.Match), stackIsHorizontal = false))
        assertFalse(claimsMainAxis(AdModifier.empty, stackIsHorizontal = true))
    }

    @Test
    fun `a main axis Match child leaves no slack for the arrangement`() {
        assertFalse(
            stackHasMainAxisSlack(
                listOf(headline(AdModifier(width = AdLayoutSize.Match)), badge()),
                stackIsHorizontal = true,
            ),
        )
    }
}
