package dev.avinya.ads

import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeRequestsAllowedHookTest {

    private fun config(appId: String) = AdConfig(
        androidAppId = appId,
        iosAppId = appId,
    )

    @Test
    fun `requests allowed again after a revocation notify native ads once`() = runSlotTest {
        val consent = FakeConsentController()
        val manager = FakeGoogleAdManager(consent = consent)
        manager.initialize(config("ca-app-pub-A"), ConsentMode.GatherBeforeInitialize)
        advanceUntilIdle()

        consent.setCanRequestAds(false)
        advanceUntilIdle()
        assertEquals(0, manager.nativeRequestsAllowedCount, "no notification while requests are refused")

        consent.setCanRequestAds(true)
        advanceUntilIdle()
        assertEquals(1, manager.nativeRequestsAllowedCount, "one notification once requests are allowed again")
    }
}
