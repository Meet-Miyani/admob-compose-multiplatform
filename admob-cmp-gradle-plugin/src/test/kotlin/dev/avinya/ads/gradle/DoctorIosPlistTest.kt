package dev.avinya.ads.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DoctorIosPlistTest {
    @Test
    fun `returns active declaration`() {
        val plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>GADApplicationIdentifier</key>
                <string>ca-app-pub-3940256099942544~1458002511</string>
            </dict>
            </plist>
        """.trimIndent()
        assertEquals("ca-app-pub-3940256099942544~1458002511", declaredAppIdInPlist(plist))
    }

    @Test
    fun `returns null for no declaration`() {
        val plist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>CFBundleIdentifier</key>
                <string>com.example.app</string>
            </dict>
            </plist>
        """.trimIndent()
        assertNull(declaredAppIdInPlist(plist))
    }

    @Test
    fun `returns empty string for declared but empty value`() {
        val plist = """
            <dict>
                <key>GADApplicationIdentifier</key>
                <string></string>
            </dict>
        """.trimIndent()
        assertEquals("", declaredAppIdInPlist(plist))
    }

    @Test
    fun `returns null for fully commented-out key-value block`() {
        val plist = """
            <dict>
                <!-- 
                <key>GADApplicationIdentifier</key>
                <string>ca-app-pub-1234~5678</string>
                -->
            </dict>
        """.trimIndent()
        assertNull(declaredAppIdInPlist(plist))
    }

    @Test
    fun `returns real declaration when a commented block is also present`() {
        val plist = """
            <dict>
                <!-- 
                <key>GADApplicationIdentifier</key>
                <string>ca-app-pub-1234~5678</string>
                -->
                <key>GADApplicationIdentifier</key>
                <string>ca-app-pub-9999~0000</string>
            </dict>
        """.trimIndent()
        assertEquals("ca-app-pub-9999~0000", declaredAppIdInPlist(plist))
    }

    @Test
    fun `returns xcode variable value`() {
        val plist = """
            <dict>
                <key>GADApplicationIdentifier</key>
                <string>${'$'}(GAD_APP_ID)</string>
            </dict>
        """.trimIndent()
        assertEquals("${'$'}(GAD_APP_ID)", declaredAppIdInPlist(plist))
    }

    @Test
    fun `returns null for key mentioned only in prose text`() {
        val plist = """
            <dict>
                <!-- Remember to add GADApplicationIdentifier before release! -->
                <key>CFBundleIdentifier</key>
                <string>com.example.app</string>
            </dict>
        """.trimIndent()
        assertNull(declaredAppIdInPlist(plist))
    }
}
