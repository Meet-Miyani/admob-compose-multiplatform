package dev.avinya.ads

import kotlin.time.Duration

/**
 * Represents the current state of the [AdManager]. Emitted via
 * [AdManager.status] as a [StateFlow].
 */
public sealed interface AdManagerStatus {
    /** SDK is not yet initialized. */
    public data object Idle : AdManagerStatus
    /** Consent is required before initialization can proceed. */
    public data object ConsentRequired : AdManagerStatus
    /** SDK is in the process of initializing. */
    public data object Initializing : AdManagerStatus
    /**
     * SDK publishes [Ready] after the platform's initialization callback reports.
     * On Android, this means mediation adapters have finished initializing, so every
     * configured network can participate in the first request. A slow adapter delays
     * [Ready] up to `InitializationTimeouts.nativeInitialize`.
     */
    public data object Ready : AdManagerStatus
    /** SDK is disabled with a [reason] message. */
    public data class Disabled(val reason: String) : AdManagerStatus
    /** SDK initialization failed with an [error]; [retryable] indicates if retrying may succeed. */
    public data class Failed(val error: AdError, val retryable: Boolean) : AdManagerStatus
}

/**
 * Strategy for handling user consent via Google's UMP before SDK
 * initialization. Choose based on your app's consent requirements and
 * target regions.
 *
 * [GatherBeforeInitialize]: The canonical mode. Requests a UMP consent-info
 * update **and** shows the consent form if the user's region/config requires
 * it (`loadAndShowConsentFormIfRequired`); then initializes ads if
 * [ConsentController.canRequestAds] is true. In regions where consent is
 * not required, no form is shown and ads still serve.
 *
 * [InitializeOnlyIfAlreadyAllowed]: Requests a UMP update but **never shows
 * a form**; initializes only if consent was already obtained (e.g. from a
 * prior session).
 *
 * [SkipConsent]: Bypasses UMP entirely and forces the ad-request gate open.
 * Intended for testing or non-consent regions only; not for EEA/UK
 * production without your own consent handling.
 */
public enum class ConsentMode { GatherBeforeInitialize, InitializeOnlyIfAlreadyAllowed, SkipConsent }

/**
 * Consent status reported by Google's UMP. Mirrors the UMP consent status
 * and is distinct from [ConsentController.canRequestAds] — `canRequestAds`
 * may be true even when consent was not explicitly obtained (e.g., in
 * non-EEA regions).
 */
public sealed interface ConsentStatus {
    /** Consent status has not been determined yet. */
    public data object Unknown : ConsentStatus
    /** Consent is required from the user. */
    public data object Required : ConsentStatus
    /** Consent is not required in the user's region. */
    public data object NotRequired : ConsentStatus
    /** Consent has been obtained from the user. */
    public data object Obtained : ConsentStatus
    /** Consent determination failed with an [error]. */
    public data class Failed(val error: AdError) : ConsentStatus
}

/**
 * Whether the user must be given the option to review their privacy
 * settings. Gating: show a privacy-settings entry point **only** when
 * this is [Required]; then call [ConsentController.showPrivacyOptions].
 */
public enum class PrivacyOptionsRequirementStatus { Unknown, Required, NotRequired }

/**
 * Represents the current load state of an ad or ad pool.
 */
public sealed interface AdLoadState {
    /** No load has been started yet. */
    public data object Idle : AdLoadState
    /** An ad load is currently in progress. */
    public data object Loading : AdLoadState
    /** An ad loaded successfully, with optional [responseInfo]. */
    public data class Loaded(val responseInfo: AdResponseInfo? = null) : AdLoadState
    /** Ad loading failed with an [error]. */
    public data class Failed(val error: AdError) : AdLoadState
}

/**
 * Availability information for cached ads.
 */
public data class AdAvailability(
    /** True when at least one ad is ready to show. */
    val isReady: Boolean,
    /** Number of ads currently cached. */
    val cachedCount: Int = 0,
    /** Duration until the next cached ad expires, or null if not available. */
    val expiresIn: Duration? = null
)
