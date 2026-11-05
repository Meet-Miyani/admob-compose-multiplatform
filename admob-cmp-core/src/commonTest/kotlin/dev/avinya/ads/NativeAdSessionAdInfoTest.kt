package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdCoordinatorCore
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.internal.NativeMemoryPressure
import dev.avinya.ads.nativead.NativeAdBatching
import dev.avinya.ads.nativead.NativeAdInfo
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdOptions
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAdSessionAdInfoTest {

    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val nativePlacement = AdPlacement(
        id = "p",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds(android = "x", ios = "y"),
    )

    @AfterTest
    fun teardown() {
        scope.cancel()
    }

    private fun coordinator(
        memoryPolicy: NativeAdMemoryPolicy = NativeAdMemoryPolicy(),
        platform: FakePlatform,
    ): NativeAdCoordinatorCore<FakeAd> = NativeAdCoordinatorCore(
        memoryPolicy = memoryPolicy,
        platform = platform,
        scope = scope,
        eventSink = {},
    )

    private fun windowWith(vararg visible: String): NativeAdWindow = NativeAdWindow(
        visible = visible.map { NativeAdSlot(it, nativePlacement) },
    )

    private fun countingPlatform(): FakePlatform {
        var next = 0
        return FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(next++) }, null))
        }
    }

    @Test fun `an admitted ad publishes its id and response info`() = runTest(dispatcher) {
        val ad = FakeAd(0)
        val platform = FakePlatform { _, _, _ -> AdAttemptResult.Success(NativeAdPlatformBatch(listOf(ad), null)) }
        platform.responseInfoFor = { AdResponseInfo(responseId = "r-${it.id}") }
        val emitted = mutableListOf<AdEvent>()
        val coord = NativeAdCoordinatorCore(
            memoryPolicy = NativeAdMemoryPolicy(),
            platform = platform,
            scope = scope,
            eventSink = emitted::add,
        )
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()

        val entryId = platform.boundIds.getValue(ad)
        assertEquals(NativeAdInfo(entryId, "p", AdResponseInfo(responseId = "r-0")), session.adInfo.value["a"])
        val render = coord.acquireForRender("s1", coord.sessionGeneration("s1")!!, "a", nativePlacement, "renderer")
        assertEquals(entryId, render?.adInstanceId)
        platform.emit(ad, AdEvent.Impression("p", entryId))
        assertTrue(emitted.contains(AdEvent.Impression("p", entryId)), "the impression must reach the sink: $emitted")
    }

    @Test fun `a batched load gives each slot its own info`() = runTest(dispatcher) {
        val placement = nativePlacement.copy(nativeOptions = NativeAdOptions(batching = NativeAdBatching.GoogleOnly))
        val platform = FakePlatform { _, count, _ ->
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map(::FakeAd), null))
        }
        platform.responseInfoFor = { AdResponseInfo(responseId = "r-${it.id}") }
        val coord = coordinator(memoryPolicy = NativeAdMemoryPolicy(softLimit = 6, hardLimit = 6), platform = platform)
        val session = coord.session("s1", NativeAdSessionPolicy(maxRetainedAds = 3))
        coord.updateWindow("s1", NativeAdWindow(visible = listOf("a", "b", "c").map { NativeAdSlot(it, placement) }))
        advanceUntilIdle()

        val infos = session.adInfo.value
        assertEquals(3, infos.size)
        assertEquals(3, infos.values.map { it.adInstanceId }.toSet().size)
        assertEquals(setOf("r-0", "r-1", "r-2"), infos.values.map { it.responseInfo?.responseId }.toSet())
    }

    @Test fun `expiry then reload gives the slot a new adInstanceId`() = runTest(dispatcher) {
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        val before = session.adInfo.value.getValue("a").adInstanceId

        coord.tickForTest(61.minutes)
        advanceUntilIdle()

        val after = session.adInfo.value.getValue("a").adInstanceId
        assertNotEquals(before, after)
        assertEquals(2, platform.loadCalls.size)
    }

    @Test fun `an expired ad replaced by a kept ad changes adInfo while state stays equal`() = runTest(dispatcher) {
        val placement = nativePlacement.copy(
            cachePolicy = AdCachePolicy(expirationPolicy = AdExpirationPolicy(nativeTtl = 40.minutes)),
        )
        val platform = countingPlatform()
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        val session = coord.session("s1", NativeAdSessionPolicy(maxRetainedAds = 2, retainBehind = 0, prefetchAhead = 0))
        fun window(vararg keys: String) = NativeAdWindow(visible = keys.map { NativeAdSlot(it, placement) })

        coord.updateWindow("s1", window("a"))
        advanceUntilIdle()
        coord.tickForTest(20.minutes)
        coord.updateWindow("s1", window("a", "b"))
        advanceUntilIdle()
        val idB = session.adInfo.value.getValue("b").adInstanceId
        coord.updateWindow("s1", window("a"))
        advanceUntilIdle()
        val stateBefore = session.state.value.slots["a"]
        val idBefore = session.adInfo.value.getValue("a").adInstanceId

        coord.tickForTest(20.minutes + 1.seconds)
        advanceUntilIdle()

        assertEquals(stateBefore, session.state.value.slots["a"])
        assertEquals(idB, session.adInfo.value.getValue("a").adInstanceId)
        assertNotEquals(idBefore, idB)
        assertEquals(2, platform.loadCalls.size, "the expired slot must adopt the kept ad, not load again")
    }

    @Test fun `a kept ad moves to the adopting slot with the same info instance`() = runTest(dispatcher) {
        val coord = coordinator(platform = countingPlatform())
        coord.setReuseUnshownAds(true)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        val captured = session.adInfo.value.getValue("a")

        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        advanceUntilIdle()
        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        assertSame(captured, session.adInfo.value["b"])
        assertNull(session.adInfo.value["a"])
    }

    @Test fun `a late arrival keeps its info when a slot adopts it`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var next = 0
        val platform = FakePlatform { _, count, _ ->
            gate.await()
            AdAttemptResult.Success(NativeAdPlatformBatch((0 until count).map { FakeAd(next++) }, null))
        }
        platform.responseInfoFor = { AdResponseInfo(responseId = "r-${it.id}") }
        val coord = coordinator(platform = platform)
        coord.setReuseUnshownAds(true)
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        runCurrent()
        coord.updateWindow("s1", NativeAdWindow(visible = emptyList()))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        coord.updateWindow("s1", windowWith("b"))
        advanceUntilIdle()

        assertEquals("r-0", session.adInfo.value["b"]?.responseInfo?.responseId)
    }

    @Test fun `memory pressure drops the evicted slots info`() = runTest(dispatcher) {
        val coord = coordinator(platform = countingPlatform())
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        assertTrue("a" in session.adInfo.value)

        coord.onMemoryPressure(NativeMemoryPressure.Critical)

        assertFalse("a" in session.adInfo.value)
    }

    @Test fun `consent revocation empties the ad map`() = runTest(dispatcher) {
        val coord = coordinator(platform = countingPlatform())
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        assertTrue(session.adInfo.value.isNotEmpty())

        coord.onConsentRevoked()

        assertTrue(session.adInfo.value.isEmpty())
    }

    @Test fun `a reaped inactive session empties its ad map`() = runTest(dispatcher) {
        val coord = coordinator(platform = countingPlatform())
        val session = coord.session("s1")
        coord.updateWindow("s1", windowWith("a"))
        advanceUntilIdle()
        coord.deactivateSession("s1")
        assertTrue(session.adInfo.value.isNotEmpty(), "the retained anchor keeps its ad while inactive")

        coord.tickForTest(31.minutes)

        assertTrue(session.adInfo.value.isEmpty())
    }
}
