package dev.avinya.ads.internal

import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdError
import dev.avinya.ads.ConsentStatus

/**
 * [AdError.domain] for errors raised by Android's UMP SDK.
 *
 * Load-bearing, not decoration. Android surfaces `com.google.android.ump.FormError.ErrorCode`
 * (INTERNAL_ERROR=1, INTERNET_ERROR=2, INVALID_OPERATION=3, TIME_OUT=4) while iOS surfaces
 * `UMPRequestErrorCode` (Internal=1, InvalidAppID=2, Network=3, Misconfiguration=4). The two
 * enumerations occupy the SAME numeric range with DIFFERENT meanings -- "2" is a network error on
 * Android and an invalid app ID on iOS -- so a bare [AdError.code] is ambiguous across platforms.
 * The domain is what disambiguates it. iOS passes the `NSError`'s own domain, which already
 * distinguishes `UMPRequestErrorDomain` from `UMPFormErrorDomain`.
 */
internal const val ANDROID_UMP_ERROR_DOMAIN: String = "com.google.android.ump"

/**
 * The outcome of a native UMP `requestConsentInfoUpdate` operation.
 */
internal sealed class ConsentInfoUpdateOutcome {
    abstract val status: ConsentStatus

    class Completed(override val status: ConsentStatus) : ConsentInfoUpdateOutcome()
    class TimedOut(override val status: ConsentStatus) : ConsentInfoUpdateOutcome()
}

/**
 * The status both platforms derive from a UMP `requestConsentInfoUpdate` callback.
 *
 * If the platform reports an error (e.g. no network connection), the status is ALWAYS
 * [ConsentStatus.Failed]. Note that this does NOT reset `canRequestAds`: UMP keeps
 * whatever the last COMPLETED refresh established, so a network drop does not revoke
 * an already-persisted consent choice. The status merely describes this specific operation.
 */
internal fun resolveConsentInfoUpdateStatus(
    error: AdError?,
    nativeStatus: ConsentStatus,
): ConsentStatus = if (error == null) nativeStatus else ConsentStatus.Failed(error)

/**
 * The status both platforms publish when the bounded info-update round trip
 * times out.
 *
 * Callers must NOT reset `canRequestAds` alongside this. The timeout means the
 * SDK could not re-confirm consent, not that consent changed; UMP has already
 * persisted the user's actual choice. On a first run admission is false anyway,
 * so a cold start still admits nothing.
 */
internal fun consentInfoUpdateTimeoutStatus(message: String?): ConsentStatus =
    ConsentStatus.Failed(AdError.message(message ?: "UMP consent info update timed out."))

/**
 * Whether closing the privacy-options form should resume a detached
 * initialization.
 *
 * True only when the user's decision now permits ad serving AND an owned
 * [AdConfig] snapshot from an earlier consent operation exists to resume with.
 * `showPrivacyOptions()` can legitimately be called before any `initialize()`,
 * and the SDK must not invent a config in that case.
 */
internal fun shouldResumeInitializationAfterPrivacyOptions(
    canRequestAds: Boolean,
    lastConfig: AdConfig?,
): Boolean = canRequestAds && lastConfig != null
