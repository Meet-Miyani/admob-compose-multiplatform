package dev.avinya.ads

import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Terminal-callback ownership for the Android banner load.
 *
 * Two rules are pinned here, both of which the pre-fix code broke:
 *
 *  - **Thread contract.** `AdView.unregisterBannerAd()` and `AdView.destroy()` are `@MainThread`
 *    in the Next-Gen reference, and Next-Gen delivers ad callbacks on a background pool. So no
 *    view call may happen on the callback thread itself. The test drives `runOnMain` by hand:
 *    nothing may touch the view until the queue is drained. The real Android stubs in a JVM test
 *    make `Looper.myLooper() == Looper.getMainLooper()` trivially true, which is exactly why the
 *    hop is injected rather than asserted through `Looper`.
 *  - **Single owner.** GMA can deliver two terminal callbacks for one request (#45). The first
 *    one owns the `AdView`; a later one must not destroy the banner `BannerCore` now holds.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AndroidBannerAdControllerSettlementTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `terminal callbacks touch no view until the main hop runs`() = runTest(dispatcher) {
        val main = ManualMainQueue()
        val views = RecordingAdViewFacade()
        val load = async { controller(views, main).load(BannerGeometry(320), AdSizePolicy.Fixed(320, 50), AdRequestOptions()) }
        runCurrent()

        views.callback().onAdLoaded(views.bannerAd)

        assertEquals(
            emptyList(),
            views.viewCalls,
            "a GMA callback runs on a background pool: no @MainThread AdView call may happen before the hop",
        )
        assertTrue(main.pending > 0, "the settle transaction must be queued for Main")

        main.drain()
        runCurrent()

        assertEquals(listOf("unregisterBannerAd"), views.viewCalls)
        assertIs<AdLoadState.Loaded>(load.await())
    }

    @Test
    fun `a duplicate success does not destroy the admitted banner`() = runTest(dispatcher) {
        val main = ManualMainQueue()
        val views = RecordingAdViewFacade()
        val load = async { controller(views, main).load(BannerGeometry(320), AdSizePolicy.Fixed(320, 50), AdRequestOptions()) }
        runCurrent()

        val callback = views.callback()
        callback.onAdLoaded(views.bannerAd)
        main.drain()
        runCurrent()
        assertIs<AdLoadState.Loaded>(load.await())

        // The second terminal callback for the same request. The AdView is the live banner
        // BannerCore owns by now.
        callback.onAdLoaded(views.bannerAd)
        main.drain()
        runCurrent()

        assertEquals(
            0,
            views.destroyCalls,
            "a duplicate onAdLoaded destroyed the banner BannerCore had just been handed",
        )
    }

    @Test
    fun `a failure arriving after success does not destroy the admitted banner`() = runTest(dispatcher) {
        val main = ManualMainQueue()
        val views = RecordingAdViewFacade()
        val load = async { controller(views, main).load(BannerGeometry(320), AdSizePolicy.Fixed(320, 50), AdRequestOptions()) }
        runCurrent()

        val callback = views.callback()
        callback.onAdLoaded(views.bannerAd)
        main.drain()
        runCurrent()
        assertIs<AdLoadState.Loaded>(load.await())

        callback.onAdFailedToLoad(loadAdError())
        main.drain()
        runCurrent()

        assertEquals(0, views.destroyCalls, "the losing failure callback must not destroy the live banner")
    }

    @Test
    fun `a failure that settles the load destroys its view exactly once`() = runTest(dispatcher) {
        val main = ManualMainQueue()
        val views = RecordingAdViewFacade()
        val error = loadAdError()
        val load = async { controller(views, main).load(BannerGeometry(320), AdSizePolicy.Fixed(320, 50), AdRequestOptions()) }
        runCurrent()

        val callback = views.callback()
        callback.onAdFailedToLoad(error)
        main.drain()
        runCurrent()
        assertIs<AdLoadState.Failed>(load.await())

        callback.onAdFailedToLoad(error)
        main.drain()
        runCurrent()

        assertEquals(1, views.destroyCalls, "the settling failure owns the view; a second callback must not re-destroy it")
    }

    /**
     * `LoadAdError.code` is an enum, and `toAdError` maps it by NAME — see the mapping contract
     * note in admob-cmp/CLAUDE.md. A mock returning null there would NPE in the mapper rather
     * than exercise ownership, so stub a real, non-retryable code.
     */
    private fun loadAdError(): LoadAdError = Mockito.mock(LoadAdError::class.java).also {
        Mockito.`when`(it.code).thenReturn(LoadAdError.ErrorCode.NO_FILL)
        Mockito.`when`(it.message).thenReturn("no fill")
    }

    private fun controller(views: RecordingAdViewFacade, main: ManualMainQueue) =
        AndroidBannerAdController(
            placement = AdPlacement(
                id = "banner",
                format = AdFormat.Banner,
                adUnitIds = AdUnitIds("test-android", "test-ios"),
            ),
            globalEvents = MutableSharedFlow(extraBufferCapacity = 16),
            adRequestBlockedError = { null },
            activityProvider = { Mockito.mock(android.app.Activity::class.java) },
            adViews = views,
            runOnMain = main::post,
        )

    /** A Main queue the test steps by hand, so "posted" and "executed" stay distinguishable. */
    private class ManualMainQueue {
        private val queue = ArrayDeque<() -> Unit>()
        val pending: Int get() = queue.size

        fun post(block: () -> Unit) {
            queue.addLast(block)
        }

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }
    }

    private class RecordingAdViewFacade : AndroidBannerAdViewFacade {
        val adView: AdView = Mockito.mock(AdView::class.java)
        val bannerAd: BannerAd = Mockito.mock(BannerAd::class.java)
        val viewCalls = mutableListOf<String>()
        var destroyCalls = 0
            private set
        private val callbacks = mutableListOf<AdLoadCallback<BannerAd>>()

        fun callback(): AdLoadCallback<BannerAd> = callbacks.single()

        override fun create(activity: android.app.Activity): AdView = adView

        override fun loadAd(adView: AdView, request: BannerAdRequest, callback: AdLoadCallback<BannerAd>) {
            callbacks += callback
        }

        override fun unregisterBannerAd(adView: AdView): BannerAd? {
            viewCalls += "unregisterBannerAd"
            return bannerAd
        }

        override fun destroy(adView: AdView) {
            viewCalls += "destroy"
            destroyCalls++
        }
    }
}
