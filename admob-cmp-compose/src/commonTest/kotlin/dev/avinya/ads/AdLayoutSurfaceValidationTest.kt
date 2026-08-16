package dev.avinya.ads

import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A transparent layout root renders correctly on Android (the ad view draws over Compose's own
 * surface) but not on iOS, where Compose clears every pixel it drew under the interop rect. The
 * validator surfaces that at layout-build time rather than leaving it to be discovered on a device.
 */
class AdLayoutSurfaceValidationTest {

    private fun layoutWithRootModifier(modifier: AdModifier) = AdLayout(
        root = AdContainerNode.Column(
            modifier = modifier,
            children = listOf(
                AdAssetNode.Headline(),
                AdAssetNode.AdBadge(),
                AdAssetNode.AdChoices(),
            ),
        ),
    )

    private fun AdLayout.hasTransparentRootWarning(): Boolean =
        validation.warnings.any { it.code == "transparent_root_background" }

    @Test
    fun `root without a background is warned about`() {
        assertTrue(layoutWithRootModifier(AdModifier.empty).hasTransparentRootWarning())
    }

    @Test
    fun `root with a translucent background is warned about`() {
        assertTrue(
            layoutWithRootModifier(AdModifier(backgroundArgb = 0x80101014)).hasTransparentRootWarning(),
        )
    }

    @Test
    fun `root with an opaque background is not warned about`() {
        assertTrue(
            !layoutWithRootModifier(AdModifier(backgroundArgb = 0xFF101014)).hasTransparentRootWarning(),
        )
    }

    @Test
    fun `a transparent root never blocks rendering`() {
        assertTrue(layoutWithRootModifier(AdModifier.empty).validation.isValid)
    }
}
