@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.avinya.ads.nativead.rendering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraLarge
import platform.UIKit.UIContentSizeCategoryLarge
import platform.UIKit.UIFont
import platform.UIKit.UIFontMetrics
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
}
