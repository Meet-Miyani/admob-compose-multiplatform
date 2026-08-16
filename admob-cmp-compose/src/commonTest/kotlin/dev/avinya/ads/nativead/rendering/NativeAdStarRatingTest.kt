package dev.avinya.ads.nativead.rendering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The star rating asset used to render as three different things — a `RatingBar` on Android, a
 * `"4.6 stars"` label on iOS, and `"★ 4.6"` in the preview. These pin the one rendering all three
 * now share, including the formatting, which has to be identical on the JVM and Kotlin/Native.
 */
class NativeAdStarRatingTest {

    @Test
    fun `a rating renders as a star and one decimal`() {
        assertEquals("★ 4.6", resolveStarRatingText(4.6))
    }

    @Test
    fun `a whole rating keeps its decimal place`() {
        assertEquals("★ 5.0", resolveStarRatingText(5.0))
        assertEquals("★ 0.0", resolveStarRatingText(0.0))
    }

    // Doubles that do not round-trip cleanly through `toString()` are exactly why the formatting
    // is done in integer arithmetic rather than left to the platform.
    @Test
    fun `ratings are rounded to one decimal rather than printed raw`() {
        assertEquals("★ 4.6", resolveStarRatingText(4.6000000000000005))
        assertEquals("★ 4.7", resolveStarRatingText(4.66))
        assertEquals("★ 4.6", resolveStarRatingText(4.64))
        assertEquals("★ 3.3", resolveStarRatingText(1.0 / 3.0 * 10.0))
    }

    @Test
    fun `a missing rating has no text so the visibility policy applies`() {
        assertNull(resolveStarRatingText(null))
    }

    @Test
    fun `a non finite rating is treated as missing`() {
        assertNull(resolveStarRatingText(Double.NaN))
        assertNull(resolveStarRatingText(Double.POSITIVE_INFINITY))
    }

    // A creative should never send these, but a malformed value must not produce "★ 12.0" or a
    // negative rating in an ad slot.
    @Test
    fun `ratings are clamped to the five star scale`() {
        assertEquals("★ 5.0", resolveStarRatingText(12.0))
        assertEquals("★ 0.0", resolveStarRatingText(-3.0))
    }
}
