package dev.avinya.ads.nativead.rendering

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.Space
import android.widget.TextView
import dev.avinya.ads.AdLogger
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdClip
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdDisplay
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdNode
import dev.avinya.ads.nativead.layout.AdSpacer
import dev.avinya.ads.nativead.layout.AdStaticText
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import com.google.android.libraries.ads.mobile.sdk.nativead.MediaView
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdView
import kotlin.math.roundToInt

internal class AndroidNativeAdLayoutRenderer(
    private val context: Context,
    private val nativeAd: NativeAd,
    private val resolvedComposeFonts: ResolvedComposeFonts = ResolvedComposeFonts.Empty,
    private val existingRoot: NativeAdView? = null
) {
    private val density: Float = context.resources.displayMetrics.density
    private var renderedMediaView: MediaView? = null

    fun render(layout: AdLayout): NativeAdView =
        (existingRoot ?: NativeAdView(context)).also { renderInto(it, layout) }

    fun renderInto(nativeAdView: NativeAdView, layout: AdLayout): NativeAdView {
        AdLogger.d("Android native renderer renderInto. layout=${layout.identity}")
        nativeAdView.removeAllViews()
        nativeAdView.clipChildren = true
        nativeAdView.clipToPadding = true
        nativeAdView.setBackgroundColor(Color.TRANSPARENT)
        renderedMediaView = null

        val content = buildNode(layout.root, nativeAdView)
        nativeAdView.addView(
            wrapForMeasurement(content, layout.root.modifier),
            frameParams(layout.root.modifier, AdAlignment.Box.TopStart)
        )

        registerAfterContainedLayout(nativeAdView)
        return nativeAdView
    }

    private fun registerAfterContainedLayout(nativeAdView: NativeAdView) {
        val listener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (view.width <= 0 || view.height <= 0) return

                val rootBounds = view.screenBounds()
                val issues = listOfNotNull(
                    nativeAdView.headlineView?.let { "headline" to it },
                    nativeAdView.bodyView?.let { "body" to it },
                    nativeAdView.callToActionView?.let { "callToAction" to it },
                    nativeAdView.iconView?.let { "icon" to it },
                    renderedMediaView?.let { "media" to it },
                    nativeAdView.advertiserView?.let { "advertiser" to it },
                    nativeAdView.priceView?.let { "price" to it },
                    nativeAdView.storeView?.let { "store" to it },
                    nativeAdView.starRatingView?.let { "starRating" to it },
                ).mapNotNull { (name, asset) ->
                    val assetBounds = asset.screenBounds()
                    "$name=$assetBounds".takeUnless { rootBounds.contains(assetBounds) }
                }
                if (issues.isNotEmpty()) {
                    AdLogger.e(
                        "Android native ad registration blocked because asset bounds escape " +
                            "NativeAdView $rootBounds: ${issues.joinToString()}"
                    )
                    return
                }

                view.removeOnLayoutChangeListener(this)
                nativeAdView.registerNativeAd(nativeAd, renderedMediaView)
                AdLogger.d(
                    "Android native renderer registered after containment. " +
                        "hasMediaView=${renderedMediaView != null}"
                )
            }
        }
        nativeAdView.addOnLayoutChangeListener(listener)
    }

    private fun View.screenBounds(): NativeAdBounds {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return NativeAdBounds(
            left = location[0],
            top = location[1],
            right = location[0] + width,
            bottom = location[1] + height,
        )
    }

    private fun buildNode(node: AdNode, nativeAdView: NativeAdView): View = when (node) {
        is AdContainerNode.Row -> LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = node.verticalAlignment.toAndroidGravity() or node.horizontalAlignment.toAndroidGravity()
            clipChildren = false
            clipToPadding = false
            applyViewStyle(this, node.modifier)
            node.children.forEachIndexed { index, child ->
                addChild(this, buildNode(child, nativeAdView), child.modifier, if (index > 0) node.spacingDp else 0f, horizontal = true)
            }
        }
        is AdContainerNode.Column -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = node.horizontalAlignment.toAndroidGravity() or node.verticalAlignment.toAndroidGravity()
            clipChildren = false
            clipToPadding = false
            applyViewStyle(this, node.modifier)
            node.children.forEachIndexed { index, child ->
                addChild(this, buildNode(child, nativeAdView), child.modifier, if (index > 0) node.spacingDp else 0f, horizontal = false)
            }
        }
        is AdContainerNode.Box -> FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            applyViewStyle(this, node.modifier)
            node.children.forEach { child ->
                addView(wrapForMeasurement(buildNode(child, nativeAdView), child.modifier), frameParams(child.modifier, node.contentAlignment))
            }
        }
        is AdSpacer -> Space(context).apply { applyViewStyle(this, node.modifier) }
        is AdStaticText -> TextView(context).apply {
            text = node.text
            applyTextStyle(node.style, node.maxLines)
            applyViewStyle(this, node.modifier)
        }
        is AdAssetNode -> buildAsset(node, nativeAdView)
    }

    private fun buildAsset(node: AdAssetNode, nativeAdView: NativeAdView): View {
        fun textAsset(value: String?, style: AdTextStyle, maxLines: Int?, assign: (View) -> Unit): TextView =
            TextView(context).apply {
                text = value.orEmpty()
                applyTextStyle(style, maxLines)
                applyViewStyle(this, node.modifier)
                applyMissingVisibility(value?.takeIf { it.isNotBlank() }, node.visibilityPolicy)
                assign(this)
            }

        return when (node) {
            is AdAssetNode.Headline -> textAsset(nativeAd.headline, node.style, node.maxLines) { nativeAdView.headlineView = it }
            is AdAssetNode.Body -> textAsset(nativeAd.body, node.style, node.maxLines) { nativeAdView.bodyView = it }
            is AdAssetNode.Advertiser -> textAsset(nativeAd.advertiser, node.style, node.maxLines) { nativeAdView.advertiserView = it }
            is AdAssetNode.Price -> textAsset(nativeAd.price, node.style, node.maxLines) { nativeAdView.priceView = it }
            is AdAssetNode.Store -> textAsset(nativeAd.store, node.style, node.maxLines) { nativeAdView.storeView = it }
            is AdAssetNode.StarRating -> RatingBar(context, null, android.R.attr.ratingBarStyleSmall).apply {
                val rating = nativeAd.starRating?.toFloat()
                if (rating != null) this.rating = rating
                numStars = 5
                stepSize = 0.5f
                isEnabled = false
                applyViewStyle(this, node.modifier)
                applyMissingVisibility(rating, node.visibilityPolicy)
                nativeAdView.starRatingView = this
            }
            is AdAssetNode.Icon -> ImageView(context).apply {
                setImageDrawable(nativeAd.icon?.drawable)
                scaleType = node.style.contentScale.toAndroidScaleType()
                adjustViewBounds = true
                val backgroundArgb = resolveNativeAdDrawableBackgroundArgb(node.modifier, node.style.backgroundArgb)
                setBackgroundColor(backgroundArgb.toAndroidColor())
                applyViewStyle(this, node.modifier, backgroundArgb)
                applyMissingVisibility(nativeAd.icon, node.visibilityPolicy)
                nativeAdView.iconView = this
            }
            is AdAssetNode.Media -> MediaView(context).apply {
                imageScaleType = node.style.contentScale.toAndroidScaleType()
                val backgroundArgb = resolveNativeAdDrawableBackgroundArgb(node.modifier, node.style.backgroundArgb)
                setBackgroundColor(backgroundArgb.toAndroidColor())
                applyViewStyle(this, node.modifier, backgroundArgb)
                renderedMediaView = this
            }
            is AdAssetNode.CallToAction -> Button(context).apply {
                text = resolveCallToActionText(nativeAd.callToAction.orEmpty(), node.style.textCase)
                isAllCaps = false
                disableDirectInteractionForNativeAdAsset()
                setTextColor(node.style.textStyle.colorArgb.toAndroidColor())
                textSize = node.style.textStyle.fontSizeSp
                typeface = node.style.textStyle.fontFamily.toTypeface(
                    node.style.textStyle.fontWeight,
                    resolvedComposeFonts,
                )
                val backgroundArgb = resolveNativeAdDrawableBackgroundArgb(node.modifier, node.style.backgroundArgb)
                background = roundedDrawable(density, backgroundArgb, node.style.cornerRadiusDp)
                applyViewStyle(this, node.modifier.withoutPadding(), backgroundArgb)
                val insets = resolveCallToActionContentInsets(node.modifier, node.style)
                setPadding(
                    dp(insets.startDp),
                    dp(insets.topDp),
                    dp(insets.endDp),
                    dp(insets.bottomDp),
                )
                gravity = Gravity.CENTER
                applyMissingVisibility(nativeAd.callToAction?.takeIf { it.isNotBlank() }, node.visibilityPolicy)
                nativeAdView.callToActionView = this
            }
            is AdAssetNode.AdChoices -> FrameLayout(context).apply {
                contentDescription = "AdChoices placement"
                applyViewStyle(this, node.modifier)
            }
            is AdAssetNode.AdBadge -> TextView(context).apply {
                text = node.text
                contentDescription = "Advertisement"
                applyTextStyle(node.style, maxLines = 1)
                gravity = Gravity.CENTER
                applyViewStyle(this, node.modifier)
            }
        }
    }

    private fun addChild(parent: LinearLayout, child: View, modifier: AdModifier, spacingDp: Float, horizontal: Boolean) {
        parent.addView(wrapForMeasurement(child, modifier), linearParams(modifier, spacingDp, horizontal))
    }

    private fun wrapForMeasurement(child: View, modifier: AdModifier): View {
        val needsWrapper = modifier.aspectRatio != null || modifier.maxWidthDp != null || modifier.maxHeightDp != null
        if (!needsWrapper) return child
        return ModifierMeasureFrameLayout(context, density, modifier).apply {
            clipChildren = false
            clipToPadding = false
            addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun linearParams(modifier: AdModifier, spacingDp: Float, horizontal: Boolean): LinearLayout.LayoutParams {
        val weighted = modifier.weight != null
        val width = if (horizontal && weighted) 0 else modifier.width.toLayoutParam(density)
        val height = if (!horizontal && weighted) 0 else modifier.height.toLayoutParam(density)
        val params = LinearLayout.LayoutParams(width, height, modifier.weight ?: 0f)
        if (horizontal) params.marginStart = dp(spacingDp) else params.topMargin = dp(spacingDp)
        applyMargins(params, modifier)
        return params
    }

    private fun frameParams(modifier: AdModifier, alignment: AdAlignment.Box): FrameLayout.LayoutParams =
        FrameLayout.LayoutParams(modifier.width.toLayoutParam(density), modifier.height.toLayoutParam(density), alignment.toAndroidGravity()).also {
            applyMargins(it, modifier)
        }

    private fun applyMargins(params: ViewGroup.MarginLayoutParams, modifier: AdModifier) {
        params.marginStart += dp(modifier.margin.startDp)
        params.topMargin += dp(modifier.margin.topDp)
        params.marginEnd += dp(modifier.margin.endDp)
        params.bottomMargin += dp(modifier.margin.bottomDp)
    }

    private fun applyViewStyle(
        view: View,
        modifier: AdModifier,
        backgroundArgb: Long? = modifier.backgroundArgb,
    ) {
        view.visibility = when (modifier.display) {
            AdDisplay.Visible -> View.VISIBLE
            AdDisplay.Invisible -> View.INVISIBLE
            AdDisplay.Gone -> View.GONE
        }
        view.alpha = modifier.alpha
        view.translationX = dp(modifier.offsetXDp).toFloat()
        view.translationY = dp(modifier.offsetYDp).toFloat()
        view.translationZ = modifier.zIndex
        view.elevation = dp(modifier.elevationDp).toFloat()
        if (!modifier.padding.isZero()) {
            view.setPadding(dp(modifier.padding.startDp), dp(modifier.padding.topDp), dp(modifier.padding.endDp), dp(modifier.padding.bottomDp))
        }
        view.minimumWidth = dp(modifier.minWidthDp ?: 0f)
        view.minimumHeight = dp(modifier.minHeightDp ?: 0f)
        if (modifier.backgroundArgb != null || modifier.borderColorArgb != null || modifier.cornerRadiusDp != null || modifier.borderRadiusDp != null) {
            view.background = roundedDrawable(
                density = density,
                colorArgb = backgroundArgb ?: 0x00000000,
                radiusDp = modifier.cornerRadiusDp ?: modifier.borderRadiusDp ?: 0f,
                borderWidthDp = modifier.borderWidthDp,
                borderColorArgb = modifier.borderColorArgb
            )
        }
        applyClip(view, modifier)
    }

    private fun applyClip(view: View, modifier: AdModifier) {
        when (val clip = modifier.clipShape) {
            AdClip.None -> return
            AdClip.Bounds -> {
                view.outlineProvider = RectOutlineProvider
                view.clipToOutline = true
            }
            is AdClip.RoundedCorner -> {
                view.outlineProvider = RoundedOutlineProvider(dp(clip.radiusDp).toFloat())
                view.clipToOutline = true
            }
            AdClip.Circle -> {
                view.outlineProvider = CircleOutlineProvider
                view.clipToOutline = true
            }
        }
        if (view is ViewGroup) {
            view.clipChildren = true
            view.clipToPadding = true
        }
    }

    private fun TextView.applyTextStyle(style: AdTextStyle, maxLines: Int?) {
        setTextColor(style.colorArgb.toAndroidColor())
        textSize = style.fontSizeSp
        typeface = style.fontFamily.toTypeface(style.fontWeight, resolvedComposeFonts)
        gravity = style.textAlign.toAndroidGravity()
        maxLines?.let { this.maxLines = it }
        includeFontPadding = false
    }

    private fun View.applyMissingVisibility(value: Any?, policy: AdVisibilityPolicy) {
        if (value != null || visibility != View.VISIBLE) return
        visibility = when (policy) {
            AdVisibilityPolicy.HideWhenMissing -> View.GONE
            AdVisibilityPolicy.InvisibleWhenMissing,
            AdVisibilityPolicy.KeepSpace -> View.INVISIBLE
        }
    }

    private fun Button.disableDirectInteractionForNativeAdAsset() {
        isClickable = false
        isFocusable = false
    }



    private fun dp(value: Float): Int = (value * density).roundToInt()
}
