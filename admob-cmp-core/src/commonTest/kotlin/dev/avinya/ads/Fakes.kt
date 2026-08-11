package dev.avinya.ads

import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.FullScreenPresentationHandle
import dev.avinya.ads.internal.FullScreenSlotCore
import dev.avinya.ads.internal.RewardDelivery
import dev.avinya.ads.nativead.NativeAdOptions
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Instant

/** Suspends until the calling coroutine is cancelled — models a real platform SDK call that
 *  never itself returns/throws on the Kotlin side; only its (separately captured) terminal
 *  callback resolves the presentation. */
private suspend fun suspendForeverUntilCancelled(): Nothing = awaitCancellation()

internal class FakeAd(val id: Int) {
    override fun equals(other: Any?): Boolean = other is FakeAd && other.id == id
    override fun hashCode(): Int = id
}

internal open class FakeFullScreenSlot(
    placement: AdPlacement,
    globalEvents: MutableSharedFlow<AdEvent>,
    adRequestBlockedError: () -> AdError?,
    clock: () -> Instant,
    private val scriptedLoads: MutableList<AdAttemptResult<String>> = mutableListOf(),
    private val scriptedShows: MutableList<AdShowResult> = mutableListOf(),
    private val onPresentationChanged: (Int) -> Unit = {},
    private val presentHandler: (suspend (String, FullScreenAdOptions, RewardDelivery?) -> AdShowResult)? = null,
    // Lets a test suspend mid-load (e.g. to clear() while loadAd is in flight) instead of
    // returning synchronously from the scripted queue.
    private val loadHandler: (suspend (AdRequestOptions) -> AdAttemptResult<String>)? = null,
    // Mirrors how a real platform delegate is retained: the handle is captured here, not
    // inside the suspended presentAd coroutine, so a test can close() it from outside the
    // show() coroutine tree — the same decoupling a real cancelled show() relies on (the
    // SDK's terminal callback still owns closing the handle after hand-off).
    private val onPresentationHandOff: ((FullScreenPresentationHandle) -> Unit)? = null,
    // Defaults to a private arbiter so existing single-slot tests are unaffected. Tests that
    // exercise process-wide arbitration pass one shared instance to two slots.
    arbiter: FullScreenPresentationArbiter = FullScreenPresentationArbiter(),
    beforeShowCommit: suspend () -> Unit = {},
    // Models the P1-12 scenario: presentAd fails EARLY — Activity / rootViewController
    // resolution blows up before any SDK callback is installed — so it returns Failed
    // without ever handing off or closing the presentation handle. The platform therefore
    // never emits a terminal event, and the core is the only thing that can.
    private val failBeforeHandOff: AdShowResult? = null,
    // Models a platform that wedges BEFORE reaching the SDK call: no hand-off, no
    // callbacks, no return. Without a watchdog this holds the process-wide presentation
    // token forever and blocks every other full-screen ad.
    private val stallBeforeHandOff: Boolean = false,
    audioController: dev.avinya.ads.internal.FullScreenAudioController? = null,
) : FullScreenSlotCore<String>(
    placement,
    globalEvents,
    adRequestBlockedError,
    clock,
    onPresentationChanged,
    arbiter,
    audioController,
    beforeShowCommit
) {
    val presentedAds = mutableListOf<String>()
    val destroyedAds = mutableListOf<String>()
    val loadCalls = mutableListOf<AdRequestOptions>()
    var presentCallCount = 0
    var rewardDelivery: RewardDelivery? = null

    fun enqueueLoadResult(result: AdAttemptResult<String>) {
        scriptedLoads.add(result)
    }

    fun enqueueShowResult(result: AdShowResult) {
        scriptedShows.add(result)
    }

    override suspend fun loadAd(requestOptions: AdRequestOptions): AdAttemptResult<String> {
        loadCalls.add(requestOptions)
        loadHandler?.let { return it(requestOptions) }
        return scriptedLoads.removeFirstOrNull()
            ?: AdAttemptResult.Success("ad-${loadCalls.size}")
    }

    override suspend fun presentAd(
        loaded: String,
        options: FullScreenAdOptions,
        presentation: FullScreenPresentationHandle,
        rewardDelivery: RewardDelivery?
    ): AdShowResult {
        this.rewardDelivery = rewardDelivery
        presentedAds.add(loaded)
        presentCallCount++
        if (stallBeforeHandOff) suspendForeverUntilCancelled()
        failBeforeHandOff?.let { return it }
        presentation.tryHandOffToCallbacks()
        if (onPresentationHandOff != null) {
            // The real platform present call is fire-and-forget from here on: the terminal
            // callback (captured above) is what eventually closes the handle, independent of
            // whatever happens to this coroutine (including its own cancellation).
            onPresentationHandOff.invoke(presentation)
            return suspendForeverUntilCancelled()
        }
        val result = presentHandler?.invoke(loaded, options, rewardDelivery)
            ?: scriptedShows.removeFirstOrNull()
            ?: AdShowResult.Shown
        presentation.close(result is AdShowResult.Shown)
        return result
    }

    suspend fun showRewardedForTest(
        options: FullScreenAdOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult = super.showRewarded(options, onRewardEarned)

    override fun destroyAd(ad: String) {
        destroyedAds.add(ad)
    }

    override fun destroyAfterPresentation(wasShown: Boolean): Boolean = when (placement.format) {
        AdFormat.Rewarded, AdFormat.RewardedInterstitial -> !wasShown
        else -> true
    }

    override fun getResponseInfo(ad: String): AdResponseInfo? = null
    var canPresentResult: AdError? = null
    var canPresentInvocations: Int = 0
    override fun canPresent(): AdError? {
        canPresentInvocations++
        return canPresentResult
    }
}

internal class FakeAppOpenAdController(
    adPlacement: AdPlacement = AdPlacement("test_app_open", AdFormat.AppOpen, "android", "ios"),
    private val simulatedLoadState: AdLoadState = AdLoadState.Loaded(),
    private val simulatedShowResult: AdShowResult = AdShowResult.Shown,
) : AppOpenAdController {
    private val _loadState = MutableStateFlow<AdLoadState>(simulatedLoadState)
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 8)
    override val loadState: StateFlow<AdLoadState> = _loadState
    override val events: SharedFlow<AdEvent> = _events
    override val placement: AdPlacement = adPlacement
    var loadCalled = false
    var showCalled = false
    private var ready: Boolean = simulatedLoadState is AdLoadState.Loaded

    override fun availability(): AdAvailability = AdAvailability(isReady = ready)
    override fun isReady(): Boolean = ready
    override suspend fun load(requestOptions: AdRequestOptions): AdLoadState {
        loadCalled = true
        ready = true
        _loadState.value = AdLoadState.Loaded()
        return _loadState.value
    }
    override suspend fun preload(requestOptions: AdRequestOptions): AdLoadState = load(requestOptions)
    override suspend fun show(options: FullScreenAdOptions): AdShowResult {
        showCalled = true
        // After show, the ad is consumed — mark not ready so coordinator triggers reload
        ready = false
        return simulatedShowResult
    }
    override suspend fun showIfAvailable(options: FullScreenAdOptions): AdShowResult =
        if (isReady()) show(options) else AdShowResult.NotReady
    override fun clear() {}

    fun setReady(r: Boolean) { ready = r; if (!r) _loadState.value = AdLoadState.Idle }
}

internal class FakeAdManager : AdManager, FullScreenPresenceAware {
    private val _status: MutableStateFlow<AdManagerStatus> = MutableStateFlow(AdManagerStatus.Ready)
    override val status: StateFlow<AdManagerStatus> = _status
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<AdEvent> = _events
    override val consent: ConsentController = FakeConsentController()
    override val diagnostics: AdDiagnostics = FakeAdDiagnostics()
    override val tracking: AdTrackingController = NoOpTrackingController
    private val _isFullScreenPresenting = MutableStateFlow(false)
    override val isFullScreenPresenting: StateFlow<Boolean> = _isFullScreenPresenting
    override val fullScreenArbiter: FullScreenPresentationArbiter = FullScreenPresentationArbiter()

    fun setStatus(s: AdManagerStatus) { _status.value = s }
    fun setFullScreenPresenting(presenting: Boolean) { _isFullScreenPresenting.value = presenting }

    /**
     * Simulates another full-screen format holding the process-wide token. Returns the token so
     * a test can release it and assert the coordinator recovers.
     */
    fun holdFullScreenToken(
        placementId: String = "other_full_screen",
        format: AdFormat = AdFormat.Interstitial
    ): FullScreenPresentationArbiter.PresentationToken =
        checkNotNull(fullScreenArbiter.tryAcquire(placementId, format)) {
            "the fake arbiter was already held"
        }

    override suspend fun initialize(config: AdConfig, consentMode: ConsentMode): AdManagerStatus = status.value
    override fun banner(placement: AdPlacement): BannerAdController = NoOpBannerAdController(placement)
    override val nativeAds: dev.avinya.ads.nativead.NativeAdManager get() = NoOpAdManager.nativeAds
    override fun interstitial(placement: AdPlacement): InterstitialAdController = NoOpInterstitialAdController(placement)
    override fun rewarded(placement: AdPlacement): RewardedAdController = NoOpRewardedAdController(placement)
    override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController = NoOpRewardedInterstitialAdController(placement)
    override fun appOpen(placement: AdPlacement): AppOpenAdController = NoOpAppOpenAdController(placement)
}

internal class FakeConsentController : ConsentController {
    private val _status: MutableStateFlow<ConsentStatus> = MutableStateFlow(ConsentStatus.Unknown)
    override val status: StateFlow<ConsentStatus> = _status
    override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus>
        get() = MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    override val canRequestAds: StateFlow<Boolean> = MutableStateFlow(true)
    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus = _status.value
    override suspend fun gatherConsent(config: AdConfig): ConsentStatus = _status.value
    override suspend fun showPrivacyOptions(): Boolean = false
    override suspend fun resetConsentForDebug(): Boolean = false
    fun setStatus(s: ConsentStatus) { _status.value = s }
}

internal class FakeAdDiagnostics : AdDiagnostics {
    override suspend fun openAdInspector(): Boolean = false
    override suspend fun openDebugMenu(adUnitId: String): Boolean = false
    override fun sdkVersion(): String? = "Test"
    override fun adapterStatuses(): List<AdapterInitializationStatus> = emptyList()
}

internal fun blockedAdRequestError(): () -> AdError? = { AdError.consentRequired() }
internal fun unblockedAdRequestError(): () -> AdError? = { null }

internal val testPlacement = AdPlacement(
    id = "test_slot",
    format = AdFormat.Interstitial,
    androidAdUnitId = "test-android",
    iosAdUnitId = "test-ios",
)

internal fun testNativePlacement(maxSize: Int = 3): AdPlacement = AdPlacement(
    id = "test_native",
    format = AdFormat.Native,
    androidAdUnitId = "test-android-native",
    iosAdUnitId = "test-ios-native",
    maxCacheSize = maxSize,
)

internal fun testBannerPlacement(): AdPlacement = AdPlacement(
    id = "test_banner",
    format = AdFormat.Banner,
    androidAdUnitId = "test-android-banner",
    iosAdUnitId = "test-ios-banner",
)

internal fun testRequestOptions(): AdRequestOptions = AdRequestOptions()

internal fun testNativeOptions(): NativeAdOptions = NativeAdOptions()

internal fun testGlobalEvents(): MutableSharedFlow<AdEvent> = MutableSharedFlow(replay = 1)

internal fun tickClock(): () -> Instant {
    var tick = 0L
    return { Instant.fromEpochSeconds(1000 + tick++) }
}
