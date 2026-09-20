package dev.avinya.ads.gradle

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The host check decides whether a Kotlin/Native test link gets the GoogleMobileAds linker
 * options at all. A false negative on a real Mac does not fail here — it fails much later, as
 * `Undefined symbols ... _OBJC_CLASS_$_GADBannerView`, with nothing pointing back at this
 * function. These cases are therefore the contract, not a formality.
 */
class MacOsHostTest {

    @Test
    fun `every os name a mac reports is recognised`() {
        // What the JVM actually reports on macOS — it never changed from the 10.x era name.
        assertTrue(isMacOsHost("Mac OS X"))
        // Defensive: a JVM that modernises the string, and the two aliases Gradle's own
        // OperatingSystem accepts.
        assertTrue(isMacOsHost("macOS"))
        assertTrue(isMacOsHost("Darwin"))
        assertTrue(isMacOsHost("OSX"))
    }

    @Test
    fun `other hosts and a missing os name are not mistaken for a mac`() {
        assertFalse(isMacOsHost("Linux"))
        assertFalse(isMacOsHost("Windows 11"))
        assertFalse(isMacOsHost(""))
    }
}
