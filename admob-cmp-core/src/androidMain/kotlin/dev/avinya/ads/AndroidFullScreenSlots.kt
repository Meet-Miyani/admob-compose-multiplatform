package dev.avinya.ads

import android.app.Activity
import android.os.Handler
import android.os.Looper
import dev.avinya.ads.internal.AudioRestoreHandle
import dev.avinya.ads.internal.FullScreenAudioController
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.FullScreenPresentationHandle
import dev.avinya.ads.internal.FullScreenSlotCore
import dev.avinya.ads.internal.RewardDelivery
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.common.Ad
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.FullScreenContentError
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAd
import com.google.android.libraries.ads.mobile.sdk.interstitial.InterstitialAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewarded.OnUserEarnedRewardListener
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardItem
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAd
import com.google.android.libraries.ads.mobile.sdk.rewarded.RewardedAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAd
import com.google.android.libraries.ads.mobile.sdk.rewardedinterstitial.RewardedInterstitialAdEventCallback
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * The global audio state the manager actually applied to GMA at initialization.
 *
 * Android GMA exposes only `setUserMutedApp` / `setUserControlledAppVolume` — there are **no
 * getters** (verified against ads-mobile-sdk 1.3.1). So unlike iOS, this platform cannot read the
 * pre-override state and must reassert the manager's effective configuration instead. `null`
 * means the host never configured that property, in which case GMA's own defaults apply.
 *
 * Consequence worth knowing: a host that calls `MobileAds.setUserMutedApp` directly, outside this
 * SDK, will have that value overwritten the first time a full-screen ad with an audio override is
 * dismissed. Configure audio through [AdConfig.globalRequestConfiguration] instead.
 */
internal fun GlobalRequestConfiguration?.effectiveAudioMuted(): Boolean = this?.appMuted ?: false

/** @see effectiveAudioMuted */
internal fun GlobalRequestConfiguration?.effectiveAudioVolume(): Float =
    (this?.appVolume ?: 1.0f).coerceIn(0f, 1f)

/**
 * @param appliedGlobalConfig the global request configuration accepted by
 *   `AdManager.initialize()`, or `null` before initialization completes. Read at *restore* time,
 *   not construction time, because slots are created before initialization finishes.
 */
internal class AndroidFullScreenAudioController(
    private val appliedGlobalConfig: () -> GlobalRequestConfiguration?
) : FullScreenAudioController {
    override suspend fun applyOverrides(options: FullScreenAdOptions): AudioRestoreHandle? {
        if (options.audioMuted == null && options.audioVolume == null) return null
        options.audioMuted?.let { MobileAds.setUserMutedApp(it) }
        options.audioVolume?.let { MobileAds.setUserControlledAppVolume(it.coerceIn(0f, 1f)) }
        return AudioRestoreHandle {
            audioRestoreOnMain {
                // Read INSIDE the hop: the manager's applied identity is a non-volatile field
                // written on Main, and restore() can be invoked from a GMA callback thread. On
                // Main both the write and this read are same-thread, so no volatile is needed.
                //
                // Restoring the manager's configured state, NOT hardcoded unmuted/1.0f: the
                // previous code permanently discarded a host's non-default global audio settings
                // after the first ad. Only properties this presentation overrode are reverted.
                val applied = appliedGlobalConfig()
                options.audioMuted?.let { MobileAds.setUserMutedApp(applied.effectiveAudioMuted()) }
                options.audioVolume?.let {
                    MobileAds.setUserControlledAppVolume(applied.effectiveAudioVolume())
                }
            }
        }
    }
}

private val fullScreenAudioHandler = Handler(Looper.getMainLooper())

/**
 * Immediate when already on Main, mirroring [destroyOnMain].
 *
 * Immediacy matters here beyond convention: a plain `post` could be overtaken by the *next*
 * `show()`, whose override is applied on `Dispatchers.Main.immediate`, leaving the restore to land
 * after it and revert the new presentation's audio.
 */
private fun audioRestoreOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        fullScreenAudioHandler.post(block)
    }
}

internal class AndroidInterstitialSlot(
    placement: AdPlacement,
    private val activityProvider: () -> Activity?,
    globalEvents: MutableSharedFlow<AdEvent>,
    adRequestBlockedError: () -> AdError?,
    onPresentationChanged: (Int) -> Unit,
    arbiter: FullScreenPresentationArbiter,
    audioController: FullScreenAudioController
) : FullScreenSlotCore<InterstitialAd>(
    placement,
    globalEvents,
    adRequestBlockedError,
    onPresentationChanged = onPresentationChanged,
    arbiter = arbiter,
    audioController = audioController
), InterstitialAdController {

    override suspend fun loadAd(requestOptions: AdRequestOptions): AdAttemptResult<InterstitialAd> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                InterstitialAd.load(requestOptions.toAndroidAdRequest(placement.androidAdUnitId), object : AdLoadCallback<InterstitialAd> {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        continuation.resumeLoadedAd(ad)
                    }
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        if (continuation.isActive) continuation.resume(AdAttemptResult.Failure(adError.toAdError()))
                    }
                })
            }
        }

    override suspend fun presentAd(
        loaded: InterstitialAd,
        options: FullScreenAdOptions,
        presentation: FullScreenPresentationHandle,
        rewardDelivery: RewardDelivery?
    ): AdShowResult = withContext(Dispatchers.Main.immediate) {
        val activity = activityProvider()
            ?: return@withContext AdShowResult.Failed(AdError.message("No current Android Activity."))
        suspendCancellableCoroutine<AdShowResult> { continuation ->
            continuation.invokeOnCancellation { presentation.closeIfCoreOwned() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            loaded.setImmersiveMode(options.immersiveMode)
            loaded.adEventCallback = object : InterstitialAdEventCallback {
                override fun onAdShowedFullScreenContent() = emit(AdEvent.OpenedFullScreen(placement.id))
                override fun onAdImpression() = emit(AdEvent.Impression(placement.id))
                override fun onAdClicked() = emit(AdEvent.Clicked(placement.id))
                override fun onAdPaid(value: com.google.android.libraries.ads.mobile.sdk.common.AdValue) {
                    emit(AdEvent.Paid(placement.id, PaidEvent(placement.id, value.toCommon(), loaded.getResponseInfo().toCommon())))
                }
                override fun onAdDismissedFullScreenContent() {
                    if (presentation.close(wasShown = true)) {
                        emit(AdEvent.ClosedFullScreen(placement.id))
                        if (continuation.isActive) continuation.resume(AdShowResult.Shown)
                    }
                }
                override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                    val adError = error.toAdError()
                    if (presentation.close(wasShown = false)) {
                        emit(AdEvent.ShowFailed(placement.id, adError))
                        if (continuation.isActive) continuation.resume(AdShowResult.Failed(adError))
                    }
                }
            }
            if (presentation.tryHandOffToCallbacks()) loaded.show(activity)
        }
    }


    override fun onAdLoaded(ad: InterstitialAd, requestOptions: AdRequestOptions) {
        (requestOptions.placementId ?: placement.requestOptions.placementId)?.let { ad.placementId = it }
    }

    override fun destroyAd(ad: InterstitialAd) = ad.destroyOnMain()

    override fun getResponseInfo(ad: InterstitialAd): AdResponseInfo? = ad.getResponseInfo().toCommon()

    override fun canPresent(): AdError? = if (activityProvider() != null) null else AdError.message("No current Android Activity.")
}

internal class AndroidRewardedSlot(
    placement: AdPlacement,
    private val activityProvider: () -> Activity?,
    globalEvents: MutableSharedFlow<AdEvent>,
    adRequestBlockedError: () -> AdError?,
    onPresentationChanged: (Int) -> Unit,
    arbiter: FullScreenPresentationArbiter,
    audioController: FullScreenAudioController
) : FullScreenSlotCore<RewardedAd>(
    placement,
    globalEvents,
    adRequestBlockedError,
    onPresentationChanged = onPresentationChanged,
    arbiter = arbiter,
    audioController = audioController
), RewardedAdController {

    override suspend fun show(
        options: FullScreenAdOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult = showRewarded(options, onRewardEarned)

    override fun destroyAfterPresentation(wasShown: Boolean): Boolean = !wasShown

    override suspend fun loadAd(requestOptions: AdRequestOptions): AdAttemptResult<RewardedAd> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                RewardedAd.load(requestOptions.toAndroidAdRequest(placement.androidAdUnitId), object : AdLoadCallback<RewardedAd> {
                    override fun onAdLoaded(ad: RewardedAd) {
                        continuation.resumeLoadedAd(ad)
                    }
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        if (continuation.isActive) continuation.resume(AdAttemptResult.Failure(adError.toAdError()))
                    }
                })
            }
        }

    override suspend fun presentAd(
        loaded: RewardedAd,
        options: FullScreenAdOptions,
        presentation: FullScreenPresentationHandle,
        rewardDelivery: RewardDelivery?
    ): AdShowResult {
        val activity = activityProvider() ?: return AdShowResult.Failed(AdError.message("No current Android Activity."))
        return showRewarded(loaded, options, presentation, rewardDelivery) { ad, listener -> ad.show(activity, listener) }
    }


    override fun onAdLoaded(ad: RewardedAd, requestOptions: AdRequestOptions) {
        (requestOptions.placementId ?: placement.requestOptions.placementId)?.let { ad.placementId = it }
    }

    override fun destroyAd(ad: RewardedAd) = ad.destroyOnMain()

    override fun getResponseInfo(ad: RewardedAd): AdResponseInfo? = ad.getResponseInfo().toCommon()

    override fun canPresent(): AdError? = if (activityProvider() != null) null else AdError.message("No current Android Activity.")
}

internal class AndroidRewardedInterstitialSlot(
    placement: AdPlacement,
    private val activityProvider: () -> Activity?,
    globalEvents: MutableSharedFlow<AdEvent>,
    adRequestBlockedError: () -> AdError?,
    onPresentationChanged: (Int) -> Unit,
    arbiter: FullScreenPresentationArbiter,
    audioController: FullScreenAudioController
) : FullScreenSlotCore<RewardedInterstitialAd>(
    placement,
    globalEvents,
    adRequestBlockedError,
    onPresentationChanged = onPresentationChanged,
    arbiter = arbiter,
    audioController = audioController
), RewardedInterstitialAdController {

    override suspend fun show(
        options: FullScreenAdOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult = showRewarded(options, onRewardEarned)

    override fun destroyAfterPresentation(wasShown: Boolean): Boolean = !wasShown

    override suspend fun loadAd(requestOptions: AdRequestOptions): AdAttemptResult<RewardedInterstitialAd> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                RewardedInterstitialAd.load(requestOptions.toAndroidAdRequest(placement.androidAdUnitId), object : AdLoadCallback<RewardedInterstitialAd> {
                    override fun onAdLoaded(ad: RewardedInterstitialAd) {
                        continuation.resumeLoadedAd(ad)
                    }
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        if (continuation.isActive) continuation.resume(AdAttemptResult.Failure(adError.toAdError()))
                    }
                })
            }
        }

    override suspend fun presentAd(
        loaded: RewardedInterstitialAd,
        options: FullScreenAdOptions,
        presentation: FullScreenPresentationHandle,
        rewardDelivery: RewardDelivery?
    ): AdShowResult {
        val activity = activityProvider() ?: return AdShowResult.Failed(AdError.message("No current Android Activity."))
        return showRewarded(loaded, options, presentation, rewardDelivery) { ad, listener -> ad.show(activity, listener) }
    }


    override fun onAdLoaded(ad: RewardedInterstitialAd, requestOptions: AdRequestOptions) {
        (requestOptions.placementId ?: placement.requestOptions.placementId)?.let { ad.placementId = it }
    }

    override fun destroyAd(ad: RewardedInterstitialAd) = ad.destroyOnMain()

    override fun getResponseInfo(ad: RewardedInterstitialAd): AdResponseInfo? = ad.getResponseInfo().toCommon()

    override fun canPresent(): AdError? = if (activityProvider() != null) null else AdError.message("No current Android Activity.")
}

@OptIn(ExperimentalTime::class)
internal class AndroidAppOpenSlot(
    placement: AdPlacement,
    private val activityProvider: () -> Activity?,
    globalEvents: MutableSharedFlow<AdEvent>,
    adRequestBlockedError: () -> AdError?,
    onPresentationChanged: (Int) -> Unit,
    arbiter: FullScreenPresentationArbiter,
    audioController: FullScreenAudioController
) : FullScreenSlotCore<AppOpenAd>(
    placement,
    globalEvents,
    adRequestBlockedError,
    onPresentationChanged = onPresentationChanged,
    arbiter = arbiter,
    audioController = audioController
), AppOpenAdController {

    override fun ttl(): Duration = placement.cachePolicy.expirationPolicy.appOpenTtl

    override suspend fun loadAd(requestOptions: AdRequestOptions): AdAttemptResult<AppOpenAd> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                AppOpenAd.load(requestOptions.toAndroidAdRequest(placement.androidAdUnitId), object : AdLoadCallback<AppOpenAd> {
                    override fun onAdLoaded(ad: AppOpenAd) {
                        continuation.resumeLoadedAd(ad)
                    }
                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        if (continuation.isActive) continuation.resume(AdAttemptResult.Failure(adError.toAdError()))
                    }
                })
            }
        }

    override suspend fun presentAd(
        loaded: AppOpenAd,
        options: FullScreenAdOptions,
        presentation: FullScreenPresentationHandle,
        rewardDelivery: RewardDelivery?
    ): AdShowResult = withContext(Dispatchers.Main.immediate) {
        val activity = activityProvider()
            ?: return@withContext AdShowResult.Failed(AdError.message("No current Android Activity."))
        suspendCancellableCoroutine<AdShowResult> { continuation ->
            continuation.invokeOnCancellation { presentation.closeIfCoreOwned() }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            loaded.adEventCallback = object : AppOpenAdEventCallback {
                override fun onAdShowedFullScreenContent() = emit(AdEvent.OpenedFullScreen(placement.id))
                override fun onAdImpression() = emit(AdEvent.Impression(placement.id))
                override fun onAdClicked() = emit(AdEvent.Clicked(placement.id))
                override fun onAdPaid(value: com.google.android.libraries.ads.mobile.sdk.common.AdValue) {
                    emit(AdEvent.Paid(placement.id, PaidEvent(placement.id, value.toCommon(), loaded.getResponseInfo().toCommon())))
                }
                override fun onAdDismissedFullScreenContent() {
                    if (presentation.close(wasShown = true)) {
                        emit(AdEvent.ClosedFullScreen(placement.id))
                        if (continuation.isActive) continuation.resume(AdShowResult.Shown)
                    }
                }
                override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                    val adError = error.toAdError()
                    if (presentation.close(wasShown = false)) {
                        emit(AdEvent.ShowFailed(placement.id, adError))
                        if (continuation.isActive) continuation.resume(AdShowResult.Failed(adError))
                    }
                }
            }
            if (presentation.tryHandOffToCallbacks()) loaded.show(activity)
        }
    }


    override fun onAdLoaded(ad: AppOpenAd, requestOptions: AdRequestOptions) {
        (requestOptions.placementId ?: placement.requestOptions.placementId)?.let { ad.placementId = it }
    }

    override fun destroyAd(ad: AppOpenAd) = ad.destroyOnMain()

    override fun getResponseInfo(ad: AppOpenAd): AdResponseInfo? = ad.getResponseInfo().toCommon()

    override fun canPresent(): AdError? = if (activityProvider() != null) null else AdError.message("No current Android Activity.")
}

private suspend fun <T : Ad> FullScreenSlotCore<T>.showRewarded(
    loaded: T,
    options: FullScreenAdOptions,
    presentation: FullScreenPresentationHandle,
    rewardDelivery: RewardDelivery?,
    show: (T, OnUserEarnedRewardListener) -> Unit
): AdShowResult = withContext(Dispatchers.Main.immediate) {
    suspendCancellableCoroutine<AdShowResult> { continuation ->
        continuation.invokeOnCancellation { presentation.closeIfCoreOwned() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val callback = object : RewardedAdEventCallback {
            override fun onAdShowedFullScreenContent() = emit(AdEvent.OpenedFullScreen(placement.id))
            override fun onAdImpression() = emit(AdEvent.Impression(placement.id))
            override fun onAdClicked() = emit(AdEvent.Clicked(placement.id))
            override fun onAdPaid(value: com.google.android.libraries.ads.mobile.sdk.common.AdValue) {
                emit(AdEvent.Paid(placement.id, PaidEvent(placement.id, value.toCommon(), (loaded as? Ad)?.getResponseInfo().toCommon())))
            }
            override fun onAdDismissedFullScreenContent() {
                if (presentation.close(wasShown = true)) {
                    emit(AdEvent.ClosedFullScreen(placement.id))
                    if (continuation.isActive) {
                        continuation.resume(AdShowResult.Shown)
                    }
                }
            }
            override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) {
                val adError = error.toAdError()
                if (presentation.close(wasShown = false)) {
                    emit(AdEvent.ShowFailed(placement.id, adError))
                    if (continuation.isActive) continuation.resume(AdShowResult.Failed(adError))
                }
            }
        }
        when (loaded) {
            is RewardedAd -> {
                loaded.setImmersiveMode(options.immersiveMode)
                options.serverSideVerification?.let {
                    loaded.setServerSideVerificationOptions(
                        com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions(it.userId.orEmpty(), it.customData.orEmpty())
                    )
                }
                loaded.adEventCallback = callback
            }
            is RewardedInterstitialAd -> {
                loaded.setImmersiveMode(options.immersiveMode)
                options.serverSideVerification?.let {
                    loaded.setServerSideVerificationOptions(
                        com.google.android.libraries.ads.mobile.sdk.rewarded.ServerSideVerificationOptions(it.userId.orEmpty(), it.customData.orEmpty())
                    )
                }
                loaded.adEventCallback = object : RewardedInterstitialAdEventCallback {
                    override fun onAdShowedFullScreenContent() = callback.onAdShowedFullScreenContent()
                    override fun onAdImpression() = callback.onAdImpression()
                    override fun onAdClicked() = callback.onAdClicked()
                    override fun onAdPaid(value: com.google.android.libraries.ads.mobile.sdk.common.AdValue) = callback.onAdPaid(value)
                    override fun onAdDismissedFullScreenContent() = callback.onAdDismissedFullScreenContent()
                    override fun onAdFailedToShowFullScreenContent(error: FullScreenContentError) = callback.onAdFailedToShowFullScreenContent(error)
                }
            }
        }
        if (presentation.tryHandOffToCallbacks()) {
            show(loaded, object : OnUserEarnedRewardListener {
                override fun onUserEarnedReward(rewardItem: RewardItem) {
                    // GMA's Android RewardItem.amount is a plain Int (no fractional rewards on
                    // this platform), so the micros conversion is exact.
                    val reward = AdReward(rewardItem.amount.toLong() * 1_000_000L, rewardItem.type)
                    rewardDelivery?.deliver(reward)
                }
            })
        }
    }
}

private val fullScreenAdDestroyHandler = Handler(Looper.getMainLooper())

private fun Ad.destroyOnMain() {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        destroy()
    } else {
        fullScreenAdDestroyHandler.post { destroy() }
    }
}

private fun <T : Ad> CancellableContinuation<AdAttemptResult<T>>.resumeLoadedAd(ad: T) {
    if (!isActive) {
        ad.destroyOnMain()
        return
    }
    resume(
        AdAttemptResult.Success(ad),
        onCancellation = { _, _, _ -> ad.destroyOnMain() }
    )
}
