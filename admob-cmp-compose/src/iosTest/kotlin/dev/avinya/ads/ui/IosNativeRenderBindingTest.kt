package dev.avinya.ads.ui

import dev.avinya.ads.AdEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosNativeRenderBindingTest {
    @Test
    fun `host release clears only the host and is idempotent`() {
        var clearedAssets = 0
        var detachedAd = 0
        var releasedView = 0
        val binding = IosNativeHostRelease(
            detachNativeAd = { detachedAd++ },
            clearAssets = { clearedAssets++ },
            releaseView = { releasedView++ },
        )

        binding.release()
        binding.release()

        assertEquals(1, detachedAd)
        assertEquals(1, clearedAssets)
        assertEquals(1, releasedView)
    }

    @Test
    fun `instance event filter does not deliver a replaced ad event`() {
        assertTrue(isNativeEventForLease(placementId = "native", adInstanceId = "current", event = AdEvent.Impression("native", "current")))
        assertFalse(isNativeEventForLease(placementId = "native", adInstanceId = "current", event = AdEvent.Impression("native", "replaced")))
    }

    @Test
    fun `video events from another same-placement native instance are not delivered`() {
        assertTrue(isNativeEventForLease(placementId = "native", adInstanceId = "first", event = AdEvent.VideoStarted("native", adInstanceId = "first")))
        assertFalse(isNativeEventForLease(placementId = "native", adInstanceId = "first", event = AdEvent.VideoStarted("native", adInstanceId = "second")))
        assertFalse(isNativeEventForLease(placementId = "native", adInstanceId = "first", event = AdEvent.VideoStarted("native")))
    }
}
