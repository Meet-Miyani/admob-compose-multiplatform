package dev.avinya.ads

import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
    /** Non-null makes the platform reject a runtime request-configuration update. */
    private val applyRequestConfigurationFailure: (() -> Throwable)? = null,
    /**
     * The adapter half of native initialization. [nativeInitialize] models the accept point
     * (Android's `MobileAds.initialize` returning, iOS's `start` call); this models GMA's adapter
     * callback. Already complete by default, which is the old single-callback behaviour, so a
     * test that does not care about adapters sees Ready as soon as accept returns.
     */
    private val adapterCompletion: Deferred<Unit> = CompletableDeferred(Unit),
    /** Pass `testScheduler.timeSource` so adapter-wait durations follow virtual time. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Non-null models iOS before App Tracking Transparency has been answered. */
    private val storedConsentRefusal: () -> String? = { null },
) : GoogleAdManagerBase() {

    override fun storedConsentStartRefusal(): String? = storedConsentRefusal()

    /** The trigger the platform consent controllers are given; a test fake can call it directly. */
    val storedConsentTrigger: dev.avinya.ads.internal.StoredConsentStart get() = storedConsentStart

    override val initializationTimeSource: TimeSource get() = timeSource

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

    /** Every configuration the "platform SDK" was actually asked to apply, in order. */
    val appliedRequestConfigurations = mutableListOf<GlobalRequestConfiguration>()

    internal override suspend fun applyGlobalRequestConfigurationNative(
        configuration: GlobalRequestConfiguration,
    ) {
        applyRequestConfigurationFailure?.let { throw it() }
        appliedRequestConfigurations += configuration
    }
    internal override fun configureNativeAdsAfterAcceptedInitialization(config: AdConfig) {
        realNativeManager?.configure(config.nativeAdMemoryPolicy)
    }

    override fun configuredNativePolicyOrNull(): NativeAdMemoryPolicy? =
        realNativeManager?.configuredPolicyOrNull()
    override fun onNativeConsentRevoked() = Unit

    /** How many times the admission collector reported that ad requests are allowed again. */
    var nativeRequestsAllowedCount = 0
        private set

    override fun onNativeRequestsAllowed() {
        nativeRequestsAllowedCount++
    }
    override fun captureDiagnosticsSnapshotOnMain() = Unit

    override suspend fun initializeMobileAdsNative(
        config: AdConfig,
        requestedIdentity: AdInitializationConfigIdentity,
        markHandoff: suspend () -> Unit,
    ): NativeInitializationHandle {
        nativeHandoffs += requestedIdentity
        failBeforeHandoff()?.let { throw it }
        markHandoff()
        handoffMarks += requestedIdentity
        awaitNativeCallback("Fake MobileAds.initialize", InitializationTimeouts.nativeInitialize) {
            nativeInitialize(config, requestedIdentity)
        }
        return NativeInitializationHandle(adapterCompletion)
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
