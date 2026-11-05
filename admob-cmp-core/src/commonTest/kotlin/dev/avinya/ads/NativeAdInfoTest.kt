package dev.avinya.ads

import dev.avinya.ads.nativead.NativeAdInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NativeAdInfoTest {
    private fun info(
        adInstanceId: String = "ad-1",
        placementId: String = "feed",
        responseId: String? = "r-1",
    ) = NativeAdInfo(adInstanceId, placementId, AdResponseInfo(responseId = responseId))

    @Test fun `equal fields make equal instances`() {
        assertEquals(info(), info())
        assertFalse(info() === info())
    }

    @Test fun `equal instances have equal hash codes`() {
        assertEquals(info().hashCode(), info().hashCode())
    }

    @Test fun `a differing adInstanceId makes instances unequal`() {
        assertNotEquals(info(adInstanceId = "ad-1"), info(adInstanceId = "ad-2"))
    }

    @Test fun `a differing placementId makes instances unequal`() {
        assertNotEquals(info(placementId = "feed"), info(placementId = "detail"))
    }

    @Test fun `a differing responseInfo makes instances unequal`() {
        assertNotEquals(info(responseId = "r-1"), info(responseId = "r-2"))
        assertNotEquals(info(responseId = "r-1"), NativeAdInfo("ad-1", "feed", null))
    }

    @Test fun `a null responseInfo on both sides is equal`() {
        assertEquals(NativeAdInfo("ad-1", "feed", null), NativeAdInfo("ad-1", "feed", null))
        assertEquals(NativeAdInfo("ad-1", "feed", null).hashCode(), NativeAdInfo("ad-1", "feed", null).hashCode())
    }

    @Test fun `toString names all three values`() {
        val text = info(adInstanceId = "ad-7", placementId = "feed", responseId = "r-9").toString()
        assertTrue("ad-7" in text, text)
        assertTrue("feed" in text, text)
        assertTrue("r-9" in text, text)
    }
}
