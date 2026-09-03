package dev.avinya.admob.showcase.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.avinya.admob.showcase.core.device.normalizeTestDeviceId
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object SettingsKeys {
    val ThemeMode = stringPreferencesKey("theme_mode")
    val OnboardingComplete = booleanPreferencesKey("onboarding_complete")
    val ConsentDebugGeography = stringPreferencesKey("consent_debug_geography")
    val ConsentTestDeviceId = stringPreferencesKey("consent_test_device_id")
    val InspectorEnabled = booleanPreferencesKey("inspector_enabled")
    val AdsMasterSwitch = booleanPreferencesKey("ads_master_switch")
}

/**
 * User preferences. Structured data lives in Room; this holds only settings.
 *
 * [adsMasterSwitch] is a local kill switch. Turning it off suppresses every
 * placement in the app without touching any SDK or consent state — useful for
 * demoing the app itself, and for proving the app is fully usable ad-free.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        // An unrecognised stored value must not crash the app on launch.
        ThemeMode.entries.firstOrNull { it.name == prefs[SettingsKeys.ThemeMode] } ?: ThemeMode.Default
    }

    val onboardingComplete: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.OnboardingComplete] ?: false }

    val consentDebugGeography: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.ConsentDebugGeography] }

    /**
     * This device's hashed test-device id, once registered from the Privacy lab.
     *
     * Stored rather than hardcoded because the value identifies one physical device: committing
     * it would put a personal advertising-id-derived hash into a public repository, and it would
     * only ever work for whoever committed it. [consentDebugGeography] is inert without it.
     */
    val consentTestDeviceId: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.ConsentTestDeviceId] }

    val inspectorEnabled: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.InspectorEnabled] ?: true }

    val adsMasterSwitch: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.AdsMasterSwitch] ?: true }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[SettingsKeys.ThemeMode] = mode.name }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[SettingsKeys.OnboardingComplete] = complete }
    }

    suspend fun setConsentDebugGeography(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(SettingsKeys.ConsentDebugGeography)
            else prefs[SettingsKeys.ConsentDebugGeography] = value
        }
    }

    suspend fun setConsentTestDeviceId(value: String?) {
        val normalized = value?.let(::normalizeTestDeviceId)
        require(value == null || normalized != null) { "Consent test-device id must be 32 hexadecimal characters." }
        dataStore.edit { prefs ->
            if (normalized == null) prefs.remove(SettingsKeys.ConsentTestDeviceId)
            else prefs[SettingsKeys.ConsentTestDeviceId] = normalized
        }
    }

    suspend fun setInspectorEnabled(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.InspectorEnabled] = enabled }
    }

    suspend fun setAdsMasterSwitch(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.AdsMasterSwitch] = enabled }
    }
}
