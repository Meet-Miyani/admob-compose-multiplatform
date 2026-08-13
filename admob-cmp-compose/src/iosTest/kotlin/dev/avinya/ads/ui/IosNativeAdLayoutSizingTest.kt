package dev.avinya.ads.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosNativeAdLayoutSizingTest {

    @Test
    fun `registration waits until the Compose host adopts the native content height`() {
        val result = resolveNativeAdLayoutSizing(
            currentHeight = 1.0,
            measuredHeight = 420.0,
        )

        assertTrue(result.shouldUpdateHeight)
        assertFalse(result.shouldRegisterNativeAd)
    }

    @Test
    fun `registration proceeds once native content is contained by the host`() {
        val result = resolveNativeAdLayoutSizing(
            currentHeight = 420.0,
            measuredHeight = 420.25,
        )

        assertFalse(result.shouldUpdateHeight)
        assertTrue(result.shouldRegisterNativeAd)
    }

    @Test
    fun `registration uses the caller constrained height when native content is taller`() {
        val result = resolveNativeAdLayoutSizing(
            currentHeight = 320.0,
            measuredHeight = 480.0,
            minHeight = 120.0,
            maxHeight = 320.0,
        )

        assertEquals(320.0, result.effectiveMeasuredHeight)
        assertFalse(result.shouldUpdateHeight)
        assertTrue(result.shouldRegisterNativeAd)
    }

    @Test
    fun `invalid native measurements never register the ad`() {
        val result = resolveNativeAdLayoutSizing(
            currentHeight = 0.0,
            measuredHeight = Double.NaN,
        )

        assertFalse(result.shouldUpdateHeight)
        assertFalse(result.shouldRegisterNativeAd)
    }

    @Test
    fun `cached height seed is constrained to the current host bounds`() {
        assertEquals(
            320.0,
            resolveIosNativeAdInitialHeight(
                cachedHeight = 480.0,
                minHeight = 120.0,
                maxHeight = 320.0,
            ),
        )
    }

    @Test
    fun `first layout uses a positive host constraint before the bootstrap`() {
        assertEquals(
            96.0,
            resolveIosNativeAdInitialHeight(
                cachedHeight = null,
                minHeight = 96.0,
                maxHeight = 300.0,
            ),
        )
        assertEquals(
            1.0,
            resolveIosNativeAdInitialHeight(
                cachedHeight = null,
                minHeight = 0.0,
                maxHeight = null,
            ),
        )
    }

    @Test
    fun `height cache is bounded and refreshes recently used entries`() {
        val cache = IosNativeAdHeightCache<String>(maxEntries = 2)
        cache.put("first", 100.0)
        cache.put("second", 200.0)

        assertEquals(100.0, cache.get("first"))
        cache.put("third", 300.0)

        assertNull(cache.get("second"))
        assertEquals(100.0, cache.get("first"))
        assertEquals(300.0, cache.get("third"))
    }

    @Test
    fun `height cache ignores invalid measurements`() {
        val cache = IosNativeAdHeightCache<String>(maxEntries = 2)

        cache.put("nan", Double.NaN)
        cache.put("zero", 0.0)

        assertNull(cache.get("nan"))
        assertNull(cache.get("zero"))
    }

    @Test
    fun `height cache isolates identical slot metadata between native sessions`() {
        val cache = IosNativeAdHeightCache<IosNativeAdHeightCacheKey>(maxEntries = 2)
        val firstSession = IosNativeAdHeightCacheKey(
            sessionKey = "home-feed",
            slotKey = "shared-slot",
            placementId = "feed-native",
            layoutIdentity = "standard-card",
            widthDp = 360,
        )
        val secondSession = IosNativeAdHeightCacheKey(
            sessionKey = "profile-feed",
            slotKey = "shared-slot",
            placementId = "feed-native",
            layoutIdentity = "standard-card",
            widthDp = 360,
        )
        cache.put(firstSession, 280.0)

        assertNull(cache.get(secondSession))
    }
}
