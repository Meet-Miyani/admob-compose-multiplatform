@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.avinya.ads.nativead.rendering

import GoogleMobileAds.GADAdChoicesView
import GoogleMobileAds.GADMediaView
import GoogleMobileAds.GADNativeAd
import GoogleMobileAds.GADNativeAdView
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdClip
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdContentScale
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdLayoutSize
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdNode
import dev.avinya.ads.nativead.layout.AdSpacer
import dev.avinya.ads.nativead.layout.AdStaticText
import dev.avinya.ads.nativead.layout.AdTextAlign
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.AdDisplay
import dev.avinya.ads.nativead.layout.AdInsets
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import kotlinx.cinterop.cValue
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIColor
import platform.UIKit.UIFont
import platform.UIKit.UIFontDescriptorSystemDesignMonospaced
import platform.UIKit.UIFontDescriptorSystemDesignSerif
import platform.UIKit.UIFontWeightBold
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIImageView
import platform.UIKit.UILabel
import platform.UIKit.UIStackView
import platform.UIKit.UIView
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIEdgeInsets
import platform.UIKit.UILayoutConstraintAxis
import platform.UIKit.NSTextAlignment
import platform.UIKit.setAccessibilityLabel

/**
 * Builds the `UIView` tree for an [dev.avinya.ads.nativead.layout.AdLayout].
 *
 * ## Modifier layering
 *
 * Every node is built as the same four-layer sandwich, outermost first, because that is the order
 * Compose and the Android renderer resolve an [AdModifier] in:
 *
 * ```
 * margin  →  size + decoration (background, border, corner, clip)  →  padding  →  content
 * ```
 *
 * The layering is the fix for a whole class of defect rather than any single one. `applyModifier`
 * used to put every property on one view and express padding as `UIView.layoutMargins`, which
 * nothing but a `UIStackView` reads — so padding on a label, an image, a media view or a `Box` was
 * accepted by the DSL, honoured by Android and the preview, and silently dropped on iOS. Insetting
 * the *content* inside a decorated container instead makes padding mean the same thing on every
 * node type, and puts the background behind the padding band exactly as Android's
 * `setPadding` + background drawable does.
 *
 * Stacks are the one exception: `UIStackView` implements padding natively through
 * `layoutMarginsRelativeArrangement`, so they inset themselves and skip the extra container.
 */
internal class IosNativeAdRenderer(
    private val nativeAd: GADNativeAd,
    private val nativeView: GADNativeAdView,
    private val density: Density,
    private val composeFontResolver: IosComposeFontResolver = DefaultIosComposeFontResolver,
) {
    fun render(node: AdNode): UIView = when (node) {
        is AdContainerNode.Row -> renderStack(
            children = node.children,
            isHorizontal = true,
            spacing = node.spacingDp,
            modifier = node.modifier,
            crossAxisPlacement = rowCrossAxisPlacement(node.verticalAlignment),
            mainAxisPlacement = rowMainAxisPlacement(node.horizontalAlignment),
        )
        is AdContainerNode.Column -> renderStack(
            children = node.children,
            isHorizontal = false,
            spacing = node.spacingDp,
            modifier = node.modifier,
            crossAxisPlacement = columnCrossAxisPlacement(node.horizontalAlignment),
            mainAxisPlacement = columnMainAxisPlacement(node.verticalAlignment),
        )
        is AdContainerNode.Box -> decorate(renderBox(node), node.modifier)
        is AdSpacer -> decorate(makeSpacer(), node.modifier)
        is AdStaticText -> decorate(makeLabel(node.text, node.style, node.maxLines), node.modifier)
        is AdAssetNode.Headline -> textAsset(nativeAd.headline, node.style, node.maxLines, node) { nativeView.headlineView = it }
        is AdAssetNode.Body -> textAsset(nativeAd.body, node.style, node.maxLines, node) { nativeView.bodyView = it }
        is AdAssetNode.Advertiser -> textAsset(nativeAd.advertiser, node.style, node.maxLines, node) { nativeView.advertiserView = it }
        is AdAssetNode.Price -> textAsset(nativeAd.price, node.style, node.maxLines, node) { nativeView.priceView = it }
        is AdAssetNode.Store -> textAsset(nativeAd.store, node.style, node.maxLines, node) { nativeView.storeView = it }
        is AdAssetNode.StarRating -> textAsset(
            value = resolveStarRatingText(nativeAd.starRating?.doubleValue),
            style = node.style,
            maxLines = 1,
            node = node,
            register = { nativeView.starRatingView = it },
        )
        is AdAssetNode.Icon -> renderIcon(node)
        is AdAssetNode.Media -> renderMedia(node)
        is AdAssetNode.CallToAction -> renderCallToAction(node)
        is AdAssetNode.AdBadge -> decorate(makeBadgeLabel(node.text, node.style), node.modifier)
        is AdAssetNode.AdChoices -> renderAdChoices(node)
    }

    // ---------------------------------------------------------------------------------------
    // Layering
    // ---------------------------------------------------------------------------------------

    /**
     * Wraps [content] in its padding container when it needs one, then applies the decoration and
     * sizing that [modifier] asks for to whichever view ends up outermost.
     *
     * Size lands on the outer view deliberately: `AdModifier.size(48.dp).padding(4.dp)` describes a
     * 48dp box with 40dp of content, which is how both Compose's `Modifier` chain and Android's
     * `LayoutParams` + `setPadding` read it.
     *
     * @param contentHandlesPadding set by stacks, which inset themselves natively.
     */
    private fun decorate(
        content: UIView,
        modifier: AdModifier,
        contentHandlesPadding: Boolean = false,
        backgroundArgb: Long? = modifier.backgroundArgb,
    ): UIView {
        content.setTranslatesAutoresizingMaskIntoConstraints(false)
        val box = if (contentHandlesPadding || !padNonZero(modifier.padding)) {
            content
        } else {
            insetContainer(content, modifier.padding)
        }
        applyDecoration(box, modifier, backgroundArgb)
        // A `CALayer` shadow is drawn outside the layer's bounds, so the `clipsToBounds` that any
        // corner radius or clip requires erases it. One view cannot both clip its content and cast
        // a shadow; the fix is the standard two-view split, and it is only paid for when the node
        // actually asks for both.
        val outer = when {
            modifier.elevationDp <= 0f -> box
            box.clipsToBounds -> shadowContainer(box, modifier, backgroundArgb)
            else -> box.also { applyShadow(it, modifier.elevationDp) }
        }
        applySizing(outer, modifier)
        return outer
    }

    /**
     * Wraps a clipping [box] in a container that carries its shadow.
     *
     * The container repeats the box's own background and corner radius rather than setting a
     * `shadowPath`: a path would have to be recomputed on every layout pass, whereas an opaque
     * rounded layer gives `CALayer` the silhouette it needs for free. The box is pinned over it, so
     * the repeated fill is never visible.
     */
    private fun shadowContainer(box: UIView, modifier: AdModifier, backgroundArgb: Long?): UIView {
        val container = UIView()
        container.setTranslatesAutoresizingMaskIntoConstraints(false)
        container.backgroundColor = backgroundArgb?.let(::color) ?: UIColor.clearColor
        container.layer.cornerRadius = box.layer.cornerRadius
        container.addSubview(box)
        NSLayoutConstraint.activateConstraints(
            listOf(
                box.leadingAnchor.constraintEqualToAnchor(container.leadingAnchor),
                box.trailingAnchor.constraintEqualToAnchor(container.trailingAnchor),
                box.topAnchor.constraintEqualToAnchor(container.topAnchor),
                box.bottomAnchor.constraintEqualToAnchor(container.bottomAnchor),
            ),
        )
        applyShadow(container, modifier.elevationDp)
        return container
    }

    private fun applyShadow(view: UIView, elevationDp: Float) {
        view.layer.masksToBounds = false
        view.layer.shadowOpacity = 0.18f
        view.layer.shadowRadius = elevationDp.toDouble()
        view.layer.shadowOffset = CGSizeMake(0.0, (elevationDp / 2f).toDouble())
    }

    /** A container holding [child] inset by [padding] on every side. */
    private fun insetContainer(child: UIView, padding: AdInsets): UIView {
        val container = UIView()
        container.setTranslatesAutoresizingMaskIntoConstraints(false)
        // Cleared on the child here rather than relying on the caller. UIKit otherwise synthesises
        // autoresizing constraints from the child's frame — which is `CGRectZero` for a view that
        // has never been laid out — and those fight the insets below, collapsing the child to
        // nothing. `decorate` happens to clear it first; `renderCallToAction` builds its container
        // directly and did not, which flattened the call-to-action into a dot on device.
        child.setTranslatesAutoresizingMaskIntoConstraints(false)
        container.addSubview(child)
        NSLayoutConstraint.activateConstraints(
            listOf(
                child.leadingAnchor.constraintEqualToAnchor(container.leadingAnchor, constant = padding.startDp.toDouble()),
                child.trailingAnchor.constraintEqualToAnchor(container.trailingAnchor, constant = -padding.endDp.toDouble()),
                child.topAnchor.constraintEqualToAnchor(container.topAnchor, constant = padding.topDp.toDouble()),
                child.bottomAnchor.constraintEqualToAnchor(container.bottomAnchor, constant = -padding.bottomDp.toDouble()),
            ),
        )
        return container
    }

    /**
     * Everything [modifier] paints: visibility, colour, border, corner, clip, shadow, offset.
     *
     * [backgroundArgb] is passed in rather than read off [modifier] so an asset can supply the
     * colour from its `AdImageStyle`/`AdButtonStyle` instead — the same signature the Android
     * renderer's `applyViewStyle` takes. Reading it from the modifier unconditionally meant an
     * icon or media node that took its background from its *style* had it painted and then
     * immediately overwritten with `clearColor` here.
     */
    private fun applyDecoration(view: UIView, modifier: AdModifier, backgroundArgb: Long? = modifier.backgroundArgb) {
        view.setTranslatesAutoresizingMaskIntoConstraints(false)
        view.hidden = modifier.display == AdDisplay.Gone
        view.alpha = if (modifier.display == AdDisplay.Invisible) 0.0 else modifier.alpha.toDouble()
        view.transform = CGAffineTransformMakeTranslation(modifier.offsetXDp.toDouble(), modifier.offsetYDp.toDouble())
        view.layer.zPosition = modifier.zIndex.toDouble()
        if (backgroundArgb != null) {
            view.backgroundColor = color(backgroundArgb)
            view.opaque = isOpaqueArgb(backgroundArgb)
        } else {
            view.backgroundColor = UIColor.clearColor
            view.opaque = false
        }
        modifier.borderColorArgb?.let { view.layer.borderColor = color(it).CGColor }
        modifier.borderWidthDp?.let { view.layer.borderWidth = it.toDouble() }
        val cornerRadius = modifier.cornerRadiusDp ?: modifier.borderRadiusDp
        cornerRadius?.let {
            view.layer.cornerRadius = it.toDouble()
            view.clipsToBounds = true
        }
        applyClip(modifier.clipShape, view)
    }

    /** Everything [modifier] constrains: fixed sizes, bounds, aspect ratio. */
    private fun applySizing(view: UIView, modifier: AdModifier) {
        applySize(modifier.width, axis = UILayoutConstraintAxisHorizontal, view)
        applySize(modifier.height, axis = UILayoutConstraintAxisVertical, view)
        modifier.minWidthDp?.let { view.widthAnchor.constraintGreaterThanOrEqualToConstant(it.toDouble()).active = true }
        modifier.minHeightDp?.let { view.heightAnchor.constraintGreaterThanOrEqualToConstant(it.toDouble()).active = true }
        modifier.maxWidthDp?.let { view.widthAnchor.constraintLessThanOrEqualToConstant(it.toDouble()).active = true }
        modifier.maxHeightDp?.let { view.heightAnchor.constraintLessThanOrEqualToConstant(it.toDouble()).active = true }
        modifier.aspectRatio?.let { ratio ->
            if (ratio > 0f) {
                view.heightAnchor.constraintEqualToAnchor(view.widthAnchor, multiplier = (1.0 / ratio.toDouble())).active = true
            }
        }
    }

    private fun applyClip(clip: AdClip, view: UIView) {
        when (clip) {
            AdClip.Bounds -> view.clipsToBounds = true
            is AdClip.RoundedCorner -> {
                view.layer.cornerRadius = clip.radiusDp.toDouble()
                view.clipsToBounds = true
            }
            AdClip.Circle -> {
                view.layer.cornerRadius = CIRCLE_CLIP_RADIUS
                view.clipsToBounds = true
            }
            AdClip.None -> Unit
        }
    }

    /**
     * `AdLayoutSize.Match` is deliberately not a constraint here.
     *
     * A stack sizes its children to the cross axis through `UIStackViewAlignmentFill`, and
     * [renderBox] pins a `Match` child to both of the box's edges itself. Emitting a constraint
     * from here as well would duplicate those and over-constrain the layout.
     */
    private fun applySize(size: AdLayoutSize, axis: UILayoutConstraintAxis, view: UIView) {
        if (size !is AdLayoutSize.Fixed) return
        val constant = size.dp.toDouble()
        if (axis == UILayoutConstraintAxisHorizontal) {
            view.widthAnchor.constraintEqualToConstant(constant).active = true
        } else {
            view.heightAnchor.constraintEqualToConstant(constant).active = true
        }
    }

    private fun wrapForMargin(childView: UIView, modifier: AdModifier): UIView {
        val margin = modifier.margin
        if (!padNonZero(margin)) return childView
        val wrapper = UIView()
        wrapper.setTranslatesAutoresizingMaskIntoConstraints(false)
        wrapper.addSubview(childView)
        NSLayoutConstraint.activateConstraints(
            listOf(
                childView.leadingAnchor.constraintEqualToAnchor(wrapper.leadingAnchor, constant = margin.startDp.toDouble()),
                childView.trailingAnchor.constraintEqualToAnchor(wrapper.trailingAnchor, constant = -margin.endDp.toDouble()),
                childView.topAnchor.constraintEqualToAnchor(wrapper.topAnchor, constant = margin.topDp.toDouble()),
                childView.bottomAnchor.constraintEqualToAnchor(wrapper.bottomAnchor, constant = -margin.bottomDp.toDouble()),
            ),
        )
        return wrapper
    }

    // ---------------------------------------------------------------------------------------
    // Containers
    // ---------------------------------------------------------------------------------------

    private fun renderStack(
        children: List<AdNode>,
        isHorizontal: Boolean,
        spacing: Float,
        modifier: AdModifier,
        crossAxisPlacement: AdAxisPlacement,
        mainAxisPlacement: AdAxisPlacement,
    ): UIView {
        val axis = if (isHorizontal) UILayoutConstraintAxisHorizontal else UILayoutConstraintAxisVertical
        val stack = UIStackView()
        stack.axis = axis
        stack.spacing = spacing.toDouble()
        // A `Row` places its children at `verticalAlignment` and lets each keep its own height;
        // Compose has no "stretch" cross-axis alignment at all. `UIStackViewAlignmentFill` is
        // therefore wrong for a row, and visibly so — it stretched the call-to-action in
        // `AdTemplates.compact` into a full-height slab where Compose and Android both draw a
        // content-height button centred in the row.
        //
        // A `Column` keeps `Fill`. Its cross axis is horizontal, and a `UILabel` given only its
        // intrinsic width has nothing to wrap against, so narrowing columns to content would stop
        // multi-line headlines and bodies wrapping — a far worse regression than a tall button.
        // Compose gets the same effect from the constraints it passes down rather than from
        // stretching, which Auto Layout has no equivalent of.
        stack.alignment = if (isHorizontal) {
            when (crossAxisPlacement) {
                AdAxisPlacement.Start -> platform.UIKit.UIStackViewAlignmentTop
                AdAxisPlacement.Center -> platform.UIKit.UIStackViewAlignmentCenter
                AdAxisPlacement.End -> platform.UIKit.UIStackViewAlignmentBottom
            }
        } else {
            platform.UIKit.UIStackViewAlignmentFill
        }
        stack.distribution = platform.UIKit.UIStackViewDistributionFill
        stack.setTranslatesAutoresizingMaskIntoConstraints(false)
        if (padNonZero(modifier.padding)) {
            val p = modifier.padding
            stack.layoutMargins = cValue<UIEdgeInsets> {
                top = p.topDp.toDouble()
                left = p.startDp.toDouble()
                bottom = p.bottomDp.toDouble()
                right = p.endDp.toDouble()
            }
            stack.layoutMarginsRelativeArrangement = true
        }

        // `AdDisplay.Gone` means "not part of the layout", so the node is never built. Hiding it
        // after the fact is not equivalent: `UIStackView` skips a hidden *arranged subview*, but
        // the arranged subview is often a margin or alignment wrapper that is not itself hidden, so
        // the space survived. Dropping the node here also matches the other two renderers, which
        // never produce it at all — Android's `LinearLayout`/`FrameLayout` exclude `View.GONE`
        // children from measurement, and `AdLayoutPreview` returns before composing them.
        //
        // Every list below is derived from this one so the indices `resolveWeightRatios` returns
        // still line up with `arranged`.
        val children = children.filterNot { it.modifier.display == AdDisplay.Gone }

        val hasClaimingChild = !stackHasMainAxisSlack(children, isHorizontal)
        val arranged = ArrayList<UIView>(children.size)
        for (child in children) {
            val childModifier = child.modifier
            val rendered = render(child)
            var placed: UIView = wrapForMargin(rendered, childModifier)
            // Only columns need this now. Under `Fill` a child that pins its own cross-axis size
            // pins the stack's — a 44dp icon in a column would narrow the whole column to 44dp —
            // so those children get a stretchable container and sit inside it at the requested
            // placement. A row no longer uses `Fill`, so its children already keep their own
            // heights and the stack already places them; wrapping there would be redundant.
            if (!isHorizontal && constrainsColumnCrossAxis(childModifier)) {
                placed = wrapForCrossAxisAlignment(placed, isHorizontal = false, placement = crossAxisPlacement)
            }
            // A node hidden by its `AdVisibilityPolicy` because the creative did not supply the
            // asset has to collapse, and `UIStackView` only collapses an arranged subview that is
            // itself hidden — which the wrappers above are not.
            if (rendered.hidden) placed.hidden = true
            stack.addArrangedSubview(placed)
            arranged += placed
            // `fillMaxHeight()` on a row child asked to match the row, which `Fill` used to grant
            // implicitly and a placed alignment does not. It has to match the *content* height:
            // with `layoutMarginsRelativeArrangement` the stack pins its arranged subviews to the
            // layout margins guide, so matching the stack's full height instead would exceed the
            // padding and leave Auto Layout to break one of the two constraints at runtime.
            if (isHorizontal && childModifier.height == AdLayoutSize.Match) {
                val heightSource =
                    if (stack.layoutMarginsRelativeArrangement) stack.layoutMarginsGuide.heightAnchor
                    else stack.heightAnchor
                placed.heightAnchor.constraintEqualToAnchor(heightSource).active = true
            }
            if (claimsMainAxis(childModifier, isHorizontal)) {
                // Hugging and compression resistance only govern a view's *intrinsic content
                // size*, and the margin/alignment wrappers are bare `UIView`s that have none — set
                // on a wrapper alone these are inert and the child keeps hugging its own content
                // instead of taking the free space its weight asks for. Set on every layer from
                // the rendered view outward so the priority reaches something intrinsic.
                //
                // The hugging priority has to go *below* the default, not to it: a weighted child
                // competes with siblings whose own hugging is already `UILayoutPriorityDefaultLow`,
                // so setting the same 250 here left `UIStackView` free to stretch whichever view it
                // liked and made the weight a no-op.
                for (view in setOf(rendered, placed)) {
                    view.setContentHuggingPriority(WEIGHTED_CHILD_HUGGING_PRIORITY, axis)
                    view.setContentCompressionResistancePriority(UILayoutPriorityDefaultLow, axis)
                }
            } else if (hasClaimingChild) {
                // The other half of the same contract, and it is not optional: dropping the
                // weighted child's hugging tells the stack who *may* grow, but nothing yet says the
                // rest must not shrink. `UIStackView` was free to satisfy the weighted child by
                // compressing a sibling instead of by using the slack, which crushed the
                // call-to-action in `AdTemplates.compact` into a one-glyph-per-line column.
                //
                // Compose has no such ambiguity: unweighted children measure at their own intrinsic
                // size and the weighted ones divide whatever is left. Pinning the siblings just
                // below `required` reproduces that, while still leaving Auto Layout a constraint it
                // can break rather than an unsatisfiable layout when the stack is genuinely too
                // narrow for them.
                for (view in setOf(rendered, placed)) {
                    view.setContentHuggingPriority(UNWEIGHTED_SIBLING_PRIORITY, axis)
                    view.setContentCompressionResistancePriority(UNWEIGHTED_SIBLING_PRIORITY, axis)
                }
            }
        }

        // Compose divides the free space between weighted children in proportion to their weights;
        // priorities alone cannot express a ratio, so state it as an explicit relation.
        for (ratio in resolveWeightRatios(children)) {
            val child = arranged[ratio.childIndex]
            val reference = arranged[ratio.referenceIndex]
            val constraint = if (isHorizontal) {
                child.widthAnchor.constraintEqualToAnchor(reference.widthAnchor, multiplier = ratio.multiplier)
            } else {
                child.heightAnchor.constraintEqualToAnchor(reference.heightAnchor, multiplier = ratio.multiplier)
            }
            constraint.active = true
        }

        // `UIStackView` has no main-axis arrangement: under `distribution = Fill` any slack is
        // handed to whichever child hugs least, whereas Compose leaves it where the arrangement's
        // alignment says. Letting the stack hug its content inside a container that does fill
        // reproduces that — and is skipped entirely when a weighted child has already claimed the
        // slack, since there is then nothing left to arrange.
        val outer = if (stackHasMainAxisSlack(children, isHorizontal)) {
            wrapForMainAxisAlignment(stack, isHorizontal, mainAxisPlacement)
        } else {
            stack
        }
        return decorate(outer, modifier, contentHandlesPadding = true)
    }

    /**
     * Wraps [childView] in a container the stack is free to stretch, pinning the child along the
     * main axis and placing it on the cross axis per [placement].
     *
     * The wrapper asserts no cross-axis size of its own beyond a low-priority hug of the child, so
     * the stack's cross-axis size is driven by its unconstrained children — and falls back to the
     * child's own size when every child is constrained.
     */
    private fun wrapForCrossAxisAlignment(
        childView: UIView,
        isHorizontal: Boolean,
        placement: AdAxisPlacement,
    ): UIView {
        val wrapper = UIView()
        wrapper.setTranslatesAutoresizingMaskIntoConstraints(false)
        wrapper.addSubview(childView)

        val (mainStart, mainEnd) = if (isHorizontal) {
            childView.leadingAnchor.constraintEqualToAnchor(wrapper.leadingAnchor) to
                childView.trailingAnchor.constraintEqualToAnchor(wrapper.trailingAnchor)
        } else {
            childView.topAnchor.constraintEqualToAnchor(wrapper.topAnchor) to
                childView.bottomAnchor.constraintEqualToAnchor(wrapper.bottomAnchor)
        }
        val (crossStart, crossEnd) = if (isHorizontal) {
            childView.topAnchor.constraintGreaterThanOrEqualToAnchor(wrapper.topAnchor) to
                childView.bottomAnchor.constraintLessThanOrEqualToAnchor(wrapper.bottomAnchor)
        } else {
            childView.leadingAnchor.constraintGreaterThanOrEqualToAnchor(wrapper.leadingAnchor) to
                childView.trailingAnchor.constraintLessThanOrEqualToAnchor(wrapper.trailingAnchor)
        }
        val place = when (placement) {
            AdAxisPlacement.Start ->
                if (isHorizontal) childView.topAnchor.constraintEqualToAnchor(wrapper.topAnchor)
                else childView.leadingAnchor.constraintEqualToAnchor(wrapper.leadingAnchor)
            AdAxisPlacement.Center ->
                if (isHorizontal) childView.centerYAnchor.constraintEqualToAnchor(wrapper.centerYAnchor)
                else childView.centerXAnchor.constraintEqualToAnchor(wrapper.centerXAnchor)
            AdAxisPlacement.End ->
                if (isHorizontal) childView.bottomAnchor.constraintEqualToAnchor(wrapper.bottomAnchor)
                else childView.trailingAnchor.constraintEqualToAnchor(wrapper.trailingAnchor)
        }
        // Keeps a stack whose children are *all* cross-axis constrained from resolving to zero.
        val hug = if (isHorizontal) {
            childView.heightAnchor.constraintEqualToAnchor(wrapper.heightAnchor)
        } else {
            childView.widthAnchor.constraintEqualToAnchor(wrapper.widthAnchor)
        }.also { it.priority = UILayoutPriorityDefaultLow }

        NSLayoutConstraint.activateConstraints(
            listOf(mainStart, mainEnd, crossStart, crossEnd, place, hug),
        )
        return wrapper
    }

    /**
     * Wraps a content-sized [stack] in a container that fills the main axis, placing the stack
     * within it per [placement] — the `UIStackView` equivalent of Compose's `Arrangement` alignment.
     */
    private fun wrapForMainAxisAlignment(
        stack: UIView,
        isHorizontal: Boolean,
        placement: AdAxisPlacement,
    ): UIView {
        val wrapper = UIView()
        wrapper.setTranslatesAutoresizingMaskIntoConstraints(false)
        wrapper.addSubview(stack)

        val constraints = mutableListOf<NSLayoutConstraint>()
        if (isHorizontal) {
            constraints += stack.topAnchor.constraintEqualToAnchor(wrapper.topAnchor)
            constraints += stack.bottomAnchor.constraintEqualToAnchor(wrapper.bottomAnchor)
            constraints += stack.leadingAnchor.constraintGreaterThanOrEqualToAnchor(wrapper.leadingAnchor)
            constraints += stack.trailingAnchor.constraintLessThanOrEqualToAnchor(wrapper.trailingAnchor)
            constraints += when (placement) {
                AdAxisPlacement.Start -> stack.leadingAnchor.constraintEqualToAnchor(wrapper.leadingAnchor)
                AdAxisPlacement.Center -> stack.centerXAnchor.constraintEqualToAnchor(wrapper.centerXAnchor)
                AdAxisPlacement.End -> stack.trailingAnchor.constraintEqualToAnchor(wrapper.trailingAnchor)
            }
            constraints += shrinkToFit(wrapper, isWidth = true)
        } else {
            constraints += stack.leadingAnchor.constraintEqualToAnchor(wrapper.leadingAnchor)
            constraints += stack.trailingAnchor.constraintEqualToAnchor(wrapper.trailingAnchor)
            constraints += stack.topAnchor.constraintGreaterThanOrEqualToAnchor(wrapper.topAnchor)
            constraints += stack.bottomAnchor.constraintLessThanOrEqualToAnchor(wrapper.bottomAnchor)
            constraints += when (placement) {
                AdAxisPlacement.Start -> stack.topAnchor.constraintEqualToAnchor(wrapper.topAnchor)
                AdAxisPlacement.Center -> stack.centerYAnchor.constraintEqualToAnchor(wrapper.centerYAnchor)
                AdAxisPlacement.End -> stack.bottomAnchor.constraintEqualToAnchor(wrapper.bottomAnchor)
            }
            constraints += shrinkToFit(wrapper, isWidth = false)
        }
        NSLayoutConstraint.activateConstraints(constraints)
        return wrapper
    }

    /**
     * Places every child of a `Box` at [AdContainerNode.Box.contentAlignment], sizing the box to
     * its largest child.
     *
     * Both halves were previously hard-coded: every child was pinned to the box's leading and top
     * anchors whatever the alignment said, and the box took its height from the first `Media` child
     * — or, failing that, from whichever child happened to be declared first. A `Box` that led with
     * anything other than its tallest child therefore clipped the rest. Required inequalities plus
     * a [shrinkToFit] preference express "as tall as the tallest child" directly, with no guess
     * about which child that is.
     */
    private fun renderBox(node: AdContainerNode.Box): UIView {
        val box = UIView()
        box.setTranslatesAutoresizingMaskIntoConstraints(false)
        val horizontal = node.contentAlignment.horizontalPlacement()
        val vertical = node.contentAlignment.verticalPlacement()

        val constraints = mutableListOf<NSLayoutConstraint>()
        // As in `renderStack`: a `Gone` node is not part of the layout. Hiding it would not be
        // enough here either — `UIView.hidden` suppresses drawing but leaves every constraint
        // active, so a hidden child would go on sizing the box.
        for (child in node.children.filterNot { it.modifier.display == AdDisplay.Gone }) {
            val childModifier = child.modifier
            val rendered = render(child)
            // `UIView.hidden` suppresses drawing but leaves every constraint active, so a hidden
            // child with a size of its own would go on sizing the box. A stack can express this —
            // `UIStackView` drops hidden arranged subviews — but a `Box` pins its children itself,
            // so the node is left out of the box entirely. Its asset outlet is `weak` and simply
            // becomes nil, which is the truth: the asset is not being displayed.
            if (rendered.hidden) continue
            val childView = wrapForMargin(rendered, childModifier)
            box.addSubview(childView)

            constraints += childView.leadingAnchor.constraintGreaterThanOrEqualToAnchor(box.leadingAnchor)
            constraints += childView.trailingAnchor.constraintLessThanOrEqualToAnchor(box.trailingAnchor)
            constraints += childView.topAnchor.constraintGreaterThanOrEqualToAnchor(box.topAnchor)
            constraints += childView.bottomAnchor.constraintLessThanOrEqualToAnchor(box.bottomAnchor)

            // A `Match`-sized child spans the box on that axis, which supersedes the alignment —
            // the same precedence `fillMaxWidth()` has over `contentAlignment` in Compose.
            constraints += if (childModifier.width == AdLayoutSize.Match) {
                listOf(
                    childView.leadingAnchor.constraintEqualToAnchor(box.leadingAnchor),
                    childView.trailingAnchor.constraintEqualToAnchor(box.trailingAnchor),
                )
            } else {
                listOf(
                    when (horizontal) {
                        AdAxisPlacement.Start -> childView.leadingAnchor.constraintEqualToAnchor(box.leadingAnchor)
                        AdAxisPlacement.Center -> childView.centerXAnchor.constraintEqualToAnchor(box.centerXAnchor)
                        AdAxisPlacement.End -> childView.trailingAnchor.constraintEqualToAnchor(box.trailingAnchor)
                    },
                )
            }
            constraints += if (childModifier.height == AdLayoutSize.Match) {
                listOf(
                    childView.topAnchor.constraintEqualToAnchor(box.topAnchor),
                    childView.bottomAnchor.constraintEqualToAnchor(box.bottomAnchor),
                )
            } else {
                listOf(
                    when (vertical) {
                        AdAxisPlacement.Start -> childView.topAnchor.constraintEqualToAnchor(box.topAnchor)
                        AdAxisPlacement.Center -> childView.centerYAnchor.constraintEqualToAnchor(box.centerYAnchor)
                        AdAxisPlacement.End -> childView.bottomAnchor.constraintEqualToAnchor(box.bottomAnchor)
                    },
                )
            }
        }
        constraints += shrinkToFit(box, isWidth = true)
        constraints += shrinkToFit(box, isWidth = false)
        NSLayoutConstraint.activateConstraints(constraints)
        return box
    }

    /**
     * A near-zero-priority "collapse to nothing" preference.
     *
     * Paired with required `<=` containment constraints this is the Auto Layout idiom for "size to
     * the largest child": the container shrinks until the first child stops it. The priority sits
     * below `UILayoutPriorityDefaultLow` so a child's own content hugging always wins.
     */
    private fun shrinkToFit(view: UIView, isWidth: Boolean): NSLayoutConstraint =
        (if (isWidth) view.widthAnchor else view.heightAnchor)
            .constraintEqualToConstant(0.0)
            .also { it.priority = UILayoutPriorityFittingSizeLevel }

    // ---------------------------------------------------------------------------------------
    // Assets
    // ---------------------------------------------------------------------------------------

    /**
     * Builds, registers and visibility-policies a text asset.
     *
     * Registration happens whether or not the creative supplied the value, matching the Android
     * renderer. iOS previously substituted a bare `UIView` for a missing asset and skipped
     * registration entirely, so the two platforms disagreed on both what was drawn and what the
     * SDK was told about.
     *
     * The *outer* view is registered rather than the label, because that is the view carrying the
     * node's padding — registering the label would hand the SDK a smaller touch target on iOS than
     * on Android, where the padded `TextView` is itself the registered view.
     */
    private fun textAsset(
        value: String?,
        style: AdTextStyle,
        maxLines: Int?,
        node: AdAssetNode,
        register: (UIView) -> Unit,
    ): UIView {
        val label = makeLabel(value.orEmpty(), style, maxLines)
        val view = decorate(label, node.modifier)
        register(view)
        applyMissingVisibility(view, value?.takeIf { it.isNotBlank() }, node.visibilityPolicy)
        return view
    }

    private fun renderIcon(node: AdAssetNode.Icon): UIView {
        val image = nativeAd.icon?.image
        val imageView = UIImageView(image)
        imageView.contentMode = contentMode(node.style.contentScale)
        imageView.clipsToBounds = true
        val view = decorate(
            content = imageView,
            modifier = node.modifier,
            backgroundArgb = resolveNativeAdBackgroundArgb(node.modifier, node.style.backgroundArgb),
        )
        nativeView.iconView = view
        applyMissingVisibility(view, image, node.visibilityPolicy)
        return view
    }

    /**
     * `mediaView` is typed `GADMediaView` on `GADNativeAdView`, so the media view itself is
     * registered even when a padding container sits above it.
     */
    private fun renderMedia(node: AdAssetNode.Media): UIView {
        val mediaView = GADMediaView()
        mediaView.mediaContent = nativeAd.mediaContent
        // "The GADMediaView class respects the UIView contentMode property when displaying
        // images" — so an unset content mode left every image creative at the `UIView` default of
        // `scaleToFill`/aspect-fit rather than the scale the node asked for. Android has always
        // mapped this onto `MediaView.imageScaleType`, so an `AdContentScale.Crop` media asset
        // filled and cropped on Android and sat pillarboxed on iOS.
        mediaView.contentMode = contentMode(node.style.contentScale)
        mediaView.clipsToBounds = true
        nativeView.mediaView = mediaView
        return decorate(
            content = mediaView,
            modifier = node.modifier,
            backgroundArgb = resolveNativeAdBackgroundArgb(node.modifier, node.style.backgroundArgb),
        )
    }

    /**
     * `adChoicesView` is typed `GADAdChoicesView`, and the header requires it be set *before*
     * `-setNativeAd:` for the overlay to render inside it — which it is, since registration happens
     * later, from `IosNativeAdHostView.layoutSubviews`.
     */
    private fun renderAdChoices(node: AdAssetNode.AdChoices): UIView {
        val adChoicesView = GADAdChoicesView()
        nativeView.adChoicesView = adChoicesView
        return decorate(adChoicesView, node.modifier)
    }

    /**
     * A label inside a painted container, deliberately not a `UIButton`.
     *
     * `UIButton` brought its own metrics: `UIButtonConfiguration` supplies content insets and a
     * minimum size of its own, and its `cornerStyle` defaults to `Dynamic`, which rescales the
     * corner radius with the user's Dynamic Type setting. None of that is expressible in the layout
     * DSL, so the call-to-action's geometry was set by UIKit on one platform and by the OEM's
     * `buttonStyle` on the other. Building it from a label makes the size exactly text plus the
     * resolved insets on both platforms, from the same numbers.
     *
     * Interaction stays off: the SDK tracks the click on `GADNativeAdView` itself, and a container
     * that swallowed touches would break that. `UIView` defaults to interactive, so it is disabled
     * explicitly — a `UIButton` was non-interactive only because the old code said so.
     */
    private fun renderCallToAction(node: AdAssetNode.CallToAction): UIView {
        val provided = nativeAd.callToAction
        val label = makeLabel(
            text = resolveCallToActionText(provided.orEmpty(), node.style.textCase),
            style = node.style.textStyle,
            maxLines = 1,
        )
        label.textAlignment = NSTextAlignmentCenter

        val container = insetContainer(label, resolveCallToActionContentInsets(node.modifier, node.style))
        container.userInteractionEnabled = false
        // Set before `decorate`, which overwrites it only when the modifier carries a radius of its
        // own — and in that case the resolved value is already the modifier's, so the two agree.
        container.layer.cornerRadius = resolveCallToActionCornerRadiusDp(node.modifier, node.style).toDouble()
        container.clipsToBounds = true

        val view = decorate(
            content = container,
            // Padding is already spent as the container's insets; passing it again would inset twice.
            modifier = node.modifier.withoutPadding(),
            backgroundArgb = resolveNativeAdBackgroundArgb(node.modifier, node.style.backgroundArgb)
                ?: TRANSPARENT_ARGB,
        )
        nativeView.callToActionView = view
        applyMissingVisibility(view, provided?.takeIf { it.isNotBlank() }, node.visibilityPolicy)
        return view
    }

    // ---------------------------------------------------------------------------------------
    // Leaves
    // ---------------------------------------------------------------------------------------

    private fun makeSpacer(): UIView {
        val spacer = UIView()
        spacer.setTranslatesAutoresizingMaskIntoConstraints(false)
        // A bare `UIView` has no intrinsic content size, so without this a spacer's size is
        // undefined rather than zero. Weighted spacers override it by claiming slack.
        NSLayoutConstraint.activateConstraints(
            listOf(shrinkToFit(spacer, isWidth = true), shrinkToFit(spacer, isWidth = false)),
        )
        return spacer
    }

    private fun makeLabel(text: String, style: AdTextStyle, maxLines: Int?): UILabel {
        val label = UILabel()
        label.text = text
        label.font = font(style)
        label.textColor = color(style.colorArgb)
        label.textAlignment = textAlignment(style.textAlign)
        label.numberOfLines = (maxLines?.toLong() ?: 0L)
        label.adjustsFontForContentSizeCategory = true
        return label
    }

    /** The "Ad" attribution badge — centred and labelled for VoiceOver, as on Android. */
    private fun makeBadgeLabel(text: String, style: AdTextStyle): UILabel {
        val label = makeLabel(text, style, maxLines = 1)
        label.textAlignment = NSTextAlignmentCenter
        label.setAccessibilityLabel("Advertisement")
        return label
    }

    /**
     * Mirrors `AndroidNativeAdLayoutRenderer.applyMissingVisibility`: a node whose asset the
     * creative did not supply keeps its place in the layout unless the policy says otherwise.
     */
    private fun applyMissingVisibility(view: UIView, value: Any?, policy: AdVisibilityPolicy) {
        if (value != null) return
        when (policy) {
            AdVisibilityPolicy.HideWhenMissing -> view.hidden = true
            AdVisibilityPolicy.InvisibleWhenMissing,
            AdVisibilityPolicy.KeepSpace -> view.alpha = 0.0
        }
    }

    private fun font(style: AdTextStyle): UIFont {
        val size = style.fontSizeSp.toDouble()
        val weight = when (style.fontWeight) {
            AdFontWeight.Bold -> UIFontWeightBold
            AdFontWeight.Medium -> UIFontWeightMedium
            AdFontWeight.Normal -> UIFontWeightRegular
        }
        return when (val family = style.fontFamily) {
            AdFontFamily.Default, AdFontFamily.SansSerif -> {
                UIFont.systemFontOfSize(size, weight)
            }
            AdFontFamily.Serif -> {
                val descriptor = UIFont.systemFontOfSize(size, weight).fontDescriptor.fontDescriptorWithDesign(UIFontDescriptorSystemDesignSerif)
                if (descriptor != null) UIFont.fontWithDescriptor(descriptor, size) else UIFont.systemFontOfSize(size, weight)
            }
            AdFontFamily.Monospace -> {
                val descriptor = UIFont.systemFontOfSize(size, weight).fontDescriptor.fontDescriptorWithDesign(UIFontDescriptorSystemDesignMonospaced)
                if (descriptor != null) UIFont.fontWithDescriptor(descriptor, size) else UIFont.monospacedSystemFontOfSize(size, weight)
            }
            is AdFontFamily.Named -> {
                UIFont.fontWithName(family.name, size) ?: UIFont.systemFontOfSize(size, weight)
            }
            is AdFontFamily.FromCompose -> {
                composeFontResolver.resolve(
                    family = family.fontFamily,
                    requestedWeight = when (style.fontWeight) {
                        AdFontWeight.Bold -> FontWeight.Bold
                        AdFontWeight.Medium -> FontWeight.Medium
                        AdFontWeight.Normal -> FontWeight.Normal
                    },
                    size = size,
                    density = density,
                ) ?: UIFont.systemFontOfSize(size, weight)
            }
        }
    }

    private fun color(argb: Long): UIColor = uiColorFromArgb(argb)

    private fun textAlignment(align: AdTextAlign): NSTextAlignment = when (align) {
        AdTextAlign.Center -> NSTextAlignmentCenter
        AdTextAlign.End -> NSTextAlignmentRight
        AdTextAlign.Start -> NSTextAlignmentLeft
    }

    private fun contentMode(scale: AdContentScale): UIViewContentMode = when (scale) {
        AdContentScale.Crop -> UIViewContentModeScaleAspectFill
        AdContentScale.FillBounds -> UIViewContentModeScaleToFill
        AdContentScale.Fit -> UIViewContentModeScaleAspectFit
    }
}

private fun padNonZero(p: AdInsets): Boolean = p.startDp != 0f || p.topDp != 0f || p.endDp != 0f || p.bottomDp != 0f

/** Converts an `AdModifier`-style 0xAARRGGBB long into a [UIColor]. */
internal fun uiColorFromArgb(argb: Long): UIColor {
    val value = argb.toULong()
    return UIColor(
        red = ((value shr 16) and 0xFFu).toDouble() / 255.0,
        green = ((value shr 8) and 0xFFu).toDouble() / 255.0,
        blue = (value and 0xFFu).toDouble() / 255.0,
        alpha = ((value shr 24) and 0xFFu).toDouble() / 255.0
    )
}

private const val TRANSPARENT_ARGB: Long = 0x00000000

/**
 * A corner radius large enough that any realistic ad asset clips to a capsule.
 *
 * `AdClip.Circle` is the one clip whose radius depends on the view's laid-out size, which is not
 * known while the tree is being built. `CALayer` clamps `cornerRadius` to half the shorter side, so
 * an over-large constant lands on exactly the radius Compose's `CircleShape` would pick.
 */
private const val CIRCLE_CLIP_RADIUS: Double = 10_000.0

private val UILayoutConstraintAxisHorizontal = platform.UIKit.UILayoutConstraintAxisHorizontal
private val UILayoutConstraintAxisVertical = platform.UIKit.UILayoutConstraintAxisVertical
private val UILayoutPriorityDefaultLow = platform.UIKit.UILayoutPriorityDefaultLow
private val UILayoutPriorityFittingSizeLevel = platform.UIKit.UILayoutPriorityFittingSizeLevel

/**
 * Sits below every default hugging priority so a weighted child yields to its siblings and takes
 * the stack's free space, rather than tying with them at `UILayoutPriorityDefaultLow`.
 */
private const val WEIGHTED_CHILD_HUGGING_PRIORITY: Float = 1f

/**
 * Holds an unweighted sibling at its intrinsic main-axis size when the stack has weighted children,
 * so the weighted ones take the slack rather than the sibling's own space. Just below `required`
 * (1000) so an over-full stack degrades by shrinking a sibling instead of failing to lay out.
 */
private const val UNWEIGHTED_SIBLING_PRIORITY: Float = 999f

private val NSTextAlignmentCenter = platform.UIKit.NSTextAlignmentCenter
private val NSTextAlignmentRight = platform.UIKit.NSTextAlignmentRight
private val NSTextAlignmentLeft = platform.UIKit.NSTextAlignmentLeft
private val UIViewContentModeScaleAspectFill = platform.UIKit.UIViewContentMode.UIViewContentModeScaleAspectFill
private val UIViewContentModeScaleToFill = platform.UIKit.UIViewContentMode.UIViewContentModeScaleToFill
private val UIViewContentModeScaleAspectFit = platform.UIKit.UIViewContentMode.UIViewContentModeScaleAspectFit
