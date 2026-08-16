package dev.avinya.ads.nativead.rendering

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the renderer contract rather than any one renderer.
 *
 * The SDK has three hand-written implementations of the same layout DSL — Android views, iOS
 * UIKit, and the Compose `AdLayoutPreview` — and every rendering defect found so far was the same
 * shape: a property one of them honoured and another silently dropped. Nothing failed; you had to
 * notice it on a device. These tests make an unclassified property a build failure instead.
 */
class NativeAdModifierConformanceTest {

    @Test
    fun `property names are readable from the data class`() {
        val declared = declaredAdModifierPropertyNames()
        assertTrue("width" in declared, "expected to parse property names, got: $declared")
        assertTrue("elevationDp" in declared, "expected to parse property names, got: $declared")
        assertTrue(declared.size > 15, "expected the full property set, got: $declared")
    }

    @Test
    fun `every AdModifier property is classified`() {
        val unclassified = declaredAdModifierPropertyNames() - adModifierSurfaces.keys
        assertEquals(
            emptySet(),
            unclassified,
            "New AdModifier properties are unclassified: $unclassified. Add each to " +
                "`adModifierSurfaces`, and make sure every renderer — Android, iOS and " +
                "AdLayoutPreview — actually consumes it. A Boundary property must be applied to " +
                "the Compose interop node on iOS, not to the UIView.",
        )
    }

    @Test
    fun `no classification outlives its property`() {
        val stale = adModifierSurfaces.keys - declaredAdModifierPropertyNames()
        assertEquals(
            emptySet(),
            stale,
            "`adModifierSurfaces` classifies properties AdModifier no longer declares: $stale",
        )
    }

    // The three properties that shipped broken, and the reason each did.
    @Test
    fun `properties that shape or escape the cut-out are Boundary`() {
        listOf("cornerRadiusDp", "clipShape", "elevationDp").forEach { property ->
            assertEquals(
                AdModifierSurface.Boundary,
                adModifierSurfaces[property],
                "$property affects the interop cut-out and must be applied Compose-side on iOS",
            )
        }
    }

    @Test
    fun `the resolvers the iOS interop node depends on read the root modifier`() {
        val root: AdNode = AdContainerNode.Column(
            modifier = AdModifier(cornerRadiusDp = 18f, elevationDp = 6f, backgroundArgb = 0xFF101014),
            children = listOf(AdAssetNode.Headline()),
        )
        assertEquals(RoundedCornerShape(18f.dp), resolveNativeAdRootShape(root))
        assertEquals(6f, resolveNativeAdRootElevationDp(root))
        assertEquals(0xFF101014, resolveNativeAdSurfaceArgb(root))
    }

    // `AdClip.Circle` has no radius to report, so the old `Float`-returning resolver answered 0 and
    // a circular root silently squared off on the Compose side of the iOS interop boundary.
    @Test
    fun `a circular root resolves to a circle rather than to no rounding`() {
        val root: AdNode = AdContainerNode.Column(
            modifier = AdModifier.empty.clipCircle(),
            children = listOf(AdAssetNode.Headline()),
        )
        assertEquals(CircleShape, resolveNativeAdRootShape(root))
    }

    @Test
    fun `a square root asks for no Compose side shape`() {
        val root: AdNode = AdContainerNode.Column(
            modifier = AdModifier.empty,
            children = listOf(AdAssetNode.Headline()),
        )
        assertEquals(null, resolveNativeAdRootShape(root))
    }
}
