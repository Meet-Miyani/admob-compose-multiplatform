package dev.avinya.ads

import android.app.Activity
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Size-policy mapping for Android banners.
 *
 * The container width passed to [toAndroidAdSize] and the width configured on
 * [AdSizePolicy.Fixed] are deliberately DIFFERENT in every case here. They used to be equal in
 * the equivalent iOS test, which is precisely why the Fixed branch could ship requesting the
 * container width: with 320 on both sides the assertion passes either way.
 *
 * Only the adaptive policies are container-driven; they need a real `Activity` and are covered
 * on device rather than here.
 */
class AndroidBannerSizingTest {

    @Test
    fun `a fixed policy requests its configured width, not the container width`() {
        val activity = Mockito.mock(Activity::class.java)

        val size = AdSizePolicy.Fixed(widthDp = 320, heightDp = 50).toAndroidAdSize(activity, widthDp = 400)

        assertEquals(320, size.width, "Fixed.widthDp is the requested width; 400 is only the container")
        assertEquals(50, size.height)
    }

    @Test
    fun `a fixed policy is independent of the container width`() {
        val activity = Mockito.mock(Activity::class.java)
        val policy = AdSizePolicy.Fixed(widthDp = 300, heightDp = 250)

        val narrow = policy.toAndroidAdSize(activity, widthDp = 320)
        val wide = policy.toAndroidAdSize(activity, widthDp = 720)

        // "Fixed" has to mean fixed: resizing the host container must not silently change the
        // requested creative size, or a rotation turns a valid request into a nonstandard one.
        assertEquals(narrow.width, wide.width)
        assertEquals(300, narrow.width)
        assertEquals(250, narrow.height)
    }
}
