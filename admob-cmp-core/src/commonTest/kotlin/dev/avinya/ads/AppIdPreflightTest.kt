package dev.avinya.ads

import dev.avinya.ads.internal.AppIdVerdict
import dev.avinya.ads.internal.DeclaredAppId
import dev.avinya.ads.internal.appIdVerdict
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppIdPreflightTest {

    private val configured = "ca-app-pub-1111111111111111"
    private val source = "Info.plist key \"GADApplicationIdentifier\""
    private val consumer = "The native Google Mobile Ads SDK resolves its application identity."

    @AfterTest
    fun restoreDefaultPolicy() {
        AdAppIdVerification.policy = AppIdVerificationPolicy.FailWhenUnusable
    }

    private fun verdict(
        declared: DeclaredAppId,
        requiredByPlatformSdk: Boolean,
        policy: AppIdVerificationPolicy = AppIdVerificationPolicy.FailWhenUnusable,
    ) = appIdVerdict(
        configuredAppId = configured,
        declared = declared,
        declaredAppIdSource = source,
        declaredAppIdConsumerDescription = consumer,
        requiredByPlatformSdk = requiredByPlatformSdk,
        policy = policy,
    )

    @Test
    fun `a matching declaration is silent under every policy`() {
        AppIdVerificationPolicy.entries.forEach { policy ->
            assertEquals(
                AppIdVerdict.Ok,
                verdict(DeclaredAppId.Present(configured), requiredByPlatformSdk = true, policy = policy),
                "a correct configuration must never be reported under $policy",
            )
        }
    }

    @Test
    fun `an unreadable declaration is never reported under any policy`() {
        // DeclaredAppId.Unknown means the READ failed, which is not evidence of
        // misconfiguration -- turning it into a failure would break test environments and
        // restricted bundles for no correctness gain.
        AppIdVerificationPolicy.entries.forEach { policy ->
            assertEquals(
                AppIdVerdict.Ok,
                verdict(DeclaredAppId.Unknown, requiredByPlatformSdk = true, policy = policy),
            )
        }
    }

    @Test
    fun `a missing declaration the platform SDK needs fails by default`() {
        // iOS: GADMobileAds resolves its app ID from the plist, so there is nothing to serve
        // ads with. Failing here turns an opaque native failure into a typed status.
        val fail = assertIs<AppIdVerdict.Fail>(verdict(DeclaredAppId.Missing, requiredByPlatformSdk = true))
        assertTrue(fail.message.contains(source))
        assertTrue("ca-app-pub-1111111111111111" !in fail.message, "IDs stay redacted")
    }

    @Test
    fun `a missing declaration the platform SDK does not need only warns by default`() {
        // Android: the manifest value is UMP's, and GMA Next-Gen initializes from AdConfig.
        // Apps that ship this way serve ads today; a minor release must not break them.
        assertIs<AppIdVerdict.Warn>(verdict(DeclaredAppId.Missing, requiredByPlatformSdk = false))
    }

    @Test
    fun `a blank declaration is unusable rather than a mismatch`() {
        // A build setting that resolved to empty leaves <string></string> in the plist. GADMobileAds
        // can no more resolve an app from that than from an absent key, so the default policy must
        // fail rather than warn its way into a native crash.
        listOf("", "   ").forEach { blank ->
            val fail = assertIs<AppIdVerdict.Fail>(
                verdict(DeclaredAppId.Present(blank), requiredByPlatformSdk = true),
                "a blank declaration must fail like a missing one",
            )
            assertTrue(
                fail.message.contains("has no value set"),
                "a blank value is a configuration gap, not a mismatch against an empty id",
            )
        }
    }

    @Test
    fun `a blank declaration the platform SDK does not need only warns by default`() {
        // Android keeps the missing-declaration severity: GMA Next-Gen initializes from AdConfig.
        assertIs<AppIdVerdict.Warn>(verdict(DeclaredAppId.Present(""), requiredByPlatformSdk = false))
    }

    @Test
    fun `a mismatch only warns by default on both platforms`() {
        val declared = DeclaredAppId.Present("ca-app-pub-2222222222222222")
        assertIs<AppIdVerdict.Warn>(verdict(declared, requiredByPlatformSdk = true))
        assertIs<AppIdVerdict.Warn>(verdict(declared, requiredByPlatformSdk = false))
    }

    @Test
    fun `strict promotes every finding to a failure`() {
        val strict = AppIdVerificationPolicy.Strict
        assertIs<AppIdVerdict.Fail>(verdict(DeclaredAppId.Missing, false, strict))
        assertIs<AppIdVerdict.Fail>(
            verdict(DeclaredAppId.Present("ca-app-pub-2222222222222222"), false, strict),
        )
    }

    @Test
    fun `warnOnly demotes even an unusable declaration to a warning`() {
        // The escape hatch: a consumer hitting a false Missing must not be locked out.
        assertIs<AppIdVerdict.Warn>(
            verdict(DeclaredAppId.Missing, requiredByPlatformSdk = true, policy = AppIdVerificationPolicy.WarnOnly),
        )
    }

    @Test
    fun `the default policy fails only what the platform cannot use`() {
        assertEquals(AppIdVerificationPolicy.FailWhenUnusable, AdAppIdVerification.policy)
    }

    @Test
    fun `a fatal preflight fails before consent and before any native call`() = runSlotTest {
        AdAppIdVerification.policy = AppIdVerificationPolicy.FailWhenUnusable
        val manager = FakeGoogleAdManager(requiresDeclaredAppId = true).apply {
            declared = DeclaredAppIdForTest.Missing
        }

        val status = manager.initialize(
            AdConfig(androidAppId = "ca-app-pub-A", iosAppId = "ca-app-pub-A"),
            ConsentMode.GatherBeforeInitialize,
        )

        val failed = assertIs<AdManagerStatus.Failed>(status)
        assertTrue(!failed.retryable, "the configuration must change; retrying cannot help")
        assertTrue(
            manager.nativeHandoffs.isEmpty(),
            "a deterministic configuration failure must never reach the native SDK",
        )
    }

    @Test
    fun `a blank declaration fails the preflight like a missing one`() = runSlotTest {
        AdAppIdVerification.policy = AppIdVerificationPolicy.FailWhenUnusable
        val manager = FakeGoogleAdManager(requiresDeclaredAppId = true).apply {
            declared = DeclaredAppIdForTest.Blank
        }

        val status = manager.initialize(
            AdConfig(androidAppId = "ca-app-pub-A", iosAppId = "ca-app-pub-A"),
            ConsentMode.SkipConsent,
        )

        val failed = assertIs<AdManagerStatus.Failed>(status)
        assertEquals(AdErrorCode.APP_ID_INVALID, failed.error.code)
        assertTrue(
            manager.nativeHandoffs.isEmpty(),
            "a blank declaration must never reach the native SDK",
        )
    }

    @Test
    fun `a warning-level finding still initializes`() = runSlotTest {
        AdAppIdVerification.policy = AppIdVerificationPolicy.FailWhenUnusable
        val manager = FakeGoogleAdManager(requiresDeclaredAppId = false).apply {
            declared = DeclaredAppIdForTest.Mismatched
        }

        val status = manager.initialize(
            AdConfig(androidAppId = "ca-app-pub-A", iosAppId = "ca-app-pub-A"),
            ConsentMode.SkipConsent,
        )

        // A mismatch is a misconfiguration, not an outage. Breaking a shipping app over it in a
        // minor release is a worse failure than the mismatch.
        assertEquals(AdManagerStatus.Ready, status)
    }

    @Test
    fun `a fatal preflight is distinguishable from an initialization conflict`() = runSlotTest {
        AdAppIdVerification.policy = AppIdVerificationPolicy.FailWhenUnusable
        val manager = FakeGoogleAdManager(requiresDeclaredAppId = true).apply {
            declared = DeclaredAppIdForTest.Missing
        }

        val status = manager.initialize(
            AdConfig(androidAppId = "ca-app-pub-A", iosAppId = "ca-app-pub-A"),
            ConsentMode.SkipConsent,
        )

        // The two failures have opposite remediations -- "restart the process and call initialize()
        // once" versus "fix the app bundle and rebuild" -- so a host must be able to tell them apart.
        val failed = assertIs<AdManagerStatus.Failed>(status)
        assertEquals(AdErrorCode.APP_ID_INVALID, failed.error.code)
    }
}
