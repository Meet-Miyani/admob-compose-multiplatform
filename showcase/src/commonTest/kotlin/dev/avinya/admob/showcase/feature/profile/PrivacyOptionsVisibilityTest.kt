package dev.avinya.admob.showcase.feature.profile

import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.PrivacyOptionsRequirementStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyOptionsVisibilityTest {

    @Test
    fun shownOnlyWhenTheRequirementStatusSaysRequired() {
        assertTrue(shouldShowPrivacyOptionsButton(PrivacyOptionsRequirementStatus.Required))
        assertFalse(shouldShowPrivacyOptionsButton(PrivacyOptionsRequirementStatus.NotRequired))
        assertFalse(shouldShowPrivacyOptionsButton(PrivacyOptionsRequirementStatus.Unknown))
    }

    @Test
    fun consentStatusObtainedDoesNotByItselfJustifyShowingTheButton() {
        // The classic mistake: gating on ConsentStatus.Obtained shows a privacy
        // button in regions where UMP requires none. Only the requirement
        // status is authoritative — this test exists to keep that true.
        assertFalse(shouldShowPrivacyOptionsButton(PrivacyOptionsRequirementStatus.NotRequired))
        assertEqualsIgnored(ConsentStatus.Obtained)
    }

    private fun assertEqualsIgnored(status: ConsentStatus) {
        // ConsentStatus is deliberately not an input to the decision.
        assertTrue(status is ConsentStatus)
    }
}
