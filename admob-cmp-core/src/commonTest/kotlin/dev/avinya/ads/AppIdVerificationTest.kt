package dev.avinya.ads

import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.appIdConfigurationWarningOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppIdVerificationTest {

    private val source = "Info.plist key \"GADApplicationIdentifier\""
    private val consumer = "The native Google Mobile Ads SDK resolves its application identity " +
        "from this Info.plist value at startup, independent of AdConfig."

    @Test
    fun `warns when the declared app id differs from AdConfig`() {
        val warning = appIdConfigurationWarningOrNull(
            configuredAppId = "ca-app-pub-1111111111111111",
            declared = DeclaredAppId.Present("ca-app-pub-2222222222222222"),
            declaredAppIdSource = source,
            declaredAppIdConsumerDescription = consumer,
        )

        requireNotNull(warning)
        assertTrue(warning.contains(source))
        assertTrue(warning.contains(consumer))
        // Full IDs must never be written to the log verbatim -- only a short suffix.
        assertTrue(warning.contains("…111111"))
        assertTrue(warning.contains("…222222"))
        assertTrue("ca-app-pub-1111111111111111" !in warning)
        assertTrue("ca-app-pub-2222222222222222" !in warning)
    }

    @Test
    fun `stays silent when the declared app id matches AdConfig`() {
        val warning = appIdConfigurationWarningOrNull(
            configuredAppId = "ca-app-pub-1111111111111111",
            declared = DeclaredAppId.Present("ca-app-pub-1111111111111111"),
            declaredAppIdSource = source,
            declaredAppIdConsumerDescription = consumer,
        )

        assertNull(warning)
    }

    @Test
    fun `warns when the declared app id is confirmed missing not just unequal`() {
        val warning = appIdConfigurationWarningOrNull(
            configuredAppId = "ca-app-pub-1111111111111111",
            declared = DeclaredAppId.Missing,
            declaredAppIdSource = source,
            declaredAppIdConsumerDescription = consumer,
        )

        requireNotNull(warning)
        assertTrue(warning.contains(source))
        assertTrue(warning.contains(consumer))
        assertTrue(warning.contains("…111111"))
        assertTrue("ca-app-pub-1111111111111111" !in warning)
    }

    @Test
    fun `stays silent when the declared app id could not be determined`() {
        // An unreadable value (permission failure, unsupported environment) is not itself
        // evidence of misconfiguration -- see DeclaredAppId's KDoc. It must never be reported
        // as either a mismatch or a missing-configuration warning.
        val warning = appIdConfigurationWarningOrNull(
            configuredAppId = "ca-app-pub-1111111111111111",
            declared = DeclaredAppId.Unknown,
            declaredAppIdSource = source,
            declaredAppIdConsumerDescription = consumer,
        )

        assertNull(warning)
    }

    @Test
    fun `a blank declared value reads as a configuration gap`() {
        // Present("") would otherwise print "does not match ... (…)" with an empty redacted id,
        // which describes neither the problem nor its fix.
        val warning = appIdConfigurationWarningOrNull(
            configuredAppId = "ca-app-pub-1111111111111111",
            declared = DeclaredAppId.Present(""),
            declaredAppIdSource = source,
            declaredAppIdConsumerDescription = consumer,
        )

        assertNotNull(warning)
        assertTrue(warning.contains("has no value set"), "a blank value is the missing-value message")
        assertTrue("does not match" !in warning, "a blank value must not be reported as a mismatch")
    }
}
