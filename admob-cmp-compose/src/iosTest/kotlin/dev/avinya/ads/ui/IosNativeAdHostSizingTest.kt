@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.ui

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
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UILayoutFittingCompressedSize
import platform.UIKit.UILayoutPriorityRequired
import platform.UIKit.UIStackView
import platform.UIKit.UILabel
import platform.UIKit.UIEdgeInsetsMake
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.additionalSafeAreaInsets
import kotlinx.cinterop.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WIDTH = 402.0

/**
 * The Home-feed ad shape: a media box, a metadata row with a weighted text column, and a
 * full-width call to action. Static text only, so no creative assets are needed.
 */
private fun feedShapedLayout() = adLayout {
    column(modifier = AdModifier.fillMaxWidth().padding(top = 24.dp)) {
        box(modifier = AdModifier.fillMaxWidth()) {
            media(modifier = AdModifier.fillMaxWidth().aspectRatio(1f))
        }
        row(modifier = AdModifier.fillMaxWidth().padding(all = 12.dp), spacing = 10.dp) {
            spacer(modifier = AdModifier.size(36.dp))
            column(modifier = AdModifier.weight(1f)) {
                text(
                    text = "Test mode: a headline long enough to wrap onto a second line",
                    style = AdTextStyle(fontSizeSp = 16f),
                    maxLines = 2,
                )
                text(
                    modifier = AdModifier.fillMaxWidth().padding(top = 4.dp),
                    text = "A body long enough that it also wraps across two whole lines of text",
                    style = AdTextStyle(fontSizeSp = 12f),
                    maxLines = 2,
                )
            }
            spacer(modifier = AdModifier.size(24.dp))
        }
        text(
            modifier = AdModifier.fillMaxWidth().height(36.dp)
                .margin(start = 12.dp, end = 12.dp, bottom = 12.dp),
            text = "Install",
            style = AdTextStyle(fontSizeSp = 14f),
            maxLines = 1,
        )
    }
}

/** Wires the host exactly as the `UIKitView` factory does. */
private fun hostForTest(fillsHost: Boolean = false): IosNativeAdHostView {
    val nativeView = GADNativeAdView()
    nativeView.translatesAutoresizingMaskIntoConstraints = false
    val content = IosNativeAdRenderer(
        nativeAd = GADNativeAd(),
        nativeView = nativeView,
        density = Density(density = 3f),
    ).render(feedShapedLayout().root)
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
        fillsHost = fillsHost,
    )
}

/**
 * Reproduces what `UIKitInteropElementLayout.measurePolicy` does for a `fillMaxWidth()` interop
 * view: pin the width, leave the height free, and take the Auto Layout fitting size.
 */
private fun IosNativeAdHostView.measureAsComposeWould(): Double {
    val width = widthAnchor.constraintEqualToConstant(WIDTH)
        .apply { priority = UILayoutPriorityRequired; active = true }
    return try {
        systemLayoutSizeFittingSize(UILayoutFittingCompressedSize.readValue()).useContents { height }
    } finally {
        width.active = false
    }
}


private fun labelsIn(root: UIView): List<UILabel> {
    val found = mutableListOf<UILabel>()
    fun walk(view: UIView) {
        if (view is UILabel) found += view
        @Suppress("UNCHECKED_CAST")
        (view.subviews as List<UIView>).forEach(::walk)
    }
    walk(root)
    return found
}

private fun stacksIn(root: UIView): List<UIStackView> {
    val found = mutableListOf<UIStackView>()
    fun walk(view: UIView) {
        if (view is UIStackView) found += view
        @Suppress("UNCHECKED_CAST")
        (view.subviews as List<UIView>).forEach(::walk)
    }
    walk(root)
    return found
}

class IosNativeAdHostSizingTest {
    /**
     * Compose sizes the ad by measuring this view, so the rendered tree has to give it a height.
     * While the content was merely *contained* by the ad view — a `<=` inequality — nothing asked
     * the view to be as tall as its content and the ad measured to nothing.
     */
    @Test
    fun `the rendered content gives the host a height for Compose to measure`() {
        val host = hostForTest()

        val measured = host.measureAsComposeWould()

        assertTrue(measured > 100.0, "ad measured $measured; the content is not driving the height")
    }

    /**
     * The regression that started this: the height the host reports has to be a function of the
     * content and the width, and of nothing else. It used to track the view's current height, so
     * every measurement fed the next one and the card ratcheted taller on scroll.
     */
    @Test
    fun `the measured height does not depend on the host's current height`() {
        val host = hostForTest()
        val natural = host.measureAsComposeWould()

        for (current in listOf(natural, natural + 120.0, natural - 120.0, 1.0, 0.0)) {
            host.setFrame(CGRectMake(0.0, 0.0, WIDTH, current))
            host.layoutIfNeeded()
            assertEquals(
                natural,
                host.measureAsComposeWould(),
                absoluteTolerance = 0.5,
                message = "measured height moved when the host was $current",
            )
        }
    }

    /** Repeated measurement must be idempotent — no drift as the feed scrolls. */
    @Test
    fun `repeated measurement is stable`() {
        val host = hostForTest()
        val first = host.measureAsComposeWould()

        repeat(8) {
            host.layoutIfNeeded()
            assertEquals(first, host.measureAsComposeWould(), absoluteTolerance = 0.5)
        }
    }

    /**
     * Measuring the tree while it is attached and live is what could never be made reliable: any
     * caller can catch it mid-update and get back the real height plus whatever the unsettled
     * subviews are short by. A detached container is the controlled environment — nothing else is
     * laying it out — so the answer has to be exact, and the same every time.
     */
    @Test
    fun `a detached measurement matches the settled attached height and repeats`() {
        val attached = hostForTest()
        val container = UIView(frame = CGRectMake(0.0, 0.0, WIDTH, 3000.0))
        container.addSubview(attached)
        attached.setFrame(CGRectMake(0.0, 0.0, WIDTH, 3000.0))
        container.layoutIfNeeded()
        val settled = attached.measureAsComposeWould()

        repeat(5) {
            val detached = hostForTest().measureDetachedHeight(WIDTH)
            assertEquals(settled, detached, absoluteTolerance = 0.5, message = "detached measurement drifted")
        }
    }

    /** A `Match`-height root takes the height Compose fixed, instead of measuring its own. */
    @Test
    fun `a filling root stretches its content to the host`() {
        val host = hostForTest(fillsHost = true)
        val container = UIView(frame = CGRectMake(0.0, 0.0, WIDTH, 900.0))
        host.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(host)
        NSLayoutConstraint.activateConstraints(
            listOf(
                host.leadingAnchor.constraintEqualToAnchor(container.leadingAnchor),
                host.trailingAnchor.constraintEqualToAnchor(container.trailingAnchor),
                host.topAnchor.constraintEqualToAnchor(container.topAnchor),
                host.bottomAnchor.constraintEqualToAnchor(container.bottomAnchor),
            ),
        )
        container.layoutIfNeeded()

        val contentHeight = host.subviews
            .filterIsInstance<UIView>()
            .first()
            .bounds
            .useContents { size.height }
        assertEquals(900.0, contentHeight, absoluteTolerance = 0.5)
    }


    /**
     * The defect this file exists for, reproduced end to end.
     *
     * Padding on a `Row`/`Column` is expressed as `UIStackView.layoutMargins`, and UIKit grows a
     * view's effective layout margins by its `safeAreaInsets` unless `insetsLayoutMarginsFromSafeArea`
     * says otherwise — which defaults to `true`. Compose measured the card detached, where the tree
     * has no safe area, and then fixed its height; once the card scrolled under the status bar the
     * stack's top margin silently grew by the overlap and that space came out of the arranged
     * subviews. The card kept its outer height while the headline collapsed to nothing, the body
     * dropped a line and the call to action thinned — and Auto Layout never warned, because a
     * `UILabel` yields at compression resistance rather than making the system unsatisfiable.
     */
    @Test
    fun `a card overlapping the safe area keeps every label at its intrinsic height`() {
        val host = hostForTest()
        val natural = host.measureDetachedHeight(WIDTH)

        val controller = UIViewController(nibName = null, bundle = null)
        controller.additionalSafeAreaInsets = UIEdgeInsetsMake(60.0, 0.0, 34.0, 0.0)
        // A safe area only exists inside a window, and only a view controller can add to it.
        val window = UIWindow(frame = CGRectMake(0.0, 0.0, WIDTH, 2000.0))
        window.rootViewController = controller
        window.hidden = false
        val container = controller.view
        window.layoutIfNeeded()
        host.translatesAutoresizingMaskIntoConstraints = false
        container.addSubview(host)
        NSLayoutConstraint.activateConstraints(
            listOf(
                host.leadingAnchor.constraintEqualToAnchor(container.leadingAnchor),
                host.trailingAnchor.constraintEqualToAnchor(container.trailingAnchor),
                host.topAnchor.constraintEqualToAnchor(container.topAnchor),
                host.heightAnchor.constraintEqualToConstant(natural),
            ),
        )
        container.layoutIfNeeded()

        val safeAreaTop = container.safeAreaInsets.useContents { top }
        assertTrue(safeAreaTop > 0.0, "the harness did not produce a safe area; the test proves nothing")

        val squashed = labelsIn(host).filter { label ->
            val laidOut = label.frame.useContents { size.height }
            val intrinsic = label.intrinsicContentSize.useContents { height }
            intrinsic > 0.5 && laidOut < intrinsic - 1.5
        }
        assertTrue(
            squashed.isEmpty(),
            "labels laid out below their intrinsic height inside the safe area: " +
                squashed.joinToString { label ->
                    val laidOut = label.frame.useContents { size.height }
                    val intrinsic = label.intrinsicContentSize.useContents { height }
                    "'" + (label.text ?: "") + "' " + laidOut + " vs " + intrinsic
                },
        )
    }

    /** The invariant the fix rests on, asserted where it is cheapest to check. */
    @Test
    fun `no stack in a rendered ad inherits layout margins from the safe area`() {
        val host = hostForTest()

        val offenders = stacksIn(host).filter { it.insetsLayoutMarginsFromSafeArea }

        assertTrue(offenders.isEmpty(), "${offenders.size} stack(s) still inset their margins from the safe area")
    }
}
