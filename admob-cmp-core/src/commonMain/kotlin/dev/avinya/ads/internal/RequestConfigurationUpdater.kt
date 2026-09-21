package dev.avinya.ads.internal

import dev.avinya.ads.GlobalRequestConfiguration
import dev.avinya.ads.RequestConfigurationUpdateResult

/**
 * Capability behind [dev.avinya.ads.updateGlobalRequestConfiguration].
 *
 * A separate internal interface, in the manner of `FullScreenPresenceAware`, so the public
 * `AdManager` gains no abstract member: adding one would break every consumer that implements
 * the interface, and the public ABI is frozen.
 */
internal interface RequestConfigurationUpdater {
    suspend fun updateGlobalRequestConfiguration(
        configuration: GlobalRequestConfiguration,
    ): RequestConfigurationUpdateResult
}

/**
 * Whether moving between these two configurations changes what the ad servers will return.
 *
 * Content rating, age treatment, publisher personalization and the publisher first-party ID
 * all affect which ads are eligible, so inventory fetched under the old values must not be
 * served under the new ones. Audio settings are playback-time only, and test-device IDs
 * affect which devices receive test ads rather than what an already-loaded ad contains —
 * dropping a warm cache for either would be a pointless fill cost.
 */
internal fun GlobalRequestConfiguration.affectsServedAds(
    other: GlobalRequestConfiguration,
): Boolean =
    maxAdContentRating != other.maxAdContentRating ||
        ageRestrictedTreatment != other.ageRestrictedTreatment ||
        publisherPrivacyPersonalizationState != other.publisherPrivacyPersonalizationState ||
        publisherFirstPartyIdEnabled != other.publisherFirstPartyIdEnabled
