package dev.avinya.ads.nativead.rendering

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdNode

/**
 * Applies the layout root's *boundary* properties — the ones that shape the ad rect or draw
 * outside it — on the Compose side, on **both** platforms.
 *
 * On iOS this is a correctness requirement. Compose composes the host modifier as
 * `modifier then platformModifier`, and `platformModifier` is what carries the
 * `drawBehind { drawRect(BlendMode.Clear) }` that cuts the interop hole. Anything applied here
 * therefore wraps that clear: [shadow]'s clip reshapes the hole itself, so rounded corners are
 * never cleared and the app's own pixels survive in them, and the shadow lands on the canvas
 * *outside* the hole, where a `CALayer` shadow on the `UIView` could never reach.
 *
 * On Android it is a parity requirement. `AndroidView` cuts no hole, so a root shadow *could* have
 * been drawn by the view — except `AndroidNativeAdLayoutRenderer` sets `clipChildren = true` on the
 * `NativeAdView` for asset containment, and the root content view fills it exactly, so the shadow
 * had nowhere to land and was clipped away. The same layout raised a visible shadow on iOS and a
 * flat card on Android. Drawing it Compose-side on both platforms is what makes
 * `adModifierSurfaces`' `Boundary` classification true rather than aspirational.
 *
 * Each renderer still applies these properties to its own root view. That application is inert for
 * the root — clipped on Android, outside the cut-out on iOS — and is left in place because the same
 * code path serves every non-root node, where it is exactly right.
 */
internal fun Modifier.adRootSurface(root: AdNode): Modifier {
    val shape = resolveNativeAdRootShape(root)
    val elevationDp = resolveNativeAdRootElevationDp(root)
    if (shape == null && elevationDp <= 0f) return this
    // `clip = true` makes Compose install the graphics layer even at zero elevation, so a rounded
    // root with no shadow is still clipped.
    return shadow(
        elevation = elevationDp.coerceAtLeast(0f).dp,
        shape = shape ?: RectangleShape,
        clip = true,
    )
}
