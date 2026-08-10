package dev.avinya.admob.showcase.feature.profile

import androidx.lifecycle.viewModelScope
import dev.avinya.ads.AdManager
import dev.avinya.admob.showcase.core.mvi.MviViewModel
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.data.repo.WalletRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ProfileViewModel(
    private val adManager: AdManager,
    private val settings: SettingsRepository,
    private val wallet: WalletRepository,
) : MviViewModel<ProfileState, ProfileIntent, ProfileEffect>(ProfileState()) {

    init {
        observeConsent()
        observePreferences()
        observeWallet()
    }

    private fun observeWallet() {
        viewModelScope.launch {
            wallet.balance().collect { balance ->
                updateState { copy(balance = balance) }
            }
        }
    }

    private fun observeConsent() {
        viewModelScope.launch {
            combine(
                adManager.consent.status,
                adManager.consent.canRequestAds,
                adManager.consent.privacyOptionsRequirementStatus,
            ) { consent, canRequest, privacy ->
                Triple(consent, canRequest, privacy)
            }.collect { (consent, canRequest, privacy) ->
                updateState {
                    copy(
                        consentStatus = consent,
                        canRequestAds = canRequest,
                        privacyOptions = privacy,
                    )
                }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            combine(settings.themeMode, settings.adsMasterSwitch) { theme, ads ->
                theme to ads
            }.collect { (theme, ads) ->
                updateState { copy(themeMode = theme, adsEnabled = ads) }
            }
        }
    }

    override fun onIntent(intent: ProfileIntent) {
        when (intent) {
            ProfileIntent.ShowPrivacyOptions -> {
                if (state.value.busy) return
                updateState { copy(busy = true) }
                viewModelScope.launch {
                    // `showPrivacyOptions()` legitimately returns false — no form
                    // is required in this region, or one is already showing. A
                    // button that silently does nothing is the worst possible
                    // teaching example, so the result is surfaced either way.
                    val shown = adManager.consent.showPrivacyOptions()
                    updateState { copy(busy = false) }
                    emitEffect(
                        ProfileEffect.Notice(
                            message = if (shown) {
                                "Privacy options opened"
                            } else {
                                "Privacy options aren't available right now"
                            },
                            success = shown,
                        ),
                    )
                }
            }

            is ProfileIntent.SetThemeMode ->
                viewModelScope.launch { settings.setThemeMode(intent.mode) }

            is ProfileIntent.SetAdsEnabled ->
                viewModelScope.launch { settings.setAdsMasterSwitch(intent.enabled) }
        }
    }
}
