package dev.avinya.ads.nativead

import android.os.Handler
import android.os.Looper
import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdResponseInfo
import dev.avinya.ads.INTERNAL_LOAD_ERROR_CODE
import dev.avinya.ads.PaidEvent
import dev.avinya.ads.internal.NativeAdPlatform
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.internal.suspendSingleShot
import dev.avinya.ads.toAdError
import dev.avinya.ads.toAndroidNativeAdRequest
import dev.avinya.ads.toCommon
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdEventCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoader
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import java.util.IdentityHashMap
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AndroidLoadedNativeAd(
    val ad: NativeAd,
    val responseInfo: AdResponseInfo?,
    val mediaInfo: NativeMediaInfo?,
) {
    internal val destroyGate = AndroidNativeDestroyGate()
}

internal class AndroidNativeDestroyGate {
    private val destroyed = AtomicBoolean(false)

    fun destroyOnce(block: () -> Unit) {
        if (destroyed.compareAndSet(false, true)) block()
    }
}

/** Small façade so host tests can drive the GMA terminal-callback protocol. */
internal interface AndroidNativeAdLoaderFacade {
    fun loadOne(request: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest, callback: NativeAdLoaderCallback)
    fun loadMany(request: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest, count: Int, callback: NativeAdLoaderCallback)
}

internal object GmaAndroidNativeAdLoaderFacade : AndroidNativeAdLoaderFacade {
    override fun loadOne(request: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest, callback: NativeAdLoaderCallback) {
        NativeAdLoader.load(request, callback)
    }

    override fun loadMany(request: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest, count: Int, callback: NativeAdLoaderCallback) {
        NativeAdLoader.load(request, count, callback)
    }
}

/** Android implementation of the Task-4 platform boundary. */
internal class AndroidNativeAdPlatform(
    private val loader: AndroidNativeAdLoaderFacade = GmaAndroidNativeAdLoaderFacade,
) : NativeAdPlatform<AndroidLoadedNativeAd> {
    private val placementIds = Collections.synchronizedMap(IdentityHashMap<AndroidLoadedNativeAd, String>())

    override suspend fun load(
        placement: AdPlacement,
        count: Int,
        generation: Long,
    ): AdAttemptResult<NativeAdPlatformBatch<AndroidLoadedNativeAd>> {
        // `produced` and `delivered` are declared OUTSIDE withContext deliberately.
        //
        // A sequential batch loads one ad at a time through separately cancellable requests,
        // and each request's own cancellation handler knows only about its own pending list.
        // Cancel or time out midway — the coordinator's timeout spans the whole batch, and
        // Sequential is the default — and every ad already accumulated was neither returned
        // nor destroyed, while `placementIds` kept a strong reference to each one for the
        // process lifetime. Holding the list out here also covers the withContext return
        // itself, which discards its value if this caller was cancelled during the hop back.
        val produced = mutableListOf<AndroidLoadedNativeAd>()
        var delivered = false
        try {
            val result = withContext(Dispatchers.Main.immediate) {
                require(count > 0) { "Native-ad load count must be positive." }
                val request = placement.requestOptions.toAndroidNativeAdRequest(placement.androidAdUnitId, placement.nativeOptions)
                when (placement.nativeOptions.batching) {
                    NativeAdBatching.Sequential -> loadSequential(placement, request, count, produced)
                    NativeAdBatching.GoogleOnly -> {
                        require(count in 1..5) { "Google-only native-ad batches must request between 1 and 5 ads." }
                        loadRequest(placement, request, count, multiAd = true)
                            .also { if (it is AdAttemptResult.Success) produced += it.value.ads }
                    }
                }
            }
            delivered = true
            return result
        } finally {
            if (!delivered) produced.forEach(::destroy)
        }
    }

    private suspend fun loadSequential(
        placement: AdPlacement,
        request: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest,
        count: Int,
        /** Caller-owned accumulator, so a cancellation mid-batch can still find these ads. */
        ads: MutableList<AndroidLoadedNativeAd>,
    ): AdAttemptResult<NativeAdPlatformBatch<AndroidLoadedNativeAd>> {
        repeat(count) {
            when (val next = loadRequest(placement, request, 1, multiAd = false)) {
                is AdAttemptResult.Failure -> {
                    if (ads.isEmpty()) return next
                    return AdAttemptResult.Success(NativeAdPlatformBatch(ads.toList(), next.error))
                }
                is AdAttemptResult.Success -> {
                    ads += next.value.ads
                    if (next.value.ads.isEmpty() || next.value.unfilledError != null) {
                        return AdAttemptResult.Success(NativeAdPlatformBatch(ads.toList(), next.value.unfilledError))
                    }
                }
            }
        }
        return AdAttemptResult.Success(NativeAdPlatformBatch(ads.toList(), null))
    }

    private suspend fun loadRequest(
        placement: AdPlacement,
        request: com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest,
        count: Int,
        multiAd: Boolean,
    ): AdAttemptResult<NativeAdPlatformBatch<AndroidLoadedNativeAd>> = suspendSingleShot { continuation ->
        val callbackState = Any()
        val pending = mutableListOf<AndroidLoadedNativeAd>()
        var cancelled = false
        var terminal = false
        var resumed = false
        var lastError: AdError? = null

        fun destroyAll(ads: List<AndroidLoadedNativeAd>) = ads.forEach(::destroy)

        continuation.invokeOnCancellation {
            val toDestroy = synchronized(callbackState) {
                cancelled = true
                val copy = pending.toList()
                pending.clear()
                copy
            }
            destroyAll(toDestroy)
        }

        val callback = object : NativeAdLoaderCallback {
            override fun onNativeAdLoaded(nativeAd: NativeAd) {
                val loaded = AndroidLoadedNativeAd(
                    ad = nativeAd,
                    responseInfo = nativeAd.getResponseInfo().toCommon(),
                    mediaInfo = nativeAd.readSessionMediaInfo(),
                )
                placementIds[loaded] = placement.id
                val accepted = synchronized(callbackState) {
                    if (cancelled || terminal || !continuation.isActive) false else {
                        pending += loaded
                        true
                    }
                }
                if (!accepted) destroy(loaded)
            }

            override fun onAdFailedToLoad(adError: LoadAdError) {
                synchronized(callbackState) {
                    if (!cancelled && !terminal) lastError = adError.toAdError()
                }
            }

            override fun onAdLoadingCompleted() {
                val result = synchronized(callbackState) {
                    if (terminal) return
                    terminal = true
                    val ads = pending.toList()
                    pending.clear()
                    if (cancelled || !continuation.isActive || resumed) {
                        ads to null
                    } else {
                        resumed = true
                        val unfilledError = if (ads.size < count) {
                            lastError ?: AdError(
                                code = INTERNAL_LOAD_ERROR_CODE,
                                message = "Native ad loader completed without filling every requested ad.",
                            )
                        } else {
                            null
                        }
                        val attempt = if (ads.isEmpty()) {
                            AdAttemptResult.Failure(requireNotNull(unfilledError))
                        } else {
                            AdAttemptResult.Success(NativeAdPlatformBatch(ads, unfilledError))
                        }
                        ads to attempt
                    }
                }
                val attempt = result.second
                if (attempt == null) {
                    // This callback decided not to resume at all (cancelled, or a terminal
                    // callback already settled the load), so nothing else will free the batch.
                    destroyAll(result.first)
                } else {
                    // onUndelivered frees the batch on every path where the value does not
                    // reach the waiter — losing the claim to a concurrent terminal callback, an
                    // already-cancelled waiter, or a cancellation landing in flight. Do NOT add
                    // a second `if (!resumed) destroyAll(...)` branch beside it: that was needed
                    // with tryResumeOnce, whose handler skipped the losing caller, and keeping
                    // it here would destroy the same batch twice (harmless only because
                    // destroyGate is idempotent, which is not a guarantee to lean on).
                    continuation.resume(attempt) { destroyAll(result.first) }
                }
            }
        }

        if (multiAd) loader.loadMany(request, count, callback) else loader.loadOne(request, callback)
    }

    override suspend fun bindEvents(ad: AndroidLoadedNativeAd, adInstanceId: String, emit: (AdEvent) -> Unit) {
        withContext(Dispatchers.Main.immediate) {
            val placementId = placementIds[ad] ?: ""
            ad.ad.adEventCallback = object : NativeAdEventCallback {
                override fun onAdImpression() = emit(AdEvent.Impression(adInstanceId = adInstanceId, placementId = placementId))
                override fun onAdClicked() = emit(AdEvent.Clicked(adInstanceId = adInstanceId, placementId = placementId))
                override fun onAdPaid(value: com.google.android.libraries.ads.mobile.sdk.common.AdValue) {
                    emit(AdEvent.Paid(placementId, PaidEvent(placementId, value.toCommon(), ad.responseInfo), adInstanceId))
                }
            }
        }
    }

    override fun destroy(ad: AndroidLoadedNativeAd) {
        ad.destroyGate.destroyOnce {
            placementIds.remove(ad)
            val teardown = {
                ad.ad.adEventCallback = null
                ad.ad.destroy()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) teardown() else Handler(Looper.getMainLooper()).post(teardown)
        }
    }

    override fun responseInfo(ad: AndroidLoadedNativeAd): AdResponseInfo? = ad.responseInfo
    override fun mediaInfo(ad: AndroidLoadedNativeAd): NativeMediaInfo? = ad.mediaInfo
}

@Suppress("USELESS_ELVIS", "USELESS_CAST")
private fun NativeAd.readSessionMediaInfo(): NativeMediaInfo? {
    val mediaContent = mediaContent ?: return null
    return NativeMediaInfo(
        aspectRatio = (mediaContent.aspectRatio as? Float)?.takeIf { it > 0f },
        hasVideoContent = mediaContent.hasVideoContent,
        durationSeconds = mediaContent.duration.takeIf { it > 0f }?.toDouble(),
    )
}
