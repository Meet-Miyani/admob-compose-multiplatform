package dev.avinya.admob.showcase.core.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TestDeviceIdTest {

    @Test
    fun `uppercase test device id is accepted unchanged`() {
        assertEquals(
            "1BFD804287B2C3AE94087F1138DDA00E",
            normalizeTestDeviceId("1BFD804287B2C3AE94087F1138DDA00E"),
        )
    }

    @Test
    fun `lowercase test device id is trimmed and normalized`() {
        assertEquals(
            "1BFD804287B2C3AE94087F1138DDA00E",
            normalizeTestDeviceId("  1bfd804287b2c3ae94087f1138dda00e  "),
        )
    }

    @Test
    fun `wrong length and non hexadecimal ids are rejected`() {
        assertNull(normalizeTestDeviceId("1BFD8042"))
        assertNull(normalizeTestDeviceId("1BFD804287B2C3AE94087F1138DDA00Z"))
        assertNull(normalizeTestDeviceId("   "))
    }
}
