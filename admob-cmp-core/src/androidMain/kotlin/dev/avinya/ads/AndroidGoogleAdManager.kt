package dev.avinya.ads

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeAdManagerImpl
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.emitOrLogDrop
import dev.avinya.ads.nativead.AndroidNativeAdPlatform
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class AndroidGoogleAdManager(
    val appContext: Context,
    val activityProvider: () -> Activity?
) : GoogleAdManagerBase() {
    override val platformTag: String = "Android"
    override val nativeInitializationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    /**
     * Shared by every full-screen slot, so a dismissed ad reverts audio to the global state this
     * manager actually applied to GMA rather than to hardcoded defaults.
     *
     * The supplier is deliberately lazy: slots are created before `initialize()` completes, and
     * `null` correctly means "the host configured nothing, so GMA's own defaults apply". iOS has
     * no equivalent — its slots take no audio controller at all.
     */
    private val fullScreenAudioController =
        AndroidFullScreenAudioController { appliedConfigIdentitySnapshot()?.globalRequestConfiguration }

    override val consent: ConsentController =
        AndroidConsentController(activityProvider, appContext, resume@{ config ->
            val mode = privacyOptionsResumeMode() ?: return@resume
            initialize(config, mode)
        })
    private val androidDiagnostics = AndroidAdDiagnostics(activityProvider)
    override val diagnostics: AdDiagnostics = androidDiagnostics
    override val tracking: AdTrackingController = AndroidTrackingController

    private val nativeManager = NativeAdManagerImpl(
        policy = null,
        platform = AndroidNativeAdPlatform(),
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

    override fun appId(config: AdConfig): String = config.androidAppId

    internal override val declaredAppIdSource: String =
        "AndroidManifest.xml meta-data \"com.google.android.gms.ads.APPLICATION_ID\""

    // declaredAppIdRequiredByPlatformSdk defaults to false, which is correct on Android: GMA Next-Gen
    // initializes from AdConfig.androidAppId directly; only UMP reads this manifest meta-data.

    // NOT what GMA Next-Gen itself uses -- see initializeMobileAdsNative below, which passes
    // config.androidAppId straight into InitializationConfig.Builder. This manifest value is
    // read by the User Messaging Platform SDK for its own consent-app resolution, so a mismatch
    // here means GMA and UMP could resolve two different apps' identities, not that "GMA will
    // use the wrong one".
    internal override val declaredAppIdConsumerDescription: String =
        "The User Messaging Platform (UMP) SDK resolves its own application identity from this " +
            "manifest value when gathering consent; GMA Next-Gen itself always initializes with " +
            "AdConfig.androidAppId directly and never reads this key."

    // A failure reading package manager state (a permission issue, a test environment without
    // one) is Unknown, not Missing: it is not evidence the manifest lacks the key, so it must
    // never be reported as a configuration gap -- see DeclaredAppId's KDoc.
    internal override fun declaredAppId(): DeclaredAppId = try {
        // A meta-data entry declared with an empty android:value is the same gap as an absent
        // key -- UMP cannot resolve an application from it either way.
        DeclaredAppId.ofDeclaredValue(
            appContext.packageManager
                .getApplicationInfo(appContext.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getString("com.google.android.gms.ads.APPLICATION_ID")
        )
    } catch (t: Throwable) {
        DeclaredAppId.Unknown
    }

    override fun captureDiagnosticsSnapshotOnMain() {
        androidDiagnostics.captureSnapshotOnMain()
    }

    override suspend fun initializeMobileAdsNative(
        config: AdConfig,
        requestedIdentity: AdInitializationConfigIdentity,
        markHandoff: suspend () -> Unit,
    ) {
        AdLogger.d("Android initializing MobileAds with global request configuration.")
        val initializationConfig = InitializationConfig.Builder(config.androidAppId)
            .setRequestConfiguration(requestedIdentity.globalRequestConfiguration.toAndroidRequestConfiguration())
            .build()
        // The builder and request-configuration mapping can reject a malformed app ID;
        // nothing is owned by GMA until MobileAds.initialize is actually called.
        markHandoff()
        withContext(Dispatchers.IO) {
            // MUST stay bounded: GMA can accept the call and never invoke the callback, which
            // would leave initialize() suspended forever otherwise. A timeout is NOT a
            // CancellationException (see awaitNativeCallback), so it reaches the catch below as a
            // real failure. The requested identity is pinned as handed-off before this call
            // regardless, so retrying the SAME configuration is still the correct next step, while
            // a retry with a DIFFERENT one is refused deterministically instead of being
            // reported as applied. See GoogleAdManagerBase.handedOffConfigIdentity.
            awaitNativeCallback(
                operation = "MobileAds.initialize",
                timeout = InitializationTimeouts.nativeInitialize
            ) {
                suspendCancellableCoroutine { continuation ->
                    MobileAds.initialize(appContext, initializationConfig) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
            }
        }
        config.globalRequestConfiguration.publisherFirstPartyIdEnabled?.let {
            MobileAds.putPublisherFirstPartyIdEnabled(it)
        }
        config.globalRequestConfiguration.appMuted?.let {
            MobileAds.setUserMutedApp(it)
        }
        config.globalRequestConfiguration.appVolume?.let {
            MobileAds.setUserControlledAppVolume(it.coerceIn(0f, 1f))
        }
    }

    override fun banner(placement: AdPlacement): BannerAdController =
        registerBanner(placement) { owned ->
            AdLogger.d("Android banner controller created. placement=${owned.id}")
            AndroidBannerAdController(owned, mutableEvents, ::adRequestBlockedError, activityProvider)
        }

    override fun interstitial(placement: AdPlacement): InterstitialAdController =
        registerFullScreenSlot(placement, AdFormat.Interstitial) {
            AndroidInterstitialSlot(
                it, activityProvider, mutableEvents, ::adRequestBlockedError,
                ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController
            )
        } as InterstitialAdController

    override fun rewarded(placement: AdPlacement): RewardedAdController =
        registerFullScreenSlot(placement, AdFormat.Rewarded) {
            AndroidRewardedSlot(
                it, activityProvider, mutableEvents, ::adRequestBlockedError,
                ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController
            )
        } as RewardedAdController

    override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController =
        registerFullScreenSlot(placement, AdFormat.RewardedInterstitial) {
            AndroidRewardedInterstitialSlot(
                it, activityProvider, mutableEvents, ::adRequestBlockedError,
                ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController
            )
        } as RewardedInterstitialAdController

    override fun appOpen(placement: AdPlacement): AppOpenAdController =
        registerFullScreenSlot(placement, AdFormat.AppOpen) {
            AndroidAppOpenSlot(
                it, activityProvider, mutableEvents, ::adRequestBlockedError,
                ::onPresentationChanged, fullScreenArbiter, fullScreenAudioController
            )
        } as AppOpenAdController
}
