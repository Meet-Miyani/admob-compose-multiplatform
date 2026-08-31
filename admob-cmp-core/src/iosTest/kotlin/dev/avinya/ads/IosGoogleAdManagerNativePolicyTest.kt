package dev.avinya.ads

import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IosGoogleAdManagerNativePolicyTest {
    @Test
    fun `native facade remains deferred then binds the accepted non-default policy once`() {
        val manager = IosGoogleAdManager()
        val config = AdConfig(
            androidAppId = "android-app",
            iosAppId = "ios-app",
            nativeAdMemoryPolicy = NativeAdMemoryPolicy(softLimit = 2, hardLimit = 3),
        )

        assertEquals(0, manager.nativeAds.state.value.loadedAds)
        assertEquals(NativeAdMemoryPolicy(), manager.nativeAds.policy)

        manager.configureNativeAdsAfterAcceptedInitialization(config)
        manager.configureNativeAdsAfterAcceptedInitialization(config)

        assertEquals(config.nativeAdMemoryPolicy, manager.nativeAds.policy)
    }

    @Test
    fun `declaredAppId reports Missing for the xctest host bundle without throwing`() {
        // The xctest host bundle's Info.plist has a real infoDictionary but no
        // GADApplicationIdentifier key, so this deterministically exercises the confirmed-absent
        // path (an unmocked NSBundle.mainBundle can't be pointed at a fixture Info.plist from a
        // KMP unit test) -- appIdConfigurationWarningOrNull (covered directly in
        // AppIdVerificationTest) turns Missing into a real warning, distinct from Unknown.
        val manager = IosGoogleAdManager()

        assertEquals(DeclaredAppId.Missing, manager.declaredAppId())
    }
}
