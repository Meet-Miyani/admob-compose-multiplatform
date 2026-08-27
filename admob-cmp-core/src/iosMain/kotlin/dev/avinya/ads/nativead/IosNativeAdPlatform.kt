@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.avinya.ads.nativead

import GoogleMobileAds.GADAdChoicesPosition
import GoogleMobileAds.GADAdLoader
import GoogleMobileAds.GADAdLoaderAdTypeNative
import GoogleMobileAds.GADMediaContent
import GoogleMobileAds.GADMultipleAdsAdLoaderOptions
import GoogleMobileAds.GADNativeAd
import GoogleMobileAds.GADNativeAdDelegateProtocol
import GoogleMobileAds.GADNativeAdImageAdLoaderOptions
import GoogleMobileAds.GADNativeAdLoaderDelegateProtocol
import GoogleMobileAds.GADNativeAdMediaAdLoaderOptions
import GoogleMobileAds.GADNativeAdViewAdOptions
import GoogleMobileAds.GADVideoOptions
import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdResponseInfo
import dev.avinya.ads.INTERNAL_LOAD_ERROR_CODE
import dev.avinya.ads.PaidEvent
import dev.avinya.ads.internal.NativeAdPlatform
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.toAdError
import dev.avinya.ads.toCommon
import dev.avinya.ads.toGADRequest
import dev.avinya.ads.topViewController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSError
import platform.Foundation.NSRecursiveLock
import platform.darwin.NSObject

internal class LoadedNativeAd(
    val ad: GADNativeAd,
    val delegates: List<NSObject>,
    val responseInfo: AdResponseInfo?,
    val mediaInfo: NativeMediaInfo?,
) {
    internal val destroyGate = IosNativeDestroyGate()
}

/** Internal loader seam; its production implementation is the GMA bridge below. */
internal interface IosNativeAdLoaderFacade<A : Any> {
    fun start(
        placement: AdPlacement,
        count: Int,
        multiple: Boolean,
        onAd: (A) -> Unit,
        onError: (AdError) -> Unit,
        onFinish: () -> Unit,
    )
    fun destroy(ad: A)
}

internal data class IosNativeLoadResult<A : Any>(val ads: List<A>, val unfilledError: AdError?)

internal fun <A : Any> IosNativeLoadResult<A>.toAttemptResult(): AdAttemptResult<NativeAdPlatformBatch<A>> =
    if (ads.isEmpty()) {
        AdAttemptResult.Failure(
            unfilledError ?: AdError(
                code = INTERNAL_LOAD_ERROR_CODE,
                message = "Native ad loader completed without filling any requested ad.",
            ),
        )
    } else {
        AdAttemptResult.Success(NativeAdPlatformBatch(ads, unfilledError))
    }

internal class IosNativeDestroyGate {
    private val lock = NSRecursiveLock()
    private var destroyed = false

    fun destroyOnce(block: () -> Unit) {
        val shouldDestroy = run {
            lock.lock()
            try {
                if (destroyed) false else {
                    destroyed = true
                    true
                }
            } finally {
                lock.unlock()
            }
        }
        if (shouldDestroy) block()
    }
}

/**
 * Serialises sequential single-ad requests and retains every callback owner
 * until GMA supplies its terminal finish callback.  Cancellation only
 * invalidates the flight: GADAdLoader has no cancellation API.
 */
internal class IosNativeLoadMachine<A : Any>(private val facade: IosNativeAdLoaderFacade<A>) {
    private val lock = NSRecursiveLock()
    private val active = mutableSetOf<Flight>()
    val activeLoadCount: Int get() = locked { active.size }

    fun load(placement: AdPlacement, count: Int, generation: Long): Deferred<IosNativeLoadResult<A>> {
        require(count > 0) { "Native ad count must be positive." }
        val multiple = placement.nativeOptions.batching == NativeAdBatching.GoogleOnly
        if (multiple) require(count <= 5) { "Google-only native ad batches are capped at 5." }
        val result = CompletableDeferred<IosNativeLoadResult<A>>()
        val flight = Flight(placement, count, multiple, result)
        locked { active += flight }
        result.invokeOnCompletion { cause -> if (cause != null) flight.invalidate() }
        try {
            flight.start()
        } catch (t: Throwable) {
            // facade.start() can throw synchronously -- constructing GADAdLoader, resolving the top
            // view controller, or an ObjC exception surfacing into Kotlin. The flight was already in
            // `active` and may already have accepted ads via onAd, so without this the load count
            // leaked for the process lifetime (throttling all later native capacity), the Deferred
            // never completed, and accepted GADNativeAd objects were retained with no teardown.
            flight.abandon(t)
            throw t
        }
        return result
    }

    fun destroy(ad: A) = facade.destroy(ad)

    private inner class Flight(
        private val placement: AdPlacement,
        private val requested: Int,
        private val multiple: Boolean,
        private val result: CompletableDeferred<IosNativeLoadResult<A>>,
    ) {
        private val ads = mutableListOf<A>()
        private var error: AdError? = null
        private var invalid = false
        private var finished = false

        fun start() = request(if (multiple) requested else 1)
        private fun request(count: Int): Unit {
            facade.start(placement, count, multiple, { ad ->
            val accepted = locked { !invalid && !finished && result.isActive && ads.add(ad) }
            if (!accepted) destroy(ad)
        }, { failure -> locked { if (!invalid && !finished) error = failure } }, {
            val next = locked {
                if (finished) return@locked -1
                if (invalid) { finished = true; active.remove(this); return@locked -1 }
                if (!multiple && error == null && ads.size < requested) 1 else 0
            }
            when (next) {
                1 -> request(1)
                0 -> finish()
            }
            })
        }

        /**
         * Terminal cleanup for a flight whose start threw: exactly-once, and safe to interleave
         * with a late callback from a facade that partially installed itself.
         */
        fun abandon(cause: Throwable) {
            val retired = locked {
                if (finished) return@locked emptyList()
                finished = true
                invalid = true
                active.remove(this)
                ads.toList().also { ads.clear() }
            }
            retired.forEach(::destroy)
            result.completeExceptionally(cause)
        }

        fun invalidate() {
            val retired = locked {
                if (invalid) emptyList() else {
                    invalid = true
                    ads.toList().also { ads.clear() }
                }
            }
            retired.forEach(::destroy)
        }

        private fun finish() {
            val completion = locked {
                if (finished || invalid) return@locked null
                finished = true
                active.remove(this)
                IosNativeLoadResult(ads.toList(), error)
            }
            if (completion != null) result.complete(completion)
        }
    }

    private inline fun <T> locked(block: () -> T): T { lock.lock(); return try { block() } finally { lock.unlock() } }
}

/**
 * Placement attribution for every admitted native ad.
 *
 * Owns the map behind a lock rather than exposing a bare `mutableMapOf`. `load` and `bindEvents` are
 * main-confined, but `destroy` is not suspend and the coordinator invokes it from `Effects.run()` on
 * `Dispatchers.Default` -- so register and remove genuinely ran concurrently. On Kotlin/Native that
 * is a memory-safety hazard, not merely a lost update, and the visible symptom was an event bound
 * with an empty or wrong placement id under load/eviction races.
 *
 * A lock rather than main-confinement, so `destroy` stays non-suspend. That is what lets the
 * coordinator keep performing platform destruction outside its own lock.
 */
internal class NativePlacementRegistry<A : Any> {
    private val lock = NSRecursiveLock()
    private val placements = mutableMapOf<A, String>()

    val size: Int get() = locked { placements.size }

    fun register(ads: List<A>, placementId: String) = locked {
        ads.forEach { ad -> placements[ad] = placementId }
    }

    fun placementOf(ad: A): String? = locked { placements[ad] }

    fun remove(ad: A) {
        locked { placements.remove(ad) }
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }
}

/** Coordinator platform boundary for iOS native-ad sessions. */
internal class IosNativeAdPlatform(
    private val facade: IosNativeAdLoaderFacade<LoadedNativeAd> = GmaIosNativeAdLoaderFacade(),
) : NativeAdPlatform<LoadedNativeAd> {
    private val machine = IosNativeLoadMachine(facade)

    private val placements = NativePlacementRegistry<LoadedNativeAd>()

    override suspend fun load(placement: AdPlacement, count: Int, generation: Long): AdAttemptResult<NativeAdPlatformBatch<LoadedNativeAd>> =
        withContext(Dispatchers.Main.immediate) {
            val batch = machine.load(placement, count, generation).await()
            placements.register(batch.ads, placement.id)
            batch.toAttemptResult()
        }

    override suspend fun bindEvents(ad: LoadedNativeAd, adInstanceId: String, emit: (AdEvent) -> Unit) =
        withContext(Dispatchers.Main.immediate) {
            IosNativeAdOwners.bind(ad, placements.placementOf(ad) ?: "", adInstanceId, emit)
        }

    override fun destroy(ad: LoadedNativeAd) {
        placements.remove(ad)
        machine.destroy(ad)
    }
    override fun responseInfo(ad: LoadedNativeAd): AdResponseInfo? = ad.responseInfo
    override fun mediaInfo(ad: LoadedNativeAd): NativeMediaInfo? = ad.mediaInfo
}

private class GmaIosNativeAdLoaderFacade : IosNativeAdLoaderFacade<LoadedNativeAd> {
    private val lock = NSRecursiveLock()
    private val flights = mutableListOf<GmaFlight>() // GADAdLoader.delegate is weak.

    override fun start(placement: AdPlacement, count: Int, multiple: Boolean, onAd: (LoadedNativeAd) -> Unit, onError: (AdError) -> Unit, onFinish: () -> Unit) {
        lateinit var delegate: GmaDelegate
        delegate = GmaDelegate(placement.id, onAd, onError) {
            locked { flights.removeAll { it.delegate === delegate } }
            onFinish()
        }
        val options = nativeOptions(placement, count, multiple)
        val loader = GADAdLoader(placement.iosAdUnitId, topViewController(), listOf(GADAdLoaderAdTypeNative), options)
        locked { flights += GmaFlight(loader, delegate) }
        loader.delegate = delegate
        loader.loadRequest(placement.requestOptions.toGADRequest())
    }

    override fun destroy(ad: LoadedNativeAd) = ad.destroyGate.destroyOnce { IosNativeAdOwners.teardown(ad) }
    private inline fun <T> locked(block: () -> T): T { lock.lock(); return try { block() } finally { lock.unlock() } }
    private data class GmaFlight(val loader: GADAdLoader, val delegate: GmaDelegate)

    private fun nativeOptions(placement: AdPlacement, count: Int, multiple: Boolean): List<Any> = buildList {
        if (multiple) add(GADMultipleAdsAdLoaderOptions().apply { numberOfAds = count.toLong() })
        add(GADNativeAdMediaAdLoaderOptions().apply { mediaAspectRatio = placement.nativeOptions.mediaAspectRatio.toGADMediaAspectRatio() })
        add(GADNativeAdImageAdLoaderOptions().apply { disableImageLoading = placement.nativeOptions.disableImageLoading; shouldRequestMultipleImages = placement.nativeOptions.requestMultipleImages })
        add(GADVideoOptions().apply { startMuted = placement.nativeOptions.videoOptions.startMuted; customControlsRequested = placement.nativeOptions.videoOptions.customControlsRequested; clickToExpandRequested = placement.nativeOptions.videoOptions.clickToExpandRequested })
        add(GADNativeAdViewAdOptions().apply { preferredAdChoicesPosition = placement.nativeOptions.adChoicesPlacement.toGADAdChoicesPosition() })
    }
}

private class GmaDelegate(
    private val placementId: String,
    private val onAd: (LoadedNativeAd) -> Unit,
    private val onError: (AdError) -> Unit,
    private val onTerminal: () -> Unit,
) : NSObject(), GADNativeAdLoaderDelegateProtocol {
    override fun adLoader(adLoader: GADAdLoader, didReceiveNativeAd: GADNativeAd) {
        val media = didReceiveNativeAd.mediaContent
        onAd(LoadedNativeAd(didReceiveNativeAd, emptyList(), didReceiveNativeAd.responseInfo?.toCommon(), media?.snapshot()))
    }
    override fun adLoader(adLoader: GADAdLoader, didFailToReceiveAdWithError: NSError) { onError(didFailToReceiveAdWithError.toAdError()) }
    override fun adLoaderDidFinishLoading(adLoader: GADAdLoader) = onTerminal()
}

private object IosNativeAdOwners {
    private val lock = NSRecursiveLock()
    private val owners = mutableMapOf<LoadedNativeAd, MutableList<NSObject>>()
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun bind(loaded: LoadedNativeAd, placementId: String, instanceId: String, emit: (AdEvent) -> Unit) {
        val native = SessionNativeDelegate(placementId, instanceId, emit)
        val retained = mutableListOf<NSObject>(native)
        loaded.ad.delegate = native
        loaded.ad.paidEventHandler = { value -> value?.toCommon()?.let { emit(AdEvent.Paid(placementId, PaidEvent(placementId, it, loaded.responseInfo), instanceId)) } }
        loaded.ad.mediaContent?.takeIf { it.hasVideoContent }?.let { media ->
            SessionVideoDelegate(placementId, instanceId, emit).also { video -> media.videoController.delegate = video; retained += video }
        }
        locked { owners[loaded] = retained }
    }
    fun teardown(loaded: LoadedNativeAd) {
        locked { owners.remove(loaded) }
        teardownScope.launch { loaded.ad.paidEventHandler = null; loaded.ad.delegate = null; loaded.ad.mediaContent?.videoController?.delegate = null }
    }
    private inline fun <T> locked(block: () -> T): T { lock.lock(); return try { block() } finally { lock.unlock() } }
}

internal fun NativeMediaAspectRatio.toGADMediaAspectRatio(): GoogleMobileAds.GADMediaAspectRatio = when (this) {
    NativeMediaAspectRatio.Unknown -> GoogleMobileAds.GADMediaAspectRatioUnknown
    NativeMediaAspectRatio.Any -> GoogleMobileAds.GADMediaAspectRatioAny
    NativeMediaAspectRatio.Landscape -> GoogleMobileAds.GADMediaAspectRatioLandscape
    NativeMediaAspectRatio.Portrait -> GoogleMobileAds.GADMediaAspectRatioPortrait
    NativeMediaAspectRatio.Square -> GoogleMobileAds.GADMediaAspectRatioSquare
}

internal fun AdChoicesPlacement.toGADAdChoicesPosition(): GoogleMobileAds.GADAdChoicesPosition = when (this) {
    AdChoicesPlacement.TopLeft -> GADAdChoicesPosition.GADAdChoicesPositionTopLeftCorner
    AdChoicesPlacement.TopRight -> GADAdChoicesPosition.GADAdChoicesPositionTopRightCorner
    AdChoicesPlacement.BottomRight -> GADAdChoicesPosition.GADAdChoicesPositionBottomRightCorner
    AdChoicesPlacement.BottomLeft -> GADAdChoicesPosition.GADAdChoicesPositionBottomLeftCorner
}

private class SessionNativeDelegate(private val placementId: String, private val id: String, private val emit: (AdEvent) -> Unit) : NSObject(), GADNativeAdDelegateProtocol {
    override fun nativeAdDidRecordImpression(nativeAd: GADNativeAd) = emit(AdEvent.Impression(placementId, id))
    override fun nativeAdDidRecordClick(nativeAd: GADNativeAd) = emit(AdEvent.Clicked(placementId, id))
    override fun nativeAdWillPresentScreen(nativeAd: GADNativeAd) {}
    override fun nativeAdWillDismissScreen(nativeAd: GADNativeAd) {}
    override fun nativeAdDidDismissScreen(nativeAd: GADNativeAd) {}
}

private class SessionVideoDelegate(
    private val placementId: String,
    private val adInstanceId: String,
    private val emit: (AdEvent) -> Unit,
) : NSObject(), GoogleMobileAds.GADVideoControllerDelegateProtocol {
    private var started = false
    override fun videoControllerDidPlayVideo(videoController: GoogleMobileAds.GADVideoController) { emit(if (started) AdEvent.VideoPlayed(placementId, adInstanceId) else AdEvent.VideoStarted(placementId, adInstanceId)); started = true }
    override fun videoControllerDidPauseVideo(videoController: GoogleMobileAds.GADVideoController) = emit(AdEvent.VideoPaused(placementId, adInstanceId))
    override fun videoControllerDidEndVideoPlayback(videoController: GoogleMobileAds.GADVideoController) = emit(AdEvent.VideoEnded(placementId, adInstanceId))
    override fun videoControllerDidMuteVideo(videoController: GoogleMobileAds.GADVideoController) = emit(AdEvent.VideoMuted(placementId, true, adInstanceId))
    override fun videoControllerDidUnmuteVideo(videoController: GoogleMobileAds.GADVideoController) = emit(AdEvent.VideoMuted(placementId, false, adInstanceId))
}

private fun GADMediaContent.snapshot() = NativeMediaInfo(aspectRatio.takeIf { it > 0.0 }?.toFloat(), hasVideoContent, duration.takeIf { it > 0f })
