@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.ui

import GoogleMobileAds.GADNativeAd
import GoogleMobileAds.GADNativeAdView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTemplates
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.adLayout
import dev.avinya.ads.nativead.rendering.IosNativeAdRenderer
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UILabel
import platform.UIKit.UILayoutPriorityRequired
import platform.UIKit.UIStackView
import platform.UIKit.UIView
import platform.UIKit.accessibilityLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val ROW_WIDTH = 358.0
private const val OVERFULL_WIDTH = 220.0
private const val TOLERANCE = 1.0

/** Horizontal padding the pill modifiers below spend on each side (16dp, at the 1:1 point scale). */
private const val PILL_PADDING = 32.0

private fun allViews(root: UIView): List<UIView> {
    val found = mutableListOf<UIView>(root)
    @Suppress("UNCHECKED_CAST")
    (root.subviews as List<UIView>).forEach { found += allViews(it) }
    return found
}

private fun widthOf(view: UIView): Double = view.frame.useContents { size.width }

private fun intrinsicWidthOf(label: UILabel): Double = label.intrinsicContentSize.useContents { width }

@Suppress("UNCHECKED_CAST")
private fun arrangedOf(stack: UIStackView): List<UIView> = stack.arrangedSubviews as List<UIView>

private fun labelWithText(root: UIView, text: String): UILabel =
    allViews(root).filterIsInstance<UILabel>().single { it.text == text }

/** The stack that holds [view] as one of its arranged subviews. */
private fun stackArranging(root: UIView, view: UIView): UIStackView =
    allViews(root).filterIsInstance<UIStackView>().single { stack -> arrangedOf(stack).any { it == view } }

/**
 * Renders [layout] the way the `UIKitView` factory does, pinned to [width] and laid out.
 * The native view is returned so tests can reach `callToActionView` as well as the subtree.
 */
private fun renderInto(layout: AdLayout, width: Double): GADNativeAdView {
    val nativeView = GADNativeAdView()
    nativeView.translatesAutoresizingMaskIntoConstraints = false
    val content = IosNativeAdRenderer(
        nativeAd = GADNativeAd(),
        nativeView = nativeView,
        density = Density(density = 3f),
    ).render(layout.root)
    nativeView.addSubview(content)
    content.leadingAnchor.constraintEqualToAnchor(nativeView.leadingAnchor).active = true
    content.trailingAnchor.constraintEqualToAnchor(nativeView.trailingAnchor).active = true
    content.topAnchor.constraintEqualToAnchor(nativeView.topAnchor).active = true
    nativeView.widthAnchor.constraintEqualToConstant(width)
        .apply { priority = UILayoutPriorityRequired; active = true }
    nativeView.setFrame(CGRectMake(0.0, 0.0, width, 400.0))
    nativeView.setNeedsLayout()
    nativeView.layoutIfNeeded()
    return nativeView
}

/**
 * Compose's `RowScope.weight` rule: every unweighted child is measured first at its own size, and
 * the weighted children then divide whatever width is left in proportion to their weights. A
 * wrap-content sibling must therefore keep exactly its content width, whether the row is underfull
 * (the weighted column takes the slack) or overfull (the weighted column gives way first).
 *
 * UIKit has no such ordering. `UIStackView` with `distribution = Fill` hands slack to whichever
 * arranged view hugs least, so a wrap-content pill beside a weighted column could swallow about
 * 185pt of a 358pt row. The renderer states Compose's rule as Auto Layout priorities: weighted
 * children hug at almost nothing, every other child keeps its content size just below `required`,
 * and weight ratios become explicit width-multiplier constraints. These tests pin that behaviour
 * on the rendered tree, where the frames are the only observable result.
 *
 * Tolerance is 1.0pt unless a test says otherwise.
 */
class IosNativeAdRowWrapTest {

    @Test
    fun `a wrap-content static pill keeps its content width and the weighted column takes the rest when underfull`() {
        val nativeView = renderInto(
            adLayout {
                row(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
                    column(modifier = AdModifier.weight(1f)) {
                        text(text = "Weekend trails, offline", style = AdTextStyle(fontSizeSp = 16f), maxLines = 2)
                        text(text = "Fieldnotes · 4.8 stars", style = AdTextStyle(fontSizeSp = 12f), maxLines = 1)
                    }
                    text(
                        modifier = AdModifier.wrapContentWidth().height(36.dp).padding(horizontal = 16.dp),
                        text = "Install",
                        style = AdTextStyle(fontSizeSp = 14f),
                        maxLines = 1,
                    )
                }
            },
            ROW_WIDTH,
        )

        val install = labelWithText(nativeView, "Install")
        val pill = assertNotNull(install.superview, "the Install label has no padding container")
        val pillWidth = widthOf(pill)
        val intrinsic = intrinsicWidthOf(install)

        assertEquals(
            intrinsic,
            widthOf(install),
            absoluteTolerance = TOLERANCE,
            message = "the Install label must keep its intrinsic width",
        )
        assertEquals(
            intrinsic + PILL_PADDING,
            pillWidth,
            absoluteTolerance = TOLERANCE,
            message = "the pill must be the label plus its 32pt horizontal padding",
        )

        val weightedColumn = stackArranging(nativeView, labelWithText(nativeView, "Weekend trails, offline"))
        assertEquals(
            ROW_WIDTH - 12.0 - pillWidth,
            widthOf(weightedColumn),
            absoluteTolerance = TOLERANCE,
            message = "the weighted column must take the row's free space",
        )
    }

    @Test
    fun `a call to action with no text keeps only its horizontal padding when the row is underfull`() {
        val nativeView = renderInto(
            adLayout {
                row(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
                    column(modifier = AdModifier.weight(1f)) {
                        text(text = "Weekend trails, offline", style = AdTextStyle(fontSizeSp = 16f), maxLines = 2)
                        text(text = "Fieldnotes · 4.8 stars", style = AdTextStyle(fontSizeSp = 12f), maxLines = 1)
                    }
                    callToAction(
                        modifier = AdModifier.wrapContentWidth().height(36.dp).padding(horizontal = 16.dp),
                        visibilityPolicy = AdVisibilityPolicy.KeepSpace,
                    )
                }
            },
            ROW_WIDTH,
        )

        // A blank GADNativeAd has no call-to-action text, so the label is empty and the pill is
        // exactly its padding.
        val pill = assertNotNull(nativeView.callToActionView, "the call-to-action view was not registered")
        assertEquals(
            PILL_PADDING,
            widthOf(pill),
            absoluteTolerance = TOLERANCE,
            message = "an empty call to action must be its 32pt padding and nothing more",
        )

        val weightedColumn = stackArranging(nativeView, labelWithText(nativeView, "Weekend trails, offline"))
        assertEquals(
            ROW_WIDTH - 12.0 - PILL_PADDING,
            widthOf(weightedColumn),
            absoluteTolerance = TOLERANCE,
            message = "the weighted column must take the row's free space",
        )
    }

    @Test
    fun `a wrap-content pill keeps its label width and the weighted column yields when the row is overfull`() {
        val nativeView = renderInto(
            adLayout {
                row(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
                    column(modifier = AdModifier.weight(1f)) {
                        text(
                            text = "A headline that is far too long to fit beside the button",
                            style = AdTextStyle(fontSizeSp = 16f),
                            maxLines = 1,
                        )
                    }
                    text(
                        modifier = AdModifier.wrapContentWidth().height(36.dp).padding(horizontal = 16.dp),
                        text = "Install the app now",
                        style = AdTextStyle(fontSizeSp = 14f),
                        maxLines = 1,
                    )
                }
            },
            OVERFULL_WIDTH,
        )

        val install = labelWithText(nativeView, "Install the app now")
        val pill = assertNotNull(install.superview, "the pill label has no padding container")
        val pillWidth = widthOf(pill)
        val intrinsic = intrinsicWidthOf(install)

        assertEquals(
            intrinsic,
            widthOf(install),
            absoluteTolerance = TOLERANCE,
            message = "the pill label must keep its full intrinsic width when the row is overfull",
        )
        assertEquals(
            intrinsic + PILL_PADDING,
            pillWidth,
            absoluteTolerance = TOLERANCE,
            message = "the pill must be the label plus its 32pt horizontal padding",
        )

        val weightedColumn = stackArranging(
            nativeView,
            labelWithText(nativeView, "A headline that is far too long to fit beside the button"),
        )
        val expectedColumn = OVERFULL_WIDTH - 12.0 - pillWidth
        assertTrue(widthOf(weightedColumn) >= 0.0, "the weighted column has a negative width")
        assertEquals(
            expectedColumn,
            widthOf(weightedColumn),
            absoluteTolerance = TOLERANCE,
            message = "the weighted column must be what is left after the pill",
        )
    }

    @Test
    fun `two weighted columns keep their 1 to 2 ratio and the wrap-content pill keeps its content width`() {
        val nativeView = renderInto(
            adLayout {
                row(modifier = AdModifier.fillMaxWidth(), spacing = 8.dp) {
                    column(modifier = AdModifier.weight(1f)) {
                        text(text = "One")
                    }
                    column(modifier = AdModifier.weight(2f)) {
                        text(text = "Two")
                    }
                    text(
                        modifier = AdModifier.wrapContentWidth().padding(horizontal = 16.dp),
                        text = "Install",
                    )
                }
            },
            ROW_WIDTH,
        )

        val one = stackArranging(nativeView, labelWithText(nativeView, "One"))
        val two = stackArranging(nativeView, labelWithText(nativeView, "Two"))
        assertEquals(
            2.0,
            widthOf(two) / widthOf(one),
            absoluteTolerance = 0.02,
            message = "the weight-2 column must be twice the weight-1 column",
        )

        val install = labelWithText(nativeView, "Install")
        val pill = assertNotNull(install.superview, "the pill label has no padding container")
        assertEquals(
            intrinsicWidthOf(install) + PILL_PADDING,
            widthOf(pill),
            absoluteTolerance = TOLERANCE,
            message = "the pill must be the label plus its 32pt horizontal padding",
        )
    }

    @Test
    fun `the compact template's badge keeps its intrinsic width and the headline takes the slack`() {
        val nativeView = renderInto(AdTemplates.compact, ROW_WIDTH)

        val badge = allViews(nativeView)
            .filterIsInstance<UILabel>()
            .single { it.accessibilityLabel == "Advertisement" }
        assertEquals(
            intrinsicWidthOf(badge),
            widthOf(badge),
            absoluteTolerance = TOLERANCE,
            message = "the badge must keep its intrinsic width; the inner weighted headline takes the slack",
        )

        val cta = assertNotNull(nativeView.callToActionView, "the call-to-action view was not registered")
        assertTrue(
            widthOf(cta) < ROW_WIDTH / 3,
            "the call to action is ${widthOf(cta)}pt of a $ROW_WIDTH pt row; a wrap-content button must not take a third",
        )
    }

    /**
     * The overfull case of the nested shape above: `AdTemplates.compact` at the largest
     * accessibility text size, where the row cannot fit everything at full width. Text sizes are
     * scaled 3x here rather than through Dynamic Type so the case is deterministic.
     *
     * The badge is an unweighted child of the *inner* row, so its own stack holds it at the same
     * just-below-required priority as the outer button. Compose measures the outer button before
     * the weighted column measures anything, so the button must keep its width and the nested
     * badge must give way. Left tied, Auto Layout split the shortfall and truncated both.
     */
    @Test
    fun `an outer wrap-content button keeps its width over an unweighted child nested in the weighted column`() {
        val nativeView = renderInto(
            adLayout {
                row(modifier = AdModifier.fillMaxWidth().padding(12.dp), spacing = 12.dp) {
                    spacer(AdModifier.size(48.dp))
                    column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                        row(spacing = 6.dp) {
                            text(
                                modifier = AdModifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    .sizeIn(minWidth = 15.dp, minHeight = 15.dp),
                                text = "Ad",
                                style = AdTextStyle(fontSizeSp = 30f),
                                maxLines = 1,
                            )
                            text(
                                modifier = AdModifier.weight(1f),
                                text = "Test mode: Google Ads",
                                style = AdTextStyle(fontSizeSp = 48f, fontWeight = AdFontWeight.Bold),
                                maxLines = 1,
                            )
                        }
                        text(
                            text = "The Google Ads mobile app helps you stay connected",
                            style = AdTextStyle(fontSizeSp = 39f),
                            maxLines = 2,
                        )
                    }
                    text(
                        modifier = AdModifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        text = "INSTALL",
                        style = AdTextStyle(fontSizeSp = 42f, fontWeight = AdFontWeight.Bold),
                        maxLines = 1,
                    )
                    spacer(AdModifier.size(20.dp))
                }
            },
            ROW_WIDTH,
        )

        val badge = labelWithText(nativeView, "Ad")
        val install = labelWithText(nativeView, "INSTALL")
        assertEquals(
            intrinsicWidthOf(install),
            widthOf(install),
            absoluteTolerance = TOLERANCE,
            message = "the outer button's label must not be truncated",
        )
        assertTrue(
            widthOf(badge) < intrinsicWidthOf(badge) - TOLERANCE,
            "the nested badge is what must give way; it is ${widthOf(badge)}pt of ${intrinsicWidthOf(badge)}pt",
        )
    }
}
