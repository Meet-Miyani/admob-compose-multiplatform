@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdAvailability
import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdLogger
import dev.avinya.ads.AdLoadState
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdRequestOptions
import dev.avinya.ads.AdResponseInfo
import dev.avinya.ads.AdReward
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdShowResult
import dev.avinya.ads.FullScreenAdController
import dev.avinya.ads.FullScreenAdOptions
import dev.avinya.ads.isRetryableLoadFailure
import dev.avinya.ads.retryAdLoad
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ownership token for one SDK presentation.
 *
 * The core owns the token until the platform has installed its terminal callbacks and is about
 * to call the SDK. From that point on, dismissal/failure owns closing it. The atomic state lets a
 * synchronous failure and a late SDK callback race without double-destroying the ad or decrementing
 * the process-wide presentation count twice.
 *
 * The handle also owns the **lifetime of the per-presentation audio override**. It used to live
 * beside the handle, applied before the `try` in [FullScreenSlotCore.showInternal] and restored in
 * that function's `finally`, which produced three separate defects: an override that threw stranded
 * the arbiter token and presence counter for the process lifetime; caller cancellation after SDK
 * hand-off restored audio while the ad was still on screen; and a throwing restore replaced the
 * primary result. Ownership by the handle fixes all three, because [close]/[closeIfCoreOwned] are
 * already the exactly-once terminal path.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class FullScreenPresentationHandle(
    private val onClosed: (wasShown: Boolean) -> Unit
) {
    private val state = AtomicInt(CORE_OWNED)
    private val audioRestore = AtomicReference<AudioRestoreHandle?>(null)

    /**
     * Atomically transfers terminal ownership to the platform callbacks.
     *
     * A cancellation handler closes a still-core-owned handle. Returning false therefore means
     * cancellation won the presentation decision and the platform must not invoke the SDK show.
     */
    internal fun tryHandOffToCallbacks(): Boolean =
        state.compareAndSet(CORE_OWNED, CALLBACK_OWNED)

    /**
     * Hands the per-presentation audio restoration to the handle, so it runs on the terminal close
     * rather than when the *caller* stops waiting.
     *
     * Called once, by the core, after the overrides have been applied and before the presentation
     * is launched. The CLOSED re-check is defence in depth: no path outside `showInternal` can
     * close a handle this early, but if one ever could, the override must not leak.
     */
    internal fun attachAudioRestore(restore: AudioRestoreHandle?) {
        if (restore == null) return
        audioRestore.store(restore)
        if (state.load() == CLOSED) runAudioRestore()
    }

    /**
     * Restores audio exactly once, swallowing failure.
     *
     * [AtomicReference.exchange] makes this exactly-once even if [attachAudioRestore] races a
     * terminal close. Failure is logged, never propagated: this runs inside the terminal close, so
     * a throw here would break the arbiter release that follows it and strand the process-wide
     * presentation token — the very failure mode the audio work is trying to avoid.
     */
    private fun runAudioRestore() {
        val restore = audioRestore.exchange(null) ?: return
        try {
            restore.restore()
        } catch (t: Throwable) {
            try {
                AdLogger.e("Failed to restore full-screen audio state after presentation.", t)
            } catch (_: Throwable) { /* logger failure must not break the terminal close */ }
        }
    }

    /** Returns true only to the callback/fallback path that performed the terminal close. */
    internal fun close(wasShown: Boolean): Boolean {
        while (true) {
            val current = state.load()
            if (current == CLOSED) return false
            if (state.compareAndSet(current, CLOSED)) {
                // BEFORE onClosed, which releases the arbiter. Once that release happens another
                // slot may immediately apply its own overrides, so a restore sequenced after it
                // would clobber the NEXT ad's audio.
                runAudioRestore()
                onClosed(wasShown)
                return true
            }
        }
    }

    /** Cancellation before SDK hand-off has no callback that could close the token. */
    internal fun closeIfCoreOwned(): Boolean {
        if (!state.compareAndSet(CORE_OWNED, CLOSED)) return false
        // Ordered before onClosed for the same reason as in close().
        runAudioRestore()
        onClosed(false)
        return true
    }

    private companion object {
        const val CORE_OWNED = 0
        const val CALLBACK_OWNED = 1
        const val CLOSED = 2
    }
}

@OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)
internal abstract class FullScreenSlotCore<AdT : Any>(
    override val placement: AdPlacement,
    private val globalEvents: MutableSharedFlow<AdEvent>,
    private val adRequestBlockedError: () -> AdError?,
    internal val clock: () -> Instant = { Clock.System.now() },
    private val onPresentationChanged: (Int) -> Unit,
    private val arbiter: FullScreenPresentationArbiter,
    private val audioController: FullScreenAudioController? = null,
    // Test-only interleaving seam; no-op in production. Runs immediately before show()'s
    // selection transaction, which is the last point at which a coroutine can suspend before
    // the cache removal and arbiter acquisition commit together. There is no suspension point
    // inside that transaction by design — it runs under publicationLock — so this is the only
    // place two slots can be deterministically interleaved on a single-threaded test dispatcher.
    private val beforeShowCommit: suspend () -> Unit = {}
) : FullScreenAdController {
    // loadMutex coalesces callers across SDK attempts/retry delay. slotState atomically owns the
    // generation, public load state, and immutable FIFO cache. operationMutex only serializes the
    // short show-selection transaction, while publicationLock linearizes cache visibility with
    // load events. Neither short mutation lock is held across network/retry/presentation.
    private val loadMutex = Mutex()
    private val operationMutex = Mutex()
    private val publicationLock = FullScreenStateLock()
    private val slotState = MutableStateFlow(
        SlotState<AdT>(
            generation = 0,
            loadState = AdLoadState.Idle,
            cache = emptyList()
        )
    )
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 32)
    private val activePresentation = AtomicReference<FullScreenPresentationHandle?>(null)
    private val reloadJob = AtomicReference<Job?>(null)
    private val reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val orphanedAds = mutableListOf<AdT>()

    override val loadState: StateFlow<AdLoadState> = LoadStateFlow(slotState)
    override val events: SharedFlow<AdEvent> = _events

    protected abstract suspend fun loadAd(requestOptions: AdRequestOptions): AdAttemptResult<AdT>
    protected abstract suspend fun presentAd(
        loaded: AdT,
        options: FullScreenAdOptions,
        presentation: FullScreenPresentationHandle,
        rewardDelivery: RewardDelivery?
    ): AdShowResult
    protected abstract fun destroyAd(ad: AdT)
    protected abstract fun getResponseInfo(ad: AdT): AdResponseInfo?
    protected abstract fun canPresent(): AdError?

    protected open fun destroyAfterPresentation(wasShown: Boolean): Boolean = true

    protected open fun onAdLoaded(ad: AdT, requestOptions: AdRequestOptions) {}

    protected open fun ttl(): Duration = placement.cachePolicy.expirationPolicy.fullScreenTtl

    override fun availability(): AdAvailability {
        val now = clock()
        val ttl = ttl()
        // Expiry MUST prune, not merely filter. A non-mutating filter leaves the expired SDK
        // objects retained and loadState at Loaded until some later show/load/clear happens to
        // touch the slot. Pruning here is an atomic transition, so a read can never observe a
        // cache and a load state that disagree.
        val retiredAds = pruneExpired(now, ttl)
        destroyAds(retiredAds)
        val fresh = slotState.value.cache
        val expiresIn = fresh.firstOrNull()?.let { ttl - (now - it.loadedAt) }
        return AdAvailability(isReady = fresh.isNotEmpty(), cachedCount = fresh.size, expiresIn = expiresIn)
    }

    /**
     * Retires every expired cache entry and recomputes the load state in one CAS.
     *
     * Returns the ads whose ownership this call took, for destruction outside the lock.
     * A [AdLoadState.Loaded] claim over an empty cache is downgraded to [AdLoadState.Idle];
     * any other state is left alone so a recorded failure is not rewritten.
     */
    private fun pruneExpired(now: Instant, ttl: Duration): List<AdT> {
        while (true) {
            val current = slotState.value
            val (fresh, expired) = partitionCache(current.cache, now, ttl)
            if (expired.isEmpty()) return emptyList()
            val nextLoadState = if (fresh.isEmpty() && current.loadState is AdLoadState.Loaded) {
                AdLoadState.Idle
            } else {
                current.loadState
            }
            val updated = SlotState(current.generation, nextLoadState, fresh)
            if (slotState.compareAndSet(current, updated)) return expired.map { it.ad }
        }
    }

    override suspend fun load(requestOptions: AdRequestOptions): AdLoadState {
        // Capture before the first suspension. A clear while this call waits behind a coalesced
        // load invalidates it instead of letting it silently adopt the next generation.
        val requestedGeneration = slotState.value.generation
        return loadForGeneration(requestOptions, requestedGeneration)
    }

    private suspend fun loadForGeneration(
        requestOptions: AdRequestOptions,
        requiredGeneration: Int
    ): AdLoadState = loadMutex.withLock {
        val initialNow = clock()
        val cacheTtl = ttl()
        val preparation = publicationLock.withLock {
            prepareLoad(requiredGeneration, initialNow, cacheTtl)
        }
        destroyAds(preparation.retiredAds)
        preparation.immediateResult?.let { return@withLock it }

        var lastError: AdError? = null
        var acceptedAny = false
        try {
            for (slotIndex in 0 until preparation.slotsToLoad) {
                if (!isCurrentGeneration(requiredGeneration)) break
                // Bounds the WHOLE attempt sequence including retry backoff, not each
                // attempt: a listener that never calls back would otherwise restart the
                // clock on every retry and still never finish. Scoped per-slot so a
                // multi-slot cache warm-up does not share one timeout budget across slots.
                val result = withTimeoutOrNull(placement.timeoutPolicy.loadTimeout) {
                    retryAdLoad(placement.retryPolicy, { it.isRetryableLoadFailure() }) {
                        if (isCurrentGeneration(requiredGeneration)) {
                            loadAd(requestOptions)
                        } else {
                            AdAttemptResult.Failure(AdError.message("Full-screen load was cleared."))
                        }
                    }
                } ?: AdAttemptResult.Failure(
                    AdError.message(
                        "Full-screen ad load timed out after ${placement.timeoutPolicy.loadTimeout}. " +
                            "The SDK accepted the request but never reported a result."
                    )
                )
                when (result) {
                    is AdAttemptResult.Success -> {
                        val loadedAd = result.value
                        val entry = try {
                            onAdLoaded(loadedAd, requestOptions)
                            CachedAd(
                                ad = loadedAd,
                                loadedAt = clock(),
                                responseInfo = getResponseInfo(loadedAd)
                            )
                        } catch (t: Throwable) {
                            safelyDestroyAd(loadedAd)
                            throw t
                        }
                        val accepted = publicationLock.withLock {
                            admitLoadedAd(
                                generation = requiredGeneration,
                                entry = entry,
                                publishLoadedEvent = !acceptedAny
                            )
                        }
                        if (accepted) {
                            acceptedAny = true
                        } else {
                            safelyDestroyAd(loadedAd)
                        }
                    }
                    is AdAttemptResult.Failure -> {
                        if (isCurrentGeneration(requiredGeneration)) lastError = result.error
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            finishCancelledLoad(requiredGeneration)
            throw e
        } catch (t: Throwable) {
            // Catch Throwable, not just CancellationException. A throw from onAdLoaded (which
            // rethrows after destroying the ad), a mapper, or getResponseInfo must not escape
            // BEFORE completeLoad runs, or loadState is left at Loading permanently.
            // finishCancelledLoad already derives the terminal state from retained inventory,
            // which is exactly the recovery wanted here, so reuse it rather than inventing a
            // second rule.
            finishCancelledLoad(requiredGeneration)
            throw t
        }

        val completion = publicationLock.withLock {
            completeLoad(
                generation = requiredGeneration,
                lastError = lastError,
                now = clock(),
                ttl = cacheTtl
            )
        }
        destroyAds(completion.retiredAds)
        completion.result
    }

    final override suspend fun show(options: FullScreenAdOptions): AdShowResult =
        showInternal(options, onRewardEarned = null)

    protected suspend fun showRewarded(
        options: FullScreenAdOptions,
        onRewardEarned: (AdReward) -> Unit
    ): AdShowResult = showInternal(options, onRewardEarned)

    private suspend fun showInternal(
        options: FullScreenAdOptions,
        onRewardEarned: ((AdReward) -> Unit)?
    ): AdShowResult {
        val rewardDelivery = when (placement.format) {
            AdFormat.Rewarded,
            AdFormat.RewardedInterstitial -> RewardDelivery(onRewardEarned) { reward ->
                emit(AdEvent.RewardEarned(placement.id, reward))
            }
            else -> null
        }
        val now = clock()
        val cacheTtl = ttl()
        // canPresent() reaches UIKit on iOS (topViewController() walks connectedScenes /
        // keyWindow / rootViewController). prepareShow() is non-suspend and runs inside
        // two locks, so the evaluation is hoisted here where it can be main-confined.
        // Pre-computing it does not change which branch prepareShow selects — the value
        // is only consumed in the branch that previously called canPresent() inline.
        val presentabilityError = withContext(Dispatchers.Main.immediate) { canPresent() }
        beforeShowCommit()
        val preparation = operationMutex.withLock {
            publicationLock.withLock {
                prepareShow(options, now, cacheTtl, presentabilityError)
            }
        }
        destroyAds(preparation.retiredAds)
        preparation.errorEvent?.let(::emit)
        preparation.immediateResult?.let { return it }

        val loaded = checkNotNull(preparation.selectedAd)
        val handle = checkNotNull(preparation.presentation)
        return try {
            // Inside the try: the ad is already out of the cache and the arbiter token + presence
            // slot are already committed, so a throw from here MUST reach the catch arms below or
            // the process-wide presentation gate leaks permanently.
            //
            // NonCancellable is load-bearing, not defensive. Without it, a caller cancelling while
            // this withContext returns leaves the overrides applied but the restore handle never
            // attached — stranding the process-wide GMA audio state for the lifetime of the app.
            // Main.immediate follows the canPresent() precedent above: both platform controllers
            // touch GMA directly, which must happen on Main.
            val audioRestore = withContext(NonCancellable + Dispatchers.Main.immediate) {
                try {
                    audioController?.applyOverrides(options)
                } catch (t: Throwable) {
                    // Wrapped only so the AdError below names audio instead of reporting a generic
                    // presentation failure; the catch arm does the actual cleanup.
                    throw IllegalStateException(
                        "Failed to apply full-screen audio overrides for ${placement.id}.",
                        t
                    )
                }
            }
            handle.attachAudioRestore(audioRestore)
            val timedOutBeforeHandOff = AtomicBoolean(false)
            val result = coroutineScope {
                val presentJob = async { presentAd(loaded, options, handle, rewardDelivery) }
                // Runs BESIDE the presentation. Acts only while the handle is still
                // CORE_OWNED — the platform never reached the SDK call. Once
                // tryHandOffToCallbacks() has succeeded the SDK owns the presentation and the
                // user may be watching, so this does nothing: force-closing there could put
                // two ads on screen, which is an AdMob policy violation as well as broken UX.
                val watchdog = launch {
                    delay(placement.timeoutPolicy.presentationHandOffTimeout)
                    if (handle.closeIfCoreOwned()) {
                        timedOutBeforeHandOff.store(true)
                        AdLogger.e(
                            "Full-screen presentation stalled before reaching the SDK. " +
                                "placement=${placement.id} " +
                                "timeout=${placement.timeoutPolicy.presentationHandOffTimeout}. " +
                                "Releasing the process-wide presentation token."
                        )
                        // Safe: pre-hand-off cancellation is already the documented path, and
                        // closeIfCoreOwned() above claimed ownership.
                        presentJob.cancel(CancellationException("presentation hand-off timed out"))
                    }
                }
                try {
                    presentJob.await()
                } catch (e: CancellationException) {
                    // Distinguish OUR watchdog from the caller cancelling show(). If the
                    // caller cancelled, the context is no longer active and the existing
                    // cancellation contract must be honoured unchanged.
                    if (!currentCoroutineContext().isActive) throw e
                    AdShowResult.Failed(
                        AdError.message(
                            "Full-screen presentation timed out after " +
                                "${placement.timeoutPolicy.presentationHandOffTimeout} without reaching the SDK."
                        )
                    )
                } finally {
                    watchdog.cancel()
                }
            }
            // timedOutBeforeHandOff.load(): the watchdog already performed the terminal
            // close in the timeout case, so the close() call below correctly returns false
            // for that case (already closed) — closedByCore alone would silently swallow
            // the ShowFailed event for exactly the failure mode this watchdog exists to
            // handle. OR-ing in the flag restores the emission guarantee below without
            // double-emitting for any other path (the flag is only ever true in this one race).
            val closedByCore = handle.close(result is AdShowResult.Shown)
            // A RETURNED Failed must emit ShowFailed here, not only preparation errors and
            // THROWN exceptions. A presentation that fails before its SDK callbacks are
            // installed (Activity / rootViewController resolution) returns Failed without
            // throwing, and would otherwise hand the host a failed suspend result with no
            // matching event.
            //
            // close() returns true only to whoever performed the terminal close, which is the
            // same gate the platform slots already use before emitting. So this fires exactly
            // when no platform callback owned the outcome, and cannot double-emit.
            if (result is AdShowResult.Failed && (closedByCore || timedOutBeforeHandOff.load())) {
                emit(AdEvent.ShowFailed(placement.id, result.error))
            }
            result
        } catch (e: CancellationException) {
            // Before hand-off there is no SDK callback to close the token, and closeIfCoreOwned()
            // restores audio as part of that terminal close. After hand-off the platform terminal
            // callback owns cleanup — including the audio restore — even though the caller is no
            // longer active: the ad is still on screen, so restoring here would end the documented
            // per-presentation override while the user is still watching.
            handle.closeIfCoreOwned()
            throw e
        } catch (t: Throwable) {
            handle.close(wasShown = false)
            val error = AdError.message(t.message ?: "Full-screen ad presentation failed.")
            emit(AdEvent.ShowFailed(placement.id, error))
            AdShowResult.Failed(error)
        }
    }

    override fun clear() {
        val retiredAds: List<AdT> = publicationLock.withLock {
            var result = emptyList<AdT>()
            while (true) {
                val current = slotState.value
                val cleared = SlotState<AdT>(
                    generation = current.generation + 1,
                    loadState = AdLoadState.Idle,
                    cache = emptyList()
                )
                if (slotState.compareAndSet(current, cleared)) {
                    val retiredOrphans = orphanedAds.toList().also { orphanedAds.clear() }
                    result = current.cache.map { it.ad } + retiredOrphans
                    break
                }
            }
            result
        }
        reloadJob.exchange(null)?.cancel()
        destroyAds(retiredAds)
    }

    internal fun emit(event: AdEvent) {
        _events.emitOrLogDrop(event, "FullScreenSlotCore(${placement.id})")
        globalEvents.emitOrLogDrop(event, "FullScreenSlotCore(${placement.id}) global")
    }

    private fun prepareLoad(
        generation: Int,
        now: Instant,
        ttl: Duration
    ): LoadPreparation<AdT> {
        while (true) {
            val current = slotState.value
            if (current.generation != generation) {
                return LoadPreparation(immediateResult = current.loadState)
            }
            val (fresh, expired) = partitionCache(current.cache, now, ttl)
            val blockedError = adRequestBlockedError()
            val nextLoadState = when {
                blockedError != null -> AdLoadState.Failed(blockedError)
                fresh.size >= placement.cachePolicy.maxSize -> AdLoadState.Loaded(fresh.last().responseInfo)
                else -> AdLoadState.Loading
            }
            val updated = SlotState(generation, nextLoadState, fresh)
            if (!slotState.compareAndSet(current, updated)) continue

            val event = blockedError?.let { AdEvent.LoadFailed(placement.id, it) }
            if (event != null) emitLoadEventLocked(event)
            // NOTE: `nextLoadState is AdLoadState.Loading` is deliberately avoided here.
            // Kotlin/Native 2.3.20 constant-folds that is-check to `true` for this
            // when-typed local (JVM compiles it correctly), which made a consent-blocked
            // load fall through into the load loop on iOS. Equality against the data
            // object is compiled correctly on both backends.
            val isLoading = nextLoadState == AdLoadState.Loading
            val retiredOrphans = orphanedAds.toList().also { orphanedAds.clear() }
            return LoadPreparation(
                immediateResult = if (isLoading) null else nextLoadState,
                slotsToLoad = if (isLoading) placement.cachePolicy.maxSize - fresh.size else 0,
                retiredAds = expired.map { it.ad } + retiredOrphans
            )
        }
    }

    /** Caller must hold [publicationLock]. */
    private fun admitLoadedAd(
        generation: Int,
        entry: CachedAd<AdT>,
        publishLoadedEvent: Boolean
    ): Boolean {
        while (true) {
            val current = slotState.value
            if (
                current.generation != generation ||
                current.cache.size >= placement.cachePolicy.maxSize
            ) {
                return false
            }
            val updated = SlotState(
                generation = generation,
                loadState = AdLoadState.Loaded(entry.responseInfo),
                cache = current.cache + entry
            )
            if (!slotState.compareAndSet(current, updated)) continue
            if (publishLoadedEvent) {
                emitLoadEventLocked(AdEvent.Loaded(placement.id, entry.responseInfo))
            }
            return true
        }
    }

    private fun completeLoad(
        generation: Int,
        lastError: AdError?,
        now: Instant,
        ttl: Duration
    ): LoadCompletion<AdT> {
        while (true) {
            val current = slotState.value
            if (current.generation != generation) {
                return LoadCompletion(result = current.loadState)
            }
            val (fresh, expired) = partitionCache(current.cache, now, ttl)
            val cached = fresh.lastOrNull()
            val nextLoadState = when {
                cached != null -> AdLoadState.Loaded(cached.responseInfo)
                lastError != null -> AdLoadState.Failed(lastError)
                else -> AdLoadState.Idle
            }
            val updated = SlotState(generation, nextLoadState, fresh)
            if (!slotState.compareAndSet(current, updated)) continue

            if (cached != null && lastError != null) {
                AdLogger.w(
                    "Partial full-screen cache fill for ${placement.id}: " +
                        "${fresh.size}/${placement.cachePolicy.maxSize} loaded, " +
                        "last error: ${lastError.message}"
                )
            }
            val event = when {
                cached == null && lastError != null -> AdEvent.LoadFailed(placement.id, lastError)
                else -> null
            }
            if (event != null) emitLoadEventLocked(event)
            return LoadCompletion(
                result = nextLoadState,
                retiredAds = expired.map { it.ad }
            )
        }
    }

    private fun prepareShow(
        options: FullScreenAdOptions,
        now: Instant,
        ttl: Duration,
        presentabilityError: AdError?
    ): ShowPreparation<AdT> {
        while (true) {
            val current = slotState.value
            val (fresh, expired) = partitionCache(current.cache, now, ttl)
            val blockedError = adRequestBlockedError()
            val presentError = when {
                blockedError != null -> blockedError
                activePresentation.load() != null || fresh.isEmpty() -> null
                else -> presentabilityError
            }
            val selected = if (
                blockedError == null &&
                activePresentation.load() == null &&
                fresh.isNotEmpty() &&
                presentError == null
            ) {
                fresh.first()
            } else {
                null
            }

            // Process-wide admission. Acquired BEFORE the cache CAS below so that "this ad is
            // committed to being shown" and "this slot owns the presentation right" are one
            // atomic decision. Losing here returns NotReady without mutating the cache, so the
            // ad stays available for the next attempt — matching the per-slot behavior when
            // activePresentation is already set.
            val token = if (selected != null) {
                arbiter.tryAcquire(placement.id, placement.format)
                    ?: run {
                        val cleaned = SlotState(current.generation, current.loadState, fresh)
                        if (!slotState.compareAndSet(current, cleaned)) continue
                        return ShowPreparation(
                            immediateResult = AdShowResult.NotReady,
                            retiredAds = expired.map { it.ad }
                        )
                    }
            } else {
                null
            }

            val remaining = if (selected != null) fresh.drop(1) else fresh
            // Derive the state from what actually REMAINS, not from whether an ad was selected.
            // Keying on selection alone lets a slot whose entire cache expired keep publishing
            // Loaded over an empty cache — isReady false and show() NotReady while loadState
            // still claims Loaded. Only a Loaded claim is downgraded here, so a Failed state is
            // never silently rewritten.
            val nextLoadState = if (remaining.isEmpty() && current.loadState is AdLoadState.Loaded) {
                AdLoadState.Idle
            } else {
                current.loadState
            }
            val updated = SlotState(current.generation, nextLoadState, remaining)
            if (!slotState.compareAndSet(current, updated)) {
                // Another writer won. Release before retrying, or this call leaks the token it
                // just acquired and every later show() process-wide is permanently blocked.
                token?.let(arbiter::release)
                continue
            }

            val error = blockedError ?: presentError
            val immediateResult = when {
                error != null -> AdShowResult.Failed(error)
                selected == null -> AdShowResult.NotReady
                else -> null
            }
            val presentation = selected?.let { createPresentation(it.ad, current.generation, checkNotNull(token)) }
            return ShowPreparation(
                immediateResult = immediateResult,
                errorEvent = error?.let { AdEvent.ShowFailed(placement.id, it) },
                selectedAd = selected?.ad,
                presentation = presentation,
                retiredAds = expired.map { it.ad }
            )
        }
    }

    private fun createPresentation(
        ad: AdT,
        generation: Int,
        token: FullScreenPresentationArbiter.PresentationToken
    ): FullScreenPresentationHandle {
        lateinit var handle: FullScreenPresentationHandle
        handle = FullScreenPresentationHandle { wasShown ->
            // The manager's count is decremented BEFORE activePresentation is cleared.
            // The old order cleared the slot first, so a concurrent prepareShow could observe
            // this slot as free while the process-wide count still read 1.
            try {
                onPresentationChanged(-1)
            } catch (t: Throwable) {
                try {
                    AdLogger.e("Failed to close full-screen presentation presence for ${placement.id}.", t)
                } catch (_: Throwable) { /* logger failure must not break presentation */ }
            }
            // Released last, and unconditionally: FullScreenPresentationHandle's CAS loop already
            // guarantees this lambda runs exactly once, so this is the single release path. No
            // parallel release mechanism exists or should be added.
            arbiter.release(token)
            activePresentation.compareAndSet(handle, null)
            if (destroyAfterPresentation(wasShown)) {
                safelyDestroyAd(ad)
            } else {
                publicationLock.withLock {
                    orphanedAds.add(ad)
                }
            }
            if (wasShown && placement.cachePolicy.reloadAfterShow) scheduleReload(generation)
        }
        activePresentation.store(handle)
        // The +1 is now wrapped exactly like the -1 above. Previously a throwing manager
        // callback propagated out of prepareShow with the ad already removed from cache and
        // activePresentation already set — leaking the cache entry, the presence slot, and (now)
        // the arbiter token. Presence accounting is diagnostics; it must never fail a show.
        try {
            onPresentationChanged(1)
        } catch (t: Throwable) {
            try {
                AdLogger.e("Failed to open full-screen presentation presence for ${placement.id}.", t)
            } catch (_: Throwable) { /* logger failure must not break presentation */ }
        }
        return handle
    }

    private fun scheduleReload(generation: Int) {
        val job = reloadScope.launch(start = CoroutineStart.LAZY) {
            // Catch at the launch boundary. This reload is detached — nobody awaits it — and
            // loadForGeneration deliberately rethrows unexpected mapper / onAdLoaded /
            // getResponseInfo failures so a foreground caller sees them. With nothing catching
            // here, that rethrow reached the uncaught-exception handler of a Main-dispatched
            // coroutine and could take down the host process, even though the show() that
            // triggered the reload had already completed normally.
            //
            // Load state needs no repair: loadForGeneration runs finishCancelledLoad() before it
            // rethrows, so the slot is already in a terminal state and a later manual load works.
            try {
                loadForGeneration(placement.requestOptions, requiredGeneration = generation)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AdLogger.e(
                    "Automatic reload after show failed for ${placement.id}. The slot stays " +
                        "usable; the next load() will retry.",
                    t
                )
            }
        }
        reloadJob.exchange(job)?.cancel()
        job.start()
    }

    private fun finishCancelledLoad(generation: Int) {
        val retiredAds: List<AdT> = publicationLock.withLock {
            var result = emptyList<AdT>()
            while (true) {
                val current = slotState.value
                if (current.generation != generation) break
                val (fresh, expired) = partitionCache(current.cache, clock(), ttl())
                val nextLoadState = fresh.lastOrNull()?.let {
                    AdLoadState.Loaded(it.responseInfo)
                } ?: AdLoadState.Idle
                val updated = SlotState(generation, nextLoadState, fresh)
                if (slotState.compareAndSet(current, updated)) {
                    result = expired.map { it.ad }
                    break
                }
            }
            result
        }
        destroyAds(retiredAds)
    }

    /** Caller must hold [publicationLock]. */
    private fun emitLoadEventLocked(event: AdEvent) {
        _events.emitOrLogDrop(event, "FullScreenSlotCore(${placement.id})")
        globalEvents.emitOrLogDrop(event, "FullScreenSlotCore(${placement.id}) global")
    }

    private fun isCurrentGeneration(generation: Int): Boolean =
        slotState.value.generation == generation

    private fun partitionCache(
        cache: List<CachedAd<AdT>>,
        now: Instant,
        ttl: Duration
    ): Pair<List<CachedAd<AdT>>, List<CachedAd<AdT>>> =
        cache.partition { now - it.loadedAt < ttl }

    private fun destroyAds(ads: List<AdT>) {
        ads.forEach(::safelyDestroyAd)
    }

    private fun safelyDestroyAd(ad: AdT) {
        try {
            destroyAd(ad)
        } catch (t: Throwable) {
            AdLogger.e("Failed to destroy full-screen ad for ${placement.id}.", t)
        }
    }

    private class SlotState<AdT>(
        val generation: Int,
        val loadState: AdLoadState,
        val cache: List<CachedAd<AdT>>
    )

    private data class CachedAd<AdT>(
        val ad: AdT,
        val loadedAt: Instant,
        val responseInfo: AdResponseInfo?
    )

    private data class LoadPreparation<AdT>(
        val immediateResult: AdLoadState? = null,
        val slotsToLoad: Int = 0,
        val retiredAds: List<AdT> = emptyList()
    )

    private data class LoadCompletion<AdT>(
        val result: AdLoadState,
        val retiredAds: List<AdT> = emptyList()
    )

    private data class ShowPreparation<AdT>(
        val immediateResult: AdShowResult? = null,
        val errorEvent: AdEvent? = null,
        val selectedAd: AdT? = null,
        val presentation: FullScreenPresentationHandle? = null,
        val retiredAds: List<AdT> = emptyList()
    )

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class LoadStateFlow<AdT>(
        private val source: StateFlow<SlotState<AdT>>
    ) : StateFlow<AdLoadState> {
        override val value: AdLoadState get() = source.value.loadState
        override val replayCache: List<AdLoadState> get() = listOf(value)

        override suspend fun collect(collector: FlowCollector<AdLoadState>): Nothing {
            source.map { it.loadState }.distinctUntilChanged().collect(collector)
            error("Slot state flow completed unexpectedly.")
        }
    }
}
