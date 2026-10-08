package dev.avinya.admob.showcase.ui.ad

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppOpenSettingsTest {

    @Test
    fun theShowcaseConfigIsShortEnoughToObserveByHand() {
        assertEquals(4.seconds, SHOWCASE_APP_OPEN_CONFIG.minBackgroundDuration)
        assertEquals(15.seconds, SHOWCASE_APP_OPEN_CONFIG.cooldownBetweenShows)
        assertFalse(SHOWCASE_APP_OPEN_CONFIG.showOnColdStart)
        assertTrue(SHOWCASE_APP_OPEN_CONFIG.preloadOnStart)
    }

    @Test
    fun preloadsOnceAdsAreAllowedAndNothingIsLoaded() {
        assertTrue(shouldPreloadAppOpen(sdkReady = true, canRequestAds = true, adsEnabled = true, alreadyLoaded = false))
    }

    @Test
    fun doesNotPreloadWhenAnAdIsAlreadyLoaded() {
        assertFalse(shouldPreloadAppOpen(sdkReady = true, canRequestAds = true, adsEnabled = true, alreadyLoaded = true))
    }

    @Test
    fun doesNotPreloadBeforeTheSdkIsReady() {
        assertFalse(shouldPreloadAppOpen(sdkReady = false, canRequestAds = true, adsEnabled = true, alreadyLoaded = false))
    }

    @Test
    fun doesNotPreloadWithoutConsent() {
        assertFalse(shouldPreloadAppOpen(sdkReady = true, canRequestAds = false, adsEnabled = true, alreadyLoaded = false))
    }

    @Test
    fun doesNotPreloadWhenAdsAreSwitchedOff() {
        assertFalse(shouldPreloadAppOpen(sdkReady = true, canRequestAds = true, adsEnabled = false, alreadyLoaded = false))
    }
}
