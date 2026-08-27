@file:OptIn(
    dev.avinya.ads.InternalAdMobCmpApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package dev.avinya.ads.ui

import GoogleMobileAds.GADNativeAdView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.avinya.ads.AdError
import dev.avinya.ads.AdLogger
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.IosNativeAdRenderLease
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.acquireIosNativeAdRenderLease
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutSize
import dev.avinya.ads.nativead.rendering.IosNativeAdRenderer
import dev.avinya.ads.nativead.rendering.adRootSurface
import dev.avinya.ads.nativead.rendering.rememberResolvedComposeFonts
import dev.avinya.ads.nativead.rendering.resolveNativeAdSurfaceArgb
import dev.avinya.ads.nativead.rendering.uiColorFromArgb
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIContentSizeCategoryDidChangeNotification
import platform.UIKit.UILayoutPriorityFittingSizeLevel
import platform.UIKit.UILayoutPriorityRequired
import platform.UIKit.UIView

@Composable
public actual fun NativeAdView(
    session: NativeAdSession,
    slotKey: String,
    placement: AdPlacement,
    layout: AdLayout,
    modifier: Modifier,
    loading: @Composable () -> Unit,
    failure: @Composable (AdError) -> Unit,
    onEvent: (AdEvent) -> Unit,
) {
    if (!placement.enabled || placement.format != AdFormat.Native || layout.validation.errors.isNotEmpty()) {
        NativeAdPlaceholder(modifier, loading)
        return
    }

    val slotState = session.state.collectAsState().value.slots[slotKey]
    val rendererId = remember(session, slotKey) { "ios-native-renderer-${nextIosRendererId++}" }
    val leaseOwner = if (slotState.canRenderNativeAd()) {
        remember(session, slotKey, placement, rendererId) {
            NativeAdRenderLeaseOwner(
                acquire = { session.acquireIosNativeAdRenderLease(slotKey, placement, rendererId) },
                release = IosNativeAdRenderLease::release,
            )
        }
    } else {
        null
    }
    val lease = leaseOwner?.lease()
    val manager = LocalAdManager.current
    val currentLease by rememberUpdatedState(lease)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val density = LocalDensity.current
    val resolvedComposeFonts = rememberResolvedComposeFonts(layout)

    LaunchedEffect(manager, placement.id) {
        manager.events.collect { event ->
            if (isNativeEventForLease(placement.id, currentLease?.adInstanceId, event)) {
                currentOnEvent(event)
            }
        }
    }

    when (slotState) {
        is NativeAdSlotState.Failed -> NativeAdPlaceholder(modifier) { failure(slotState.error) }
        is NativeAdSlotState.Ready, is NativeAdSlotState.Retained, is NativeAdSlotState.Mounted -> {
            val mountedLease = lease
            if (mountedLease == null) {
                NativeAdPlaceholder(modifier, loading)
            } else {
                BoxWithConstraints(modifier = modifier, propagateMinConstraints = true) {
                    val widthPoints = maxWidth.value.toDouble()
                    val minHeightPoints = minHeight.value.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val maxHeightPoints = maxHeight.value.toDouble().takeIf { it.isFinite() && it > 0.0 }
                    // A root that asks to fill takes the host's height rather than measuring its
                    // own: see `IosNativeAdHostView.fillsHost`.
                    val rootFillsHost = layout.root.modifier.height == AdLayoutSize.Match && minHeightPoints > 0.0
                    val prepared = remember(
                        mountedLease.adInstanceId,
                        layout.identity,
                        resolvedComposeFonts,
                        widthPoints,
                        rootFillsHost,
                    ) {
                        prepareNativeAd(
                            placementId = placement.id,
                            nativeAd = mountedLease.ad,
                            layout = layout,
                            density = density,
                            width = widthPoints,
                            fillsHost = rootFillsHost,
                        )
                    }
                    var height by remember(prepared, minHeightPoints, maxHeightPoints) {
                        mutableDoubleStateOf(prepared.height.coerceIntoHostBounds(minHeightPoints, maxHeightPoints))
                    }
                    // The one thing that legitimately resizes a built tree: every label sets
                    // `adjustsFontForContentSizeCategory`, so a Dynamic Type change re-measures.
                    ObserveContentSizeCategory(prepared) {
                        height = prepared.host.measureDetachedHeight(widthPoints)
                            .coerceIntoHostBounds(minHeightPoints, maxHeightPoints)
                    }
                    key(prepared) {
                        UIKitView(
                            factory = { prepared.host },
                            onRelease = { it.releaseHost() },
                            // A fixed height on purpose. `UIKitView` would otherwise measure the
                            // interop view's fitting size during Compose's measure pass, and that
                            // measurement reads a live Auto Layout tree — which any caller can
                            // catch mid-update, getting back the real height plus whatever its
                            // unsettled subviews are short by. The height handed down here was
                            // measured detached instead, where nothing else was laying the tree
                            // out, so it is exact and never has to be revised.
                            //
                            // The clip sits OUTSIDE Compose's interop `drawBehind { Clear }`
                            // (`layoutNode.modifier = modifier then platformModifier`), so it
                            // shapes the cut-out itself: the corners are never cleared, and the
                            // app's own pixels survive there instead of the platform backdrop.
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (rootFillsHost) Modifier.fillMaxHeight() else Modifier.height(height.dp))
                                .adRootSurface(layout.root),
                            properties = UIKitInteropProperties(isInteractive = true, isNativeAccessibilityEnabled = true),
                        )
                    }
                }
            }
        }
        else -> NativeAdPlaceholder(modifier, loading)
    }
}

/**
 * Owns every pixel of the ad rect.
 *
 * Compose embeds this view *below* its Metal canvas and cuts a hole for it — see
 * `UIKitInteropElementHolder.clearBackgroundIfNeeded`, which draws the interop bounds with
 * `BlendMode.Clear`. Everything Compose painted behind the ad is erased, so a transparent host
 * composites onto `ComposeContainerView`'s backdrop (`UIColor.whiteColor` in light mode,
 * `blackColor` in dark) rather than onto the app's own surface. That backdrop follows the *system*
 * appearance, so an app rendering its own dark theme under a light system shows white behind the
 * ad. The layout's opaque root background, resolved into [surfaceArgb], is what closes that hole.
 */
internal class IosNativeAdHostView(
    /** Diagnostics only — identifies the host in a containment failure report. */
    private val placementId: String,
    private val nativeView: GADNativeAdView,
    private val content: UIView,
    private val nativeAd: GoogleMobileAds.GADNativeAd,
    surfaceArgb: Long?,
    /**
     * The layout root asked for [dev.avinya.ads.nativead.layout.AdLayoutSize.Match] height.
     *
     * Compose then fixes this view's height — `fillMaxHeight` against bounded constraints — and the
     * content has to stretch into it rather than size itself, so the constraint tying the two must
     * be unbreakable. Otherwise the content keeps its own height and a full-screen layout draws its
     * bottom-aligned content against the bottom of the media rather than the bottom of the page.
     */
    private val fillsHost: Boolean,
) : UIView(frame = kotlinx.cinterop.cValue { }) {
    private var nativeAdRegistered = false
    private var containmentFailureReported = false
    /**
     * Keeps the rendered tree inside the ad view — and, as a consequence, gives the ad view a
     * height for Compose to measure.
     *
     * Paired with the top edge pinned by equality, the inequality reads "the ad view is at least as
     * tall as its content", which is the lower bound Compose's fitting-size measurement needs. It
     * is therefore active from construction rather than from registration: Compose measures this
     * view long before the ad is bound, and an unconstrained bottom edge would measure to nothing.
     *
     * A [fillsHost] root is the other direction — Compose has already fixed the height and the
     * content must stretch into it — so there the constraint is an equality.
     */
    private val containmentConstraint: NSLayoutConstraint =
        if (fillsHost) content.bottomAnchor.constraintEqualToAnchor(nativeView.bottomAnchor)
        else content.bottomAnchor.constraintLessThanOrEqualToAnchor(nativeView.bottomAnchor)
    private val hostRelease = IosNativeHostRelease(
        detachNativeAd = { nativeView.nativeAd = null },
        clearAssets = {
            nativeView.headlineView = null
            nativeView.bodyView = null
            nativeView.callToActionView = null
            nativeView.iconView = null
            nativeView.mediaView = null
            nativeView.advertiserView = null
            nativeView.priceView = null
            nativeView.storeView = null
            nativeView.starRatingView = null
            nativeView.adChoicesView = null
            nativeView.subviews.forEach { (it as? UIView)?.removeFromSuperview() }
        },
        releaseView = { removeFromSuperview() },
    )

    init {
        // The GADNativeAdView and the rendered content stay transparent so the layout's own
        // per-node backgrounds show through unchanged; only this host paints the surface, once.
        val surface = surfaceArgb?.let(::uiColorFromArgb) ?: platform.UIKit.UIColor.clearColor
        // See `IosNativeAdRenderer.renderStack`: the height handed down by Compose was measured
        // detached, where the tree has no safe area, so no view under this host may quietly grow
        // its layout margins once the card scrolls under the status bar or the home indicator.
        insetsLayoutMarginsFromSafeArea = false
        nativeView.insetsLayoutMarginsFromSafeArea = false
        content.insetsLayoutMarginsFromSafeArea = false
        backgroundColor = surface
        opaque = surfaceArgb != null
        // Held explicitly rather than relying on GADNativeAdView's default: this host's surface is
        // visible only *through* it, and `UIView.opaque` defaults to true.
        nativeView.backgroundColor = platform.UIKit.UIColor.clearColor
        nativeView.opaque = false
        nativeView.clipsToBounds = true
        addSubview(nativeView)
        nativeView.leadingAnchor.constraintEqualToAnchor(leadingAnchor).active = true
        nativeView.trailingAnchor.constraintEqualToAnchor(trailingAnchor).active = true
        nativeView.topAnchor.constraintEqualToAnchor(topAnchor).active = true
        nativeView.bottomAnchor.constraintEqualToAnchor(bottomAnchor).active = true
        // Active from the start, not from registration: Compose measures this view before the ad
        // is ever bound, and until this constraint exists the content puts no floor under the
        // view's height for that measurement to find.
        containmentConstraint.active = true
    }

    /**
     * Registration only. Sizing belongs to Compose.
     *
     * This used to measure the content with `systemLayoutSizeFittingSize` and push the result back
     * as the interop view's height. That could not be made correct. `layoutSubviews` runs top-down,
     * so at this point the view already carries its new size while every descendant still holds the
     * frames solved for the previous one; measuring there returns the content's real height plus
     * that stale deficit. On device an ad ratcheted 567.7 -> 586.3 -> 606.3 -> 629.7 and latched
     * ~62pt too tall, with the headline, body and call to action visibly reshuffling under the
     * media on every step — the media itself never moving, since its height is pinned to the width
     * by an aspect ratio and cannot absorb the error.
     *
     * Compose now measures this view's fitting size inside its own measure pass, which is the same
     * contract `AndroidView` has always had on Android, and is why Android never showed the defect.
     */
    override fun layoutSubviews() {
        super.layoutSubviews()
        val (width, height) = bounds.useContents { size.width to size.height }
        if (!width.isFinite() || width <= 0.0) return
        if (!height.isFinite() || height <= 0.0) return
        registerNativeAdOnce()
    }

    /**
     * The height this ad needs at [width], measured where nothing else can perturb the answer.
     *
     * The view is laid out inside a throwaway container first, at the width it will really have, so
     * every multi-line label has resolved its own wrap width before the fitting size is taken. That
     * is the whole difference between this and measuring in place: an attached tree can be caught
     * part-way through an update and answers with the real height plus whatever its unsettled
     * subviews are short by, and there is no reliable way to ask whether it has finished.
     */
    fun measureDetachedHeight(width: Double): Double {
        val previousSuperview = superview
        val container = UIView(frame = CGRectMake(0.0, 0.0, width, 0.0))
        container.addSubview(this)
        setTranslatesAutoresizingMaskIntoConstraints(false)
        val pins = listOf(
            leadingAnchor.constraintEqualToAnchor(container.leadingAnchor),
            trailingAnchor.constraintEqualToAnchor(container.trailingAnchor),
            topAnchor.constraintEqualToAnchor(container.topAnchor),
            container.widthAnchor.constraintEqualToConstant(width),
        )
        NSLayoutConstraint.activateConstraints(pins)
        container.layoutIfNeeded()
        val measured = systemLayoutSizeFittingSize(
            targetSize = CGSizeMake(width, 0.0),
            withHorizontalFittingPriority = UILayoutPriorityRequired,
            verticalFittingPriority = UILayoutPriorityFittingSizeLevel,
        ).useContents { height }
        NSLayoutConstraint.deactivateConstraints(pins)
        removeFromSuperview()
        previousSuperview?.addSubview(this)
        return measured
    }

    /** Binds the ad to its view exactly once, and only while every asset is inside the root. */
    private fun registerNativeAdOnce() {
        if (nativeAdRegistered) return
        layoutIfNeeded()
        nativeView.layoutIfNeeded()
        val issues = registeredAssetContainmentIssues()
        if (issues.isNotEmpty()) {
            reportContainmentFailure(issues)
            return
        }
        nativeView.nativeAd = nativeAd
        nativeAdRegistered = true
    }

    /**
     * Reports the first containment failure for this host, once.
     *
     * Failing closed is correct — registering an ad whose assets sit outside the root is an AdMob
     * policy violation — but the offending bounds were computed and then discarded, so a layout
     * regression presented as a permanently blank ad with nothing to diagnose it by.
     *
     * Latched deliberately: [registerNativeAdOnce] runs on **every** layout pass until registration
     * succeeds, so logging unconditionally would emit on every frame. Registration is still retried
     * each pass, so a layout that later converges binds normally and the diagnostic stands as a
     * record of why the first attempts were refused.
     */
    private fun reportContainmentFailure(issues: List<String>) {
        if (containmentFailureReported) return
        containmentFailureReported = true
        AdLogger.e(
            "Native ad not registered for placement '$placementId': ${issues.size} asset(s) fall " +
                "outside the ad view's bounds, which would be an AdMob policy violation. " +
                "The ad will stay blank until the layout is corrected. Offending assets: " +
                issues.joinToString("; ")
        )
    }

    fun releaseHost() = hostRelease.release()

    private fun registeredAssetContainmentIssues(): List<String> {
        val root = nativeView.bounds.toRectSnapshot()
        val assets = listOfNotNull(
            nativeView.headlineView?.let { "headline" to it },
            nativeView.bodyView?.let { "body" to it },
            nativeView.callToActionView?.let { "callToAction" to it },
            nativeView.iconView?.let { "icon" to it },
            nativeView.mediaView?.let { "media" to it },
            nativeView.advertiserView?.let { "advertiser" to it },
            nativeView.priceView?.let { "price" to it },
            nativeView.storeView?.let { "store" to it },
            nativeView.starRatingView?.let { "starRating" to it },
            nativeView.adChoicesView?.let { "adChoices" to it },
        )
        return assets.mapNotNull { (name, asset) ->
            val bounds = nativeView.convertRect(asset.bounds, fromView = asset).toRectSnapshot()
            "$name=$bounds root=$root".takeUnless { root.contains(bounds) }
        }
    }
}

private data class IosNativeRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    fun contains(other: IosNativeRect, tolerance: Double = 0.5): Boolean =
        other.x >= x - tolerance && other.y >= y - tolerance &&
            other.x + other.width <= x + width + tolerance &&
            other.y + other.height <= y + height + tolerance
}

private fun kotlinx.cinterop.CValue<platform.CoreGraphics.CGRect>.toRectSnapshot(): IosNativeRect = useContents {
    IosNativeRect(origin.x, origin.y, size.width, size.height)
}

/** Clears only Compose's iOS host; the coordinator retains delegates and destroys GADNativeAd. */
internal class IosNativeHostRelease(
    private val detachNativeAd: () -> Unit,
    private val clearAssets: () -> Unit,
    private val releaseView: () -> Unit,
) {
    private var released = false
    fun release() {
        if (released) return
        released = true
        detachNativeAd()
        clearAssets()
        releaseView()
    }
}

/**
 * Asks Compose to remeasure the ad when the text size category changes.
 *
 * Every label in the rendered tree sets `adjustsFontForContentSizeCategory`, so a Dynamic Type
 * change resizes them — and UIKit has no way to tell Compose that the fitting size moved.
 */
@Composable
private fun ObserveContentSizeCategory(key: Any, onChanged: () -> Unit) {
    val currentOnChanged by rememberUpdatedState(onChanged)
    DisposableEffect(key) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIContentSizeCategoryDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> currentOnChanged() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
}

/**
 * A built, measured ad, ready to hand to `UIKitView`.
 *
 * Building the tree here rather than in the `UIKitView` factory is what makes the height available
 * to the modifier in the same composition: the factory only runs once Compose is already laying the
 * node out, by which point the height has to be known.
 */
private class PreparedNativeAd(
    val host: IosNativeAdHostView,
    val height: Double,
) : RememberObserver {
    override fun onRemembered() = Unit
    // Releasing twice is harmless — `IosNativeHostRelease` is idempotent — and one of these two
    // always has to happen: a prepared ad that Compose abandons before the factory runs would
    // otherwise keep its `GADNativeAdView` and the ad bound to it alive.
    override fun onForgotten() = host.releaseHost()
    override fun onAbandoned() = host.releaseHost()
}

/** Builds the ad's view tree and measures it, detached, at the width Compose is offering. */
private fun prepareNativeAd(
    placementId: String,
    nativeAd: GoogleMobileAds.GADNativeAd,
    layout: AdLayout,
    density: androidx.compose.ui.unit.Density,
    width: Double,
    fillsHost: Boolean,
): PreparedNativeAd {
    val nativeView = GADNativeAdView()
    nativeView.translatesAutoresizingMaskIntoConstraints = false
    val content = IosNativeAdRenderer(
        nativeAd = nativeAd,
        nativeView = nativeView,
        density = density,
    ).render(layout.root)
    nativeView.addSubview(content)
    content.leadingAnchor.constraintEqualToAnchor(nativeView.leadingAnchor).active = true
    content.trailingAnchor.constraintEqualToAnchor(nativeView.trailingAnchor).active = true
    content.topAnchor.constraintEqualToAnchor(nativeView.topAnchor).active = true
    val host = IosNativeAdHostView(
        placementId = placementId,
        nativeView = nativeView,
        content = content,
        nativeAd = nativeAd,
        surfaceArgb = resolveNativeAdSurfaceArgb(layout.root),
        fillsHost = fillsHost,
    )
    return PreparedNativeAd(host = host, height = host.measureDetachedHeight(width))
}

/** Clamps a measured height into the bounds the caller's constraints allow. */
private fun Double.coerceIntoHostBounds(minHeight: Double, maxHeight: Double?): Double {
    if (!isFinite() || this <= 0.0) return minHeight
    val ceiling = maxHeight?.takeIf { it.isFinite() && it > 0.0 }
    val floor = minHeight.takeIf { it.isFinite() && it > 0.0 }
        ?.let { low -> ceiling?.let { low.coerceAtMost(it) } ?: low }
        ?: 0.0
    return if (ceiling == null) coerceAtLeast(floor) else coerceIn(floor, ceiling)
}

internal fun isNativeEventForLease(
    placementId: String,
    adInstanceId: String?,
    event: AdEvent,
): Boolean = adInstanceId != null && event.placementId == placementId && event.nativeAdInstanceIdOrNull() == adInstanceId

private fun AdEvent.nativeAdInstanceIdOrNull(): String? = when (this) {
    is AdEvent.Impression -> adInstanceId
    is AdEvent.Clicked -> adInstanceId
    is AdEvent.Paid -> adInstanceId
    is AdEvent.VideoStarted -> adInstanceId
    is AdEvent.VideoPlayed -> adInstanceId
    is AdEvent.VideoPaused -> adInstanceId
    is AdEvent.VideoEnded -> adInstanceId
    is AdEvent.VideoMuted -> adInstanceId
    else -> null
}

private fun NativeAdSlotState?.canRenderNativeAd(): Boolean =
    this is NativeAdSlotState.Ready || this is NativeAdSlotState.Retained || this is NativeAdSlotState.Mounted

private var nextIosRendererId: Long = 0
