package dev.avinya.ads.nativead

import android.content.ComponentCallbacks2
import android.content.Context
import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdUnitIds
import dev.avinya.ads.internal.NativeMemoryPressure
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdLoaderCallback
import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAdRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Before
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class AndroidNativeAdPlatformTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun installMainDispatcher() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun resetMainDispatcher() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `zero ad terminal completion is a load failure`() = runTest(dispatcher) {
        val loader = RecordingLoader()
        val result = async { AndroidNativeAdPlatform(loader).load(placement(), 3, 0) }
        runCurrent()

        assertEquals(1, loader.singleRequests)
        loader.callbacks.single().onAdLoadingCompleted()
        runCurrent()

        val failure = assertIs<AdAttemptResult.Failure>(result.await())
        assertEquals("INTERNAL_ERROR", failure.error.code)
        assertEquals(1, loader.singleRequests, "sequential loading must stop at its first terminal failure")
        assertEquals(0, loader.multiRequests)
    }

    @Test
    fun `sequential partial fill remains success with unfilled error`() = runTest(dispatcher) {
        val loader = RecordingLoader()
        val result = async { AndroidNativeAdPlatform(loader).load(placement(), 2, 0) }
        runCurrent()

        loader.callbacks[0].onNativeAdLoaded(Mockito.mock(com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd::class.java))
        loader.callbacks[0].onAdLoadingCompleted()
        runCurrent()
        loader.callbacks[1].onAdLoadingCompleted()
        runCurrent()

        val batch = assertIs<AdAttemptResult.Success<*>>(result.await()).value as dev.avinya.ads.internal.NativeAdPlatformBatch<*>
        assertEquals(1, batch.ads.size)
        assertEquals("INTERNAL_ERROR", batch.unfilledError?.code)
    }

    /**
     * A sequential batch cancelled midway destroys the ads it already collected.
     *
     * `Sequential` is the default batching, and the coordinator's load timeout spans the whole
     * batch rather than each request, so a slow network reaches this on ordinary traffic. Each
     * request's own cancellation handler only knows about its own pending list, so ads from
     * EARLIER requests were neither returned nor destroyed — and `placementIds` held a strong
     * reference to each one for the life of the process.
     */
    @Test
    fun `cancelling a sequential batch destroys the ads already accumulated`() = runTest(dispatcher) {
        val loader = RecordingLoader()
        val firstAd = Mockito.mock(com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd::class.java)
        val load = async { AndroidNativeAdPlatform(loader).load(placement(), 2, 0) }
        runCurrent()

        // Request one completes; request two is issued and never answers.
        loader.callbacks[0].onNativeAdLoaded(firstAd)
        loader.callbacks[0].onAdLoadingCompleted()
        runCurrent()
        assertEquals(2, loader.singleRequests, "precondition: the batch moved on to its second request")

        load.cancel()
        runCurrent()

        Mockito.verify(firstAd).destroy()
    }

    /**
     * A batch that completed while its caller was being cancelled is destroyed.
     *
     * This is the `withContext` prompt-cancellation guarantee: the value is discarded during
     * the dispatch back to the caller, with no cleanup hook anywhere in the platform.
     */
    @Test
    fun `a batch lost on the way back to the caller is destroyed`() = runTest(dispatcher) {
        val loader = RecordingLoader()
        val ad = Mockito.mock(com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd::class.java)
        val load = async { AndroidNativeAdPlatform(loader).load(placement(), 1, 0) }
        runCurrent()

        loader.callbacks[0].onNativeAdLoaded(ad)
        loader.callbacks[0].onAdLoadingCompleted()
        // Cancel before the completed result is dispatched back to the awaiting caller.
        load.cancel()
        runCurrent()

        Mockito.verify(ad).destroy()
    }

    @Test
    fun `destroy gate does not retain ads and runs teardown once`() {
        val gate = AndroidNativeDestroyGate()
        var calls = 0
        gate.destroyOnce { calls += 1 }
        gate.destroyOnce { calls += 1 }
        assertEquals(1, calls)
    }

    @Test
    fun `google only accepts exact counts one through five and rejects larger before GMA`() = runTest(dispatcher) {
        val loader = RecordingLoader()
        val platform = AndroidNativeAdPlatform(loader)

        val failure = supervisorScope {
            async { platform.load(placement(NativeAdBatching.GoogleOnly), 6, 0) }.runCatchingAwait()
        }
        assertEquals(IllegalArgumentException::class, failure!!::class)
        assertEquals(0, loader.singleRequests + loader.multiRequests)
    }

    @Test
    fun `zero count is rejected before touching GMA`() = runTest(dispatcher) {
        val loader = RecordingLoader()

        val failure = supervisorScope {
            async { AndroidNativeAdPlatform(loader).load(placement(), 0, 0) }.runCatchingAwait()
        }
        assertEquals(IllegalArgumentException::class, failure!!::class)
        assertEquals(0, loader.singleRequests + loader.multiRequests)
    }

    @Test
    fun `memory callback maps moderate and critical levels`() {
        assertEquals(
            NativeMemoryPressure.Moderate,
            AndroidNativeMemorySignal.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN),
        )
        assertEquals(
            NativeMemoryPressure.Moderate,
            AndroidNativeMemorySignal.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND),
        )
        assertEquals(
            NativeMemoryPressure.Critical,
            AndroidNativeMemorySignal.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW),
        )
        assertEquals(
            NativeMemoryPressure.Critical,
            AndroidNativeMemorySignal.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_COMPLETE),
        )
    }

    @Test
    fun `memory callback ignores levels without memory pressure`() {
        assertNull(AndroidNativeMemorySignal.memoryPressureFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
    }

    @Test
    fun `memory signal unregisters once after close`() {
        val context = Mockito.mock(Context::class.java)
        val application = Mockito.mock(Context::class.java)
        Mockito.`when`(context.applicationContext).thenReturn(application)

        val signal = AndroidNativeMemorySignal(context) {}
        signal.close()
        signal.close()

        Mockito.verify(application).registerComponentCallbacks(signal)
        Mockito.verify(application).unregisterComponentCallbacks(signal)
        Mockito.verifyNoMoreInteractions(application)
    }

    private fun placement(batching: NativeAdBatching = NativeAdBatching.Sequential) = AdPlacement(
        id = "native",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds("test-android", "test-ios"),
        nativeOptions = NativeAdOptions(batching = batching),
    )

    private class RecordingLoader : AndroidNativeAdLoaderFacade {
        var singleRequests = 0
        var multiRequests = 0
        val callbacks = mutableListOf<NativeAdLoaderCallback>()

        override fun loadOne(request: NativeAdRequest, callback: NativeAdLoaderCallback) {
            singleRequests++
            callbacks += callback
        }

        override fun loadMany(request: NativeAdRequest, count: Int, callback: NativeAdLoaderCallback) {
            multiRequests++
            callbacks += callback
        }
    }
}

private suspend fun <T> kotlinx.coroutines.Deferred<T>.runCatchingAwait(): Throwable? =
    try {
        await()
        null
    } catch (failure: Throwable) {
        failure
    }
