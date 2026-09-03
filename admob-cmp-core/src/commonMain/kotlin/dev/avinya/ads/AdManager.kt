package dev.avinya.ads

import dev.avinya.ads.internal.emitOrLogDrop
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.NoOpControllerRegistry
import dev.avinya.ads.nativead.NativeMediaInfo
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.internal.NativeAdManagerImpl
import dev.avinya.ads.internal.NativeAdPlatform
import dev.avinya.ads.internal.NativeAdPlatformBatch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The primary entry point to the AdMob CMP SDK. 
 *
 * This singleton manager provides access to ad format controllers, UMP consent 
 * lifecycle flows, diagnostics, and a unified stream of [AdEvent]s.
 * 
 * **How to get an instance:**
 * - In Compose: Use [rememberAdManager] at the root of your app.
 * - Outside Compose (Android): Use `AdMob.manager(context)`.
 * - Outside Compose (iOS): Use `IosAdMob.manager`.
 *
 * **Best Practices for Placements:**
 * Factory functions (like [banner], [interstitial], etc.) return cached controllers 
 * for each unique placement ID. Because these controllers are cached for the lifetime 
 * of the app, you should use a small, fixed set of placement IDs (e.g., `"home_banner"`, 
 * `"feed_native"`). 
 * 
 * > **Warning:** Never generate dynamic placement IDs for repeating UI items
 * > (e.g. `"feed_item_1"`, `"feed_item_2"`), as this will leak memory. Reuse the same
 * > placement ID instead; the SDK handles multiple instances automatically.
 *
 * Declared stable in `admob-cmp-compose/compose_compiler_config.conf` (this type is compiled
 * without the Compose plugin and would otherwise carry no stability metadata at all, making
 * [rememberAdManager]'s value non-skippable everywhere it flows). An implementation must honor
 * that promise the same way [dev.avinya.ads.nativead.NativeAdSession] does: every observable
 * change is visible only through a `Flow` property ([status] is a `StateFlow`, [events] a
 * `SharedFlow`), never through a plain `var` or an unreflected side effect.
 */
public interface AdManager {
    /** Current initialization and consent state of the SDK. */
    public val status: StateFlow<AdManagerStatus>
    /**
     * A unified stream of lifecycle events ([AdEvent]) across all your ad formats.
     * Perfect for driving UI changes or logging analytics.
     *
     * **Important Note:** This is a "hot" stream designed for real-time reactions, 
     * not a durable log. If no observers are actively collecting from this flow when an 
     * event occurs, that event is silently dropped. 
     * 
     * > **Billing Warning:** Do not use these events as your primary system of record 
     * > for revenue or impression accounting. Always rely on the AdMob dashboard for billing.
     */
    public val events: SharedFlow<AdEvent>
    /** Consent lifecycle controller (UMP integration). */
    public val consent: ConsentController
    /** Diagnostics and debug tools. */
    public val diagnostics: AdDiagnostics
    /** App Tracking Transparency controller (iOS); a no-op reporting NotApplicable on Android. */
    public val tracking: AdTrackingController

    /**
     * Initializes the Google Mobile Ads SDK with the given [config] and [consentMode].
     *
     * @param config SDK configuration including app ids, test mode, and request defaults.
     * @param consentMode Consent-gathering strategy. [ConsentMode.GatherBeforeInitialize]
     *   requests a UMP update and shows the consent form if required, then initializes
     *   ads if [ConsentController.canRequestAds] is true.
     *   [ConsentMode.InitializeOnlyIfAlreadyAllowed] updates UMP but never shows a form.
     *   [ConsentMode.SkipConsent] bypasses UMP entirely.
     * @return The resulting [AdManagerStatus] after initialization completes.
     */
    public suspend fun initialize(
        config: AdConfig,
        consentMode: ConsentMode = ConsentMode.GatherBeforeInitialize
    ): AdManagerStatus

    /** Returns a [BannerAdController] for [placement], cached by id. */
    public fun banner(placement: AdPlacement): BannerAdController
    /** Process-wide bounded coordinator for feed-shaped native-ad sessions. */
    public val nativeAds: NativeAdManager
    /** Returns an [InterstitialAdController] for [placement], cached by id. */
    public fun interstitial(placement: AdPlacement): InterstitialAdController
    /** Returns a [RewardedAdController] for [placement], cached by id. */
    public fun rewarded(placement: AdPlacement): RewardedAdController
    /** Returns a [RewardedInterstitialAdController] for [placement], cached by id. */
    public fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController
    /** Returns an [AppOpenAdController] for [placement], cached by id. */
    public fun appOpen(placement: AdPlacement): AppOpenAdController
}

/**
 * Internal capability: process-wide full-screen presentation arbitration.
 *
 * [isFullScreenPresenting] remains a derived observable for hosts that want to react to a
 * full-screen ad being on screen. It is **no longer the admission mechanism** — reading it and
 * then acting on the result is a TOCTOU, which is exactly the defect this interface's
 * [fullScreenArbiter] closes. Anything deciding *whether it may present* must acquire a token
 * from the arbiter instead.
 *
 * Implemented by the real platform managers and consulted by
 * [dev.avinya.ads.appopen.AppOpenAdCoordinator] so it never stacks an app-open ad on top of
 * another full-screen ad (an AdMob policy violation). Not part of the public API.
 */
internal interface FullScreenPresenceAware {
    /** Derived observable. Safe to render; never gate a presentation decision on it. */
    val isFullScreenPresenting: StateFlow<Boolean>

    /**
     * The single process-wide admission gate. Every full-screen slot owned by this manager
     * acquires from this instance, so a token held here blocks all of them.
     */
    val fullScreenArbiter: FullScreenPresentationArbiter
}

/**
 * Manages the User Messaging Platform (UMP) consent lifecycle.
 *
 * **Standard Flow:**
 * 1. Call [requestConsentInfoUpdate] when your app launches.
 * 2. Call [gatherConsent] to show the privacy form if the user hasn't consented yet.
 * 3. Observe [canRequestAds] before loading any ads.
 * 
 * (Alternatively, use `AdManager.gatherConsentAndInitialize()` to handle this automatically.)
 */
public interface ConsentController {
    /** Current UMP consent status ([ConsentStatus]). */
    public val status: StateFlow<ConsentStatus>
    /** Whether privacy options must be shown to the user. */
    public val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus>
    /** True when ads may be requested based on the current consent state. */
    public val canRequestAds: StateFlow<Boolean>
    /** Requests a consent info update from UMP. Call every app launch. */
    public suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus
    /** Requests an update and shows the consent form if required. */
    public suspend fun gatherConsent(config: AdConfig): ConsentStatus
    /**
     * Shows the privacy options form. Should only be called when [privacyOptionsRequirementStatus]
     * is [PrivacyOptionsRequirementStatus.Required].
     *
     * Returns `false` when the form could not be shown, or when a form-presenting operation
     * already holds the SDK's consent slot — two forms cannot stack. The slot is claimed when
     * such an operation starts, not when UMP puts a form on screen, so this also covers the window
     * in which a [gatherConsent] — including the one an `initialize(…, GatherBeforeInitialize)`
     * sequence runs — is still waiting for a host and running its bounded info update, before
     * anything is visible. A [requestConsentInfoUpdate] never claims the slot and is **not** a
     * decline: this call waits for it. Retry once the in-flight operation finishes rather than
     * treating `false` as a permanent failure.
     */
    public suspend fun showPrivacyOptions(): Boolean

    /**
     * Resets consent state for debug/testing purposes only.
     *
     * Returns `false` when a form-presenting operation holds the SDK's consent slot — resetting
     * consent out from under a form the user is reading, or out from under a [gatherConsent] that
     * is about to present one, is incoherent. A bounded [requestConsentInfoUpdate] never claims
     * the slot and is not a decline: this call waits for it. Retry once the in-flight operation
     * finishes.
     */
    public suspend fun resetConsentForDebug(): Boolean

}

/**
 * Diagnostics and debug tools for the Google Mobile Ads SDK.
 */
public interface AdDiagnostics {
    /** Opens the Ad Inspector UI (debug tool). Returns true if successful. */
    public suspend fun openAdInspector(): Boolean
    /** Opens the debug menu for [adUnitId]. Returns true if successful. */
    public suspend fun openDebugMenu(adUnitId: String): Boolean
    /**
     * Returns the GMA SDK version string, or `null` if not available.
     *
     * This is an **initialization-time snapshot**, captured on the main thread while
     * the SDK initializes. It returns `null` before initialization completes. The GMA
     * SDK requires main-thread access, and this getter is synchronous with nowhere to
     * hop, so a cached snapshot is how the value is obtained safely.
     */
    public fun sdkVersion(): String?

    /**
     * Returns the initialization status of all ad adapters.
     *
     * This is an **initialization-time snapshot**, captured on the main thread while
     * the SDK initializes. It returns an empty list before initialization completes,
     * and an adapter whose status changes afterwards is not reflected until the
     * manager re-initializes. Accepted because adapter statuses are near-static
     * post-init and this is debug/support tooling, not part of the ad-serving path.
     */
    public fun adapterStatuses(): List<AdapterInitializationStatus>
}

/**
 * Controls a single banner ad placement. Handles loading, refresh, and
 * lifecycle. For Compose UI, prefer [BannerAdView] which manages the
 * controller automatically.
 */
public interface BannerAdController {
    /** The placement this controller is bound to. */
    public val placement: AdPlacement
    /** Current load state of the banner ad. */
    public val loadState: StateFlow<AdLoadState>
    /** Shared flow of banner lifecycle events. */
    public val events: SharedFlow<AdEvent>
    /**
     * Loads a banner sized for [geometry] using [sizePolicy] and [requestOptions].
     *
     * [geometry] is host-supplied because the controller is process-cached and has no
     * layout context of its own. Pass `null` only for headless use, where the controller
     * falls back to a platform-provided width; that fallback is best-effort and returns
     * a [AdLoadState.Failed] when the platform cannot determine one.
     *
     * Prefer [BannerAdView], which measures its own container and supplies [geometry]
     * automatically.
     */
    public suspend fun load(
        geometry: BannerGeometry? = null,
        sizePolicy: AdSizePolicy = placement.bannerSizePolicy,
        requestOptions: AdRequestOptions = placement.requestOptions
    ): AdLoadState
    /**
     * Reloads the banner, replaying the geometry, size policy AND request options
     * resolved by the most recent [load]. Fails if nothing has been loaded yet.
     */
    public suspend fun refresh(): AdLoadState
    /** Clears the loaded ad and releases resources. */
    public fun clear()
}

/**
 * Controls a full-screen ad format (interstitial, rewarded, rewarded
 * interstitial, or app-open). Full-screen ads are single-use: [show]
 * consumes the ad. Successfully presented rewarded ads deliberately remain
 * callback-owned after dismissal so a mediated reward callback may arrive later.
 * Cache and TTL are managed by [AdCachePolicy].
 */
public interface FullScreenAdController {
    /** The placement this controller is bound to. */
    public val placement: AdPlacement
    /** Current load state. */
    public val loadState: StateFlow<AdLoadState>
    /** Shared flow of full-screen ad lifecycle events. */
    public val events: SharedFlow<AdEvent>
    /** Returns current availability info (cached count, expiry). */
    public fun availability(): AdAvailability
    /** True when at least one cached ad is ready to show. */
    public fun isReady(): Boolean = availability().isReady
    /** Loads an ad. Suspends until the load completes. */
    public suspend fun load(requestOptions: AdRequestOptions = placement.requestOptions): AdLoadState
    /** Alias for [load]. */
    public suspend fun preload(requestOptions: AdRequestOptions = placement.requestOptions): AdLoadState = load(requestOptions)
    /** Shows the ad. Suspends until the ad is dismissed and returns [AdShowResult]. */
    public suspend fun show(options: FullScreenAdOptions = placement.fullScreenOptions): AdShowResult
    /** Clears cached ads and releases resources. */
    public fun clear()
}

/**
 * Typealias for [FullScreenAdController]. Use this type when you need a
 * slot that can hold any full-screen format (interstitial, rewarded, etc.).
 */
public typealias FullScreenAdSlot = FullScreenAdController

/** Full-screen ad controller for interstitial format. */
public interface InterstitialAdController : FullScreenAdController

/** Full-screen ad controller for rewarded video format. */
public interface RewardedAdController : FullScreenAdController {
    public suspend fun show(
        options: FullScreenAdOptions = placement.fullScreenOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult
}

/** Full-screen ad controller for rewarded interstitial format. */
public interface RewardedInterstitialAdController : FullScreenAdController {
    public suspend fun show(
        options: FullScreenAdOptions = placement.fullScreenOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult
}

/**
 * Full-screen ad controller for app-open format. Shows an ad when the app
 * returns to the foreground. For automated lifecycle management use
 * [AppOpenAdCoordinator].
 */
public interface AppOpenAdController : FullScreenAdController {
    /** Shows the ad only if [isReady]. Returns [AdShowResult.NotReady] otherwise. */
    public suspend fun showIfAvailable(options: FullScreenAdOptions = placement.fullScreenOptions): AdShowResult =
        if (isReady()) show(options) else AdShowResult.NotReady
}

/**
 * Default no-op manager used when ads aren't configured. All loads fail
 * with [AdError.sdkNotReady] and the status is always
 * [AdManagerStatus.Disabled].
 */
public object NoOpAdManager : AdManager {
    private val _status = MutableStateFlow<AdManagerStatus>(AdManagerStatus.Disabled("Ads SDK is not configured."))
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 32)

    override val status: StateFlow<AdManagerStatus> = _status
    override val events: SharedFlow<AdEvent> = _events
    override val consent: ConsentController = NoOpConsentController
    override val diagnostics: AdDiagnostics = NoOpAdDiagnostics
    override val tracking: AdTrackingController = NoOpTrackingController

    private val controllers = NoOpControllerRegistry()

    override suspend fun initialize(config: AdConfig, consentMode: ConsentMode): AdManagerStatus = status.value
    override fun banner(placement: AdPlacement): BannerAdController = controllers.banner(placement)
    override val nativeAds: NativeAdManager = NativeAdManagerImpl(
        NativeAdMemoryPolicy(),
        NoOpNativePlatform,
    )
    override fun interstitial(placement: AdPlacement): InterstitialAdController = controllers.interstitial(placement)
    override fun rewarded(placement: AdPlacement): RewardedAdController = controllers.rewarded(placement)
    override fun rewardedInterstitial(placement: AdPlacement): RewardedInterstitialAdController = controllers.rewardedInterstitial(placement)
    override fun appOpen(placement: AdPlacement): AppOpenAdController = controllers.appOpen(placement)
}

private object NoOpConsentController : ConsentController {
    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacy = MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)
    override val status: StateFlow<ConsentStatus> = _status
    override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> = _privacy
    override val canRequestAds: StateFlow<Boolean> = _canRequestAds
    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus = status.value
    override suspend fun gatherConsent(config: AdConfig): ConsentStatus = status.value
    override suspend fun showPrivacyOptions(): Boolean = false
    override suspend fun resetConsentForDebug(): Boolean = false
}

private object NoOpAdDiagnostics : AdDiagnostics {
    override suspend fun openAdInspector(): Boolean = false
    override suspend fun openDebugMenu(adUnitId: String): Boolean = false
    override fun sdkVersion(): String? = null
    override fun adapterStatuses(): List<AdapterInitializationStatus> = emptyList()
}

internal class NoOpBannerAdController(override val placement: AdPlacement) : BannerAdController {
    private val _loadState = MutableStateFlow<AdLoadState>(AdLoadState.Idle)
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 8)
    override val loadState: StateFlow<AdLoadState> = _loadState
    override val events: SharedFlow<AdEvent> = _events
    override suspend fun load(
        geometry: BannerGeometry?,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions
    ): AdLoadState = failLoad()
    override suspend fun refresh(): AdLoadState = load()
    override fun clear() = Unit
    private fun failLoad(): AdLoadState.Failed {
        val error = AdError.sdkNotReady()
        return AdLoadState.Failed(error).also {
            _loadState.value = it
            _events.emitOrLogDrop(AdEvent.LoadFailed(placement.id, error), "NoOp(${placement.id})")
        }
    }
}

internal open class NoOpFullScreenAdController(override val placement: AdPlacement) : FullScreenAdController {
    private val _loadState = MutableStateFlow<AdLoadState>(AdLoadState.Idle)
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 8)
    override val loadState: StateFlow<AdLoadState> = _loadState
    override val events: SharedFlow<AdEvent> = _events
    override fun availability(): AdAvailability = AdAvailability(isReady = false)
    override suspend fun load(requestOptions: AdRequestOptions): AdLoadState {
        val error = AdError.sdkNotReady()
        return AdLoadState.Failed(error).also {
            _loadState.value = it
            _events.emitOrLogDrop(AdEvent.LoadFailed(placement.id, error), "NoOp(${placement.id})")
        }
    }
    override suspend fun show(options: FullScreenAdOptions): AdShowResult {
        val error = AdError.sdkNotReady()
        _events.emitOrLogDrop(AdEvent.ShowFailed(placement.id, error), "NoOp(${placement.id})")
        return AdShowResult.Failed(error)
    }
    override fun clear() = Unit
}

internal class NoOpInterstitialAdController(placement: AdPlacement) : NoOpFullScreenAdController(placement), InterstitialAdController

internal class NoOpRewardedAdController(placement: AdPlacement) : NoOpFullScreenAdController(placement), RewardedAdController {
    override suspend fun show(
        options: FullScreenAdOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult = show(options)
}

internal class NoOpRewardedInterstitialAdController(placement: AdPlacement) : NoOpFullScreenAdController(placement), RewardedInterstitialAdController {
    override suspend fun show(
        options: FullScreenAdOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult = show(options)
}

internal class NoOpAppOpenAdController(placement: AdPlacement) : NoOpFullScreenAdController(placement), AppOpenAdController

private object NoOpNativePlatform : NativeAdPlatform<Any> {
    override suspend fun load(placement: AdPlacement, count: Int, generation: Long): AdAttemptResult<NativeAdPlatformBatch<Any>> =
        AdAttemptResult.Failure(AdError.sdkNotReady())
    override suspend fun bindEvents(ad: Any, adInstanceId: String, emit: (AdEvent) -> Unit) = Unit
    override fun destroy(ad: Any) = Unit
    override fun responseInfo(ad: Any): AdResponseInfo? = null
    override fun mediaInfo(ad: Any): NativeMediaInfo? = null
}

/**
 * Convenience extension that calls [initialize] with
 * [ConsentMode.GatherBeforeInitialize] — the standard consent flow that
 * requests a UMP update, shows the form if required, and initializes ads
 * when permitted.
 */
public suspend fun AdManager.gatherConsentAndInitialize(config: AdConfig): AdManagerStatus =
    initialize(config, ConsentMode.GatherBeforeInitialize)

/**
 * Convenience extension that calls [ConsentController.showPrivacyOptions]
 * on this manager's [AdManager.consent] controller.
 */
public suspend fun AdManager.showPrivacyOptions(): Boolean = consent.showPrivacyOptions()
