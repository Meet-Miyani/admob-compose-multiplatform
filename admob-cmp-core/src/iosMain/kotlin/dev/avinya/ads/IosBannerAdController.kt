@file:OptIn(ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADAdSize
import GoogleMobileAds.GADBannerView
import GoogleMobileAds.GADBannerViewDelegateProtocol
import dev.avinya.ads.internal.BannerCore
import dev.avinya.ads.internal.BannerPlatform
import dev.avinya.ads.internal.suspendSingleShot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.cinterop.CValue
import platform.Foundation.NSError
import platform.Foundation.NSRecursiveLock
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import kotlin.native.ref.WeakReference
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * iOS banner controller. All policy lives in [BannerCore]; this class implements only the
 * SDK-touching primitives of [BannerPlatform].
 *
 * The handle type is [IosLoadedBanner] (view + delegate) rather than a bare `GADBannerView`:
 * `GADBannerView.delegate` is weak, so a strong Kotlin-side reference must outlive the view
 * (CLAUDE.md invariant #4). Carrying the delegate inside the handle means "the core retains
 * the banner" already implies "the delegate is alive", and the core never has to know
 * delegates exist. Delegate creation, the assign-before-loadRequest ordering and the
 * [activeLoad] in-flight registry all stay in this file for the same reason.
 */
internal class IosBannerAdController internal constructor(
    override val placement: AdPlacement,
    globalEvents: MutableSharedFlow<AdEvent>,
    private val adRequestBlockedError: () -> AdError?,
    // Nullable by contract: the core, not the platform, owns the failure policy. Reads the
    // key window (correct in split view / Slide Over), never UIScreen.mainScreen.
    private val viewportWidthProvider: () -> Int? = { keyWindowWidthDp() }
) : BannerAdController, BannerPlatform<IosLoadedBanner, CValue<GADAdSize>> {

    private val stateLock = NSRecursiveLock()
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val core = BannerCore(placement, this, globalEvents)

    // In-flight load registry. Stays iOS-side: an in-flight view+delegate must remain
    // strongly retained until its SDK callback settles, even after the core has invalidated
    // the generation via detach()/clear(). Each attempt drains its own entry in the finally
    // block below, so this cannot outlive the attempt that created it.
    private var activeLoad: IosBannerLoad? = null

    override val loadState: StateFlow<AdLoadState> get() = core.loadState
    override val events: SharedFlow<AdEvent> get() = core.events

    internal fun currentView(): GADBannerView? = core.currentBanner()?.view

    internal fun attach() = core.attach()
    internal fun detach() = core.detach()

    /** Records container geometry without loading — see [BannerCore.registerGeometry]. */
    internal fun registerGeometry(
        geometry: BannerGeometry,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions
    ) = core.registerGeometry(geometry, sizePolicy, requestOptions)

    override suspend fun load(
        geometry: BannerGeometry?,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions
    ): AdLoadState = core.load(geometry, sizePolicy, requestOptions, adRequestBlockedError)

    override suspend fun refresh(): AdLoadState = core.refresh(adRequestBlockedError)

    override fun clear() = core.clear()

    internal fun emit(event: AdEvent) = core.emitPlatformEvent(event)

    // --- BannerPlatform ---

    override fun <T> withStateLock(block: () -> T): T = locked(block)

    override fun fallbackWidthDp(): Int? = viewportWidthProvider()?.takeIf { it > 0 }

    override fun resolveSize(sizePolicy: AdSizePolicy, widthDp: Int): CValue<GADAdSize> =
        sizePolicy.toIOSAdSize(widthDp.coerceAtLeast(1))

    override fun destroy(banner: IosLoadedBanner) = teardownBanner(banner.view)

    // Pure field read of a value snapshotted on Main at load time, so this is safe to call
    // from whatever dispatcher BannerCore's caller resumed on.
    override fun responseInfo(banner: IosLoadedBanner): AdResponseInfo? = banner.responseInfo

    override suspend fun loadBanner(
        size: CValue<GADAdSize>,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions,
        requiredGeneration: Long
    ): AdAttemptResult<IosLoadedBanner> = withContext(Dispatchers.Main.immediate) {
        var attempt: IosBannerLoad? = null
        try {
            val result = suspendSingleShot<AdAttemptResult<IosLoadedBanner>> { continuation ->
                val banner = GADBannerView(size)
                banner.adUnitID = placement.iosAdUnitId
                val windowScene = UIApplication.sharedApplication.connectedScenes
                    .filterIsInstance<UIWindowScene>()
                    .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
                    ?: UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull()
                val rootVC = (windowScene?.keyWindow
                    ?: windowScene?.windows?.filterIsInstance<UIWindow>()?.firstOrNull())
                    ?.rootViewController
                banner.rootViewController = rootVC ?: topViewController()
                lateinit var bannerDelegate: BannerDelegate
                bannerDelegate = BannerDelegate(
                    onLoaded = {
                        // Capture response info HERE, on Main. GADBannerView is a UIView, and
                        // BannerCore resumes on whatever dispatcher its caller used — reading
                        // `.responseInfo` there was a UIKit property access off the main
                        // thread (CLAUDE.md invariant #5). It is fixed once loaded, so
                        // snapshotting it loses nothing.
                        continuation.resume(
                            AdAttemptResult.Success(
                                IosLoadedBanner(banner, bannerDelegate, banner.responseInfo?.toCommon())
                            )
                        )
                    },
                    onFailedToLoad = { error ->
                        continuation.resume(AdAttemptResult.Failure(error.toAdError()))
                    },
                    onImpression = { emit(AdEvent.Impression(placement.id)) },
                    onClicked = { emit(AdEvent.Clicked(placement.id)) }
                )
                val load = IosBannerLoad(banner, bannerDelegate)
                attempt = load
                val accepted = locked {
                    if (!core.isCurrentGeneration(requiredGeneration)) false else {
                        activeLoad = load
                        true
                    }
                }
                if (accepted) {
                    continuation.invokeOnCancellation {
                        retireActiveLoad(load)?.let(::teardownBanner)
                    }
                    if (!continuation.isActive) return@suspendSingleShot
                    // MUST precede loadRequest: GADBannerView.delegate is weak and GMA reads
                    // it during the load (CLAUDE.md invariant #4).
                    banner.delegate = bannerDelegate
                    val weakBanner = WeakReference(banner)
                    banner.paidEventHandler = { value ->
                        val strongBanner = weakBanner.value
                        val adValue = value?.toCommon()
                        if (strongBanner != null && adValue != null) {
                            emit(AdEvent.Paid(placement.id, PaidEvent(placement.id, adValue, strongBanner.responseInfo?.toCommon())))
                        }
                    }
                    banner.loadRequest(requestOptions.withCollapsible(sizePolicy).toGADRequest())
                } else {
                    teardownBanner(banner)
                    continuation.resume(AdAttemptResult.Failure(AdError.message("Banner load was cleared.")))
                }
            }
            if (result is AdAttemptResult.Failure) {
                attempt?.let(::retireActiveLoad)?.let(::teardownBanner)
            }
            result
        } finally {
            attempt?.let(::clearActiveLoad)
        }
    }

    private fun clearActiveLoad(load: IosBannerLoad) {
        locked { if (activeLoad === load) activeLoad = null }
    }

    private fun retireActiveLoad(load: IosBannerLoad): GADBannerView? = locked {
        if (activeLoad !== load) return@locked null
        activeLoad = null
        load.view
    }

    private fun teardownBanner(view: GADBannerView) {
        // Ownership is always removed under stateLock before reaching this boundary. Cancellation
        // may call it from any thread, so schedule only the physical GMA/UIKit work on Main.
        teardownScope.launch {
            view.delegate = null
            view.paidEventHandler = null
            view.removeFromSuperview()
        }
    }

    private inline fun <T> locked(block: () -> T): T {
        stateLock.lock()
        try {
            return block()
        } finally {
            stateLock.unlock()
        }
    }

    private data class IosBannerLoad(val view: GADBannerView, val delegate: BannerDelegate)
}

/** Banner handle: the view plus the strong delegate reference it needs to outlive it. */
internal data class IosLoadedBanner(
    val view: GADBannerView,
    val delegate: BannerDelegate,
    /** Snapshotted on Main at load time — see the capture site in `loadBanner`. */
    val responseInfo: AdResponseInfo?,
)

internal class BannerDelegate(
    private val onLoaded: () -> Unit,
    private val onFailedToLoad: (NSError) -> Unit,
    private val onImpression: () -> Unit,
    private val onClicked: () -> Unit
) : NSObject(), GADBannerViewDelegateProtocol {
    override fun bannerViewDidReceiveAd(bannerView: GADBannerView) { onLoaded() }
    override fun bannerView(bannerView: GADBannerView, didFailToReceiveAdWithError: NSError) { onFailedToLoad(didFailToReceiveAdWithError) }
    override fun bannerViewDidRecordImpression(bannerView: GADBannerView) { onImpression() }
    override fun bannerViewDidRecordClick(bannerView: GADBannerView) { onClicked() }
}

@InternalAdMobCmpApi
public fun BannerAdController.currentIosBannerView(): GADBannerView? =
    (this as? IosBannerAdController)?.currentView()

@InternalAdMobCmpApi
public fun BannerAdController.attachIosBanner(): Unit {
    (this as? IosBannerAdController)?.attach()
}

@InternalAdMobCmpApi
public fun BannerAdController.detachIosBanner(): Unit {
    (this as? IosBannerAdController)?.detach()
}

@InternalAdMobCmpApi
public fun BannerAdController.registerIosBannerGeometry(
    geometry: BannerGeometry,
    sizePolicy: AdSizePolicy,
    requestOptions: AdRequestOptions
): Unit {
    (this as? IosBannerAdController)?.registerGeometry(geometry, sizePolicy, requestOptions)
}

private fun AdRequestOptions.withCollapsible(sizePolicy: AdSizePolicy): AdRequestOptions {
    val collapsible = when (sizePolicy) {
        is AdSizePolicy.LargeAnchoredAdaptive -> sizePolicy.collapsible
        else -> null
    } ?: return this
    val key = "collapsible"
    val value = when (collapsible) {
        CollapsiblePlacement.Top -> "top"
        CollapsiblePlacement.Bottom -> "bottom"
    }
    if (googleExtras.containsKey(key)) return this
    val merged = googleExtras + (key to value)
    return copy(googleExtras = merged)
}
