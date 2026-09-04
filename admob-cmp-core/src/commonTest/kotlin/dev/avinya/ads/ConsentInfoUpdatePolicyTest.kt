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
            nativeStatus = ConsentStatus.Obtained,
        )

        assertEquals(ConsentStatus.Obtained, status)
    }

    @Test
    fun `completed success maps to native status when not required`() {
        val status = resolveConsentInfoUpdateStatus(
            error = null,
            nativeStatus = ConsentStatus.NotRequired,
        )

        assertEquals(ConsentStatus.NotRequired, status)
    }

    @Test
    fun `completed error maps to Failed regardless of canRequestAds`() {
        val networkError = AdError.message("Network dropped")
        val statusWithTrue = resolveConsentInfoUpdateStatus(
            error = networkError,
            nativeStatus = ConsentStatus.Obtained,
        )
        val statusWithFalse = resolveConsentInfoUpdateStatus(
            error = networkError,
            nativeStatus = ConsentStatus.Unknown,
        )

        // The point of the branch is that the NATIVE status no longer decides the outcome: a
        // completed-with-error refresh reports the error either way, and carries it through
        // unchanged so the caller can act on the real cause.
        val failedTrue = assertIs<ConsentStatus.Failed>(statusWithTrue)
        assertEquals(networkError, failedTrue.error)

        val failedFalse = assertIs<ConsentStatus.Failed>(statusWithFalse)
        assertEquals(networkError, failedFalse.error)
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
