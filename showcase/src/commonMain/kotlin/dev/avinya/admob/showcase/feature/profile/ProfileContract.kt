package dev.avinya.admob.showcase.feature.profile

import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.PrivacyOptionsRequirementStatus
import dev.avinya.admob.showcase.ui.theme.ThemeMode

/**
 * Whether to offer the privacy-options ("manage consent") button.
 *
 * Gated **only** on [PrivacyOptionsRequirementStatus.Required] — never on
 * `ConsentStatus.Obtained`, which is the common mistake and puts a dead
 * button in front of users in regions where UMP requires no such control.
 */
fun shouldShowPrivacyOptionsButton(status: PrivacyOptionsRequirementStatus): Boolean =
    status == PrivacyOptionsRequirementStatus.Required

/**
 * Profile is a **consumer** surface.
 *
 * It holds only what a reader would plausibly touch: how the app looks, whether
 * it shows ads, one route into the privacy controls the SDK owns, their reward
 * balance, and one clearly-labelled door into the developer tooling.
 *
 * Debug geography, SDK status and version, retry-initialisation, the Google Ad
 * Inspector, the ATT prompt, and the telemetry toggle all live in SDK Lab now.
 * They were here before, and they made the app's settings screen read like a
 * debug menu — which is not what an integration should look like.
 */
data class ProfileState(
    val consentStatus: ConsentStatus = ConsentStatus.Unknown,
    val canRequestAds: Boolean = false,
    val privacyOptions: PrivacyOptionsRequirementStatus = PrivacyOptionsRequirementStatus.Unknown,
    val themeMode: ThemeMode = ThemeMode.Default,
    val adsEnabled: Boolean = true,
    val balance: Int = 0,
    val busy: Boolean = false,
)

sealed interface ProfileIntent {
    data object ShowPrivacyOptions : ProfileIntent
    data class SetThemeMode(val mode: ThemeMode) : ProfileIntent
    data class SetAdsEnabled(val enabled: Boolean) : ProfileIntent
}

sealed interface ProfileEffect {
    /** Shown as a transient message. [success] false means the SDK refused the request. */
    data class Notice(val message: String, val success: Boolean) : ProfileEffect
}
