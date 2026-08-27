package dev.avinya.ads

import dev.avinya.ads.internal.BannerCore
import dev.avinya.ads.internal.BannerPlatform
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BannerCoreTest {

    private class FakeBanner(val widthDp: Int) {
        var destroyCount = 0
        val destroyed: Boolean get() = destroyCount > 0
    }

    private class FakeBannerPlatform : BannerPlatform<FakeBanner, Int> {
        var fallbackWidth: Int? = 360
        var nextResult: () -> AdAttemptResult<FakeBanner> = {
            AdAttemptResult.Success(FakeBanner(320))
        }
        var lastRequestOptions: AdRequestOptions? = null
        var lastSize: Int? = null
        val loadedPolicies = mutableListOf<AdSizePolicy>()
        val loadedOptions = mutableListOf<AdRequestOptions>()

        /** Runs between loadBanner being entered and it returning — lets a test detach mid-load. */
        var beforeReturn: (suspend () -> Unit)? = null

        /**
         * Fires once, immediately after the NEXT state-lock body completes.
         *
         * The seam for testing check-then-write races deterministically: it models a competing
         * caller taking the state lock at the earliest legal moment after the core released it.
         * A transition that decides under one acquisition and publishes under a second is
         * observably broken here; one that does both under a single acquisition is not.
         */
        var afterUnlock: (() -> Unit)? = null

        /** Set to make the response-info accessor throw, modelling a beta SDK accessor blowing up. */
        var responseInfoError: Throwable? = null

        // No real lock: commonTest has no multiplatform `synchronized`, and these tests
        // drive the core from a single runTest coroutine. Invoking directly is also
        // trivially reentrant, which is what the seam requires.
        override fun <T> withStateLock(block: () -> T): T {
            val result = block()
            afterUnlock?.let { hook ->
                // One-shot, and cleared BEFORE invoking so a hook that itself takes the lock
                // (clear() does) cannot re-enter itself.
                afterUnlock = null
                hook()
            }
            return result
        }

        override fun fallbackWidthDp(): Int? = fallbackWidth
        override fun resolveSize(sizePolicy: AdSizePolicy, widthDp: Int): Int = widthDp
        override fun destroy(banner: FakeBanner) {
            banner.destroyCount++
        }

        override fun responseInfo(banner: FakeBanner): AdResponseInfo? {
            responseInfoError?.let { throw it }
            return null
        }
        override suspend fun loadBanner(
            size: Int,
            sizePolicy: AdSizePolicy,
            requestOptions: AdRequestOptions,
            requiredGeneration: Long
        ): AdAttemptResult<FakeBanner> {
            loadedPolicies += sizePolicy
            loadedOptions += requestOptions
            lastSize = size
            lastRequestOptions = requestOptions
            beforeReturn?.invoke()
            return nextResult()
        }
    }

    private fun core(platform: FakeBannerPlatform) = BannerCore(
        placement = testBannerPlacement(),
        platform = platform,
        globalEvents = MutableSharedFlow(extraBufferCapacity = 32)
    )

    @Test
    fun geometryRejectsNonPositiveWidth() {
        assertFailsWith<IllegalArgumentException> { BannerGeometry(0) }
        assertFailsWith<IllegalArgumentException> { BannerGeometry(-1) }
    }

    @Test
    fun geometryKeepsPositiveWidth() {
        assertEquals(320, BannerGeometry(320).widthDp)
    }

    @Test
    fun loadWithoutGeometryFailsWhenThePlatformHasNoFallbackWidth() = runTest {
        val platform = FakeBannerPlatform().apply { fallbackWidth = null }
        val core = core(platform)

        val state = core.load(
            geometry = null,
            sizePolicy = AdSizePolicy.LargeAnchoredAdaptive(),
            requestOptions = testRequestOptions()
        ) { null }

        assertTrue(state is AdLoadState.Failed, "no geometry and no fallback must fail, not guess")
    }

    @Test
    fun hostGeometryWinsOverThePlatformFallback() = runTest {
        val platform = FakeBannerPlatform().apply { fallbackWidth = 999 }
        val core = core(platform)

        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }

        assertEquals(320, platform.lastSize, "host-supplied width must not be overridden by the fallback")
    }

    @Test
    fun refreshReplaysTheResolvedRequestOptions() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        val custom = testRequestOptions().copy(contentUrl = "https://example.com/article")

        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), custom) { null }
        platform.lastRequestOptions = null
        core.refresh { null }

        assertEquals(
            custom,
            platform.lastRequestOptions,
            "refresh() must replay the options the original load resolved, not placement defaults"
        )
    }

    @Test
    fun mutationAfterLoadOrRegisterDoesNotAffectRetainedRequest() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        val keywords = mutableSetOf("one")
        val options = testRequestOptions().copy(keywords = keywords)

        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), options) { null }
        keywords.add("two")

        core.refresh { null }
        assertEquals(
            setOf("one"),
            platform.lastRequestOptions?.keywords,
            "refresh() must use a snapshot of the request options from load(), not the caller's mutated object"
        )

        keywords.clear()
        keywords.add("three")

        val platform2 = FakeBannerPlatform()
        val core2 = core(platform2)
        core2.registerGeometry(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), options)
        keywords.add("four")

        core2.refresh { null }

        assertEquals(
            setOf("three"),
            platform2.lastRequestOptions?.keywords,
            "refresh() must use a snapshot of the request options from registerGeometry(), not the caller's mutated object"
        )
    }

    @Test
    fun refreshBeforeAnyLoadFails() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)

        val state = core.refresh { null }

        assertTrue(state is AdLoadState.Failed)
    }

    @Test
    fun aFailedRefreshKeepsThePreviousBannerAlive() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }
        val first = assertNotNull(core.currentBanner())

        platform.nextResult = { AdAttemptResult.Failure(AdError.message("no fill")) }
        core.refresh { null }

        assertTrue(!first.destroyed, "the displayed banner must survive a failed refresh (no blank flash)")
        assertEquals(first, core.currentBanner())
        // A banner is still on screen, so the publicly observable state must say so too —
        // a host reacting to Failed by hiding the slot would otherwise hide a live ad.
        assertTrue(
            core.loadState.value is AdLoadState.Loaded,
            "a banner is still displayed after the failed refresh, so loadState must stay Loaded; " +
                "was ${core.loadState.value}"
        )
    }

    @Test
    fun aFailedLoadWithNoDisplayedBannerPublishesFailed() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        platform.nextResult = { AdAttemptResult.Failure(AdError.message("no fill")) }

        val state = core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }

        assertNull(core.currentBanner(), "no banner was ever loaded, so nothing should be displayed")
        assertTrue(
            state is AdLoadState.Failed,
            "a failed load with no displayed banner must publish Failed, not Loaded; was $state"
        )
    }

    /**
     * Pins the retention question flagged as an uncertainty in the plan for Task 11.
     *
     * After the extraction `attach`/`detach` live in the core while iOS's `activeLoad`
     * registry stays in `IosBannerAdController`, so the core's `detach()` bumps the
     * generation without that file knowing. The worry was that an in-flight banner arriving
     * afterwards would either be adopted by a controller nobody is attached to, or be torn
     * down twice (once by the core's stale-generation rejection, once by the platform's own
     * cleanup).
     *
     * Exactly-once destruction is the assertion that distinguishes those outcomes, and it is
     * checked here rather than in `iosTest` because reaching `loadBanner` on iOS needs a real
     * `GADBannerView` load that never completes under a unit test — the same chicken-and-egg
     * the plan records for the native pools (E-4). The behaviour under test is the core's, so
     * this is where it is exercisable.
     */
    @Test
    fun aBannerArrivingAfterTheLastDetachIsNotAdoptedAndIsDestroyedExactlyOnce() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        core.attach()
        val late = FakeBanner(320)
        platform.nextResult = { AdAttemptResult.Success(late) }
        // The last attachment leaves while the load is in flight, so the banner that
        // eventually arrives belongs to a generation nobody is attached to.
        platform.beforeReturn = { core.detach() }

        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }

        assertNull(core.currentBanner(), "a banner arriving after the last detach must not be adopted")
        assertEquals(1, late.destroyCount, "the in-flight banner must be torn down exactly once")
        assertEquals(AdLoadState.Idle, core.loadState.value)
    }

    @Test
    fun aBannerArrivingAfterClearIsNotAdoptedAndIsDestroyedExactlyOnce() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        val late = FakeBanner(320)
        platform.nextResult = { AdAttemptResult.Success(late) }
        platform.beforeReturn = { core.clear() }

        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }

        assertNull(core.currentBanner(), "a cleared controller must not adopt a late banner")
        assertEquals(1, late.destroyCount, "the in-flight banner must be torn down exactly once")
    }

    @Test
    fun anUnexpectedThrowableDuringLoadDoesNotStrandTheStateInLoading() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        platform.beforeReturn = { throw IllegalStateException("beta SDK mapper blew up") }

        runCatching {
            core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }
        }

        // Pins: an arbitrary Throwable must not escape with loadState stuck at Loading — the
        // refresh loop waits on `loadState !is Loading`, so it would never resume.
        assertTrue(
            core.loadState.value !is AdLoadState.Loading,
            "an unexpected throwable must not strand the controller in Loading; was ${core.loadState.value}"
        )
    }

    @Test
    fun anUnexpectedThrowableKeepsTheDisplayedBannerAndItsLoadedState() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }
        val displayed = assertNotNull(core.currentBanner())

        platform.beforeReturn = { throw IllegalStateException("boom") }
        runCatching { core.refresh { null } }

        assertTrue(!displayed.destroyed, "a throwing refresh must not tear down the displayed banner")
        assertTrue(
            core.loadState.value is AdLoadState.Loaded,
            "a banner is still on screen, so the state must stay Loaded; was ${core.loadState.value}"
        )
    }

    @Test
    fun aBannerLoadThatNeverCallsBackTimesOut() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)
        platform.beforeReturn = { kotlinx.coroutines.awaitCancellation() }

        val state = core.load(
            BannerGeometry(320),
            AdSizePolicy.LargeAnchoredAdaptive(),
            testRequestOptions()
        ) { null }

        // Unbounded, this leaves loadState at Loading forever — and BannerAdView's refresh
        // loop awaits `loadState !is Loading`, so refresh dies silently for the placement.
        assertTrue(state is AdLoadState.Failed, "a banner load with no callback must fail on timeout")
    }

    @Test
    fun `per call collapsible policy reaches platform load`() = runTest {
        val policy = AdSizePolicy.LargeAnchoredAdaptive(CollapsiblePlacement.Top)
        val platform = FakeBannerPlatform()
        val core = core(platform)

        core.load(BannerGeometry(320), policy, AdRequestOptions()) { null }

        assertEquals(listOf<AdSizePolicy>(policy), platform.loadedPolicies)
    }

    @Test
    fun `refresh replays per call collapsible policy`() = runTest {
        val policy = AdSizePolicy.LargeAnchoredAdaptive(CollapsiblePlacement.Bottom)
        val platform = FakeBannerPlatform()
        val core = core(platform)

        core.load(BannerGeometry(320), policy, AdRequestOptions()) { null }
        core.refresh { null }

        assertEquals(listOf<AdSizePolicy>(policy, policy), platform.loadedPolicies)
    }

    // The replay record has two owners: load() owns requestOptions, the host's
    // container measurement owns size/sizePolicy. These two tests pin BOTH halves. They must
    // pass together — an implementation that picks one whole record over another (rather than
    // merging per-owner on write) fails exactly one of them, which is how this regressed twice.

    @Test
    fun `registerGeometry after a load updates the replayed width`() = runTest {
        val policy = AdSizePolicy.LargeAnchoredAdaptive()
        val platform = FakeBannerPlatform()
        val core = core(platform)

        // Portrait: a real load resolves the request at 411dp.
        core.load(BannerGeometry(411), policy, AdRequestOptions()) { null }
        assertEquals(411, platform.lastSize)

        // Rotate. The composable re-measures and registers the new geometry without loading.
        core.registerGeometry(BannerGeometry(891), policy, AdRequestOptions())
        core.refresh { null }

        assertEquals(
            891,
            platform.lastSize,
            "refresh() must replay the newest measured width, not the width of the last load"
        )
    }

    @Test
    fun `registerGeometry preserves the request options a load resolved`() = runTest {
        val policy = AdSizePolicy.LargeAnchoredAdaptive()
        val platform = FakeBannerPlatform()
        val core = core(platform)
        val custom = AdRequestOptions(keywords = setOf("premium"), contentUrl = "https://example.com")

        // A real load resolves custom options that are NOT placement.requestOptions.
        core.load(BannerGeometry(411), policy, custom) { null }

        // The composable re-measures, passing the placement defaults — a re-measure is not a
        // statement about request options and must not overwrite them.
        core.registerGeometry(BannerGeometry(891), policy, AdRequestOptions())
        core.refresh { null }

        assertEquals(891, platform.lastSize, "geometry half must still update")
        assertEquals(
            custom,
            platform.lastRequestOptions,
            "refresh() must replay the options load() resolved, not the re-measure's defaults"
        )
    }

    @Test
    fun `registerGeometry seeds the replay record when no load has happened`() = runTest {
        val policy = AdSizePolicy.LargeAnchoredAdaptive()
        val platform = FakeBannerPlatform()
        val core = core(platform)
        val manualOptions = AdRequestOptions(keywords = setOf("manual"))

        // BannerRefreshPolicy.Manual: the composable registers geometry and never loads, so
        // registerGeometry is the only source of request options for the first refresh().
        core.registerGeometry(BannerGeometry(411), policy, manualOptions)
        core.refresh { null }

        assertEquals(411, platform.lastSize)
        assertEquals(manualOptions, platform.lastRequestOptions)
    }

    @Test
    fun `cancelling a refresh keeps the displayed banner reported as Loaded`() = runTest {
        val platform = FakeBannerPlatform()
        val displayed = FakeBanner(320)
        platform.nextResult = { AdAttemptResult.Success(displayed) }
        val core = core(platform)
        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }
        assertTrue(core.loadState.value is AdLoadState.Loaded)

        // A replacement load is cancelled mid-flight — a restarted LaunchedEffect, a resize,
        // or an explicit cancel. The previously displayed banner is untouched and still on
        // screen, so the published state must say so. Publishing Idle here made BannerAdView
        // drop its reference and blank a live ad.
        val job = launch {
            platform.beforeReturn = { awaitCancellation() }
            core.refresh { null }
        }
        // runCurrent, NOT advanceUntilIdle: advancing the virtual clock fires the 30s load
        // timeout first, and the test would then pass through failOrRestore without ever
        // exercising cancellation. The currentTime assertion below keeps that honest.
        runCurrent()
        job.cancelAndJoin()

        assertEquals(0L, testScheduler.currentTime, "the load timeout must not have fired")
        assertTrue(
            core.loadState.value is AdLoadState.Loaded,
            "cancellation must not report Idle while the core still owns a displayed banner"
        )
        assertEquals(0, displayed.destroyCount, "the displayed banner must survive the cancelled load")
    }

    @Test
    fun `cancelling the first load with no displayed banner reports Idle`() = runTest {
        val platform = FakeBannerPlatform()
        val core = core(platform)

        // The mirror case: nothing is owned, so Idle is the correct terminal state. Guards
        // against "restore Loaded" over-correcting into a Loaded state with no ad behind it.
        val job = launch {
            platform.beforeReturn = { awaitCancellation() }
            core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }
        }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(0L, testScheduler.currentTime, "the load timeout must not have fired")
        assertEquals(AdLoadState.Idle, core.loadState.value)
    }

    @Test
    fun `clear racing failed recovery does not resurrect Loaded`() = runTest {
        val platform = FakeBannerPlatform()
        val displayed = FakeBanner(320)
        platform.nextResult = { AdAttemptResult.Success(displayed) }
        val core = core(platform)
        core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }
        assertTrue(core.loadState.value is AdLoadState.Loaded)

        // The refresh fails. Arm clear() to land at the earliest legal moment after the
        // recovery path's decision — the window a check-then-write failOrRestore left open.
        // clear() retires and destroys the displayed banner and publishes Idle.
        platform.nextResult = { AdAttemptResult.Failure(AdError.message("refresh failed")) }
        platform.beforeReturn = { platform.afterUnlock = { core.clear() } }
        core.refresh { null }

        assertEquals(
            AdLoadState.Idle,
            core.loadState.value,
            "a completed clear() must not be overwritten by recovery's stale banner check"
        )
        assertTrue(displayed.destroyed, "clear() still owns teardown of the retired banner")
    }

    @Test
    fun `a throwing response info accessor destroys the banner it could not admit`() = runTest {
        val platform = FakeBannerPlatform()
        val loaded = FakeBanner(320)
        platform.nextResult = { AdAttemptResult.Success(loaded) }
        platform.responseInfoError = IllegalStateException("SDK accessor blew up")
        val core = core(platform)

        // The banner never reaches the `banner` field, so no later clear()/detach can reach
        // it — the load path is its only chance at teardown. (runCatching, not assertFailsWith:
        // the latter's block is not a suspend lambda, so load() cannot be called inside it.)
        val thrown = runCatching {
            core.load(BannerGeometry(320), AdSizePolicy.LargeAnchoredAdaptive(), testRequestOptions()) { null }
        }
        assertTrue(
            thrown.exceptionOrNull() is IllegalStateException,
            "the accessor's throwable must propagate, not be swallowed"
        )

        assertTrue(loaded.destroyed, "a banner that failed admission must not leak")
        assertNull(core.currentBanner())
    }
}
