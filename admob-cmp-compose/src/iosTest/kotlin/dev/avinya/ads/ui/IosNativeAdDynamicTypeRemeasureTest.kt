@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.ui

import GoogleMobileAds.GADMediaView
import GoogleMobileAds.GADNativeAd
import GoogleMobileAds.GADNativeAdView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.adLayout
import dev.avinya.ads.nativead.rendering.IosNativeAdRenderer
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryLarge
import platform.UIKit.UILabel
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.UIKit.traitOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CARD_WIDTH = 370.0

/**
 * A text-size change while an ad is on screen must re-measure the ad *after* its labels restyle,
 * and must not disturb how Compose holds the view while doing it.
 *
 * Every label sets `adjustsFontForContentSizeCategory`, so UIKit restyles the tree in place and the
 * host has to report the new height. Two defects shipped here, both reproduced on the simulator:
 *
 *  - It re-measured from `UIContentSizeCategoryDidChangeNotification`, which UIKit posts before the
 *    new category reaches the views. The measurement saw the old fonts and the card kept its old
 *    height, clipping the restyled text.
 *  - It measured by moving the host into a scratch container and back. `UIKitView` pins the view to
 *    its own host once, and `removeFromSuperview` deletes those pins for good, so after any
 *    text-size change the ad was sized by its own content instead of by Compose — at the largest
 *    size, wider than its card, with its 16:9 media stretched to match.
 *
 * The harness embeds the host the way `UIKitView` does — a frame-driven host view, the ad pinned to
 * all four of its edges — and drives the change through a window's trait override, the same
 * propagation a system change takes. It has to be a window: UIKit propagates traits only inside one.
 */
class IosNativeAdDynamicTypeRemeasureTest {

    private class Embedded(val window: UIWindow, val composeHost: UIView, val ad: IosNativeAdHostView) {
        val pins: List<NSLayoutConstraint> = listOf(
            ad.leftAnchor.constraintEqualToAnchor(composeHost.leftAnchor),
            ad.rightAnchor.constraintEqualToAnchor(composeHost.rightAnchor),
            ad.topAnchor.constraintEqualToAnchor(composeHost.topAnchor),
            ad.bottomAnchor.constraintEqualToAnchor(composeHost.bottomAnchor),
        )

        /** What Compose does with the height it is handed: sets the host view's frame. */
        fun showAt(height: Double) {
            composeHost.setFrame(CGRectMake(0.0, 0.0, CARD_WIDTH, height))
            window.layoutIfNeeded()
        }
    }

    private fun feedLayout() = adLayout {
        column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
            row(modifier = AdModifier.fillMaxWidth(), spacing = 8.dp) {
                text(
                    modifier = AdModifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    text = "SPONSORED",
                    style = AdTextStyle(fontSizeSp = 11f),
                    maxLines = 1,
                )
                text(modifier = AdModifier.weight(1f), text = "Advertiser", style = AdTextStyle(fontSizeSp = 12f), maxLines = 1)
            }
            media(modifier = AdModifier.fillMaxWidth().aspectRatio(16f / 9f))
            text(text = "Weekend trails, offline", style = AdTextStyle(fontSizeSp = 16f), maxLines = 2)
            text(
                text = "A body long enough to wrap onto a second line at the larger sizes",
                style = AdTextStyle(fontSizeSp = 13f),
                maxLines = 3,
            )
            // A wrapping label whose width depends on a font-sized neighbour: when the text grows,
            // the button widens, the label narrows and wraps further. A measurement that used the
            // label's previous width would be off by those extra lines.
            row(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
                text(
                    modifier = AdModifier.weight(1f),
                    text = "Free on the App Store, rated four point eight by eleven thousand people",
                    style = AdTextStyle(fontSizeSp = 13f),
                    maxLines = 6,
                )
                text(
                    modifier = AdModifier.padding(horizontal = 16.dp),
                    text = "Install now",
                    style = AdTextStyle(fontSizeSp = 14f),
                    maxLines = 1,
                )
            }
        }
    }

    private fun newHost(): IosNativeAdHostView {
        val nativeView = GADNativeAdView()
        nativeView.translatesAutoresizingMaskIntoConstraints = false
        val content = IosNativeAdRenderer(
            nativeAd = GADNativeAd(),
            nativeView = nativeView,
            density = Density(density = 3f),
        ).render(feedLayout().root)
        nativeView.addSubview(content)
        content.leadingAnchor.constraintEqualToAnchor(nativeView.leadingAnchor).active = true
        content.trailingAnchor.constraintEqualToAnchor(nativeView.trailingAnchor).active = true
        content.topAnchor.constraintEqualToAnchor(nativeView.topAnchor).active = true
        return IosNativeAdHostView(
            placementId = "test-native",
            nativeView = nativeView,
            content = content,
            nativeAd = GADNativeAd(),
            surfaceArgb = null,
            fillsHost = false,
        )
    }

    /** Measures before attaching, then embeds, exactly as `prepareNativeAd` and `UIKitView` do. */
    private fun embed(category: String?): Pair<Embedded, Double> {
        val window = UIWindow(frame = CGRectMake(0.0, 0.0, CARD_WIDTH, 3000.0)).also { it.hidden = false }
        window.traitOverrides.preferredContentSizeCategory = category
        settle()
        val ad = newHost()
        val initial = ad.measureHeight(CARD_WIDTH)
        val composeHost = UIView(frame = CGRectMake(0.0, 0.0, CARD_WIDTH, initial))
        composeHost.translatesAutoresizingMaskIntoConstraints = true
        window.addSubview(composeHost)
        composeHost.addSubview(ad)
        ad.translatesAutoresizingMaskIntoConstraints = false
        val embedded = Embedded(window, composeHost, ad)
        NSLayoutConstraint.activateConstraints(embedded.pins)
        embedded.showAt(initial)
        settle()
        return embedded to initial
    }

    private fun headlinePointSize(root: UIView): Double {
        fun find(view: UIView): UILabel? {
            if (view is UILabel && view.text == "Weekend trails, offline") return view
            @Suppress("UNCHECKED_CAST")
            for (child in view.subviews as List<UIView>) find(child)?.let { return it }
            return null
        }
        return find(root)!!.font.pointSize
    }

    private fun mediaWidth(root: UIView): Double {
        fun find(view: UIView): UIView? {
            if (view is GADMediaView) return view
            @Suppress("UNCHECKED_CAST")
            for (child in view.subviews as List<UIView>) find(child)?.let { return it }
            return null
        }
        return find(root)!!.frame.useContents { size.width }
    }

    /** Lets UIKit deliver trait changes and the main-queue turn the host defers its check to. */
    private fun settle() {
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.2))
    }

    @Test
    fun `a text-size change re-measures once after the labels restyle and at the new size`() {
        val (embedded, initialHeight) = embed(UIContentSizeCategoryLarge)
        val initialPointSize = headlinePointSize(embedded.ad)

        val reports = mutableListOf<Pair<Double, Double>>()
        embedded.ad.onContentSizeCategoryChanged = {
            reports += headlinePointSize(embedded.ad) to embedded.ad.measureHeight(CARD_WIDTH)
        }
        settle()
        assertEquals(0, reports.size, "nothing changed yet, so nothing may be re-measured")

        embedded.window.traitOverrides.preferredContentSizeCategory = UIContentSizeCategoryAccessibilityExtraExtraExtraLarge
        settle()

        assertEquals(1, reports.size, "one category change must re-measure exactly once; got $reports")
        val (pointSizeAtMeasure, grownHeight) = reports.single()
        assertTrue(
            pointSizeAtMeasure > initialPointSize * 1.5,
            "the re-measure ran before the labels restyled: headline was ${pointSizeAtMeasure}pt, started at ${initialPointSize}pt",
        )
        assertTrue(grownHeight > initialHeight * 1.3, "the re-measure must see the larger text: $initialHeight -> $grownHeight")

        embedded.showAt(grownHeight)
        settle()
        assertEquals(1, reports.size, "showing the new height must not trigger another measurement")
        assertEquals(
            grownHeight,
            embedded.ad.measureHeight(CARD_WIDTH),
            absoluteTolerance = 0.5,
            message = "the re-measured height must already be final; a second answer means the card resizes twice",
        )

        embedded.window.traitOverrides.preferredContentSizeCategory = UIContentSizeCategoryLarge
        settle()
        assertEquals(2, reports.size, "returning to the original size must re-measure again")
        assertEquals(initialHeight, reports.last().second, absoluteTolerance = 0.5)
    }

    @Test
    fun `re-measuring an attached ad keeps the pins Compose installed and its width`() {
        val (embedded, _) = embed(UIContentSizeCategoryLarge)
        var grown = 0.0
        embedded.ad.onContentSizeCategoryChanged = { grown = embedded.ad.measureHeight(CARD_WIDTH) }

        embedded.window.traitOverrides.preferredContentSizeCategory = UIContentSizeCategoryAccessibilityExtraExtraExtraLarge
        settle()
        embedded.showAt(grown)

        // `==`, not `assertSame`: Kotlin/Native wraps each read of an Objective-C property afresh.
        assertTrue(embedded.ad.superview == embedded.composeHost, "the ad must stay in Compose's host view")
        assertTrue(embedded.pins.all { it.active }, "re-measuring removed the edge pins Compose installed")
        assertEquals(CARD_WIDTH, embedded.ad.frame.useContents { size.width }, absoluteTolerance = 0.5)
        assertEquals(
            CARD_WIDTH,
            mediaWidth(embedded.ad),
            absoluteTolerance = 0.5,
            message = "the full-width media must stay as wide as the card, not as wide as its content",
        )
    }

    /**
     * Measuring in place has to give the detached answer, whatever height Compose is currently
     * showing the ad at. The current height is the dangerous input: an in-place measurement that
     * leaked it is what once ratcheted an ad taller on every pass.
     *
     * Both sides run at the default text size: a detached view has no window, so it cannot see a
     * window's trait override, and only the app-wide size reaches it — in the app as well as here.
     */
    @Test
    fun `measuring in place agrees with measuring detached at any shown height`() {
        val detached = newHost().measureHeight(CARD_WIDTH)
        val (embedded, _) = embed(null)

        for (shown in listOf(detached, detached - 120.0, detached + 120.0, 1.0)) {
            embedded.showAt(shown)
            assertEquals(
                detached,
                embedded.ad.measureHeight(CARD_WIDTH),
                absoluteTolerance = 0.5,
                message = "the in-place measurement moved when the ad was shown at ${shown}pt",
            )
        }
    }
}
