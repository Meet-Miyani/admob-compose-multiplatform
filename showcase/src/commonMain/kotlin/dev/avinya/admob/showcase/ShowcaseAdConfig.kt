package dev.avinya.admob.showcase

import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdInitializationHook
import dev.avinya.ads.AdInitializationPhase
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.ConsentDebugGeography

/** Google's public sample app id for Android. Never a production unit. */
const val SHOWCASE_ANDROID_APP_ID: String = "ca-app-pub-3940256099942544~3347511713"

/** Google's public sample app id for iOS. Never a production unit. */
const val SHOWCASE_IOS_APP_ID: String = "ca-app-pub-3940256099942544~1458002511"

/**
 * The showcase's [AdConfig].
 *
 * `testMode = true` configures **UMP consent debugging** only — it does not make
 * GMA serve test ads. Test ads come from using [dev.avinya.ads.TestAdIds] units,
 * which every showcase placement does.
 */
fun showcaseAdConfig(
    trackingHook: AdInitializationHook,
    debugGeography: ConsentDebugGeography = ConsentDebugGeography.Disabled,
    testDeviceIds: List<String> = emptyList(),
): AdConfig = AdConfig(
    androidAppId = SHOWCASE_ANDROID_APP_ID,
    iosAppId = SHOWCASE_IOS_APP_ID,
    testMode = true,
    // Feeds BOTH systems: buildConsentParams concatenates consentTestDeviceIds + testDeviceIds,
    // so one registered id makes the UMP debug geography apply AND forces GMA test ads.
    testDeviceIds = testDeviceIds,
    debugGeography = debugGeography,
    initializationHooks = listOf(trackingHook),
)

/**
 * Runs App Tracking Transparency before GMA initialises.
 *
 * Order is load-bearing: requesting ads before ATT resolves permanently
 * forfeits the IDFA for those requests. Android has no ATT and reports
 * [dev.avinya.ads.AdTrackingAuthorization.NotApplicable].
 */
class TrackingAuthorizationHook(
    private val requestAuthorization: suspend () -> Unit,
) : AdInitializationHook {
    override suspend fun onPhase(phase: AdInitializationPhase, config: AdConfig) {
        if (phase == AdInitializationPhase.BeforeMobileAdsInitialize) {
            requestAuthorization()
        }
    }
}

/** What the UI needs to know about SDK startup, collapsed from [AdManagerStatus]. */
sealed interface StartupState {
    data object Starting : StartupState
    data object Ready : StartupState
    data object ConsentRequired : StartupState
    data class Failed(val message: String, val retryable: Boolean) : StartupState
}

fun AdManagerStatus.toStartupState(): StartupState = when {
    this == AdManagerStatus.Idle || this == AdManagerStatus.Initializing -> StartupState.Starting
    this == AdManagerStatus.Ready -> StartupState.Ready
    this == AdManagerStatus.ConsentRequired -> StartupState.ConsentRequired
    this is AdManagerStatus.Failed -> StartupState.Failed(error.message, retryable)
    this is AdManagerStatus.Disabled -> StartupState.Failed(reason, retryable = false)
    else -> StartupState.Failed("Unknown SDK state.", retryable = true)
}
