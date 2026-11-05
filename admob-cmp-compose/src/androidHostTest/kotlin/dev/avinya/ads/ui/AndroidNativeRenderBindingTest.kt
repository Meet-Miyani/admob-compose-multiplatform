package dev.avinya.ads.ui

import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdResponseInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidNativeRenderBindingTest {
    @Test
    fun `host release clears only the host and is idempotent`() {
        var clearedAssets = 0
        var clearedChildren = 0
        var destroyedHost = 0
        val binding = AndroidNativeHostRelease(
            clearAssets = { clearedAssets++ },
            clearChildren = { clearedChildren++ },
            destroyHost = { destroyedHost++ },
        )

        binding.release()
        binding.release()

        assertEquals(1, clearedAssets)
        assertEquals(1, clearedChildren)
        assertEquals(1, destroyedHost)
    }

    @Test
    fun `instance event filter does not deliver a replaced ad event`() {
        assertTrue(isNativeEventForLease(placementId = "native", adInstanceId = "current", event = AdEvent.Impression("native", "current")))
        assertFalse(isNativeEventForLease(placementId = "native", adInstanceId = "current", event = AdEvent.Impression("native", "replaced")))
        assertFalse(isNativeEventForLease(placementId = "native", adInstanceId = "current", event = AdEvent.Impression("native")))
    }

    @Test
    fun `native load events never reach a row`() {
        assertFalse(
            isNativeEventForLease(
                placementId = "native",
                adInstanceId = "current",
                event = AdEvent.Loaded("native", AdResponseInfo(responseId = "r")),
            ),
        )
        assertFalse(
            isNativeEventForLease(
                placementId = "native",
                adInstanceId = "current",
                event = AdEvent.LoadFailed("native", AdError.message("none")),
            ),
        )
    }
}
