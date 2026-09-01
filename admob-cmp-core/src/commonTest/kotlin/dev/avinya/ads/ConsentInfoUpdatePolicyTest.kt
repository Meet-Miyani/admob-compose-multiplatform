package dev.avinya.ads

import dev.avinya.ads.internal.consentInfoUpdateTimeoutStatus
import dev.avinya.ads.internal.resolveConsentInfoUpdateStatus
import dev.avinya.ads.internal.shouldResumeInitializationAfterPrivacyOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the consent info-update decision table that BOTH platform controllers
 * publish from.
 *
 * Android's `updateWithActivity` and iOS's `requestConsentInfoUpdate` used to
 * carry this policy separately, each with a comment requiring it stay identical
 * to the other. Nothing enforced that. Now both call the same function, and
 * this test is what pins its behaviour — a divergence would have to be
 * introduced deliberately, in one place, against these assertions.
 */
class ConsentInfoUpdatePolicyTest {

    private val networkError = AdError(code = "3", message = "network error")

    @Test
    fun `a successful update publishes the native status`() {
        val status = resolveConsentInfoUpdateStatus(
            error = null,
            canRequestAds = true,
            nativeStatus = ConsentStatus.Obtained,
        )

        assertEquals(ConsentStatus.Obtained, status)
    }

    @Test
    fun `a successful update publishes NotRequired without collapsing it to Obtained`() {
        val status = resolveConsentInfoUpdateStatus(
            error = null,
            canRequestAds = true,
            nativeStatus = ConsentStatus.NotRequired,
        )

        assertEquals(ConsentStatus.NotRequired, status)
    }

    @Test
    fun `an error while ads remain servable keeps the native status not Failed`() {
        // UMP can fail a refresh while the previously persisted decision still
        // permits ad serving. Publishing Failed here would block admission on a
        // network blip, for no gain in consent correctness.
        val status = resolveConsentInfoUpdateStatus(
            error = networkError,
            canRequestAds = true,
            nativeStatus = ConsentStatus.Obtained,
        )

        assertEquals(ConsentStatus.Obtained, status)
    }

    @Test
    fun `an error with no servable consent publishes Failed carrying that error`() {
        val status = resolveConsentInfoUpdateStatus(
            error = networkError,
            canRequestAds = false,
            nativeStatus = ConsentStatus.Unknown,
        )

        val failed = assertIs<ConsentStatus.Failed>(status)
        assertEquals("3", failed.error.code)
        assertEquals("network error", failed.error.message)
    }

    @Test
    fun `the resolution never reports Failed while ads are servable`() {
        // The admission-preserving invariant, stated directly: whatever the
        // error, a true canRequestAds must not produce Failed on either platform.
        val statuses = listOf(
            ConsentStatus.Unknown,
            ConsentStatus.Required,
            ConsentStatus.NotRequired,
            ConsentStatus.Obtained,
        )

        for (native in statuses) {
            val status = resolveConsentInfoUpdateStatus(
                error = networkError,
                canRequestAds = true,
                nativeStatus = native,
            )
            assertFalse(
                status is ConsentStatus.Failed,
                "canRequestAds=true with nativeStatus=$native must not resolve to Failed",
            )
        }
    }

    @Test
    fun `a timeout publishes Failed carrying the timeout message`() {
        val status = consentInfoUpdateTimeoutStatus("UMP consent info update timed out.")

        val failed = assertIs<ConsentStatus.Failed>(status)
        assertEquals("UMP consent info update timed out.", failed.error.message)
    }

    @Test
    fun `a timeout with no message still publishes a described failure`() {
        val status = consentInfoUpdateTimeoutStatus(null)

        val failed = assertIs<ConsentStatus.Failed>(status)
        assertEquals("UMP consent info update timed out.", failed.error.message)
    }

    @Test
    fun `privacy options resume initialization only when consent was granted`() {
        val config = AdConfig(
            appIds = AdAppIds(
                android = "ca-app-pub-3940256099942544~3347511713",
                ios = "ca-app-pub-3940256099942544~1458002511",
            ),
        )

        assertTrue(shouldResumeInitializationAfterPrivacyOptions(canRequestAds = true, lastConfig = config))
        assertFalse(shouldResumeInitializationAfterPrivacyOptions(canRequestAds = false, lastConfig = config))
    }

    @Test
    fun `privacy options cannot resume initialization without an owned config`() {
        // showPrivacyOptions() can be called before any initialize(), in which
        // case there is no config snapshot to resume with and the SDK must not
        // invent one.
        assertFalse(shouldResumeInitializationAfterPrivacyOptions(canRequestAds = true, lastConfig = null))
    }
}
