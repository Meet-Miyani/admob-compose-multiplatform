package dev.avinya.ads.internal

import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdError
import dev.avinya.ads.ConsentStatus

/**
 * The status both platforms publish after a UMP `requestConsentInfoUpdate`
 * round trip.
 *
 * Android's `updateWithActivity` and iOS's `requestConsentInfoUpdate` carried
 * this three-branch decision separately, each with a comment requiring it stay
 * byte-identical to the other because "a consent-admission divergence between
 * the platforms is the kind of thing that is only discovered in production".
 * Nothing enforced that. It lives here now so the parity is structural.
 *
 * The middle branch is the load-bearing one: UMP can fail a refresh while the
 * user's previously persisted decision still permits ad serving. A dropped
 * network round trip is not evidence that consent was withdrawn, so admission
 * keeps whatever the last COMPLETED refresh established rather than collapsing
 * to [ConsentStatus.Failed].
 */
internal fun resolveConsentInfoUpdateStatus(
    error: AdError?,
    canRequestAds: Boolean,
    nativeStatus: ConsentStatus,
): ConsentStatus = when {
    error == null -> nativeStatus
    canRequestAds -> nativeStatus
    else -> ConsentStatus.Failed(error)
}

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
