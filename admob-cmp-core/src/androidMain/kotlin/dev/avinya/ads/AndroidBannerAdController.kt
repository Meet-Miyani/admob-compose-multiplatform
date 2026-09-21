package dev.avinya.ads

import dev.avinya.ads.internal.BannerCore
import dev.avinya.ads.internal.BannerPlatform
import dev.avinya.ads.internal.tryResumeOnce
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Android banner controller. All policy — generation, attachment refcounting, the load
 * mutex, the swap-on-success and the resolved request — lives in [BannerCore]; this class
 * implements only the SDK-touching primitives of [BannerPlatform] plus a thin
 * [BannerAdController] delegation shell.
 */
internal class AndroidBannerAdController internal constructor(
    override val placement: AdPlacement,
    globalEvents: MutableSharedFlow<AdEvent>,
    private val adRequestBlockedError: () -> AdError?,
    private val activityProvider: () -> android.app.Activity? = { null }
) : BannerAdController, BannerPlatform<AndroidLoadedBanner, AdSize> {

    private val stateLock = Any()
    private val core = BannerCore(placement, this, globalEvents)

    override val loadState: StateFlow<AdLoadState> get() = core.loadState
    override val events: SharedFlow<AdEvent> get() = core.events

    internal fun currentAd(): BannerAd? = core.currentBanner()?.ad
    internal fun currentView(): AdView? = core.currentBanner()?.view

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

    // --- BannerPlatform ---

    override fun <T> withStateLock(block: () -> T): T = synchronized(stateLock) { block() }

    // Nullability is meaningful: with no current Activity there is no width to resolve, and
    // the core fails the load rather than guessing one.
    override fun fallbackWidthDp(): Int? = activityProvider()?.screenWidthDp()?.coerceAtLeast(1)

    override fun resolveSize(sizePolicy: AdSizePolicy, widthDp: Int): AdSize {
        // Reached only after host geometry or fallbackWidthDp already produced a width, so a
        // null Activity here is a genuine edge case rather than the common path. Fall back to
        // the SDK's fixed size instead of throwing.
        val activity = activityProvider() ?: return AdSize(widthDp, 50)
        return sizePolicy.toAndroidAdSize(activity, widthDp)
    }

    override fun destroy(banner: AndroidLoadedBanner) = banner.destroy()

    // Pure field read of a value snapshotted on Main at load time, so this is safe to call
    // from whatever dispatcher BannerCore's caller resumed on.
    override fun responseInfo(banner: AndroidLoadedBanner): AdResponseInfo? = banner.responseInfo

    override suspend fun loadBanner(
        size: AdSize,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions,
        requiredGeneration: Long
    ): AdAttemptResult<AndroidLoadedBanner> = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val activity = activityProvider()
            if (activity == null) {
                continuation.tryResumeOnce(
                    AdAttemptResult.Failure(AdError.message("No current Android Activity."))
                )
                return@suspendCancellableCoroutine
            }
            val mergedOptions = requestOptions.withCollapsible(sizePolicy)
            val request = BannerAdRequest.Builder(placement.androidAdUnitId, size)
                .applyOptions(mergedOptions)
                .build()
            val adView = AdView(activity)
            continuation.invokeOnCancellation { adView.destroyOnMain() }
            adView.loadAd(request, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) {
                    if (!continuation.isActive) {
                        adView.destroy()
                        return
                    }
                    ad.adEventCallback = object : BannerAdEventCallback {
                        override fun onAdImpression() {
                            core.emitPlatformEvent(AdEvent.Impression(placement.id))
                        }

                        override fun onAdClicked() {
                            core.emitPlatformEvent(AdEvent.Clicked(placement.id))
                        }

                        override fun onAdPaid(value: com.google.android.libraries.ads.mobile.sdk.common.AdValue) {
                            core.emitPlatformEvent(
                                AdEvent.Paid(
                                    placement.id,
                                    PaidEvent(placement.id, value.toCommon(), ad.getResponseInfo().toCommon())
                                )
                            )
                        }
                    }
                    // The core publishes Loaded/_loadState after the banner is swapped, so the
                    // composable mirrors the NEW ad.
                    // AdView.loadAd automatically registers before this callback. Detach the
                    // loaded ad immediately so the SDK's server-driven refresh loop is cancelled;
                    // BannerCore remains the single owner of refresh/load-once semantics.
                    val detachedAd = adView.unregisterBannerAd() ?: ad
                    // Capture response info HERE, on Main. This callback is Main-confined, but
                    // BannerCore resumes on whatever dispatcher its caller used, so reading it
                    // there put a GMA access on an arbitrary thread (CLAUDE.md invariant #5).
                    // Response info is fixed once the ad is loaded, so a snapshot loses nothing.
                    val loaded = AndroidLoadedBanner(adView, detachedAd, detachedAd.getResponseInfo().toCommon())
                    // Atomic single-shot resume. GMA can deliver its terminal callbacks on two
                    // threads at once; a bare `if (isActive) resume(...)` lets both through and the
                    // loser throws `IllegalStateException: Already resumed` on the SDK's thread,
                    // killing the process. The reported crash came from this same callback's
                    // `onAdFailedToLoad` branch below, which likewise claims the continuation
                    // instead of reading `isActive` first.
                    if (!continuation.tryResumeOnce(AdAttemptResult.Success(loaded)) { _, _, _ -> loaded.destroy() }) {
                        // Lost the race. `tryResume` does not run onCancellation for an
                        // already-resumed or already-cancelled continuation, so nothing else will
                        // release this banner's AdView/native ad.
                        loaded.destroy()
                    }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    adView.destroy()
                    continuation.tryResumeOnce(AdAttemptResult.Failure(adError.toAdError()))
                }
            })
        }
    }
}

internal data class AndroidLoadedBanner(
    val view: AdView,
    val ad: BannerAd,
    /** Snapshotted on Main at load time — see the capture site in `loadBanner`. */
    val responseInfo: AdResponseInfo?,
) {
    fun destroy() {
        val cleanup = {
            view.unregisterBannerAd()
            view.destroy()
            ad.destroy()
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            cleanup()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(cleanup)
        }
    }
}

private fun AdView.destroyOnMain() {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        destroy()
    } else {
        android.os.Handler(android.os.Looper.getMainLooper()).post(::destroy)
    }
}

@InternalAdMobCmpApi
public fun BannerAdController.currentAndroidBannerAd(): BannerAd? =
    (this as? AndroidBannerAdController)?.currentAd()

@InternalAdMobCmpApi
public fun BannerAdController.currentAndroidBannerView(): AdView? =
    (this as? AndroidBannerAdController)?.currentView()

@InternalAdMobCmpApi
public fun BannerAdController.attachAndroidBanner(): Unit {
    (this as? AndroidBannerAdController)?.attach()
}

@InternalAdMobCmpApi
public fun BannerAdController.detachAndroidBanner(): Unit {
    (this as? AndroidBannerAdController)?.detach()
}

@InternalAdMobCmpApi
public fun BannerAdController.registerAndroidBannerGeometry(
    geometry: BannerGeometry,
    sizePolicy: AdSizePolicy,
    requestOptions: AdRequestOptions
): Unit {
    (this as? AndroidBannerAdController)?.registerGeometry(geometry, sizePolicy, requestOptions)
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
