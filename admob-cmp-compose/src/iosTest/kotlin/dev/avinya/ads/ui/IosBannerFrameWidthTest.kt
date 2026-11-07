package dev.avinya.ads.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The banner frame takes the ad size's own width, centred in a full-width row; only a size that
 * reports no width (fluid, 0) fills the row. A Fixed banner used to be stretched to the container
 * on iOS, so a 320×50 ad rendered 832 pt wide in landscape.
 */
class IosBannerFrameWidthTest {
    /** The regression: a fixed size must keep its own width, not fall back to filling the row. */
    @Test
    fun `a fixed 320 point size keeps exactly 320 dp`() {
        assertEquals(320.dp, bannerFrameWidthDp(320.0))
    }

    /** Adaptive sizes are already container-wide, so their width passes straight through. */
    @Test
    fun `an adaptive width is passed through unchanged`() {
        assertEquals(402.dp, bannerFrameWidthDp(402.0))
    }

    /** GMA's fluid size reports 0 width; null makes the view keep filling the row. */
    @Test
    fun `a zero width fluid size returns null so the view fills the row`() {
        assertNull(bannerFrameWidthDp(0.0))
    }

    /** A width that is not finite or is negative is not a usable frame width. */
    @Test
    fun `non-finite or negative widths return null`() {
        assertNull(bannerFrameWidthDp(Double.NaN))
        assertNull(bannerFrameWidthDp(-1.0))
    }
}
