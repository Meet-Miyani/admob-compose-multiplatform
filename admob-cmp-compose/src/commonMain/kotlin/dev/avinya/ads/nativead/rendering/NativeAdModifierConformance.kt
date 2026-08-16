package dev.avinya.ads.nativead.rendering

import dev.avinya.ads.nativead.layout.AdModifier

/**
 * Which side of the iOS interop boundary an [AdModifier] property has to be applied on.
 *
 * The iOS renderer builds the ad out of `UIView`s, but Compose embeds those views *below* its own
 * canvas and cuts a hole for them. That boundary splits the modifier surface in two, and getting a
 * property on the wrong side of it fails silently — which is how a root background, a root corner
 * radius and a root elevation each shipped broken.
 */
internal enum class AdModifierSurface {
    /**
     * Painted by the ad's own views, inside the cut-out. Safe on the UIKit side.
     */
    Interior,

    /**
     * Shapes the cut-out or draws outside it, so it must be applied to the Compose node instead of
     * the platform view — see [Modifier.adRootSurface][adRootSurface], which both platforms use.
     */
    Boundary,

    /**
     * Consumed while building the view tree rather than drawn: sizing, spacing and stacking that
     * every renderer resolves in its own layout pass.
     */
    Structural,
}

/**
 * Every visual property of [AdModifier], classified.
 *
 * `NativeAdModifierConformanceTest` asserts this covers [AdModifier] exactly — add a property to
 * the data class without classifying it here and the build fails. That is the point: each of the
 * silent divergences found so far was a property one renderer honoured and another dropped, and
 * nothing failed until someone looked at a device.
 *
 * ## Known remaining divergences
 *
 * Classification says which side of the boundary a property belongs on; it does not promise the
 * three renderers produce identical pixels for every combination. One case is knowingly left:
 *
 * - **A `HideWhenMissing` asset with a fixed size inside a `Box`.** `AdDisplay.Gone` is static, so
 *   every renderer drops those nodes while building the tree, and a stack can drop a
 *   policy-hidden one too because `UIStackView` skips hidden arranged subviews. A `Box` on iOS
 *   pins its children itself, so it leaves a policy-hidden child out entirely — which is right for
 *   the box's own size, but means the asset's `weak` outlet on `GADNativeAdView` becomes nil
 *   rather than pointing at a hidden view. That is accurate (the asset is not displayed) and is
 *   recorded here only because it differs from Android, which keeps the `View.GONE` child
 *   registered.
 */
internal val adModifierSurfaces: Map<String, AdModifierSurface> = mapOf(
    // Structural — resolved by each renderer's layout pass.
    "width" to AdModifierSurface.Structural,
    "height" to AdModifierSurface.Structural,
    "minWidthDp" to AdModifierSurface.Structural,
    "minHeightDp" to AdModifierSurface.Structural,
    "maxWidthDp" to AdModifierSurface.Structural,
    "maxHeightDp" to AdModifierSurface.Structural,
    "padding" to AdModifierSurface.Structural,
    "margin" to AdModifierSurface.Structural,
    "weight" to AdModifierSurface.Structural,
    "aspectRatio" to AdModifierSurface.Structural,
    "display" to AdModifierSurface.Structural,

    // Interior — painted by the ad's own views, inside the cut-out.
    "backgroundArgb" to AdModifierSurface.Interior,
    "borderWidthDp" to AdModifierSurface.Interior,
    "borderColorArgb" to AdModifierSurface.Interior,
    "borderRadiusDp" to AdModifierSurface.Interior,
    "alpha" to AdModifierSurface.Interior,
    "offsetXDp" to AdModifierSurface.Interior,
    "offsetYDp" to AdModifierSurface.Interior,
    "zIndex" to AdModifierSurface.Interior,

    // Boundary — reshapes the cut-out, or draws beyond it.
    "cornerRadiusDp" to AdModifierSurface.Boundary,
    "clipShape" to AdModifierSurface.Boundary,
    "elevationDp" to AdModifierSurface.Boundary,
)

/**
 * The property names [AdModifier] actually declares.
 *
 * Read off the data class `toString()`, which lists every constructor property — the only
 * reflection-free way to enumerate them that works on Kotlin/Native as well as JVM.
 */
internal fun declaredAdModifierPropertyNames(): Set<String> {
    val body = AdModifier.empty.toString().substringAfter('(').substringBeforeLast(')')
    val names = mutableSetOf<String>()
    val field = StringBuilder()
    var depth = 0

    fun takeField() {
        field.toString().substringBefore('=').trim().takeIf { it.isNotEmpty() }?.let(names::add)
        field.clear()
    }

    for (character in body) {
        when {
            // Nested values print their own properties — `padding=AdInsets(startDp=0.0, topDp=…)`
            // — so only split on commas at the top level.
            character == '(' -> depth++.also { field.append(character) }
            character == ')' -> (--depth).also { field.append(character) }
            character == ',' && depth == 0 -> takeField()
            else -> field.append(character)
        }
    }
    takeField()
    return names
}
