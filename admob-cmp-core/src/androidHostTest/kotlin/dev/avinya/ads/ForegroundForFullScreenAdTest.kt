package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors GMA Next-Gen 1.4.0's own background check (ads_mobile_sdk.t.a). */
class ForegroundForFullScreenAdTest {

    @Test
    fun `interactive unlocked and foreground importance is foreground`() {
        assertTrue(isForegroundForFullScreenAd(interactive = true, keyguardLocked = false, importance = 100))
    }

    @Test
    fun `screen off is not foreground`() {
        assertFalse(isForegroundForFullScreenAd(interactive = false, keyguardLocked = false, importance = 100))
    }

    @Test
    fun `locked keyguard is not foreground`() {
        assertFalse(isForegroundForFullScreenAd(interactive = true, keyguardLocked = true, importance = 100))
    }

    @Test
    fun `any importance other than foreground is not foreground`() {
        // 125 = FOREGROUND_SERVICE, 200 = VISIBLE: the process is not yet ranked as the top app.
        assertFalse(isForegroundForFullScreenAd(interactive = true, keyguardLocked = false, importance = 125))
        assertFalse(isForegroundForFullScreenAd(interactive = true, keyguardLocked = false, importance = 200))
    }

    @Test
    fun `a process missing from the running list is not foreground`() {
        assertFalse(isForegroundForFullScreenAd(interactive = true, keyguardLocked = false, importance = null))
    }
}
