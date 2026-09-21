package dev.avinya.ads

import dev.avinya.ads.internal.BannerCore
import dev.avinya.ads.internal.BannerPlatform
import dev.avinya.ads.internal.suspendSingleShot
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
import kotlinx.coroutines.withContext

/**
 * Creates and drives the `AdView` for one load, so host tests can pin the terminal-callback
 * ownership rules without a real `Activity`.
 *
 * `AdView` is a final `FrameLayout` from the GMA aar: it cannot be constructed off-device and
 * cannot be subclassed. The seam mirrors [dev.avinya.ads.nativead.AndroidNativeAdLoaderFacade],
 * which exists for the same reason on the native side.
 */
internal interface AndroidBannerAdViewFacade {
    fun create(activity: android.app.Activity): AdView
    fun loadAd(adView: AdView, request: BannerAdRequest, callback: AdLoadCallback<BannerAd>)
    fun unregisterBannerAd(adView: AdView): BannerAd?
    fun destroy(adView: AdView)
}

internal object GmaAndroidBannerAdViewFacade : AndroidBannerAdViewFacade {
    override fun create(activity: android.app.Activity): AdView = AdView(activity)
    override fun loadAd(adView: AdView, request: BannerAdRequest, callback: AdLoadCallback<BannerAd>): Unit =
        adView.loadAd(request, callback)
    override fun unregisterBannerAd(adView: AdView): BannerAd? = adView.unregisterBannerAd()
    override fun destroy(adView: AdView): Unit = adView.destroy()
}

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
    private val activityProvider: () -> android.app.Activity? = { null },
    private val adViews: AndroidBannerAdViewFacade = GmaAndroidBannerAdViewFacade,
    // Every view-touching statement in a GMA terminal callback goes through this. Default is
    // the real Main hop; host tests substitute a queue they step by hand, because the Android
    // stubs in a JVM test make `myLooper() == getMainLooper()` trivially true and would hide
    // the very dispatch this exists to prove.
    private val runOnMain: (() -> Unit) -> Unit = ::defaultRunOnMain
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
        suspendSingleShot { continuation ->
            val activity = activityProvider()
            if (activity == null) {
                continuation.resume(
                    AdAttemptResult.Failure(AdError.message("No current Android Activity."))
                )
                return@suspendSingleShot
            }
            val mergedOptions = requestOptions.withCollapsible(sizePolicy)
            val request = BannerAdRequest.Builder(placement.androidAdUnitId, size)
                .applyOptions(mergedOptions)
                .build()
            val adView = adViews.create(activity)
            // Exactly one terminal callback owns this AdView. GMA delivers its callbacks on a
            // background pool and can deliver TWO terminal callbacks for one request (#45), so
            // both the claim and every view call it guards have to happen on one thread —
            // claiming on the callback thread and then hopping would let a second callback
            // observe an unclaimed view and reach the same statements. Main is that thread, so
            // the whole settle transaction is posted rather than just its cleanup.
            val ownership = BannerLoadOwnership(adView, adViews)
            continuation.invokeOnCancellation { runOnMain { ownership.destroyIfUnclaimed() } }
            adViews.loadAd(adView, request, object : AdLoadCallback<BannerAd> {
                override fun onAdLoaded(ad: BannerAd) = runOnMain {
                    // Main-confined from here. A second onAdLoaded, or a failure arriving after
                    // this one won, finds the view claimed and must not touch it: it is the live
                    // banner BannerCore owns by then.
                    if (!ownership.claim()) return@runOnMain
                    if (!continuation.isActive) {
                        ownership.destroyClaimed()
                        return@runOnMain
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
                    // unregisterBannerAd() and destroy() are both @MainThread in the Next-Gen
                    // reference, which is why this whole block is posted rather than run inline.
                    val detachedAd = adViews.unregisterBannerAd(adView) ?: ad
                    // Capture response info HERE, on Main, because BannerCore resumes on whatever
                    // dispatcher its caller used and reading it there put a GMA access on an
                    // arbitrary thread (CLAUDE.md invariant #5). Response info is fixed once the
                    // ad is loaded, so a snapshot loses nothing.
                    val loaded = AndroidLoadedBanner(
                        adView,
                        detachedAd,
                        detachedAd.getResponseInfo().toCommon(),
                        adViews,
                        runOnMain,
                    )
                    // resume() hands ownership to BannerCore; onUndelivered covers the waiter
                    // being cancelled while this value is in flight. The claim above is what
                    // makes this single-shot: GMA can deliver terminal callbacks on two threads
                    // at once, and the loser of a bare `if (isActive) resume(...)` throws
                    // `Already resumed` on the SDK's own thread, killing the process (#45).
                    continuation.resume(AdAttemptResult.Success(loaded)) { loaded.destroy() }
                }

                override fun onAdFailedToLoad(adError: LoadAdError) = runOnMain {
                    // Destroy only as the callback that claimed this load. If onAdLoaded already
                    // won, this AdView is the live banner BannerCore now owns; if the load was
                    // cancelled, invokeOnCancellation destroyed it.
                    if (!ownership.claim()) return@runOnMain
                    continuation.resume(AdAttemptResult.Failure(adError.toAdError()))
                    ownership.destroyClaimed()
                }
            })
        }
    }
}

/**
 * Single-owner gate over one load's `AdView`.
 *
 * Not an atomic: every call is already confined to Main by the `runOnMain` hop around each
 * terminal callback, and confinement is what makes the claim and the view calls it guards
 * indivisible. An atomic would make the claim safe and still leave the statements after it
 * racing a second callback.
 */
private class BannerLoadOwnership(
    private val adView: AdView,
    private val adViews: AndroidBannerAdViewFacade,
) {
    private var claimed = false
    private var destroyed = false

    /** True only for the first caller; every later terminal callback must return. */
    fun claim(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }

    /** Destroys the view only if no terminal callback took it — the cancellation path. */
    fun destroyIfUnclaimed() {
        if (claimed) return
        claimed = true
        destroyClaimed()
    }

    fun destroyClaimed() {
        if (destroyed) return
        destroyed = true
        adViews.destroy(adView)
    }
}

internal data class AndroidLoadedBanner(
    val view: AdView,
    val ad: BannerAd,
    /** Snapshotted on Main at load time — see the capture site in `loadBanner`. */
    val responseInfo: AdResponseInfo?,
    private val adViews: AndroidBannerAdViewFacade = GmaAndroidBannerAdViewFacade,
    private val runOnMain: (() -> Unit) -> Unit = ::defaultRunOnMain,
) {
    fun destroy() {
        runOnMain {
            adViews.unregisterBannerAd(view)
            adViews.destroy(view)
            ad.destroy()
        }
    }
}

/**
 * Runs [block] on Main: inline when already there, posted otherwise.
 *
 * Inline matters beyond convention. `BannerCore` swaps banners on Main, so a plain `post` from a
 * Main caller would let the swap complete before the old ad's teardown ran.
 */
private fun defaultRunOnMain(block: () -> Unit) {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
        block()
    } else {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
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
