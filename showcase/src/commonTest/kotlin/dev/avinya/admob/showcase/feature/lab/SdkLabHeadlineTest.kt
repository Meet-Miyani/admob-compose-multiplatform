package dev.avinya.admob.showcase.feature.lab

import dev.avinya.ads.AdManagerStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class SdkLabHeadlineTest {

    @Test
    fun readyWithConsent_saysAdsMayLoad() {
        assertEquals("Ready — ads may load", sdkLabHeadline(AdManagerStatus.Ready, canRequestAds = true))
    }

    // Regression guard: Ready stays Ready after consent is withdrawn, and the SDK refuses requests.
    @Test
    fun readyWithoutConsent_saysConsentBlocksAds() {
        assertEquals("Ready — consent blocks ads", sdkLabHeadline(AdManagerStatus.Ready, canRequestAds = false))
    }

    @Test
    fun consentRequired_ignoresCanRequestAds() {
        assertEquals("Consent required", sdkLabHeadline(AdManagerStatus.ConsentRequired, canRequestAds = true))
    }
}
