package dev.avinya.ads

import dev.avinya.ads.internal.ConsentStateHolder
import dev.avinya.ads.internal.StoredConsentStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [StoredConsentStart] against a real [ConsentStateHolder]: the switch, the stored answer, and
 * whether the UMP update is still outstanding decide whether the stored answer is published and
 * whether initialization is allowed to start from it.
 */
class StoredConsentStartTest {

    private fun config() = AdConfig(androidAppId = "ca-app-pub-A", iosAppId = "ca-app-pub-A")

    @Test
    fun `enabled switch with stored consent that allows ads publishes it and starts once`() {
        val state = ConsentStateHolder()
        val generation = state.beginOperation()
        state.markInfoUpdateStarted(generation)
        val allowed = mutableListOf<AdConfig>()
        val trigger = StoredConsentStart(enabled = { true }, onAllowed = { allowed += it })
        val cfg = config()

        trigger.onInfoUpdateRequested(
            state = state,
            generation = generation,
            config = cfg,
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        assertEquals(1, allowed.size, "stored consent that allows ads must start initialization exactly once")
        assertSame(cfg, allowed.single(), "the callback must receive the config the update was made with")
        assertTrue(state.canRequestAds.value, "the stored answer must be published to canRequestAds")
        assertEquals(ConsentStatus.Obtained, state.status.value, "the status passed in must be published")
        assertEquals(
            PrivacyOptionsRequirementStatus.Required,
            state.privacyOptionsRequirementStatus.value,
            "the privacy requirement passed in must be published",
        )
    }

    @Test
    fun `enabled switch with stored consent that does not allow ads never starts and keeps the gate closed`() {
        val state = ConsentStateHolder()
        val generation = state.beginOperation()
        state.markInfoUpdateStarted(generation)
        val allowed = mutableListOf<AdConfig>()
        val trigger = StoredConsentStart(enabled = { true }, onAllowed = { allowed += it })

        trigger.onInfoUpdateRequested(
            state = state,
            generation = generation,
            config = config(),
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = false,
            status = ConsentStatus.Required,
        )

        assertTrue(allowed.isEmpty(), "stored consent that refuses ads must not start initialization")
        assertFalse(state.canRequestAds.value, "a refusing stored answer must leave the gate closed")
        assertEquals(
            ConsentStatus.Required,
            state.status.value,
            "the stored answer is still published as the state of the in-flight update",
        )
    }

    @Test
    fun `disabled switch publishes nothing and never starts even when stored consent allows ads`() {
        val state = ConsentStateHolder()
        val generation = state.beginOperation()
        state.markInfoUpdateStarted(generation)
        val allowed = mutableListOf<AdConfig>()
        val trigger = StoredConsentStart(enabled = { false }, onAllowed = { allowed += it })

        trigger.onInfoUpdateRequested(
            state = state,
            generation = generation,
            config = config(),
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        assertTrue(allowed.isEmpty(), "with the switch off nothing may start from the stored answer")
        assertFalse(state.canRequestAds.value, "with the switch off the stored answer must not be published")
        assertEquals(ConsentStatus.Unknown, state.status.value, "with the switch off the status must not be published")
        assertEquals(
            PrivacyOptionsRequirementStatus.Unknown,
            state.privacyOptionsRequirementStatus.value,
            "with the switch off the privacy requirement must not be published",
        )
    }

    @Test
    fun `an update that has already reported back ignores the stored answer`() {
        val state = ConsentStateHolder()
        val generation = state.beginOperation()
        state.markInfoUpdateStarted(generation)
        // The update's own callback landed first: its answer is newer than the stored one.
        state.releaseInfoUpdate(generation)
        val allowed = mutableListOf<AdConfig>()
        val trigger = StoredConsentStart(enabled = { true }, onAllowed = { allowed += it })

        trigger.onInfoUpdateRequested(
            state = state,
            generation = generation,
            config = config(),
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        assertTrue(allowed.isEmpty(), "a finished update must not be overtaken by the stored answer")
        assertFalse(state.canRequestAds.value, "a finished update must not have its gate reopened by the stored answer")
    }

    @Test
    fun `the Disabled trigger never publishes or starts`() {
        val state = ConsentStateHolder()
        val generation = state.beginOperation()
        state.markInfoUpdateStarted(generation)

        StoredConsentStart.Disabled.onInfoUpdateRequested(
            state = state,
            generation = generation,
            config = config(),
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        assertFalse(state.canRequestAds.value, "the Disabled trigger must not publish a stored answer")
        assertEquals(ConsentStatus.Unknown, state.status.value, "the Disabled trigger must not publish a status")
    }
}
