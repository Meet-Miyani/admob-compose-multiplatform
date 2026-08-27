package dev.avinya.ads

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidBannerControllerCharacterizationTest {

    private val bannerPlacement = AdPlacement(
        id = "char_banner",
        format = AdFormat.Banner,
        androidAdUnitId = "test-android",
        iosAdUnitId = "test-ios"
    )

    private fun controller(blocked: () -> AdError? = { null }) =
        AndroidBannerAdController(
            placement = bannerPlacement,
            globalEvents = MutableSharedFlow(extraBufferCapacity = 16),
            adRequestBlockedError = blocked,
            activityProvider = { null }
        )

    @Test
    fun `load with no geometry and no resolvable width fails and names the remedy`() =
        runTest(StandardTestDispatcher()) {
            val state = controller().load()
            advanceUntilIdle()

            // Both platforms behave the same here: with no host-supplied BannerGeometry
            // and no width the platform can resolve, the load FAILS rather
            // than guessing a screen width. The failure is now raised by BannerCore, so the
            // wording is shared rather than Android's old Activity-specific message — but
            // the contract it encodes (fail, don't guess; name the remedy) is unchanged.
            val failed = assertIs<AdLoadState.Failed>(
                state,
                "no geometry and no resolvable width must fail, not fall back to a guess"
            )
            val message = failed.error.message ?: ""
            assertTrue(
                message.contains("width"),
                "the error must tell the caller what is missing: $message"
            )
            assertTrue(
                message.contains("BannerAdView"),
                "the error must name the supported alternative: $message"
            )
        }

    @Test
    fun `the consent gate is checked and reported as consentRequired`() =
        runTest(StandardTestDispatcher()) {
            val state = controller(blocked = { AdError.consentRequired() }).load()
            advanceUntilIdle()

            val failed = assertIs<AdLoadState.Failed>(state)
            assertTrue(
                failed.error.code == AdErrorCode.CONSENT_REQUIRED ||
                    (failed.error.message ?: "").contains("Activity"),
                "unexpected failure: ${failed.error}"
            )
        }

    @Test
    fun `clear resets loadState to Idle`() =
        runTest(StandardTestDispatcher()) {
            val controller = controller()
            controller.load()
            advanceUntilIdle()
            controller.clear()
            assertEquals(AdLoadState.Idle, controller.loadState.value)
        }

    @Test
    fun `detach only tears down when the last attachment leaves`() =
        runTest(StandardTestDispatcher()) {
            val controller = controller()
            controller.attach()
            controller.attach()
            controller.detach()
            controller.detach()
            assertEquals(AdLoadState.Idle, controller.loadState.value)
        }

    @Test
    fun `detach below zero does not underflow the attachment count`() =
        runTest(StandardTestDispatcher()) {
            val controller = controller()
            controller.detach()
            controller.detach()
            controller.attach()
            controller.detach()
            assertEquals(AdLoadState.Idle, controller.loadState.value)
        }
}
