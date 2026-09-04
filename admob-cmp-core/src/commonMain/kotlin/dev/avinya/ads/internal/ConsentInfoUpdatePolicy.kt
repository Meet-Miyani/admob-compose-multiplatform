package dev.avinya.ads.internal

import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdError
import dev.avinya.ads.ConsentStatus

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
