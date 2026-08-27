@file:OptIn(ExperimentalForeignApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADAdSize
import GoogleMobileAds.GADAdSizeFromCGSize
import GoogleMobileAds.GADAdSizeFluid
import GoogleMobileAds.GADCurrentOrientationInlineAdaptiveBannerAdSizeWithWidth
import GoogleMobileAds.GADInlineAdaptiveBannerAdSizeWithWidthAndMaxHeight
import GoogleMobileAds.GADLargeAnchoredAdaptiveBannerAdSizeWithWidth
import GoogleMobileAds.GADMobileAds
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeAdManagerImpl
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.emitOrLogDrop
import dev.avinya.ads.nativead.IosNativeAdPlatform
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGSizeMake

private object IosAdManagerHolder {
    val instance: IosGoogleAdManager = IosGoogleAdManager()
}

/** Public entry point for the process-wide iOS [AdManager] singleton. */
public object IosAdMob {
    public val manager: AdManager get() = IosAdManagerHolder.instance
}

internal class IosGoogleAdManager : GoogleAdManagerBase() {
    override val platformTag: String = "iOS"

    // iOS's equivalent of Android's nativeInitializationScope is already Dispatchers.Main, not
    // .immediate — see the KDoc on GoogleAdManagerBase.nativeInitializationDispatcher for why
    // this is preserved rather than unified.
    override val nativeInitializationDispatcher: CoroutineDispatcher = Dispatchers.Main

    override val consent: ConsentController = IosConsentController(resume@{ config ->
        val mode = privacyOptionsResumeMode() ?: return@resume
        initialize(config, mode)
    })
    private val iosDiagnostics = IosAdDiagnostics()
    override val diagnostics: AdDiagnostics = iosDiagnostics
    override val tracking: AdTrackingController = IosTrackingController

    private val nativeManager = NativeAdManagerImpl(
        policy = null,
        platform = IosNativeAdPlatform(),
        canRequestAds = { adRequestBlockedError() == null },
        // Routed through the shared drop-aware helper like every other emitter. This path called
        // tryEmit and discarded the Boolean -- the exact pattern emitOrLogDrop was introduced to
        // remove -- so a dropped native event was silent, precisely where batching makes bursts
        // most likely.
        eventSink = { mutableEvents.emitOrLogDrop(it, "NativeAds") },
    )
    override val nativeAds: NativeAdManager = nativeManager

    init {
        // Must run after `consent` above is constructed — see GoogleAdManagerBase.startAdmissionTracking.
        startAdmissionTracking()
    }

    internal override fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig) {
        nativeManager.configure(config.nativeAdMemoryPolicy)
    }

    override fun configuredNativePolicyOrNull(): NativeAdMemoryPolicy? = nativeManager.configuredPolicyOrNull()

    override fun onNativeConsentRevoked() {
        nativeManager.onConsentRevoked()
    }

    override fun appId(config: AdConfig): String = config.iosAppId

    override fun captureDiagnosticsSnapshotOnMain() {
        iosDiagnostics.captureSnapshotOnMain()
    }

    override suspend fun initializeMobileAdsNative(
        config: AdConfig,
        requestedIdentity: AdInitializationConfigIdentity,
    ) {
        GADMobileAds.sharedInstance.requestConfiguration.let { requestConfig ->
            requestedIdentity.globalRequestConfiguration.applyTo(requestConfig)
        }
        // MUST stay bounded: GMA can accept start() and never invoke the handler, which would
        // leave initialize() suspended forever otherwise. A timeout is NOT a
        // CancellationException (see awaitNativeCallback), so it reaches the catch below as a
        // real failure and leaves the identity uncommitted -- making a retry the correct next
        // step.
        awaitNativeCallback(
            operation = "GADMobileAds.start",
            timeout = InitializationTimeouts.nativeInitialize
        ) {
            suspendCancellableCoroutine<Unit> { continuation ->
                GADMobileAds.sharedInstance.startWithCompletionHandler { status ->
                    val adapterStates = status?.adapterStatusesByClassName
                    if (adapterStates != null) {
                        adapterStates.forEach { (name, _) ->
                            AdLogger.d("iOS adapter '${name}'")
                        }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        config.globalRequestConfiguration.publisherFirstPartyIdEnabled?.let {
            AdLogger.d("iOS publisherFirstPartyIdEnabled is Ad Manager only, skipping")
        }
        config.globalRequestConfiguration.appMuted?.let {
            GADMobileAds.sharedInstance.applicationMuted = it
        }
        config.globalRequestConfiguration.appVolume?.let {
            GADMobileAds.sharedInstance.applicationVolume = it.coerceIn(0f, 1f)
        }
    }

    override fun banner(placement: AdPlacement): BannerAdController =
        registerBanner(placement) { owned ->
            AdLogger.d("iOS banner controller created. placement=${owned.id}")
            IosBannerAdController(owned, mutableEvents, ::adRequestBlockedError)
        }

    override fun interstitial(placement: AdPlacement): InterstitialAdController =
        registerFullScreenSlot(placement, AdFormat.Interstitial) {
            IosInterstitialSlot(it, mutableEvents, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter)
        } as InterstitialAdController

    override fun rewarded(placement: AdPlacement): RewardedAdController =
        registerFullScreenSlot(placement, AdFormat.Rewarded) {
            IosRewardedSlot(it, mutableEvents, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter)
        } as RewardedAdController

    override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController =
        registerFullScreenSlot(placement, AdFormat.RewardedInterstitial) {
            IosRewardedInterstitialSlot(it, mutableEvents, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter)
        } as RewardedInterstitialAdController

    override fun appOpen(placement: AdPlacement): AppOpenAdController =
        registerFullScreenSlot(placement, AdFormat.AppOpen) {
            IosAppOpenSlot(it, mutableEvents, ::adRequestBlockedError, ::onPresentationChanged, fullScreenArbiter)
        } as AppOpenAdController
}

internal fun AdSizePolicy.toIOSAdSize(widthDp: Int): CValue<GADAdSize> = when (this) {
    is AdSizePolicy.LargeAnchoredAdaptive -> GADLargeAnchoredAdaptiveBannerAdSizeWithWidth(widthDp.toDouble())
    is AdSizePolicy.InlineAdaptive -> maxHeightDp?.let {
        GADInlineAdaptiveBannerAdSizeWithWidthAndMaxHeight(widthDp.toDouble(), it.toDouble())
    } ?: GADCurrentOrientationInlineAdaptiveBannerAdSizeWithWidth(widthDp.toDouble())
    is AdSizePolicy.Fixed -> GADAdSizeFromCGSize(CGSizeMake(widthDp.toDouble(), heightDp.toDouble()))
    // GADAdSizeFluid is a C global (a CStructVar lvalue), unlike the functions above which
    // already return CValue<GADAdSize> by value — readValue() is the correct conversion,
    // not a cast (the struct is not a CValue, so `as CValue<GADAdSize>` throws at runtime).
    is AdSizePolicy.Fluid -> GADAdSizeFluid.readValue()
}
