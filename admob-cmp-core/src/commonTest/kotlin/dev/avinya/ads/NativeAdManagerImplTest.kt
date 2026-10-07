package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdManagerImpl
import dev.avinya.ads.internal.NativeAdPlatform
import dev.avinya.ads.internal.NativeAdPlatformBatch
import dev.avinya.ads.internal.NativeAdRecordId
import dev.avinya.ads.internal.NativeAdRenderLeaseProvider
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.ads.nativead.NativeAdManagerState
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeAdManagerImplTest {
    @Test
    fun `same session key shares coordinator state and rejects a conflicting policy`() = runTest {
        val manager = NativeAdManagerImpl(
            policy = NativeAdMemoryPolicy(),
            platform = EmptyNativePlatform,
        )

        val first = manager.session("feed")
        val again = manager.session("feed")

        assertSame(first.state, again.state)
        assertFailsWith<IllegalStateException> {
            manager.session("feed", NativeAdSessionPolicy(maxRetainedAds = 2, retainBehind = 0, prefetchAhead = 0))
        }
    }

    @Test
    fun `deferred manager binds the first accepted memory policy without creating a default coordinator`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val policy = NativeAdMemoryPolicy(softLimit = 2, hardLimit = 3)

        assertEquals(null, manager.configuredPolicyOrNull())
        assertEquals(0, manager.state.value.loadedAds)
        manager.configure(policy)

        assertEquals(policy, manager.policy)
        manager.configure(policy)
        assertFailsWith<IllegalStateException> {
            manager.configure(NativeAdMemoryPolicy(softLimit = 1, hardLimit = 2))
        }
    }

    @Test
    fun `session before configure returns an inert handle instead of throwing`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val session = manager.session("feed")
        assertEquals(NativeAdMemoryPolicy(), manager.policy)
        assertFalse(session.state.value.active)
        assertTrue(session.state.value.slots.isEmpty())

        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", testNativePlacement()))))
        session.deactivate()
        session.close()
        assertFalse(session.state.value.active)
    }

    @Test
    fun `dormant session reuses one handle per key and rejects a conflicting policy`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val first = manager.session("feed")
        val again = manager.session("feed")

        assertSame(first, again)
        assertSame(first.state, again.state)

        assertFailsWith<IllegalStateException> {
            manager.session("feed", NativeAdSessionPolicy(maxRetainedAds = 2, retainBehind = 0, prefetchAhead = 0))
        }
        assertFailsWith<IllegalArgumentException> {
            manager.session("")
        }
    }

    @Test
    fun `configure materialises a dormant session and replays its last window`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val session = manager.session("feed")
        val placement = testNativePlacement()
        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", placement))))

        manager.configure(NativeAdMemoryPolicy())

        assertTrue(session.state.value.active)
        assertEquals(NativeAdSlotState.Loading, session.state.value.slots["slot"])
    }

    @Test
    fun `a dormant session closed before configure is never materialised`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val session = manager.session("feed")
        session.close()
        manager.configure(NativeAdMemoryPolicy())

        assertFalse(session.state.value.active)
    }

    @Test
    fun `a dormant session deactivated before configure materialises inactive`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val session = manager.session("feed")
        val placement = testNativePlacement()
        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", placement))))
        session.deactivate()

        manager.configure(NativeAdMemoryPolicy())
        assertFalse(session.state.value.active)

        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", placement))))
        assertTrue(session.state.value.active)
    }

    @Test
    fun `acquireRender on a dormant session returns null`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val session = manager.session("feed")
        val placement = testNativePlacement()

        val provider = manager as NativeAdRenderLeaseProvider<String>
        val render = provider.acquireRender("slot", placement, "renderer-1", session)
        assertEquals(null, render)

        provider.releaseRender("slot", placement, "renderer-1", NativeAdRecordId(1), session)
    }

    @Test
    fun `closeSession removes a dormant entry`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val session = manager.session("feed")
        val placement = testNativePlacement()
        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", placement))))
        manager.closeSession("feed")

        manager.configure(NativeAdMemoryPolicy())
        assertFalse(session.state.value.active)
    }

    @Test
    fun `clear does not remove a dormant entry`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        val session = manager.session("feed")
        val placement = testNativePlacement()
        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", placement))))
        manager.clear()

        manager.configure(NativeAdMemoryPolicy())
        assertTrue(session.state.value.active)
    }

    @Test
    fun `dormant registry rejects more than maxSessionRecords keys`() {
        val manager = NativeAdManagerImpl<String>(policy = null, platform = EmptyNativePlatform)
        repeat(64) { i ->
            manager.session("feed-$i")
        }
        assertFailsWith<IllegalStateException> {
            manager.session("feed-overflow")
        }
    }

    @Test
    fun `closed wrapper cannot mutate replacement generation`() = runTest {
        val manager = NativeAdManagerImpl(NativeAdMemoryPolicy(), EmptyNativePlatform)
        val stale = manager.session("feed")
        stale.close()
        val replacement = manager.session("feed")

        assertFailsWith<IllegalStateException> {
            stale.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", testNativePlacement()))))
        }
        replacement.deactivate()
        assertFalse(replacement.state.value.active)
    }

    @Test
    fun `clear preserves session definition and permits fresh demand`() = runTest {
        val manager = NativeAdManagerImpl(NativeAdMemoryPolicy(), EmptyNativePlatform)
        val session = manager.session("feed")
        manager.clear()

        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", testNativePlacement()))))
        assertEquals(true, session.state.value.active)
    }

    @Test
    fun `manager state follows asynchronous reservation admission and retirement`() = runTest {
        val platform = GateNativePlatform()
        val manager = NativeAdManagerImpl(
            policy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 1),
            platform = platform,
            scope = backgroundScope,
        )
        val session = manager.session("feed")

        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("slot", testNativePlacement()))))
        runCurrent()
        assertEquals(0, manager.state.value.loadedAds)
        assertEquals(1, manager.state.value.reservedLoads)

        platform.complete("native-ad")
        runCurrent()
        assertEquals(1, manager.state.value.loadedAds)
        assertEquals(0, manager.state.value.reservedLoads)

        manager.clear()
        assertEquals(0, manager.state.value.loadedAds)
        assertEquals(0, manager.state.value.reservedLoads)
    }

    @Test
    fun `reuse of unshown ads is off by default`() {
        val manager = NativeAdManagerImpl<String>(policy = NativeAdMemoryPolicy(), platform = EmptyNativePlatform)
        assertFalse(manager.reuseUnshownAds)
    }

    @Test
    fun `a reuse setting made before configure applies once native ads start`() = runTest {
        val platform = CountingNativePlatform()
        val manager = NativeAdManagerImpl(policy = null, platform = platform, scope = backgroundScope)
        manager.reuseUnshownAds = true
        manager.configure(NativeAdMemoryPolicy())
        val session = manager.session("feed")

        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("a", testNativePlacement()))))
        runCurrent()
        session.updateWindow(NativeAdWindow(emptyList()))
        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("b", testNativePlacement()))))
        runCurrent()

        assertTrue(manager.reuseUnshownAds)
        assertEquals(1, platform.loads)
        assertTrue(platform.destroyed.isEmpty())
    }

    @Test
    fun `turning reuse off destroys the kept ads`() = runTest {
        val platform = CountingNativePlatform()
        val manager = NativeAdManagerImpl(policy = NativeAdMemoryPolicy(), platform = platform, scope = backgroundScope)
        manager.reuseUnshownAds = true
        val session = manager.session("feed")
        session.updateWindow(NativeAdWindow(listOf(NativeAdSlot("a", testNativePlacement()))))
        runCurrent()
        session.updateWindow(NativeAdWindow(emptyList()))

        manager.reuseUnshownAds = false

        assertEquals(listOf("ad-0"), platform.destroyed)
    }

    @Test
    fun `a NativeAdManager that does not override reuse keeps an inert off switch`() {
        val custom = object : NativeAdManager {
            override val policy: NativeAdMemoryPolicy = NativeAdMemoryPolicy()
            override val state: StateFlow<NativeAdManagerState> = MutableStateFlow(NativeAdManagerState(0, 0, 0, 0, 6))
            override fun session(key: String, policy: NativeAdSessionPolicy): NativeAdSession = error("unused")
            override fun closeSession(key: String) = Unit
            override fun clear() = Unit
        }

        custom.reuseUnshownAds = true

        assertFalse(custom.reuseUnshownAds)
    }
}

private class GateNativePlatform : NativeAdPlatform<String> {
    private val result = CompletableDeferred<AdAttemptResult<NativeAdPlatformBatch<String>>>()

    fun complete(ad: String) {
        result.complete(AdAttemptResult.Success(NativeAdPlatformBatch(ads = listOf(ad), unfilledError = null)))
    }

    override suspend fun load(placement: AdPlacement, count: Int, generation: Long): AdAttemptResult<NativeAdPlatformBatch<String>> = result.await()
    override suspend fun bindEvents(ad: String, adInstanceId: String, emit: (AdEvent) -> Unit) = Unit
    override fun destroy(ad: String) = Unit
    override fun responseInfo(ad: String): AdResponseInfo? = null
    override fun mediaInfo(ad: String) = null
}

private object EmptyNativePlatform : NativeAdPlatform<String> {
    override suspend fun load(placement: AdPlacement, count: Int, generation: Long): AdAttemptResult<NativeAdPlatformBatch<String>> =
        AdAttemptResult.Success(NativeAdPlatformBatch(emptyList(), AdError.sdkNotReady()))
    override suspend fun bindEvents(ad: String, adInstanceId: String, emit: (AdEvent) -> Unit) = Unit
    override fun destroy(ad: String) = Unit
    override fun responseInfo(ad: String): AdResponseInfo? = null
    override fun mediaInfo(ad: String) = null
}

private class CountingNativePlatform : NativeAdPlatform<String> {
    var loads = 0
    val destroyed = mutableListOf<String>()

    override suspend fun load(placement: AdPlacement, count: Int, generation: Long): AdAttemptResult<NativeAdPlatformBatch<String>> {
        val first = loads
        loads += 1
        return AdAttemptResult.Success(NativeAdPlatformBatch(ads = (0 until count).map { "ad-${first + it}" }, unfilledError = null))
    }
    override suspend fun bindEvents(ad: String, adInstanceId: String, emit: (AdEvent) -> Unit) = Unit
    override fun destroy(ad: String) { destroyed += ad }
    override fun responseInfo(ad: String): AdResponseInfo? = null
    override fun mediaInfo(ad: String) = null
}
