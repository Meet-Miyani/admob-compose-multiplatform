@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.avinya.ads.nativead.rendering

import GoogleMobileAds.GADNativeAd
import GoogleMobileAds.GADNativeAdView
import androidx.compose.ui.unit.Density
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.adLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraLarge
import platform.UIKit.UIContentSizeCategoryLarge
import platform.UIKit.UIFont
import platform.UIKit.UIFontMetrics
import platform.UIKit.UILabel
import platform.UIKit.UIView
import platform.UIKit.UITraitCollection

/**
 * Dynamic Type scaling for iOS native-ad text.
 *
 * The renderer sets `adjustsFontForContentSizeCategory` on every label, but Apple documents
 * that flag as effective only for a font obtained from `preferredFont(forTextStyle:)` or
 * scaled through `UIFontMetrics`. Every font the renderer built was a fixed-size
 * `systemFont(ofSize:)`, so the flag did nothing and iOS ad copy stayed at its base size
 * while the rest of the app grew — whereas Android, assigning `textSize` in `sp`, scaled all
 * along. The field is called `fontSizeSp`, so scaling is what it already promises.
 *
 * These assert the UIKit contract the fix depends on, at the same construction the renderer
 * now performs. The rendered result itself is a device check (Profile → SDK Lab → Native at
 * several text sizes), since a UILabel needs a real trait environment to restyle.
 */
class IosDynamicTypeScalingTest {

    @Test
    fun `a scaled font grows with an accessibility content size category`() {
        val base = UIFont.systemFontOfSize(14.0)
        val metrics = UIFontMetrics.defaultMetrics

        val atDefault = metrics.scaledFontForFont(
            base,
            compatibleWithTraitCollection = UITraitCollection.traitCollectionWithPreferredContentSizeCategory(
                UIContentSizeCategoryLarge,
            ),
        )
        val atAccessibility = metrics.scaledFontForFont(
            base,
            compatibleWithTraitCollection = UITraitCollection.traitCollectionWithPreferredContentSizeCategory(
                UIContentSizeCategoryAccessibilityExtraLarge,
            ),
        )

        assertEquals(14.0, atDefault.pointSize, absoluteTolerance = 0.01)
        assertTrue(
            atAccessibility.pointSize > atDefault.pointSize,
            "an accessibility text size must enlarge ad copy; was ${atAccessibility.pointSize}",
        )
    }

    @Test
    fun `an unscaled system font ignores the content size category`() {
        // The pre-fix behaviour, kept as the contrast: this is what every renderer font used
        // to be, and it is why setting adjustsFontForContentSizeCategory achieved nothing.
        val fixed = UIFont.systemFontOfSize(14.0)

        assertEquals(14.0, fixed.pointSize, absoluteTolerance = 0.01)
    }

    /**
     * Characterization, NOT a regression test — and the distinction is the point.
     *
     * Two framings were tried and both pass against the unfixed renderer:
     *
     *  - comparing the label's point size to `scaledFont(for:)` at the default category, where
     *    the scale factor is 1.0, so a fixed-size font produces the same number;
     *  - re-scaling the label's own font for an accessibility category, because
     *    `UIFontMetrics` will happily scale *any* `UIFont`, fixed-size ones included.
     *
     * What distinguishes the fix is whether the font UIKit holds was BUILT through metrics,
     * and that provenance is not observable from the `UIFont` afterwards. So no unit test on
     * this platform can catch a regression here: only a device or a simulator with its text
     * size actually changed will show it (Profile → SDK Lab → Native, per the project's
     * native-ad verification note). This test therefore pins only that a label is rendered
     * with the flag set, and exists to carry that warning to the next contributor rather than
     * to imply coverage that does not exist.
     */
    @Test
    fun `rendered labels opt into content size category updates`() {
        val layout = adLayout {
            text(text = "Install", style = AdTextStyle(fontSizeSp = 14f), maxLines = 1)
        }

        val rendered = IosNativeAdRenderer(
            nativeAd = GADNativeAd(),
            nativeView = GADNativeAdView(),
            density = Density(density = 3f),
        ).render(layout.root)

        val label = assertNotNull(rendered.firstLabel(), "the layout should have rendered a UILabel")
        assertTrue(
            label.adjustsFontForContentSizeCategory,
            "the flag is necessary but NOT sufficient — it does nothing unless font() keeps " +
                "building through UIFontMetrics, which only a device check can confirm",
        )
    }

    private fun UIView.firstLabel(): UILabel? =
        this as? UILabel ?: subviews.filterIsInstance<UIView>().firstNotNullOfOrNull { it.firstLabel() }
}
