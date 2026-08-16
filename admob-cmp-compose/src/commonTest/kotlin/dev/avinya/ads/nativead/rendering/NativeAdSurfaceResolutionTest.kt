package dev.avinya.ads.nativead.rendering

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdClip
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS renderer places the ad below Compose's Metal canvas, which erases every Compose pixel
 * under the ad rect. The layout root's own opaque background is therefore the only thing that can
 * paint that rect, so resolving it has to be exact.
 */
class NativeAdSurfaceResolutionTest {

    @Test
    fun `fully opaque alpha is opaque`() {
        assertTrue(isOpaqueArgb(0xFF112233))
    }

    @Test
    fun `translucent alpha is not opaque`() {
        assertFalse(isOpaqueArgb(0xFE112233))
    }

    @Test
    fun `zero alpha is not opaque`() {
        assertFalse(isOpaqueArgb(0x00112233))
    }

    @Test
    fun `opaque root background is the ad surface`() {
        assertEquals(
            0xFF101014,
            resolveNativeAdSurfaceArgb(
                AdContainerNode.Column(
                    modifier = AdModifier(backgroundArgb = 0xFF101014),
                    children = listOf(AdAssetNode.Headline()),
                ),
            ),
        )
    }

    @Test
    fun `translucent root background is not a usable surface`() {
        assertNull(
            resolveNativeAdSurfaceArgb(
                AdContainerNode.Column(
                    modifier = AdModifier(backgroundArgb = 0x80101014),
                    children = listOf(AdAssetNode.Headline()),
                ),
            ),
        )
    }

    @Test
    fun `root without a background has no surface`() {
        assertNull(
            resolveNativeAdSurfaceArgb(
                AdContainerNode.Column(
                    modifier = AdModifier.empty,
                    children = listOf(AdAssetNode.Headline()),
                ),
            ),
        )
    }

    @Test
    fun `a descendant background is not promoted to the root surface`() {
        assertNull(
            resolveNativeAdSurfaceArgb(
                AdContainerNode.Column(
                    modifier = AdModifier.empty,
                    children = listOf(
                        AdContainerNode.Row(
                            modifier = AdModifier(backgroundArgb = 0xFF101014),
                            children = listOf(AdAssetNode.Headline()),
                        ),
                    ),
                ),
            ),
        )
    }
}

/**
 * iOS applies this radius as a Compose `Modifier.clip` on the interop node, which sits outside
 * Compose's `drawBehind { Clear }` and therefore shapes the cut-out itself — the corners are never
 * cleared, so the app's own pixels survive there.
 */
class NativeAdRootShapeTest {

    @Test
    fun `root shape is taken from the root modifier`() {
        assertEquals(
            RoundedCornerShape(18f.dp),
            resolveNativeAdRootShape(
                AdContainerNode.Column(
                    modifier = AdModifier(cornerRadiusDp = 18f),
                    children = listOf(AdAssetNode.Headline()),
                ),
            ),
        )
    }

    @Test
    fun `root shape prefers cornerRadiusDp over borderRadiusDp`() {
        assertEquals(
            RoundedCornerShape(18f.dp),
            resolveNativeAdRootShape(
                AdContainerNode.Column(
                    modifier = AdModifier(cornerRadiusDp = 18f, borderRadiusDp = 4f),
                    children = listOf(AdAssetNode.Headline()),
                ),
            ),
        )
    }

    @Test
    fun `root shape falls back to an explicit rounded clip`() {
        assertEquals(
            RoundedCornerShape(12f.dp),
            resolveNativeAdRootShape(
                AdContainerNode.Column(
                    modifier = AdModifier(clipShape = AdClip.RoundedCorner(12f)),
                    children = listOf(AdAssetNode.Headline()),
                ),
            ),
        )
    }

    @Test
    fun `a square root reports no shape`() {
        assertEquals(
            null,
            resolveNativeAdRootShape(
                AdContainerNode.Column(
                    modifier = AdModifier.empty,
                    children = listOf(AdAssetNode.Headline()),
                ),
            ),
        )
    }

    @Test
    fun `a descendant corner radius is not promoted to the root`() {
        assertEquals(
            null,
            resolveNativeAdRootShape(
                AdContainerNode.Column(
                    modifier = AdModifier.empty,
                    children = listOf(
                        AdContainerNode.Row(
                            modifier = AdModifier(cornerRadiusDp = 18f),
                            children = listOf(AdAssetNode.Headline()),
                        ),
                    ),
                ),
            ),
        )
    }
}
