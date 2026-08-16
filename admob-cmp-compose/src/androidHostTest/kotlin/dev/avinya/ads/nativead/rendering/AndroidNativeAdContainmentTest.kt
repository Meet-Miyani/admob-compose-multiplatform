package dev.avinya.ads.nativead.rendering

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidNativeAdContainmentTest {

    private val root = NativeAdBounds(left = 10, top = 20, right = 210, bottom = 320)

    @Test
    fun `asset fully inside root is contained`() {
        val asset = NativeAdBounds(left = 10, top = 20, right = 210, bottom = 120)

        assertTrue(root.contains(asset))
    }

    /**
     * Every dp in the layout is converted with `roundToInt`, so an asset pinned flush to the root's
     * edge can land a single pixel outside it. Failing that check skips `registerNativeAd`
     * altogether — an ad that draws perfectly and never records an impression — so a rounding
     * pixel must not be treated as an escape. iOS's equivalent has always allowed half a point.
     */
    @Test
    fun `an asset a rounding pixel outside root is still contained`() {
        assertTrue(root.contains(NativeAdBounds(left = 9, top = 20, right = 210, bottom = 120)))
        assertTrue(root.contains(NativeAdBounds(left = 10, top = 19, right = 210, bottom = 120)))
        assertTrue(root.contains(NativeAdBounds(left = 10, top = 20, right = 211, bottom = 120)))
        assertTrue(root.contains(NativeAdBounds(left = 10, top = 20, right = 210, bottom = 321)))
    }

    @Test
    fun `an asset genuinely outside root is rejected`() {
        assertFalse(root.contains(NativeAdBounds(left = 8, top = 20, right = 210, bottom = 120)))
        assertFalse(root.contains(NativeAdBounds(left = 10, top = 18, right = 210, bottom = 120)))
        assertFalse(root.contains(NativeAdBounds(left = 10, top = 20, right = 212, bottom = 120)))
        assertFalse(root.contains(NativeAdBounds(left = 10, top = 20, right = 210, bottom = 322)))
    }

    @Test
    fun `tolerance is configurable and zero restores exact containment`() {
        val offByOne = NativeAdBounds(left = 9, top = 20, right = 210, bottom = 120)

        assertFalse(root.contains(offByOne, tolerance = 0))
        assertTrue(root.contains(offByOne, tolerance = 1))
    }
}
