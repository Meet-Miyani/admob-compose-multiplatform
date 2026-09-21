@file:OptIn(ExperimentalForeignApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADAdSize
import GoogleMobileAds.GADAdSizeFromCGSize
import GoogleMobileAds.GADAdSizeFluid
import GoogleMobileAds.GADCurrentOrientationInlineAdaptiveBannerAdSizeWithWidth
import GoogleMobileAds.GADInlineAdaptiveBannerAdSizeWithWidthAndMaxHeight
import GoogleMobileAds.GADLargeAnchoredAdaptiveBannerAdSizeWithWidth
import GoogleMobileAds.GADMobileAds
import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeAdManagerImpl
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.emitOrLogDrop
import dev.avinya.ads.internal.suspendSingleShot
import dev.avinya.ads.nativead.IosNativeAdPlatform
import dev.avinya.ads.nativead.IosNativeMemorySignal
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSBundle

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

    /** See the Android counterpart: registered once, at configuration time. */
    private var memorySignal: IosNativeMemorySignal? = null

    internal override fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig) {
        nativeManager.configure(config.nativeAdMemoryPolicy)
        if (memorySignal == null) {
            memorySignal = IosNativeMemorySignal(
                callback = { nativeManager.onMemoryPressure(it) },
            )
        }
    }

    /**
     * Applies a runtime request-configuration change.
     *
     * `GADMobileAds.sharedInstance.requestConfiguration` is a live, mutable object, so the
     * same `applyTo` the initialization path uses is also the update path — including the
     * audio settings, which it writes onto the shared instance directly.
     */
    internal override suspend fun applyGlobalRequestConfigurationNative(
        configuration: GlobalRequestConfiguration,
    ) {
        configuration.applyTo(GADMobileAds.sharedInstance.requestConfiguration)
    }

    override fun configuredNativePolicyOrNull(): NativeAdMemoryPolicy? = nativeManager.configuredPolicyOrNull()

    override fun onNativeConsentRevoked() {
        nativeManager.onConsentRevoked()
    }

    override fun appId(config: AdConfig): String = config.iosAppId

    internal override val declaredAppIdSource: String = "Info.plist key \"GADApplicationIdentifier\""
    internal override val declaredAppIdRequiredByPlatformSdk: Boolean = true

    // GADMobileAds really does resolve its own app ID from this Info.plist key at startup,
    // independent of AdConfig -- unlike Android, where the manifest equivalent is read by UMP,
    // not by GMA itself. See GoogleAdManagerBase's declaredAppId KDoc.
    internal override val declaredAppIdConsumerDescription: String =
        "The native Google Mobile Ads SDK resolves its application identity from this " +
            "Info.plist value at startup, independent of AdConfig."

    // infoDictionary itself is null only if the bundle could not be read at all (Unknown, never
    // a warning); a present dictionary with no usable String value for this key is a genuine
    // configuration gap (Missing) -- see DeclaredAppId's KDoc. A key declared with an empty
    // string is that same gap, not a mismatch: GADMobileAds cannot resolve an app from it and
    // crashes at startup exactly as it does when the key is absent.
    internal override fun declaredAppId(): DeclaredAppId {
        val infoDictionary = NSBundle.mainBundle.infoDictionary ?: return DeclaredAppId.Unknown
        return DeclaredAppId.ofDeclaredValue(infoDictionary["GADApplicationIdentifier"] as? String)
    }

    override fun captureDiagnosticsSnapshotOnMain() {
        iosDiagnostics.captureSnapshotOnMain()
    }

    override suspend fun initializeMobileAdsNative(
        config: AdConfig,
        requestedIdentity: AdInitializationConfigIdentity,
        markHandoff: suspend () -> Unit,
    ) {
        // Writing the request configuration onto the shared instance is already a process-global
        // mutation, so iOS's boundary is earlier than Android's.
        markHandoff()
        GADMobileAds.sharedInstance.requestConfiguration.let { requestConfig ->
            requestedIdentity.globalRequestConfiguration.applyTo(requestConfig)
        }
        // MUST stay bounded: GMA can accept start() and never invoke the handler, which would
        // leave initialize() suspended forever otherwise. A timeout is NOT a
        // CancellationException (see awaitNativeCallback), so it reaches the catch below as a
        // real failure. The requested identity is pinned as handed-off before this call
        // regardless, so retrying the SAME configuration is still the correct next step, while
        // a retry with a DIFFERENT one is refused deterministically instead of being
        // reported as applied. See GoogleAdManagerBase.handedOffConfigIdentity.
        awaitNativeCallback(
            operation = "GADMobileAds.start",
            timeout = InitializationTimeouts.nativeInitialize
        ) {
            suspendSingleShot<Unit> { continuation ->
                GADMobileAds.sharedInstance.startWithCompletionHandler { status ->
                    val adapterStates = status?.adapterStatusesByClassName
                    if (adapterStates != null) {
                        adapterStates.forEach { (name, _) ->
                            AdLogger.d("iOS adapter '${name}'")
                        }
                    }
                    continuation.resume(Unit)
                }
            }
        }
        // publisherFirstPartyIdEnabled is applied by applyTo() above, with the rest of the
        // request configuration and before start(). It used to be logged and dropped here.
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

/**
 * Resolves [this] policy against the host-measured [containerWidthDp].
 *
 * The parameter is NOT named `widthDp`: that shadowed `AdSizePolicy.Fixed.widthDp`, so the Fixed
 * branch requested the container width instead of the configured one while `heightDp` stayed
 * correct. Android's mapper had the identical defect. Keep the names distinct here rather than
 * relying on `this.` qualification surviving future edits.
 */
internal fun AdSizePolicy.toIOSAdSize(containerWidthDp: Int): CValue<GADAdSize> = when (this) {
    is AdSizePolicy.LargeAnchoredAdaptive -> GADLargeAnchoredAdaptiveBannerAdSizeWithWidth(containerWidthDp.toDouble())
    is AdSizePolicy.InlineAdaptive -> maxHeightDp?.let {
        GADInlineAdaptiveBannerAdSizeWithWidthAndMaxHeight(containerWidthDp.toDouble(), it.toDouble())
    } ?: GADCurrentOrientationInlineAdaptiveBannerAdSizeWithWidth(containerWidthDp.toDouble())
    is AdSizePolicy.Fixed -> GADAdSizeFromCGSize(CGSizeMake(widthDp.toDouble(), heightDp.toDouble()))
    // GADAdSizeFluid is a C global (a CStructVar lvalue), unlike the functions above which
    // already return CValue<GADAdSize> by value — readValue() is the correct conversion,
    // not a cast (the struct is not a CValue, so `as CValue<GADAdSize>` throws at runtime).
    is AdSizePolicy.Fluid -> GADAdSizeFluid.readValue()
}
