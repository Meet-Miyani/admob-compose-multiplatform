package dev.avinya.ads.nativead.rendering

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdButtonTextCase
import dev.avinya.ads.nativead.layout.AdClip
import dev.avinya.ads.nativead.layout.AdInsets
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdNode

internal fun resolveNativeAdBackgroundArgb(
    modifier: AdModifier,
    styleBackgroundArgb: Long?,
): Long? = modifier.backgroundArgb ?: styleBackgroundArgb

/** True when [argb]'s alpha channel is fully opaque. Assumes the 0xAARRGGBB encoding of `toArgbLong`. */
internal fun isOpaqueArgb(argb: Long): Boolean = ((argb shr 24) and 0xFF) == 0xFFL

/**
 * The opaque colour a layout paints behind its own content, or `null` when the root is transparent
 * or merely translucent.
 *
 * This is the one colour a native ad layout can be relied upon to own on every platform, and iOS
 * depends on it: [dev.avinya.ads.ui.NativeAdView] embeds the ad through `UIKitView`, which Compose
 * places *below* its Metal canvas and cuts a hole for — `BlendMode.Clear` erases every Compose
 * pixel under the ad rect. Nothing Compose drew behind the ad survives, so a translucent ad view
 * composites straight onto `ComposeContainerView`'s backdrop (white in light mode, black in dark)
 * rather than onto the app's surface. Only a descendant's background is deliberately ignored: it
 * covers its own subtree, not the ad rect.
 */
internal fun resolveNativeAdSurfaceArgb(root: AdNode): Long? =
    root.modifier.backgroundArgb?.takeIf(::isOpaqueArgb)

/**
 * The elevation a layout raises its own root by, in dp, or 0 when it sits flat.
 *
 * Like the root corner radius this is a *boundary* property: a shadow renders outside the view it
 * belongs to, which on iOS is outside the interop cut-out entirely, so a `CALayer` shadow on the
 * root can never be seen. It has to be drawn on the Compose side of the boundary instead.
 */
internal fun resolveNativeAdRootElevationDp(root: AdNode): Float = root.modifier.elevationDp

/**
 * The outline a layout clips its own root to, or `null` when the root is square and needs no
 * Compose-side shaping at all.
 *
 * Mirrors the precedence the platform renderers already use for a node's corner radius
 * (`cornerRadiusDp` before `borderRadiusDp`), falling back to an explicit [AdClip].
 *
 * Returns a shape rather than a radius because [AdClip.Circle] has no radius to report: the
 * previous `Float`-returning resolver answered `0` for it, so a circular root silently squared off
 * on the Compose side of the iOS boundary while the `UIView` beneath rounded itself to a corner
 * radius of 10 000 that nothing could see.
 */
internal fun resolveNativeAdRootShape(root: AdNode): Shape? {
    val modifier = root.modifier
    val radiusDp = modifier.cornerRadiusDp
        ?: modifier.borderRadiusDp
        ?: (modifier.clipShape as? AdClip.RoundedCorner)?.radiusDp
    return when {
        radiusDp != null && radiusDp > 0f -> RoundedCornerShape(radiusDp.dp)
        modifier.clipShape == AdClip.Circle -> CircleShape
        else -> null
    }
}

internal fun resolveNativeAdDrawableBackgroundArgb(
    modifier: AdModifier,
    styleBackgroundArgb: Long?,
): Long = resolveNativeAdBackgroundArgb(modifier, styleBackgroundArgb) ?: 0x00000000

/**
 * The padding inside a call-to-action, from the node's own `padding` when it sets one and from
 * [AdButtonStyle] otherwise.
 *
 * The vertical fallback is a constant rather than an `AdButtonStyle` property on purpose: adding a
 * sixth property to that public data class removes its existing constructor, `component5()` and
 * `copy()` from the klib ABI, which this project treats as frozen. A layout that wants a different
 * button height sets `AdModifier.padding(...)` on the `callToAction` node, which takes precedence
 * over the whole style fallback and is what the branch above is for.
 *
 * It has to be non-zero. The renderers size the call-to-action as *text plus these insets* — that
 * is the point of building it from a label rather than a platform button — so a zero vertical inset
 * would collapse the most important tap target in the ad to the height of its own text. It
 * previously did not collapse only because `android.widget.Button` quietly imposed the OEM theme's
 * minimum height, which is exactly the platform-dependence this replaced.
 */
internal fun resolveCallToActionContentInsets(
    modifier: AdModifier,
    style: AdButtonStyle,
): AdInsets = modifier.padding.takeUnless(::isZeroInset)
    ?: AdInsets(
        startDp = style.horizontalPaddingDp,
        topDp = DEFAULT_CALL_TO_ACTION_VERTICAL_PADDING_DP,
        endDp = style.horizontalPaddingDp,
        bottomDp = DEFAULT_CALL_TO_ACTION_VERTICAL_PADDING_DP,
    )

/**
 * Vertical padding inside a call-to-action when neither the node nor its style specifies one.
 *
 * Chosen so a 14sp label lands near Material's 36dp minimum button height and within reach of the
 * 44pt iOS tap-target guidance, on both platforms, from the same arithmetic.
 */
private const val DEFAULT_CALL_TO_ACTION_VERTICAL_PADDING_DP: Float = 10f

/**
 * The corner radius a call-to-action rounds itself to.
 *
 * A call-to-action is the one node with two possible sources for its surface — its [AdButtonStyle]
 * and its own [AdModifier] — so the precedence between them has to be stated once rather than
 * re-derived by each renderer. The node's modifier wins, in the same order the root uses
 * (`cornerRadiusDp`, then `borderRadiusDp`, then an explicit rounded [AdClip]), and the style is the
 * fallback. That is what makes `callToAction(modifier = AdModifier.background(c).cornerRadius(r))`
 * behave the way the DSL implies.
 *
 * [AdClip.Circle] is deliberately not answered here: it is not a radius, and every renderer already
 * turns it into a capsule through its own clip handling.
 */
internal fun resolveCallToActionCornerRadiusDp(
    modifier: AdModifier,
    style: AdButtonStyle,
): Float = modifier.cornerRadiusDp
    ?: modifier.borderRadiusDp
    ?: (modifier.clipShape as? AdClip.RoundedCorner)?.radiusDp
    ?: style.cornerRadiusDp

/**
 * Applies [AdButtonStyle.textCase] to a creative's call-to-action label.
 *
 * Shared by every renderer (Android, iOS, preview) so a layout previews with the same wording it
 * ships with — the divergence that made the old media-background bug invisible until runtime.
 */
internal fun resolveCallToActionText(text: String, textCase: AdButtonTextCase): String =
    when (textCase) {
        AdButtonTextCase.AsProvided -> text
        AdButtonTextCase.SentenceCase -> text.sentenceCaseIfAllCaps()
    }

/**
 * Sentence-cases a string only when it carries no case information of its own — i.e. it contains
 * letters and every one of them is uppercase. Mixed-case labels are returned untouched so
 * deliberate capitalisation ("Shop at H&M") is never flattened.
 */
private fun String.sentenceCaseIfAllCaps(): String {
    val firstLetter = indexOfFirst(Char::isLetter)
    if (firstLetter < 0) return this
    if (any { it.isLetter() && it.isLowerCase() }) return this
    val lowered = lowercase()
    return lowered.substring(0, firstLetter) +
        lowered[firstLetter].uppercaseChar() +
        lowered.substring(firstLetter + 1)
}

internal fun AdModifier.withoutPadding(): AdModifier = copy(padding = AdInsets())

private fun isZeroInset(insets: AdInsets): Boolean =
    insets.startDp == 0f && insets.topDp == 0f && insets.endDp == 0f && insets.bottomDp == 0f
