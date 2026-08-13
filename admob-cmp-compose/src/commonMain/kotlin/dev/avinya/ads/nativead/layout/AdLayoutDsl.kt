package dev.avinya.ads.nativead.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Builder for the native ad layout DSL. Should only be instantiated via the
 * [adLayout] top-level function.
 */
@AdLayoutDsl
public class AdLayoutBuilder internal constructor() : AdLayoutScope()

/**
 * Receiver scope for the native ad layout DSL. Provides builder functions
 * for every supported layout node and ad asset.
 */
@AdLayoutDsl
public open class AdLayoutScope internal constructor() {
    private val _children = mutableListOf<AdNode>()
    /** The list of child nodes accumulated in this scope. */
    public val children: List<AdNode> get() = _children

    /**
     * Adds a horizontal row container.
     *
     * @param modifier Layout modifier for the row.
     * @param horizontalAlignment Horizontal alignment of children.
     * @param verticalAlignment Vertical alignment of children.
     * @param spacing Spacing between children.
     * @param content Child layout content.
     */
    public fun row(
        modifier: AdModifier = AdModifier.empty,
        horizontalAlignment: AdAlignment.Horizontal = AdAlignment.Horizontal.Start,
        verticalAlignment: AdAlignment.Vertical = AdAlignment.Vertical.CenterVertically,
        spacing: Dp = 0.dp,
        content: AdLayoutScope.() -> Unit
    ) {
        val scope = AdLayoutScope().apply(content)
        _children += AdContainerNode.Row(modifier, scope.children, horizontalAlignment, verticalAlignment, spacing.value)
    }

    /**
     * Adds a vertical column container.
     *
     * @param modifier Layout modifier for the column.
     * @param horizontalAlignment Horizontal alignment of children.
     * @param verticalAlignment Vertical arrangement of children.
     * @param spacing Spacing between children.
     * @param content Child layout content.
     */
    public fun column(
        modifier: AdModifier = AdModifier.empty,
        horizontalAlignment: AdAlignment.Horizontal = AdAlignment.Horizontal.Start,
        verticalAlignment: AdAlignment.Vertical = AdAlignment.Vertical.Top,
        spacing: Dp = 0.dp,
        content: AdLayoutScope.() -> Unit
    ) {
        val scope = AdLayoutScope().apply(content)
        _children += AdContainerNode.Column(modifier, scope.children, horizontalAlignment, verticalAlignment, spacing.value)
    }

    /**
     * Adds a box (arbitrary positioned) container.
     *
     * @param modifier Layout modifier for the box.
     * @param contentAlignment Content alignment within the box.
     * @param content Child layout content.
     */
    public fun box(
        modifier: AdModifier = AdModifier.empty,
        contentAlignment: AdAlignment.Box = AdAlignment.Box.TopStart,
        content: AdLayoutScope.() -> Unit
    ) {
        val scope = AdLayoutScope().apply(content)
        _children += AdContainerNode.Box(modifier, scope.children, contentAlignment)
    }

    /**
     * Adds an empty spacer node.
     *
     * @param modifier Layout modifier sizing the spacer.
     */
    public fun spacer(modifier: AdModifier = AdModifier.empty) {
        _children += AdSpacer(modifier)
    }

    /**
     * Adds a static (non-ad) text node.
     *
     * @param text The text to display.
     * @param modifier Layout modifier for the text.
     * @param style Text style.
     * @param maxLines Maximum lines before truncation.
     */
    public fun text(
        text: String,
        modifier: AdModifier = AdModifier.empty,
        style: AdTextStyle = AdTextStyle.body,
        maxLines: Int? = null
    ) {
        _children += AdStaticText(text, modifier, style, maxLines)
    }

    /**
     * Binds the native ad's headline text asset.
     *
     * @param modifier Layout modifier for the headline.
     * @param style Text style (defaults to title).
     * @param maxLines Maximum lines (default 1).
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun headline(
        modifier: AdModifier = AdModifier.empty,
        style: AdTextStyle = AdTextStyle.title,
        maxLines: Int? = 1,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.Headline(modifier, style, maxLines, visibilityPolicy)
    }

    /**
     * Binds the native ad's body text asset.
     *
     * @param modifier Layout modifier for the body.
     * @param style Text style (defaults to body).
     * @param maxLines Maximum lines (default 2).
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun body(
        modifier: AdModifier = AdModifier.empty,
        style: AdTextStyle = AdTextStyle.body,
        maxLines: Int? = 2,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.Body(modifier, style, maxLines, visibilityPolicy)
    }

    /**
     * Binds the native ad's call-to-action button asset.
     * The renderer displays the creative-provided call-to-action text as supplied; this node
     * controls its layout and appearance, not its wording.
     *
     * @param modifier Layout modifier for the button.
     * @param style Button style (defaults to filled).
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun callToAction(
        modifier: AdModifier = AdModifier.empty,
        style: AdButtonStyle = AdButtonStyle.filled,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.CallToAction(modifier, style, visibilityPolicy)
    }

    /**
     * Binds the native ad's icon image asset.
     *
     * @param modifier Layout modifier for the icon.
     * @param style Image style.
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun icon(
        modifier: AdModifier = AdModifier.empty,
        style: AdImageStyle = AdImageStyle(),
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.Icon(modifier, style, visibilityPolicy)
    }

    /**
     * Binds the native ad's media asset (image or video).
     *
     * @param modifier Layout modifier for the media.
     * @param style Image style with crop content scale by default.
     * @param visibilityPolicy Visibility when the asset is missing (defaults to KeepSpace).
     */
    public fun media(
        modifier: AdModifier = AdModifier.fillMaxWidth().aspectRatio(16f / 9f),
        style: AdImageStyle = AdImageStyle(contentScale = AdContentScale.Crop),
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.KeepSpace
    ) {
        _children += AdAssetNode.Media(modifier, style, visibilityPolicy)
    }

    /**
     * Binds the native ad's advertiser/sponsor name asset.
     *
     * @param modifier Layout modifier for the advertiser text.
     * @param style Text style (defaults to caption).
     * @param maxLines Maximum lines (default 1).
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun advertiser(
        modifier: AdModifier = AdModifier.empty,
        style: AdTextStyle = AdTextStyle.caption,
        maxLines: Int? = 1,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.Advertiser(modifier, style, maxLines, visibilityPolicy)
    }

    /**
     * Binds the native ad's price string asset.
     *
     * @param modifier Layout modifier for the price text.
     * @param style Text style (defaults to caption).
     * @param maxLines Maximum lines (default 1).
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun price(
        modifier: AdModifier = AdModifier.empty,
        style: AdTextStyle = AdTextStyle.caption,
        maxLines: Int? = 1,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.Price(modifier, style, maxLines, visibilityPolicy)
    }

    /**
     * Binds the native ad's store/app name asset.
     *
     * @param modifier Layout modifier for the store text.
     * @param style Text style (defaults to caption).
     * @param maxLines Maximum lines (default 1).
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun store(
        modifier: AdModifier = AdModifier.empty,
        style: AdTextStyle = AdTextStyle.caption,
        maxLines: Int? = 1,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.Store(modifier, style, maxLines, visibilityPolicy)
    }

    /**
     * Binds the native ad's star rating asset.
     *
     * @param modifier Layout modifier for the star rating.
     * @param style Text style (defaults to caption).
     * @param visibilityPolicy Visibility when the asset is missing.
     */
    public fun starRating(
        modifier: AdModifier = AdModifier.empty,
        style: AdTextStyle = AdTextStyle.caption,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) {
        _children += AdAssetNode.StarRating(modifier, style, visibilityPolicy)
    }

    /**
     * Reserves space for the SDK-owned AdChoices privacy icon. Policy-relevant.
     *
     * @param modifier Layout modifier sizing the AdChoices slot.
     * @param visibilityPolicy Visibility when the asset is missing (defaults to KeepSpace).
     */
    public fun adChoices(
        modifier: AdModifier = AdModifier.empty,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.KeepSpace
    ) {
        _children += AdAssetNode.AdChoices(modifier, visibilityPolicy)
    }

    /**
     * Adds an "Ad" attribution badge. Policy-relevant.
     *
     * @param modifier Layout modifier for the badge.
     * @param text Badge text (default "Ad").
     * @param style Text style (defaults to badge).
     * @param visibilityPolicy Visibility when the asset is missing (defaults to KeepSpace).
     */
    public fun adBadge(
        modifier: AdModifier = AdModifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .sizeIn(minWidth = 15.dp, minHeight = 15.dp)
            .border(1.dp, Color(0xFF777777), 3.dp),
        text: String = "Ad",
        style: AdTextStyle = AdTextStyle.badge,
        visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.KeepSpace
    ) {
        _children += AdAssetNode.AdBadge(modifier, text, style, visibilityPolicy)
    }
}

/**
 * Top-level DSL entry point that builds an [AdLayout] tree. Single-child
 * content becomes the root node; multi-child content is wrapped in a
 * full-width [AdContainerNode.Column].
 *
 * Each [adLayout] construction recursively validates the tree and computes structural identity.
 * In Compose components, retain custom layouts using `remember { adLayout { ... } }` or
 * `remember(key) { adLayout { ... } }` to avoid re-validating and rebuilding platform native views
 * on every recomposition.
 */
public fun adLayout(content: AdLayoutBuilder.() -> Unit): AdLayout {
    val scope = AdLayoutBuilder().apply(content)
    val root = when (scope.children.size) {
        0 -> AdContainerNode.Column(AdModifier.empty, emptyList())
        1 -> scope.children.first()
        else -> AdContainerNode.Column(AdModifier.fillMaxWidth(), scope.children)
    }
    return AdLayout(root)
}
