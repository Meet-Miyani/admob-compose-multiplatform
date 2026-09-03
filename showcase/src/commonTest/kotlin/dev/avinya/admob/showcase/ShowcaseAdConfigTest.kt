package dev.avinya.admob.showcase

import dev.avinya.ads.AdError
import dev.avinya.ads.AdErrorCode
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.ConsentDebugGeography
import kotlin.test.Test
import kotlin.test.assertEquals

class ShowcaseAdConfigTest {

    @Test
    fun usesGoogleSampleAppIdsSoNoProductionInventoryIsEverRequested() {
        assertEquals("ca-app-pub-3940256099942544~3347511713", SHOWCASE_ANDROID_APP_ID)
        assertEquals("ca-app-pub-3940256099942544~1458002511", SHOWCASE_IOS_APP_ID)
    }

    @Test
    fun debugGeographyDefaultsToDisabledSoRealGeographyIsUsed() {
        val config = showcaseAdConfig(
            trackingHook = TrackingAuthorizationHook { },
            debugGeography = ConsentDebugGeography.Disabled,
        )

        assertEquals(ConsentDebugGeography.Disabled, config.debugGeography)
    }

    @Test
    fun debugGeographyIsCarriedIntoTheConfig() {
        val config = showcaseAdConfig(
            trackingHook = TrackingAuthorizationHook { },
            debugGeography = ConsentDebugGeography.Eea,
        )

        assertEquals(ConsentDebugGeography.Eea, config.debugGeography)
    }

    @Test
    fun testDeviceIdsAreCarriedIntoTheConfig() {
        val ids = listOf("1BFD804287B2C3AE94087F1138DDA00E")

        val config = showcaseAdConfig(
            trackingHook = TrackingAuthorizationHook { },
            testDeviceIds = ids,
        )

        assertEquals(ids, config.testDeviceIds)
    }

    @Test
    fun mapsInitialisingStatusesToStarting() {
        assertEquals(StartupState.Starting, AdManagerStatus.Idle.toStartupState())
        assertEquals(StartupState.Starting, AdManagerStatus.Initializing.toStartupState())
    }

    @Test
    fun mapsReadyAndConsentRequiredToTheirOwnStates() {
        assertEquals(StartupState.Ready, AdManagerStatus.Ready.toStartupState())
        assertEquals(StartupState.ConsentRequired, AdManagerStatus.ConsentRequired.toStartupState())
    }

    @Test
    fun carriesRetryabilityThroughFailure() {
        val status = AdManagerStatus.Failed(
            error = AdError(code = AdErrorCode.SDK_NOT_READY, message = "not ready"),
            retryable = true,
        )

        assertEquals(StartupState.Failed("not ready", retryable = true), status.toStartupState())
    }
}
