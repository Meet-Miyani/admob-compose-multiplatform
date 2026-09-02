package dev.avinya.ads

import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

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
    private val failBeforeHandoff: () -> Throwable? = { null },
    private val nativeInitialize: suspend (AdConfig, AdInitializationConfigIdentity) -> Unit = { _, _ -> },
) : GoogleAdManagerBase() {

    override val platformTag: String = "Fake"
    override val nativeInitializationDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
    override val diagnostics: AdDiagnostics = FakeAdDiagnostics()
    override val tracking: AdTrackingController = NoOpTrackingController
    override val nativeAds: NativeAdManager get() = NoOpAdManager.nativeAds

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
        DeclaredAppIdForTest.Matching -> DeclaredAppId.Present("ca-app-pub-A")
        DeclaredAppIdForTest.Mismatched -> DeclaredAppId.Present("ca-app-pub-OTHER")
    }

    init { startAdmissionTracking() }

    override fun appId(config: AdConfig): String = config.androidAppId
    internal override fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig) = Unit
    override fun configuredNativePolicyOrNull(): NativeAdMemoryPolicy? = null
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
internal enum class DeclaredAppIdForTest { Unknown, Missing, Matching, Mismatched }
