package dev.avinya.ads.nativead.layout

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.avinya.ads.nativead.rendering.effectiveWeight
import dev.avinya.ads.nativead.rendering.resolveCallToActionContentInsets
import dev.avinya.ads.nativead.rendering.resolveCallToActionCornerRadiusDp
import dev.avinya.ads.nativead.rendering.resolveStarRatingText
import dev.avinya.ads.nativead.rendering.resolveCallToActionText
import dev.avinya.ads.nativead.rendering.resolveNativeAdBackgroundArgb
import dev.avinya.ads.nativead.rendering.withoutPadding

/**
 * Renders an [AdLayout] with sample preview data for Compose previews /
 * design-time visualisation. **Does not load a real ad.** Use this in
 * `@Preview` composables to verify your native ad layout's appearance.
 *
 * @param layout The layout to render (defaults to [AdTemplates.mediaCard]).
 * @param modifier Modifier for the root container.
 * @param data Sample data to populate the layout assets.
 */
@Composable
public fun AdLayoutPreview(
    layout: AdLayout = AdTemplates.mediaCard,
    modifier: Modifier = Modifier,
    data: AdLayoutPreviewData = AdLayoutPreviewData.default
) {
    RenderAdLayoutPreviewNode(
        node = layout.root,
        data = data,
        modifier = modifier
    )
}

/**
 * Sample data for previewing a native ad layout in [AdLayoutPreview].
 * All fields carry realistic defaults suitable for design-time rendering.
 */
@Immutable
public data class AdLayoutPreviewData(
    /** Sample headline text. */
    val headline: String = "Build faster with sample native ads",
    /** Sample body text. */
    val body: String = "Preview your native ad layout with realistic sample copy before wiring live AdMob assets.",
    /** Sample call-to-action text. */
    val callToAction: String = "Install",
    /** Sample advertiser name. */
    val advertiser: String = "AdMob CMP",
    /** Sample price string. */
    val price: String = "Free",
    /** Sample store name. */
    val store: String = "App Store",
    /** Sample star rating (out of 5). */
    val starRating: Double = 4.6,
    /** Whether to show the icon placeholder. */
    val hasIcon: Boolean = true,
    /** Whether to show the media placeholder. */
    val hasMedia: Boolean = true
) {
    public companion object {
        /** Default preview data with all fields set to realistic sample values. */
        public val default: AdLayoutPreviewData = AdLayoutPreviewData()
    }
}

@Composable
private fun RenderAdLayoutPreviewNode(
    node: AdNode,
    data: AdLayoutPreviewData,
    modifier: Modifier = Modifier
) {
    if (node.modifier.display == AdDisplay.Gone) return

    val nodeModifier = modifier.then(node.modifier.toComposeModifier())
    when (node) {
        is AdContainerNode.Row -> Row(
            modifier = nodeModifier,
            horizontalArrangement = Arrangement.spacedBy(node.spacingDp.dp, node.horizontalAlignment.toComposeAlignment()),
            verticalAlignment = node.verticalAlignment.toComposeAlignment()
        ) {
            node.children.forEach { child ->
                val childModifier = child.modifier.effectiveWeight?.let { Modifier.weight(it) } ?: Modifier
                RenderAdLayoutPreviewNode(child, data, childModifier)
            }
        }
        is AdContainerNode.Column -> Column(
            modifier = nodeModifier,
            verticalArrangement = Arrangement.spacedBy(node.spacingDp.dp, node.verticalAlignment.toComposeAlignment()),
            horizontalAlignment = node.horizontalAlignment.toComposeAlignment()
        ) {
            node.children.forEach { child ->
                val childModifier = child.modifier.effectiveWeight?.let { Modifier.weight(it) } ?: Modifier
                RenderAdLayoutPreviewNode(child, data, childModifier)
            }
        }
        is AdContainerNode.Box -> Box(
            modifier = nodeModifier,
            contentAlignment = node.contentAlignment.toComposeAlignment()
        ) {
            node.children.forEach { child ->
                RenderAdLayoutPreviewNode(child, data)
            }
        }
        is AdSpacer -> Spacer(nodeModifier)
        is AdStaticText -> PreviewText(node.text, node.style, node.maxLines, nodeModifier)
        is AdAssetNode.Headline -> PreviewText(data.headline, node.style, node.maxLines, nodeModifier, node.visibilityPolicy)
        is AdAssetNode.Body -> PreviewText(data.body, node.style, node.maxLines, nodeModifier, node.visibilityPolicy)
        is AdAssetNode.CallToAction -> PreviewButton(
            text = resolveCallToActionText(data.callToAction, node.style.textCase),
            style = node.style,
            nodeModifier = node.modifier,
            modifier = modifier.then(node.modifier.withoutPadding().toComposeModifier()),
            visibilityPolicy = node.visibilityPolicy,
        )
        is AdAssetNode.Icon -> PreviewIcon(nodeModifier, node.modifier, node.style, data.hasIcon, node.visibilityPolicy)
        is AdAssetNode.Media -> PreviewMedia(nodeModifier, node.modifier, data.hasMedia, node.style, node.visibilityPolicy)
        is AdAssetNode.Advertiser -> PreviewText(data.advertiser, node.style, node.maxLines, nodeModifier, node.visibilityPolicy)
        is AdAssetNode.Price -> PreviewText(data.price, node.style, node.maxLines, nodeModifier, node.visibilityPolicy)
        is AdAssetNode.Store -> PreviewText(data.store, node.style, node.maxLines, nodeModifier, node.visibilityPolicy)
        is AdAssetNode.StarRating -> PreviewText(
            resolveStarRatingText(data.starRating), node.style, 1, nodeModifier, node.visibilityPolicy,
        )
        is AdAssetNode.AdChoices -> PreviewAdChoices(nodeModifier)
        is AdAssetNode.AdBadge -> PreviewText(node.text, node.style, 1, nodeModifier, node.visibilityPolicy)
    }
}

@Composable
private fun PreviewText(
    text: String?,
    style: AdTextStyle,
    maxLines: Int?,
    modifier: Modifier,
    visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.KeepSpace
) {
    if (text.isNullOrBlank()) {
        MissingPreviewAsset(modifier, visibilityPolicy)
        return
    }
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines ?: Int.MAX_VALUE,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            color = Color(style.colorArgb),
            fontSize = style.fontSizeSp.sp,
            fontWeight = style.fontWeight.toComposeFontWeight(),
            textAlign = style.textAlign.toComposeTextAlign(),
            fontFamily = style.fontFamily.toComposeFontFamily()
        )
    )
}

@Composable
private fun PreviewButton(
    text: String?,
    style: AdButtonStyle,
    nodeModifier: AdModifier,
    modifier: Modifier,
    visibilityPolicy: AdVisibilityPolicy
) {
    if (text.isNullOrBlank()) {
        MissingPreviewAsset(modifier, visibilityPolicy)
        return
    }
    val insets = resolveCallToActionContentInsets(nodeModifier, style)
    val backgroundArgb = resolveNativeAdBackgroundArgb(nodeModifier, style.backgroundArgb)
    Box(
        modifier = modifier
            // The same precedence the two shipping renderers use: the node's own corner radius
            // overrides the button style's, so `background(c).cornerRadius(r)` on a callToAction
            // previews as it renders.
            .clip(RoundedCornerShape(resolveCallToActionCornerRadiusDp(nodeModifier, style).dp))
            .background(backgroundArgb?.let(::Color) ?: Color.Transparent)
            .padding(insets.startDp.dp, insets.topDp.dp, insets.endDp.dp, insets.bottomDp.dp),
        contentAlignment = Alignment.Center
    ) {
        PreviewText(text, style.textStyle, 1, Modifier)
    }
}

@Composable
private fun PreviewIcon(
    modifier: Modifier,
    nodeModifier: AdModifier,
    style: AdImageStyle,
    hasIcon: Boolean,
    visibilityPolicy: AdVisibilityPolicy
) {
    if (!hasIcon) {
        MissingPreviewAsset(modifier, visibilityPolicy)
        return
    }
    val backgroundArgb = resolveNativeAdBackgroundArgb(nodeModifier, style.backgroundArgb)
    Box(
        modifier = modifier
            .background(backgroundArgb?.let(::Color) ?: Color.Transparent)
            .border(1.dp, Color(0xFFD2E3FC), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        BasicText("A", style = TextStyle(color = Color(0xFF1967D2), fontSize = 18.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun PreviewMedia(
    modifier: Modifier,
    nodeModifier: AdModifier,
    hasMedia: Boolean,
    style: AdImageStyle,
    visibilityPolicy: AdVisibilityPolicy
) {
    if (!hasMedia) {
        MissingPreviewAsset(modifier, visibilityPolicy)
        return
    }
    val mediaModifier = when (style.contentScale) {
        AdContentScale.Fit -> modifier
        AdContentScale.Crop -> modifier.clip(RoundedCornerShape(10.dp))
        AdContentScale.FillBounds -> modifier
    }
    Box(
        modifier = mediaModifier.background(
            resolveNativeAdBackgroundArgb(nodeModifier, style.backgroundArgb)?.let(::Color) ?: Color.Transparent,
        ),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = "Media",
            style = TextStyle(color = Color(0xFF5F6368), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun PreviewAdChoices(modifier: Modifier) {
    Box(
        modifier = modifier
            // A default, not an override: forcing 22.dp here discarded whatever size the node
            // asked for, so `adChoices(AdModifier.size(20.dp))` previewed at a size the shipped
            // renderers never use.
            .defaultMinSize(22.dp, 22.dp)
            .background(Color(0xCCFFFFFF))
            .border(1.dp, Color(0xFFBDBDBD)),
        contentAlignment = Alignment.Center
    ) {
        BasicText("i", style = TextStyle(color = Color(0xFF5F6368), fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun MissingPreviewAsset(
    modifier: Modifier,
    visibilityPolicy: AdVisibilityPolicy
) {
    when (visibilityPolicy) {
        AdVisibilityPolicy.HideWhenMissing -> Unit
        AdVisibilityPolicy.InvisibleWhenMissing -> Spacer(modifier.alpha(0f))
        AdVisibilityPolicy.KeepSpace -> Spacer(modifier)
    }
}

internal fun AdModifier.toComposeModifier(): Modifier {
    var modifier: Modifier = Modifier
    // Margin is space *outside* the node, so it has to precede every visual property — otherwise
    // the background and border would paint over it.
    if (margin.startDp != 0f || margin.topDp != 0f || margin.endDp != 0f || margin.bottomDp != 0f) {
        modifier = modifier.padding(margin.startDp.dp, margin.topDp.dp, margin.endDp.dp, margin.bottomDp.dp)
    }
    if (minWidthDp != null || maxWidthDp != null) {
        modifier = modifier.widthIn(
            min = minWidthDp?.dp ?: Dp.Unspecified,
            max = maxWidthDp?.dp ?: Dp.Unspecified,
        )
    }
    if (minHeightDp != null || maxHeightDp != null) {
        modifier = modifier.heightIn(
            min = minHeightDp?.dp ?: Dp.Unspecified,
            max = maxHeightDp?.dp ?: Dp.Unspecified,
        )
    }
    modifier = when (width) {
        AdLayoutSize.Match -> modifier.fillMaxWidth()
        is AdLayoutSize.Fixed -> modifier.width(width.toDp())
        AdLayoutSize.Wrap -> modifier
    }
    modifier = when (height) {
        AdLayoutSize.Match -> modifier.fillMaxHeight()
        is AdLayoutSize.Fixed -> modifier.height(height.toDp())
        AdLayoutSize.Wrap -> modifier
    }
    if (width == AdLayoutSize.Match && height == AdLayoutSize.Match) modifier = modifier.fillMaxSize()
    aspectRatio?.let { modifier = modifier.aspectRatio(it) }
    // Applied only when they actually do something. Emitting `alpha(1f)`, `zIndex(0f)` and
    // zero-valued padding/offset on every node allocated a modifier element per node per preview
    // for no effect, and left an empty AdModifier indistinguishable from a populated one.
    if (offsetXDp != 0f || offsetYDp != 0f) modifier = modifier.offset(offsetXDp.dp, offsetYDp.dp)
    // `Gone` is handled by the caller, which skips the node entirely. `Invisible` has to be drawn
    // as occupied-but-unpainted space, and was previously not handled anywhere in the preview: a
    // node marked `AdModifier.invisible()` went out INVISIBLE on Android, alpha-0 on iOS, and fully
    // visible in the preview.
    if (display == AdDisplay.Invisible) modifier = modifier.alpha(0f)
    else if (alpha != 1f) modifier = modifier.alpha(alpha)
    if (zIndex != 0f) modifier = modifier.zIndex(zIndex)
    // Drawn before the clip and background so the shadow falls outside the shape, as on Android.
    if (elevationDp > 0f) {
        modifier = modifier.shadow(elevationDp.dp, toComposeShape(), clip = false)
    }
    modifier = when (val clip = clipShape) {
        AdClip.Bounds -> modifier.clip(RoundedCornerShape(0.dp))
        AdClip.Circle -> modifier.clip(CircleShape)
        AdClip.None -> modifier
        is AdClip.RoundedCorner -> modifier.clip(RoundedCornerShape(clip.radiusDp.dp))
    }
    cornerRadiusDp?.let { modifier = modifier.clip(RoundedCornerShape(it.dp)) }
    backgroundArgb?.let { modifier = modifier.background(Color(it)) }
    if (borderWidthDp != null && borderColorArgb != null) {
        modifier = modifier.border(
            width = borderWidthDp.dp,
            color = Color(borderColorArgb),
            shape = RoundedCornerShape((borderRadiusDp ?: cornerRadiusDp ?: 0f).dp)
        )
    }
    // Padding is space *inside* the node, so it must follow the background and border — Android
    // (`setPadding` + a background drawable) and iOS (stack `layoutMargins` inside the surface)
    // both paint the background across it. Applying it earlier left the padding band transparent
    // and previewed a layout like `background(surface).padding(vertical = 12.dp)` with bands the
    // shipped renderers fill.
    if (padding.startDp != 0f || padding.topDp != 0f || padding.endDp != 0f || padding.bottomDp != 0f) {
        modifier = modifier.padding(padding.startDp.dp, padding.topDp.dp, padding.endDp.dp, padding.bottomDp.dp)
    }
    return modifier
}

/** The outline a node draws its shadow, clip and border against. */
private fun AdModifier.toComposeShape(): Shape = when (val clip = clipShape) {
    AdClip.Circle -> CircleShape
    is AdClip.RoundedCorner -> RoundedCornerShape(clip.radiusDp.dp)
    AdClip.Bounds, AdClip.None -> RoundedCornerShape((cornerRadiusDp ?: 0f).dp)
}

private fun AdLayoutSize.Fixed.toDp() = dp.dp

private fun AdAlignment.Horizontal.toComposeAlignment(): Alignment.Horizontal = when (this) {
    AdAlignment.Horizontal.Start -> Alignment.Start
    AdAlignment.Horizontal.CenterHorizontally -> Alignment.CenterHorizontally
    AdAlignment.Horizontal.End -> Alignment.End
}

private fun AdAlignment.Vertical.toComposeAlignment(): Alignment.Vertical = when (this) {
    AdAlignment.Vertical.Top -> Alignment.Top
    AdAlignment.Vertical.CenterVertically -> Alignment.CenterVertically
    AdAlignment.Vertical.Bottom -> Alignment.Bottom
}

private fun AdAlignment.Box.toComposeAlignment(): Alignment = when (this) {
    AdAlignment.Box.TopStart -> Alignment.TopStart
    AdAlignment.Box.TopCenter -> Alignment.TopCenter
    AdAlignment.Box.TopEnd -> Alignment.TopEnd
    AdAlignment.Box.CenterStart -> Alignment.CenterStart
    AdAlignment.Box.Center -> Alignment.Center
    AdAlignment.Box.CenterEnd -> Alignment.CenterEnd
    AdAlignment.Box.BottomStart -> Alignment.BottomStart
    AdAlignment.Box.BottomCenter -> Alignment.BottomCenter
    AdAlignment.Box.BottomEnd -> Alignment.BottomEnd
}

private fun AdFontWeight.toComposeFontWeight(): FontWeight = when (this) {
    AdFontWeight.Normal -> FontWeight.Normal
    AdFontWeight.Medium -> FontWeight.Medium
    AdFontWeight.Bold -> FontWeight.Bold
}

private fun AdTextAlign.toComposeTextAlign(): TextAlign = when (this) {
    AdTextAlign.Start -> TextAlign.Start
    AdTextAlign.Center -> TextAlign.Center
    AdTextAlign.End -> TextAlign.End
}

private fun AdFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    AdFontFamily.Default -> FontFamily.Default
    AdFontFamily.SansSerif -> FontFamily.SansSerif
    AdFontFamily.Serif -> FontFamily.Serif
    AdFontFamily.Monospace -> FontFamily.Monospace
    is AdFontFamily.Named -> FontFamily.Default
    is AdFontFamily.FromCompose -> fontFamily
}
