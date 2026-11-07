package dev.avinya.ads.internal

import dev.avinya.ads.AdConfig
import dev.avinya.ads.ConsentStatus

/**
 * Capability behind `initialize(…, ConsentMode.InitializeOnlyIfAlreadyAllowed)`'s consent step.
 *
 * UMP asks for one consent info update per app launch, and every update is a network round trip.
 * The manual order the SDK documents for iOS — `gatherConsent`, then ATT, then
 * `initialize(…, InitializeOnlyIfAlreadyAllowed)` — used to make two in a row, and GMA waited
 * for both. An internal interface, in the manner of `RequestConfigurationUpdater`, so the public
 * `ConsentController` gains no member and its own `requestConsentInfoUpdate` keeps its contract:
 * an explicit call always asks UMP again.
 */
internal interface LaunchConsentUpdater {
    /**
     * Makes sure UMP's consent information was updated successfully in this process.
     *
     * Waits for an update already in flight, as [dev.avinya.ads.ConsentController.requestConsentInfoUpdate]
     * does, then reuses it if it succeeded. Otherwise — no update yet, or the last one failed or
     * timed out — runs one. A reused update makes no network request and dispatches no
     * `BeforeConsentRequest` hook, because no request is made.
     */
    suspend fun ensureUpdatedThisLaunch(config: AdConfig): ConsentStatus
}
