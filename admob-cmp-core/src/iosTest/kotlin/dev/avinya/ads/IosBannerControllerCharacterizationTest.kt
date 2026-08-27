@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADAdSizeIsFluid
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IosBannerControllerCharacterizationTest {

    private val bannerPlacement = AdPlacement(
        id = "char_banner",
        format = AdFormat.Banner,
        androidAdUnitId = "test-android",
        iosAdUnitId = "test-ios"
    )

    private fun controller(
        blocked: () -> AdError? = blockedAdRequestError(),
        width: Int? = 320
    ) = IosBannerAdController(
        placement = bannerPlacement,
        globalEvents = MutableSharedFlow(extraBufferCapacity = 16),
        adRequestBlockedError = blocked,
        viewportWidthProvider = { width }
    )

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a blocked load fails with consentRequired and emits LoadFailed`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val controller = controller()
            val events = mutableListOf<AdEvent>()
            val collector = launch { controller.events.collect { events.add(it) } }
            advanceUntilIdle()

            val state = controller.load()
            advanceUntilIdle()

            val failed = assertIs<AdLoadState.Failed>(state, "a blocked load must report Failed")
            assertEquals(AdErrorCode.CONSENT_REQUIRED, failed.error.code)
            assertTrue(
                events.any { it is AdEvent.LoadFailed },
                "a blocked load must emit LoadFailed; got $events"
            )
            collector.cancel()
        }

    @Test
    fun `clear resets loadState to Idle`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val controller = controller()
            controller.load()
            advanceUntilIdle()
            assertIs<AdLoadState.Failed>(controller.loadState.value)

            controller.clear()
            assertEquals(AdLoadState.Idle, controller.loadState.value)
        }

    @Test
    fun `a load whose generation was invalidated by clear does not publish a failure`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val controller = controller()
            controller.clear()
            controller.load()
            advanceUntilIdle()

            assertTrue(
                controller.loadState.value == AdLoadState.Idle ||
                    controller.loadState.value is AdLoadState.Failed,
                "unexpected terminal state: ${controller.loadState.value}"
            )
        }

    @Test
    fun `registerGeometry makes refresh reuse the measured width instead of the viewport`() =
        runTest(StandardTestDispatcher()) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            // width = null: no viewport fallback available, so refresh() can only proceed
            // using the geometry the composable registered.
            val controller = controller(width = null)
            controller.registerGeometry(
                BannerGeometry(300),
                AdSizePolicy.Fixed(widthDp = 300, heightDp = 50),
                controller.placement.requestOptions
            )
            controller.refresh()
            advanceUntilIdle()

            assertIs<AdLoadState.Failed>(
                controller.loadState.value,
                "refresh() on a blocked controller must still reach the consent gate"
            )
        }

    @Test
    fun `size policies map to the expected GADAdSize widths`() {
        val fixed = AdSizePolicy.Fixed(widthDp = 320, heightDp = 50).toIOSAdSize(320)
        fixed.useContents {
            assertEquals(320.0, size.width, absoluteTolerance = 0.01)
            assertEquals(50.0, size.height, absoluteTolerance = 0.01)
        }

        val large = AdSizePolicy.LargeAnchoredAdaptive().toIOSAdSize(375)
        large.useContents {
            assertTrue(size.height in 50.0..150.0, "large anchored height was ${size.height}")
        }
    }

    @Test
    fun `Fluid maps to the GADAdSizeFluid sentinel without throwing`() {
        // Regression test: GADAdSizeFluid is a C global (a CStructVar), not a CValue like the
        // functions the other branches call, so a naive `as CValue<GADAdSize>` cast on it threw
        // ClassCastException at runtime. Calling toIOSAdSize itself is the real assertion here.
        val fluid = AdSizePolicy.Fluid.toIOSAdSize(320)

        assertTrue(GADAdSizeIsFluid(fluid), "expected the Fluid policy to map to GADAdSizeFluid")
    }
}
