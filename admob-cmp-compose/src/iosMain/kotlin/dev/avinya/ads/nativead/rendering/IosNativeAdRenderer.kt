@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.avinya.ads.nativead.rendering

import GoogleMobileAds.GADAdChoicesView
import GoogleMobileAds.GADMediaView
import GoogleMobileAds.GADNativeAd
import GoogleMobileAds.GADNativeAdView
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdClip
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdContentScale
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdImageStyle
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
import platform.UIKit.UIButton
import platform.UIKit.UIButtonConfiguration
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
import platform.UIKit.UIViewController
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIEdgeInsets
import platform.UIKit.NSDirectionalEdgeInsets
import platform.UIKit.UILayoutConstraintAxis
import platform.UIKit.NSTextAlignment

internal class IosNativeAdRenderer(
    private val nativeAd: GADNativeAd,
    private val nativeView: GADNativeAdView,
    private val density: Density,
    private val composeFontResolver: IosComposeFontResolver = DefaultIosComposeFontResolver,
) {
    fun render(node: AdNode): UIView = when (node) {
        is AdContainerNode.Row -> renderStack(node.children, axis = UILayoutConstraintAxisHorizontal, spacing = node.spacingDp, modifier = node.modifier)
        is AdContainerNode.Column -> renderStack(node.children, axis = UILayoutConstraintAxisVertical, spacing = node.spacingDp, modifier = node.modifier)
        is AdContainerNode.Box -> renderBox(node.children, modifier = node.modifier)
        is AdSpacer -> UIView().also { applyModifier(node.modifier, it) }
        is AdStaticText -> makeLabel(node.text, node.style, node.maxLines, node.modifier)
        is AdAssetNode.Headline -> makeLabel(nativeAd.headline ?: "", node.style, node.maxLines, node.modifier).also { nativeView.headlineView = it }
        is AdAssetNode.Body -> optionalLabel(nativeAd.body, node.style, node.maxLines, node.modifier, node.visibilityPolicy) { nativeView.bodyView = it }
        is AdAssetNode.Advertiser -> optionalLabel(nativeAd.advertiser, node.style, node.maxLines, node.modifier, node.visibilityPolicy) { nativeView.advertiserView = it }
        is AdAssetNode.Price -> optionalLabel(nativeAd.price, node.style, node.maxLines, node.modifier, node.visibilityPolicy) { nativeView.priceView = it }
        is AdAssetNode.Store -> optionalLabel(nativeAd.store, node.style, node.maxLines, node.modifier, node.visibilityPolicy) { nativeView.storeView = it }
        is AdAssetNode.StarRating -> {
            val text = nativeAd.starRating?.let { "${it.stringValue} stars" }
            optionalLabel(text, node.style, 1, node.modifier, node.visibilityPolicy) { nativeView.starRatingView = it }
        }
        is AdAssetNode.Icon -> renderIcon(node)
        is AdAssetNode.Media -> renderMedia(node)
        is AdAssetNode.CallToAction -> renderCallToAction(node)
        is AdAssetNode.AdBadge -> makeLabel(node.text, node.style, 1, node.modifier)
        is AdAssetNode.AdChoices -> GADAdChoicesView().also { applyModifier(node.modifier, it) }.also { nativeView.adChoicesView = it }
    }

    private fun renderStack(children: List<AdNode>, axis: UILayoutConstraintAxis, spacing: Float, modifier: AdModifier): UIView {
        val stack = UIStackView()
        stack.axis = axis
        stack.spacing = spacing.toDouble()
        stack.alignment = platform.UIKit.UIStackViewAlignmentFill
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
        applyModifier(modifier, stack, handlesPadding = true)
        for (child in children) {
            val childView = wrapForMargin(render(child), modifierOf(child))
            stack.addArrangedSubview(childView)
            if (modifierOf(child).weight != null) {
                childView.setContentHuggingPriority(UILayoutPriorityDefaultLow, axis)
                childView.setContentCompressionResistancePriority(UILayoutPriorityDefaultLow, axis)
            }
        }
        return stack
    }

    private fun renderBox(children: List<AdNode>, modifier: AdModifier): UIView {
        val box = UIView()
        box.setTranslatesAutoresizingMaskIntoConstraints(false)
        applyModifier(modifier, box)
        val sizingChildIndex = children.indexOfFirst { it is AdAssetNode.Media }
            .takeIf { it >= 0 }
            ?: 0
        for ((index, child) in children.withIndex()) {
            val childView = wrapForMargin(render(child), modifierOf(child))
            val childModifier = modifierOf(child)
            box.addSubview(childView)
            childView.leadingAnchor.constraintEqualToAnchor(box.leadingAnchor).active = true
            if (childModifier.width == AdLayoutSize.Match) {
                childView.trailingAnchor.constraintEqualToAnchor(box.trailingAnchor).active = true
            } else {
                childView.trailingAnchor.constraintLessThanOrEqualToAnchor(box.trailingAnchor).active = true
            }
            childView.topAnchor.constraintEqualToAnchor(box.topAnchor).active = true
            if (index == sizingChildIndex) {
                childView.bottomAnchor.constraintEqualToAnchor(box.bottomAnchor).active = true
            } else {
                childView.bottomAnchor.constraintLessThanOrEqualToAnchor(box.bottomAnchor).active = true
            }
        }
        return box
    }

    private fun renderIcon(node: AdAssetNode.Icon): UIView {
        val image = nativeAd.icon?.image
        if (image == null) return missingView(node.modifier, node.visibilityPolicy)
        val imageView = UIImageView(image)
        imageView.contentMode = contentMode(node.style.contentScale)
        imageView.clipsToBounds = true
        imageView.backgroundColor = color(
            resolveNativeAdBackgroundArgb(node.modifier, node.style.backgroundArgb) ?: 0x00000000,
        )
        nativeView.iconView = imageView
        applyModifier(node.modifier, imageView)
        return imageView
    }

    private fun renderMedia(node: AdAssetNode.Media): UIView {
        val mediaView = GADMediaView()
        mediaView.mediaContent = nativeAd.mediaContent
        mediaView.clipsToBounds = true
        mediaView.backgroundColor = color(
            resolveNativeAdBackgroundArgb(node.modifier, node.style.backgroundArgb) ?: 0x00000000,
        )
        nativeView.mediaView = mediaView
        applyModifier(node.modifier, mediaView)
        return mediaView
    }

    private fun renderCallToAction(node: AdAssetNode.CallToAction): UIView {
        val title = nativeAd.callToAction
        if (title == null || title.isEmpty()) return missingView(node.modifier, node.visibilityPolicy)
        val button = platform.UIKit.UIButton.buttonWithType(platform.UIKit.UIButtonTypeSystem)
        val insets = resolveCallToActionContentInsets(node.modifier, node.style)
        val configuration = UIButtonConfiguration.plainButtonConfiguration().apply {
            this.title = title
            baseForegroundColor = color(node.style.textStyle.colorArgb)
            baseBackgroundColor = color(
                resolveNativeAdBackgroundArgb(node.modifier, node.style.backgroundArgb) ?: 0x00000000,
            )
            background.cornerRadius = node.style.cornerRadiusDp.toDouble()
            contentInsets = cValue<NSDirectionalEdgeInsets> {
                top = insets.topDp.toDouble()
                leading = insets.startDp.toDouble()
                bottom = insets.bottomDp.toDouble()
                trailing = insets.endDp.toDouble()
            }
        }
        button.configuration = configuration
        button.titleLabel?.font = font(node.style.textStyle)
        button.contentHorizontalAlignment = platform.UIKit.UIControlContentHorizontalAlignmentCenter
        button.contentVerticalAlignment = platform.UIKit.UIControlContentVerticalAlignmentCenter
        button.userInteractionEnabled = false
        nativeView.callToActionView = button
        applyModifier(node.modifier.withoutPadding(), button)
        return button
    }

    private fun makeLabel(text: String, style: AdTextStyle, maxLines: Int?, modifier: AdModifier): UILabel {
        val label = UILabel()
        label.text = text
        label.font = font(style)
        label.textColor = color(style.colorArgb)
        label.textAlignment = textAlignment(style.textAlign)
        label.numberOfLines = (maxLines?.toLong() ?: 0L)
        label.adjustsFontForContentSizeCategory = true
        applyModifier(modifier, label)
        return label
    }

    private fun optionalLabel(
        text: String?,
        style: AdTextStyle,
        maxLines: Int?,
        modifier: AdModifier,
        policy: AdVisibilityPolicy,
        register: (UILabel) -> Unit
    ): UIView {
        if (text == null || text.isEmpty()) return missingView(modifier, policy)
        val view = makeLabel(text, style, maxLines, modifier)
        register(view)
        return view
    }

    private fun missingView(modifier: AdModifier, policy: AdVisibilityPolicy): UIView {
        val view = UIView()
        applyModifier(modifier, view)
        when (policy) {
            AdVisibilityPolicy.HideWhenMissing -> view.hidden = true
            AdVisibilityPolicy.InvisibleWhenMissing -> view.alpha = 0.0
            AdVisibilityPolicy.KeepSpace -> Unit
        }
        return view
    }

    private fun applyModifier(modifier: AdModifier, view: UIView, handlesPadding: Boolean = false) {
        view.setTranslatesAutoresizingMaskIntoConstraints(false)
        view.hidden = modifier.display == AdDisplay.Gone
        if (modifier.display == AdDisplay.Invisible) view.alpha = 0.0 else view.alpha = modifier.alpha.toDouble()
        view.transform = CGAffineTransformMakeTranslation(modifier.offsetXDp.toDouble(), modifier.offsetYDp.toDouble())
        view.layer.zPosition = modifier.zIndex.toDouble()
        if (modifier.elevationDp > 0f) {
            view.layer.shadowOpacity = 0.18f
            view.layer.shadowRadius = modifier.elevationDp.toDouble()
            view.layer.shadowOffset = CGSizeMake(0.0, (modifier.elevationDp / 2f).toDouble())
        }
        if (padNonZero(modifier.padding)) {
            val p = modifier.padding
            view.layoutMargins = cValue<UIEdgeInsets> {
                top = p.topDp.toDouble()
                left = p.startDp.toDouble()
                bottom = p.bottomDp.toDouble()
                right = p.endDp.toDouble()
            }
        }
        modifier.backgroundArgb?.let { view.backgroundColor = color(it) }
        modifier.borderColorArgb?.let { view.layer.borderColor = color(it).CGColor }
        modifier.borderWidthDp?.let { view.layer.borderWidth = it.toDouble() }
        val cornerRadius = modifier.cornerRadiusDp ?: modifier.borderRadiusDp
        cornerRadius?.let {
            view.layer.cornerRadius = it.toDouble()
            view.clipsToBounds = true
        }
        applyClip(modifier.clipShape, view)
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
            AdClip.Bounds -> {
                view.clipsToBounds = true
            }
            is AdClip.RoundedCorner -> {
                view.layer.cornerRadius = clip.radiusDp.toDouble()
                view.clipsToBounds = true
            }
            AdClip.Circle -> {
                view.layer.cornerRadius = 10_000.0
                view.clipsToBounds = true
            }
            AdClip.None -> Unit
        }
    }

    private fun applySize(size: AdLayoutSize, axis: UILayoutConstraintAxis, view: UIView) {
        when (size) {
            is AdLayoutSize.Fixed -> {
                val constant = size.dp.toDouble()
                if (axis == UILayoutConstraintAxisHorizontal)
                    view.widthAnchor.constraintEqualToConstant(constant).active = true
                else
                    view.heightAnchor.constraintEqualToConstant(constant).active = true
            }
            else -> Unit
        }
    }

    private fun wrapForMargin(childView: UIView, modifier: AdModifier): UIView {
        val margin = modifier.margin
        if (margin.startDp == 0f && margin.topDp == 0f && margin.endDp == 0f && margin.bottomDp == 0f) return childView
        val wrapper = UIView()
        wrapper.setTranslatesAutoresizingMaskIntoConstraints(false)
        wrapper.addSubview(childView)
        childView.leadingAnchor.constraintEqualToAnchor(wrapper.leadingAnchor, constant = margin.startDp.toDouble()).active = true
        childView.trailingAnchor.constraintEqualToAnchor(wrapper.trailingAnchor, constant = -margin.endDp.toDouble()).active = true
        childView.topAnchor.constraintEqualToAnchor(wrapper.topAnchor, constant = margin.topDp.toDouble()).active = true
        childView.bottomAnchor.constraintEqualToAnchor(wrapper.bottomAnchor, constant = -margin.bottomDp.toDouble()).active = true
        return wrapper
    }

    private fun modifierOf(node: AdNode): AdModifier = node.modifier
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

    private fun color(argb: Long): UIColor {
        val value = argb.toULong()
        return UIColor(
            red = ((value shr 16) and 0xFFu).toDouble() / 255.0,
            green = ((value shr 8) and 0xFFu).toDouble() / 255.0,
            blue = (value and 0xFFu).toDouble() / 255.0,
            alpha = ((value shr 24) and 0xFFu).toDouble() / 255.0
        )
    }

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

private val UILayoutConstraintAxisHorizontal = platform.UIKit.UILayoutConstraintAxisHorizontal
private val UILayoutConstraintAxisVertical = platform.UIKit.UILayoutConstraintAxisVertical
private val UILayoutPriorityDefaultLow = platform.UIKit.UILayoutPriorityDefaultLow
private val NSTextAlignmentCenter = platform.UIKit.NSTextAlignmentCenter
private val NSTextAlignmentRight = platform.UIKit.NSTextAlignmentRight
private val NSTextAlignmentLeft = platform.UIKit.NSTextAlignmentLeft
private val UIViewContentModeScaleAspectFill = platform.UIKit.UIViewContentMode.UIViewContentModeScaleAspectFill
private val UIViewContentModeScaleToFill = platform.UIKit.UIViewContentMode.UIViewContentModeScaleToFill
private val UIViewContentModeScaleAspectFit = platform.UIKit.UIViewContentMode.UIViewContentModeScaleAspectFit
