@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.avinya.ads.ui

import GoogleMobileAds.GADNativeAdView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UILayoutPriorityFittingSizeLevel
import platform.UIKit.UILayoutPriorityRequired
import platform.UIKit.UIView
import kotlin.math.roundToInt

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
                    val minHeightPoints = minHeight.value.toDouble().takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                    val maxHeightPoints = maxHeight.value.toDouble().takeIf { it.isFinite() && it > 0.0 }
                    val widthBucket = maxWidth.value.takeIf { it.isFinite() && it > 0f }?.roundToInt()
                    val heightCacheKey = remember(session.key, slotKey, placement.id, layout.identity, widthBucket) {
                        IosNativeAdHeightCacheKey(session.key, slotKey, placement.id, layout.identity, widthBucket)
                    }
                    // A root that asks to fill takes the host's height rather than measuring its
                    // own: see `IosNativeAdHostView.fillsHost`.
                    val rootFillsHost = layout.root.modifier.height == AdLayoutSize.Match && minHeightPoints > 0.0
                    val initialHeight = remember(heightCacheKey, minHeightPoints, maxHeightPoints) {
                        resolveIosNativeAdInitialHeight(
                            cachedHeight = iosNativeAdHeightCache.get(heightCacheKey),
                            minHeight = minHeightPoints,
                            maxHeight = maxHeightPoints,
                        )
                    }
                    var preferredHeight by remember(
                        mountedLease.adInstanceId,
                        layout.identity,
                        resolvedComposeFonts,
                        heightCacheKey,
                    ) {
                        mutableDoubleStateOf(initialHeight)
                    }
                    key(mountedLease.adInstanceId, layout.identity, resolvedComposeFonts, heightCacheKey) {
                        UIKitView(
                            factory = {
                                val nativeView = GADNativeAdView()
                                nativeView.translatesAutoresizingMaskIntoConstraints = false
                                val content = IosNativeAdRenderer(
                                    nativeAd = mountedLease.ad,
                                    nativeView = nativeView,
                                    density = density,
                                ).render(layout.root)
                                nativeView.addSubview(content)
                                content.leadingAnchor.constraintEqualToAnchor(nativeView.leadingAnchor).active = true
                                content.trailingAnchor.constraintEqualToAnchor(nativeView.trailingAnchor).active = true
                                content.topAnchor.constraintEqualToAnchor(nativeView.topAnchor).active = true
                                IosNativeAdHostView(
                                    placementId = placement.id,
                                    nativeView = nativeView,
                                    content = content,
                                    nativeAd = mountedLease.ad,
                                    surfaceArgb = resolveNativeAdSurfaceArgb(layout.root),
                                    minHeight = minHeightPoints,
                                    maxHeight = maxHeightPoints,
                                    fillsHost = rootFillsHost,
                                ) { effectiveHeight ->
                                    iosNativeAdHeightCache.put(heightCacheKey, effectiveHeight)
                                    preferredHeight = effectiveHeight
                                }
                            },
                            onRelease = { it.releaseHost() },
                            // The clip sits OUTSIDE Compose's interop `drawBehind { Clear }`
                            // (`layoutNode.modifier = modifier then platformModifier`), so it
                            // shapes the cut-out itself: the corners are never cleared, and the
                            // app's own pixels survive there instead of the platform backdrop.
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(preferredHeight.dp)
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
private class IosNativeAdHostView(
    /** Diagnostics only — identifies the host in a containment failure report. */
    private val placementId: String,
    private val nativeView: GADNativeAdView,
    private val content: UIView,
    private val nativeAd: GoogleMobileAds.GADNativeAd,
    surfaceArgb: Long?,
    private val minHeight: Double,
    private val maxHeight: Double?,
    /**
     * The layout root asked for [dev.avinya.ads.nativead.layout.AdLayoutSize.Match] height and the
     * host has a bounded one to give it.
     *
     * Nothing else propagates the host's height into the rendered tree: [content] is pinned to
     * three edges and merely *contained* by the fourth, so a `fillMaxSize()` root had no parent
     * height to match and collapsed onto its tallest child. A full-screen layout therefore drew its
     * bottom-aligned content against the bottom of the media rather than the bottom of the page,
     * while Compose still reserved the full height — which is exactly what `AndroidView` gets for
     * free by handing the Compose modifier straight to the host.
     *
     * When this is set the containment constraint becomes an equality and is active from the start,
     * and the measure/report loop below is skipped entirely: the height is already decided by the
     * constraints Compose passed down, so measuring the content would only fight them.
     */
    private val fillsHost: Boolean,
    private val onPreferredHeightChanged: (Double) -> Unit,
) : UIView(frame = kotlinx.cinterop.cValue { }) {
    private var nativeAdRegistered = false
    private var containmentFailureReported = false
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
        // A filling root has to be stretched before the first draw, not at registration time, or
        // the first frame shows the collapsed tree.
        if (fillsHost) containmentConstraint.active = true
    }

    /**
     * Measures on every pass, not just until the ad registers.
     *
     * Registration is one-shot, but the *height* is not: the content can grow after the ad is
     * bound — a Dynamic Type change resizes every label (`adjustsFontForContentSizeCategory`), and
     * an asset can settle at a different intrinsic size than it was measured at. Latching the
     * height at registration left that growth with nowhere to go, so `clipsToBounds` ate it.
     *
     * Convergence rests on the deadband in [resolveNativeAdLayoutSizing], not on the state write
     * being idempotent. Relying on the latter was wrong: Auto Layout rounds a laid-out frame to
     * whole device pixels, so a settled ad measures a fraction of a point taller than it currently
     * is and the "unchanged" write never actually happened. Only a change the deadband admits is
     * reported back.
     */
    override fun layoutSubviews() {
        super.layoutSubviews()
        val (width, currentHeight) = bounds.useContents { size.width to size.height }
        if (!width.isFinite() || width <= 0.0) return
        if (fillsHost) {
            if (currentHeight > 0.0) registerNativeAdOnce()
            return
        }
        val measuredHeight = content.systemLayoutSizeFittingSize(
            targetSize = CGSizeMake(width, 0.0),
            withHorizontalFittingPriority = UILayoutPriorityRequired,
            verticalFittingPriority = UILayoutPriorityFittingSizeLevel,
        ).useContents { height }
        val sizing = resolveNativeAdLayoutSizing(currentHeight, measuredHeight, minHeight, maxHeight)
        // Reported only when [resolveNativeAdLayoutSizing] says the height actually moved. Writing
        // it unconditionally made the deadband that function applies unobservable: Auto Layout
        // rounds a laid-out frame to whole device pixels, so a converged ad measures a third of a
        // point taller than it currently is, that value was written back, Compose resized the
        // interop view, and the next pass measured a third of a point taller again — an unbounded
        // ratchet that ran on every layout pass for as long as the ad stayed on screen.
        if (sizing.shouldUpdateHeight) sizing.effectiveMeasuredHeight?.let(onPreferredHeightChanged)
        if (sizing.shouldRegisterNativeAd) {
            containmentConstraint.active = true
            registerNativeAdOnce()
        }
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

internal data class IosNativeAdLayoutSizing(
    val shouldUpdateHeight: Boolean,
    val shouldRegisterNativeAd: Boolean,
    val effectiveMeasuredHeight: Double?,
)

internal fun resolveNativeAdLayoutSizing(
    currentHeight: Double,
    measuredHeight: Double,
    minHeight: Double = 0.0,
    maxHeight: Double? = null,
): IosNativeAdLayoutSizing {
    if (!measuredHeight.isFinite() || measuredHeight <= 0.0 || !currentHeight.isFinite() || currentHeight <= 0.0) {
        return IosNativeAdLayoutSizing(false, false, null)
    }
    val effectiveMeasuredHeight = constrainIosNativeAdHeight(measuredHeight, minHeight, maxHeight)
    return if (kotlin.math.abs(currentHeight - effectiveMeasuredHeight) > 0.5) {
        IosNativeAdLayoutSizing(true, false, effectiveMeasuredHeight)
    } else {
        IosNativeAdLayoutSizing(false, true, effectiveMeasuredHeight)
    }
}

internal fun resolveIosNativeAdInitialHeight(
    cachedHeight: Double?,
    minHeight: Double,
    maxHeight: Double?,
): Double {
    val candidate = cachedHeight?.takeIf { it.isFinite() && it > 0.0 }
        ?: minHeight.takeIf { it.isFinite() && it > 0.0 }
        ?: IOS_NATIVE_AD_BOOTSTRAP_HEIGHT
    return constrainIosNativeAdHeight(candidate, minHeight, maxHeight)
}

private fun constrainIosNativeAdHeight(
    height: Double,
    minHeight: Double,
    maxHeight: Double?,
): Double {
    val validMaximum = maxHeight?.takeIf { it.isFinite() && it > 0.0 }
    val validMinimum = minHeight.takeIf { it.isFinite() && it > 0.0 }
        ?.let { minimum -> validMaximum?.let { minimum.coerceAtMost(it) } ?: minimum }
        ?: 0.0
    return if (validMaximum == null) {
        height.coerceAtLeast(validMinimum)
    } else {
        height.coerceIn(validMinimum, validMaximum)
    }
}

internal class IosNativeAdHeightCache<K : Any>(private val maxEntries: Int) {
    private val entries = LinkedHashMap<K, Double>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun get(key: K): Double? {
        val height = entries.remove(key) ?: return null
        entries[key] = height
        return height
    }

    fun put(key: K, height: Double) {
        if (!height.isFinite() || height <= 0.0) return
        entries.remove(key)
        entries[key] = height
        while (entries.size > maxEntries) {
            entries.remove(entries.entries.first().key)
        }
    }
}

internal data class IosNativeAdHeightCacheKey(
    val sessionKey: String,
    val slotKey: String,
    val placementId: String,
    val layoutIdentity: String,
    val widthDp: Int?,
)

/** Accessed only from Compose and UIKit callbacks, both on the iOS main thread. */
private val iosNativeAdHeightCache = IosNativeAdHeightCache<IosNativeAdHeightCacheKey>(maxEntries = 32)

private const val IOS_NATIVE_AD_BOOTSTRAP_HEIGHT: Double = 1.0

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
