package dev.avinya.ads.nativead.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * DSL marker annotation that restricts lambda receivers in the native ad
 * layout DSL. Ensures builders are scoped correctly and prevents accidental
 * nesting of unrelated DSLs.
 */
@DslMarker
public annotation class AdLayoutDsl

/**
 * An immutable, rendered native ad layout tree. Produced by the [adLayout]
 * DSL function. [validation] is computed eagerly and reports any missing
 * policy-required assets (headline, ad badge, AdChoices).
 */
@Immutable
public data class AdLayout(
    /**
     * The root node of the layout tree, as supplied.
     *
     * Prefer [frozenRoot] for anything that renders or validates: a caller can hand a
     * `MutableList` to a container constructor (Kotlin's `List` is read-only, not immutable)
     * and keep mutating it afterwards, which would otherwise change the tree underneath the
     * identity and validation cached below.
     */
    val root: AdNode
) {
    /**
     * A deep copy taken once, at construction.
     *
     * [identity] and [validation] are computed from this, and both renderers consume it, so
     * every consumer of an `AdLayout` sees exactly the tree that was validated. Without it,
     * mutating a retained child list left a layout whose cached report described a tree that
     * no longer existed — and `@Immutable` is a promise to the Compose compiler that the
     * public properties cannot change after construction, which a caller-owned list breaks as
     * a matter of type, not merely of use.
     *
     * The copy is cheap (layout trees are a handful of nodes, built inside `remember`) and
     * needs no public API change, which is what keeps the frozen ABI intact.
     */
    internal val frozenRoot: AdNode = root.deepCopy()

    /**
     * Stable identity string for the layout, used as a recomposition key.
     *
     * Deliberately NOT a constructor property: `identity` used to default to
     * `root.identity()` but be stored as ordinary constructor state, so `copy(root =
     * changedRoot)` silently kept the OLD identity — Kotlin's generated `copy()` only
     * copies constructor properties as-is, it never re-evaluates a default expression.
     * Both native views key recomposition/rendering on `layout.identity`, so a stale
     * identity meant the platform view was never rebuilt after the tree changed.
     * Computing it here, in the class body, means it is recomputed on EVERY
     * construction — including every `copy()` — so it can never go stale.
     */
    public val identity: String = frozenRoot.identity()

    /** Validation report for this layout, computed at construction. */
    public val validation: AdLayoutValidationReport = AdLayoutValidator.validate(frozenRoot)
}

/**
 * Base type for all nodes in a native ad layout tree. Every node carries
 * an [AdModifier] for sizing, spacing, and appearance.
 */
@Immutable
public sealed interface AdNode {
    /** Styling and layout modifier for this node. */
    public val modifier: AdModifier
}

/**
 * A container node that holds child [AdNode]s in a specific arrangement.
 */
@Immutable
public sealed interface AdContainerNode : AdNode {
    /** Child nodes in this container. */
    public val children: List<AdNode>

    /** Horizontal row layout with alignment and spacing. */
    public data class Row(
        override val modifier: AdModifier,
        override val children: List<AdNode>,
        /** Horizontal alignment of children. */
        val horizontalAlignment: AdAlignment.Horizontal = AdAlignment.Horizontal.Start,
        /** Vertical alignment of children. */
        val verticalAlignment: AdAlignment.Vertical = AdAlignment.Vertical.CenterVertically,
        /** Spacing between children in density-independent pixels. */
        val spacingDp: Float = 0f
    ) : AdContainerNode

    /** Vertical column layout with alignment and spacing. */
    public data class Column(
        override val modifier: AdModifier,
        override val children: List<AdNode>,
        /** Horizontal alignment of children. */
        val horizontalAlignment: AdAlignment.Horizontal = AdAlignment.Horizontal.Start,
        /** Vertical arrangement of children. */
        val verticalAlignment: AdAlignment.Vertical = AdAlignment.Vertical.Top,
        /** Spacing between children in density-independent pixels. */
        val spacingDp: Float = 0f
    ) : AdContainerNode

    /** Arbitrary box layout with content alignment. */
    public data class Box(
        override val modifier: AdModifier,
        override val children: List<AdNode>,
        /** Alignment of the content within the box. */
        val contentAlignment: AdAlignment.Box = AdAlignment.Box.TopStart
    ) : AdContainerNode
}

/**
 * Copies [this] tree so no caller-owned collection remains reachable from it.
 *
 * Only containers hold children, so only they need copying; leaf nodes are already values.
 */
internal fun AdNode.deepCopy(): AdNode = when (this) {
    is AdContainerNode.Row -> copy(children = children.map { it.deepCopy() })
    is AdContainerNode.Column -> copy(children = children.map { it.deepCopy() })
    is AdContainerNode.Box -> copy(children = children.map { it.deepCopy() })
    else -> this
}

/**
 * An empty spacer node used to create gaps in a layout.
 */
@Immutable
public data class AdSpacer(
    override val modifier: AdModifier = AdModifier.empty
) : AdNode

/**
 * A node that displays static (non-ad) text. Useful for labels, headers,
 * or decorative text in native ad layouts.
 */
@Immutable
public data class AdStaticText(
    /** The text content to display. */
    val text: String,
    override val modifier: AdModifier = AdModifier.empty,
    /** Text styling (font, color, alignment). */
    val style: AdTextStyle = AdTextStyle.body,
    /** Maximum lines before truncation. Null = unlimited. */
    val maxLines: Int? = null
) : AdNode

/**
 * A node that renders a native ad asset (e.g., headline, icon, media).
 * Each subtype binds to a specific field from the native ad data.
 */
@Immutable
public sealed interface AdAssetNode : AdNode {
    /** Controls layout behavior when the asset is missing from the ad. */
    public val visibilityPolicy: AdVisibilityPolicy

    /** Binds to the native ad's headline text. Policy-required asset. */
    public data class Headline(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdTextStyle = AdTextStyle.title,
        val maxLines: Int? = 1,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Binds to the native ad's body text. */
    public data class Body(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdTextStyle = AdTextStyle.body,
        val maxLines: Int? = 2,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Binds to the native ad's call-to-action button. */
    public data class CallToAction(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdButtonStyle = AdButtonStyle.filled,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Binds to the native ad's icon image. */
    public data class Icon(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdImageStyle = AdImageStyle(),
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Binds to the native ad's main media asset (image or video). */
    public data class Media(
        override val modifier: AdModifier = AdModifier.fillMaxWidth().aspectRatio(16f / 9f),
        val style: AdImageStyle = AdImageStyle(contentScale = AdContentScale.Crop),
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.KeepSpace
    ) : AdAssetNode

    /** Binds to the native ad's advertiser / sponsor name. */
    public data class Advertiser(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdTextStyle = AdTextStyle.caption,
        val maxLines: Int? = 1,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Binds to the native ad's price string. */
    public data class Price(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdTextStyle = AdTextStyle.caption,
        val maxLines: Int? = 1,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Binds to the native ad's store/app name. */
    public data class Store(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdTextStyle = AdTextStyle.caption,
        val maxLines: Int? = 1,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Binds to the native ad's star rating. */
    public data class StarRating(
        override val modifier: AdModifier = AdModifier.empty,
        val style: AdTextStyle = AdTextStyle.caption,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.InvisibleWhenMissing
    ) : AdAssetNode

    /** Reserves space for the SDK-owned AdChoices privacy icon. Policy-relevant. */
    public data class AdChoices(
        override val modifier: AdModifier = AdModifier.empty,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.KeepSpace
    ) : AdAssetNode

    /** Displays the "Ad" attribution badge on the native ad. Policy-relevant. */
    public data class AdBadge(
        override val modifier: AdModifier = AdModifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .sizeIn(minWidth = 15.dp, minHeight = 15.dp)
            .border(1.dp, androidx.compose.ui.graphics.Color(0xFF777777), 3.dp),
        /** Badge text (default "Ad"). */
        val text: String = "Ad",
        /** Text style for the badge. */
        val style: AdTextStyle = AdTextStyle.badge,
        override val visibilityPolicy: AdVisibilityPolicy = AdVisibilityPolicy.KeepSpace
    ) : AdAssetNode
}

internal fun AdNode.identity(): String = when (this) {
    is AdContainerNode.Row -> "row(mod=$modifier,ha=$horizontalAlignment,va=$verticalAlignment,sp=$spacingDp,children=${children.joinToString("|") { it.identity() }})"
    is AdContainerNode.Column -> "column(mod=$modifier,ha=$horizontalAlignment,va=$verticalAlignment,sp=$spacingDp,children=${children.joinToString("|") { it.identity() }})"
    is AdContainerNode.Box -> "box(mod=$modifier,align=$contentAlignment,children=${children.joinToString("|") { it.identity() }})"
    is AdSpacer -> "spacer(mod=$modifier)"
    is AdStaticText -> "text(value=$text,mod=$modifier,style=$style,max=$maxLines)"
    is AdAssetNode.Headline -> "headline(mod=$modifier,style=$style,max=$maxLines,visibility=$visibilityPolicy)"
    is AdAssetNode.Body -> "body(mod=$modifier,style=$style,max=$maxLines,visibility=$visibilityPolicy)"
    is AdAssetNode.CallToAction -> "cta(mod=$modifier,style=$style,visibility=$visibilityPolicy)"
    is AdAssetNode.Icon -> "icon(mod=$modifier,style=$style,visibility=$visibilityPolicy)"
    is AdAssetNode.Media -> "media(mod=$modifier,style=$style,visibility=$visibilityPolicy)"
    is AdAssetNode.Advertiser -> "advertiser(mod=$modifier,style=$style,max=$maxLines,visibility=$visibilityPolicy)"
    is AdAssetNode.Price -> "price(mod=$modifier,style=$style,max=$maxLines,visibility=$visibilityPolicy)"
    is AdAssetNode.Store -> "store(mod=$modifier,style=$style,max=$maxLines,visibility=$visibilityPolicy)"
    is AdAssetNode.StarRating -> "rating(mod=$modifier,style=$style,visibility=$visibilityPolicy)"
    is AdAssetNode.AdChoices -> "adchoices(mod=$modifier,visibility=$visibilityPolicy)"
    is AdAssetNode.AdBadge -> "adbadge(mod=$modifier,text=$text,style=$style,visibility=$visibilityPolicy)"
}
