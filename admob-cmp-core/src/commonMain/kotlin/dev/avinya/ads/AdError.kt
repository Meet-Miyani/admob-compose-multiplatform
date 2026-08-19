package dev.avinya.ads


/**
 * Represents an error from the ad SDK. [code] carries the SDK's own error
 * code string (e.g. [AdErrorCode.CONSENT_REQUIRED]) or a numeric GMA error
 * code as a string. [message] is a human-readable description. [domain]
 * identifies the source subsystem.
 */
public data class AdError(
    /** SDK error code (e.g., "consent_required", "sdk_not_ready") or numeric GMA code. */
    val code: String? = null,
    /** Human-readable error description. */
    val message: String,
    /** Error domain / source subsystem. */
    val domain: String? = null,
    /** Response info for the failed request. */
    val responseInfo: AdResponseInfo? = null
) {
    public companion object {
        /** Creates a generic [AdError] with only a [message]. */
        public fun message(message: String): AdError = AdError(message = message)
        /** Creates an [AdError] indicating consent is required before ads can be requested. */
        public fun consentRequired(): AdError = AdError(
            code = AdErrorCode.CONSENT_REQUIRED,
            message = "Ads cannot be requested until consent allows ad requests."
        )
        /** Creates an [AdError] indicating the GMA SDK has not been initialized. */
        public fun sdkNotReady(): AdError = AdError(
            code = AdErrorCode.SDK_NOT_READY,
            message = "Google Mobile Ads SDK is not initialized yet."
        )
    }
}

/**
 * SDK-defined error code constants used by [AdError]. GMA numeric error
 * codes appear as strings in [AdError.code] but are not listed here.
 */
public object AdErrorCode {
    /** Consent is required before making ad requests (UMP gate). */
    public const val CONSENT_REQUIRED: String = "consent_required"
    /** The GMA SDK has not completed initialization. */
    public const val SDK_NOT_READY: String = "sdk_not_ready"

    /**
     * `initialize()` requested a configuration that conflicts with the one the process-wide ad SDK
     * singleton already accepted, so the request was refused.
     *
     * Never retryable: the platform SDKs cannot be reconfigured after initialization, so the same
     * call will fail identically until the process restarts. Distinguishing this from
     * [SDK_NOT_READY] matters — that one means "wait and retry", this one means "fix the call".
     */
    public const val INITIALIZATION_CONFLICT: String = "initialization_conflict"
}

/**
 * Shared retry classification for all platform load paths. Platform mappers
 * preserve their native diagnostic codes: Android exposes enum names and iOS
 * exposes GMA numeric error codes.
 */
internal fun AdError.isRetryableLoadFailure(): Boolean = code in retryableLoadFailureCodes

internal const val INTERNAL_LOAD_ERROR_CODE: String = "INTERNAL_ERROR"

private val retryableLoadFailureCodes: Set<String> = setOf(
    INTERNAL_LOAD_ERROR_CODE,
    "NETWORK_ERROR",
    "TIMEOUT",
    "2",  // iOS GADErrorNetworkError
    "5",  // iOS GADErrorTimeout
    "11"  // iOS GADErrorInternalError
)
