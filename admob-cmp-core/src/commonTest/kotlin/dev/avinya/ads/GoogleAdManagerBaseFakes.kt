package dev.avinya.ads

import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A `GoogleAdManagerBase` with the native boundary replaced by a scriptable lambda.
 *
 * Exists so the initialization state machine — the part that owns process-global native
 * ownership — can be exercised in commonTest. The platform managers reach MobileAds/GADMobileAds
 * through statics and cannot be driven end-to-end without a static-mocking dependency, which is
 * exactly why this defect class was never covered.
 */
internal class FakeGoogleAdManager(
    override val consent: ConsentController = FakeConsentController(),
    /** True models iOS (`GADApplicationIdentifier`); false models Android. */
    private val requiresDeclaredAppId: Boolean = false,
    // Suspending so a test can park here — between the native call starting and markHandoff —
    // and cancel a caller at exactly that point. That window is where the leader/follower and
    // detached-publication defects live, and it cannot be scripted with a plain lambda.
    private val failBeforeHandoff: suspend () -> Throwable? = { null },
    private val nativeInitialize: suspend (AdConfig, AdInitializationConfigIdentity) -> Unit = { _, _ -> },
    /**
     * A real [NativeAdManagerImpl] instead of the no-op one.
     *
     * Needed by any test about the ORDER of native-session configuration against status
     * publication: with the no-op manager `configureNativeAdsAfterAcceptedInitialization` does
     * nothing, so nothing can observe the request gate at the wrong moment. The manager's own
     * `adRequestBlockedError()` is wired in as the gate, exactly as the platform managers do.
     */
    nativePlatform: dev.avinya.ads.internal.NativeAdPlatform<String>? = null,
    nativeScope: CoroutineScope? = null,
) : GoogleAdManagerBase() {

    override val platformTag: String = "Fake"
    override val nativeInitializationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    override val diagnostics: AdDiagnostics = FakeAdDiagnostics()
    override val tracking: AdTrackingController = NoOpTrackingController

    private val realNativeManager: dev.avinya.ads.internal.NativeAdManagerImpl<String>? =
        nativePlatform?.let { platform ->
            dev.avinya.ads.internal.NativeAdManagerImpl(
                policy = null,
                platform = platform,
                canRequestAds = { adRequestBlockedError() == null },
                scope = nativeScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }

    override val nativeAds: NativeAdManager get() = realNativeManager ?: NoOpAdManager.nativeAds

    /** Every identity this fake was actually asked to hand to the "native" SDK, in order. */
    val nativeHandoffs = mutableListOf<AdInitializationConfigIdentity>()

    val handoffMarks = mutableListOf<AdInitializationConfigIdentity>()

    /** What the fake "platform manifest" declares. */
    var declared: DeclaredAppIdForTest = DeclaredAppIdForTest.Unknown

    override val declaredAppIdSource: String = "the fake platform manifest"
    override val declaredAppIdConsumerDescription: String = "A fake consumer reads this value."
    override val declaredAppIdRequiredByPlatformSdk: Boolean get() = requiresDeclaredAppId

    // appId() returns config.androidAppId, so these agree or disagree with whatever AdConfig
    // the test built.
    override fun declaredAppId(): DeclaredAppId = when (declared) {
        DeclaredAppIdForTest.Unknown -> DeclaredAppId.Unknown
        DeclaredAppIdForTest.Missing -> DeclaredAppId.Missing
        // Deliberately built through the factory, exactly as the platform readers do.
        DeclaredAppIdForTest.Blank -> DeclaredAppId.ofDeclaredValue("")
        DeclaredAppIdForTest.Matching -> DeclaredAppId.Present("ca-app-pub-A")
        DeclaredAppIdForTest.Mismatched -> DeclaredAppId.Present("ca-app-pub-OTHER")
    }

    init { startAdmissionTracking() }

    override fun appId(config: AdConfig): String = config.androidAppId
    internal override fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig) {
        realNativeManager?.configure(config.nativeAdMemoryPolicy)
    }

    override fun configuredNativePolicyOrNull(): NativeAdMemoryPolicy? =
        realNativeManager?.configuredPolicyOrNull()
    override fun onNativeConsentRevoked() = Unit
    override fun captureDiagnosticsSnapshotOnMain() = Unit

    override suspend fun initializeMobileAdsNative(
        config: AdConfig,
        requestedIdentity: AdInitializationConfigIdentity,
        markHandoff: suspend () -> Unit,
    ) {
        nativeHandoffs += requestedIdentity
        failBeforeHandoff()?.let { throw it }
        markHandoff()
        handoffMarks += requestedIdentity
        awaitNativeCallback("Fake MobileAds.initialize", InitializationTimeouts.nativeInitialize) {
            nativeInitialize(config, requestedIdentity)
        }
    }

    private fun unreachable(): Nothing =
        throw UnsupportedOperationException("FakeGoogleAdManager exercises initialization only.")

    override fun banner(placement: AdPlacement): BannerAdController = unreachable()
    override fun interstitial(placement: AdPlacement): InterstitialAdController = unreachable()
    override fun rewarded(placement: AdPlacement): RewardedAdController = unreachable()
    override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController = unreachable()
    override fun appOpen(placement: AdPlacement): AppOpenAdController = unreachable()
}

/** Mirrors `DeclaredAppId` so a test reads as a scenario rather than a platform type. */
internal enum class DeclaredAppIdForTest { Unknown, Missing, Blank, Matching, Mismatched }
