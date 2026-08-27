package dev.avinya.ads.internal

import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdLoadState
import dev.avinya.ads.AdLogger
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdRequestOptions
import dev.avinya.ads.AdResponseInfo
import dev.avinya.ads.AdSizePolicy
import dev.avinya.ads.BannerGeometry
import dev.avinya.ads.isRetryableLoadFailure
import dev.avinya.ads.retryAdLoad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Platform primitives the shared banner controller needs.
 *
 * [V] is the platform's banner handle. Android binds `BannerAd`. iOS binds a
 * view+delegate pair, because `GADBannerView.delegate` is weak and must be strongly
 * retained for the view's whole lifetime (CLAUDE.md invariant #4). Carrying the
 * delegate inside [V] means "the core retains the banner" already implies "the
 * delegate is alive", and the core never has to know delegates exist.
 *
 * [S] is the platform's resolved ad-size type (`AdSize` / `CValue<GADAdSize>`),
 * which cannot cross into commonMain.
 */
internal interface BannerPlatform<V : Any, S : Any> {
    fun <T> withStateLock(block: () -> T): T

    /**
     * Best-effort width for a headless load with no host geometry, or null when the
     * platform cannot determine one.
     *
     * Nullable on BOTH platforms deliberately: the core, not the platform, owns the
     * failure policy. Android returns null with no current Activity; iOS returns null
     * when it cannot resolve a viewport width.
     */
    fun fallbackWidthDp(): Int?

    fun resolveSize(sizePolicy: AdSizePolicy, widthDp: Int): S

    /** Destroy/tear down a banner. Synchronous on Android; Main-confined async on iOS. */
    fun destroy(banner: V)

    fun responseInfo(banner: V): AdResponseInfo?

    /**
     * Create, configure and load one banner at [size].
     *
     * The platform owns delegate creation, assignment order and retention. On iOS the
     * delegate MUST be assigned before `loadRequest` and strongly held; the core never
     * touches it. [requiredGeneration] lets the platform reject a load the core has
     * already invalidated before handing an ad to the SDK.
     */
    suspend fun loadBanner(
        size: S,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions,
        requiredGeneration: Long
    ): AdAttemptResult<V>
}

/**
 * Shared banner controller state machine. Owns the load mutex, generation, attachment
 * refcounting, the resolved request, load-state publication and the
 * keep-the-old-banner-until-the-new-one-lands swap.
 *
 * Restores CLAUDE.md invariant #6: geometry is a host-supplied *input*, never something
 * the controller reaches for. Both controllers previously violated this — Android through
 * `Activity`, iOS through `UIScreen.mainScreen.bounds`.
 */
internal class BannerCore<V : Any, S : Any>(
    val placement: AdPlacement,
    private val platform: BannerPlatform<V, S>,
    private val globalEvents: MutableSharedFlow<AdEvent>
) {
    private val loadMutex = Mutex()
    private val _loadState = MutableStateFlow<AdLoadState>(AdLoadState.Idle)
    private val _events = MutableSharedFlow<AdEvent>(extraBufferCapacity = 32)

    private var banner: V? = null
    private var generation = 0L

    // Response info for the CURRENTLY OWNED banner, captured once at admission.
    //
    // Recovery paths (failOrRestore/onCancelled) must be able to republish Loaded without
    // calling back into the platform. Reading it lazily instead was wrong twice over: it
    // touched a GMA object while holding the state lock — which on iOS is also taken from GMA
    // callback threads, which are not safe for synchronous SDK reads — and
    // it could read from a banner that clear() had already retired and destroyed. Mirrors
    // cached response metadata follows the same rule.
    private var bannerResponseInfo: AdResponseInfo? = null

    // The single record refresh() replays. Its fields have two DIFFERENT owners with
    // different lifetimes, so it is merged on write rather than chosen between on read:
    //   - requestOptions is owned by the load() caller. A refresh must replay the
    //     options that call resolved, never rebuild them from placement.requestOptions.
    //   - size/sizePolicy are owned by the host's container measurement and change on every
    //     resize (rotation, split-screen, fold).
    // This was previously two records (lastRequest / registeredRequest) picked between in
    // refresh(). That cannot work: choosing either whole record discards the other owner's
    // half — preferring the load record replayed a stale width after a resize, preferring
    // the geometry record dropped custom request options. See registerGeometry.
    private var replayRequest: ResolvedBannerRequest<S>? = null

    // Count of live UI attachments (BannerAdView composables) bound to this controller.
    // The controller is manager-cached for the whole process, so it can't destroy its ad on
    // every composable dispose (a sibling screen briefly mounted during a nav transition may
    // share the same placement). Instead we destroy when the LAST attachment leaves.
    private var attachCount = 0

    val loadState: StateFlow<AdLoadState> = _loadState
    val events: SharedFlow<AdEvent> = _events

    fun currentBanner(): V? = platform.withStateLock { banner }

    fun attach() {
        platform.withStateLock { attachCount++ }
    }

    fun detach() {
        val retired = platform.withStateLock {
            attachCount = (attachCount - 1).coerceAtLeast(0)
            // Invalidate the current load generation even when loadMutex is held. Its eventual
            // callback is allowed to settle the suspended load, but may not reinstall an ad for
            // a UI attachment that no longer exists.
            if (attachCount == 0) clearLocked() else null
        }
        retired?.let { platform.destroy(it) }
    }

    suspend fun load(
        geometry: BannerGeometry?,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions,
        blockedError: () -> AdError? = { null }
    ): AdLoadState {
        val requiredGeneration = currentGeneration()
        // Consent is the harder gate, so it is checked before geometry: a consent-blocked
        // caller with no geometry should be told about consent, not sent to fix a width and
        // then hit the gate on the retry. Re-checked inside loadForGeneration, which is the
        // path refresh() also takes (CLAUDE.md invariant #5 — the gate is checked in load()).
        blockedError()?.let { return failIfCurrent(requiredGeneration, it) }
        val widthDp = resolveWidthDp(geometry)
            ?: return failIfCurrent(
                requiredGeneration,
                AdError.message(
                    "Banner load requires a width: no BannerGeometry was supplied and the platform " +
                        "could not determine a fallback. Use BannerAdView, which measures its container."
                )
            )
        val resolved = ResolvedBannerRequest(
            size = platform.resolveSize(sizePolicy, widthDp),
            sizePolicy = sizePolicy,
            requestOptions = requestOptions.ownedSnapshot()
        )
        loadForGeneration(resolved, requiredGeneration, blockedError)
        return _loadState.value
    }

    suspend fun refresh(blockedError: () -> AdError? = { null }): AdLoadState {
        val requiredGeneration = currentGeneration()
        // Replay the WHOLE resolved request, not just the size. There is exactly one
        // replay record; load() and registerGeometry each update the fields they own, so no
        // precedence decision is needed here.
        val resolved = platform.withStateLock { replayRequest }
            ?: return failIfCurrent(
                requiredGeneration,
                AdError.message("Banner refresh requires a prior successful load to replay.")
            )
        loadForGeneration(resolved, requiredGeneration, blockedError)
        return _loadState.value
    }

    /**
     * Records the container geometry the host measured, without triggering a load.
     *
     * Exists for [dev.avinya.ads.BannerRefreshPolicy.Manual], where the composable
     * deliberately performs no automatic load but the consumer still drives [refresh].
     * Without it, such a placement could never refresh: it has no prior load to replay
     * and no way to hand geometry in.
     *
     * Updates ONLY the geometry-derived fields of the replay record. The host re-measures on
     * every rotation, split-screen change and fold, and a re-measure is not a statement about
     * request options — so [requestOptions] seeds the record only when no load has resolved
     * options yet. Overwriting them here would undo the resolved-options rule above; ignoring
     * the new geometry would make [refresh] replay a stale width for the rest of the session.
     */
    fun registerGeometry(
        geometry: BannerGeometry,
        sizePolicy: AdSizePolicy,
        requestOptions: AdRequestOptions
    ) {
        // resolveSize is a platform call and stays outside the state lock: on iOS that lock is
        // also taken from GMA callback threads (the same callback-thread rule).
        val resolvedSize = platform.resolveSize(sizePolicy, geometry.widthDp)
        platform.withStateLock {
            val existing = replayRequest
            replayRequest = existing?.copy(size = resolvedSize, sizePolicy = sizePolicy)
                ?: ResolvedBannerRequest(
                    size = resolvedSize,
                    sizePolicy = sizePolicy,
                    requestOptions = requestOptions.ownedSnapshot()
                )
        }
    }

    private fun resolveWidthDp(geometry: BannerGeometry?): Int? =
        geometry?.widthDp ?: platform.fallbackWidthDp()

    private suspend fun loadForGeneration(
        resolved: ResolvedBannerRequest<S>,
        requiredGeneration: Long,
        blockedError: () -> AdError?
    ): V? = loadMutex.withLock {
        val previousAd = platform.withStateLock {
            if (generation != requiredGeneration) return@withStateLock null
            // An actual load is the authoritative source for every field, request options
            // included — unlike registerGeometry, which owns only the geometry-derived half.
            replayRequest = resolved
            _loadState.value = AdLoadState.Loading
            // Box so a null previous ad is distinguishable from a stale generation.
            Box(banner)
        } ?: return@withLock null

        blockedError()?.let { error ->
            failIfCurrent(requiredGeneration, error)
            return@withLock null
        }

        // Keep the currently displayed ad on screen until the new one returns (no blank
        // flash on refresh). The old ad is destroyed only after the new ad loads
        // successfully; on failure the old ad stays visible.
        val previous = previousAd.value
        try {
            // Bounds the WHOLE attempt sequence including retry backoff, not each
            // attempt: a listener that never calls back would otherwise restart the
            // clock on every retry and still never finish.
            val result = withTimeoutOrNull(placement.timeoutPolicy.loadTimeout) {
                retryAdLoad<V>(placement.retryPolicy, { it.isRetryableLoadFailure() }) {
                    if (!isCurrentGeneration(requiredGeneration)) {
                        AdAttemptResult.Failure(AdError.message("Banner load was cleared."))
                    } else {
                        platform.loadBanner(
                            resolved.size,
                            resolved.sizePolicy,
                            resolved.requestOptions,
                            requiredGeneration
                        )
                    }
                }
            } ?: AdAttemptResult.Failure(
                AdError.message(
                    "Banner load timed out after ${placement.timeoutPolicy.loadTimeout}. " +
                        "The SDK accepted the request but never reported a result."
                )
            )
            when (result) {
                is AdAttemptResult.Success -> {
                    // The handle is UNOWNED until the admission below stores it. responseInfo is
                    // a platform SDK call and can throw — the catch(Throwable) at the bottom of
                    // this try exists precisely because SDK accessors do. Without destroying here
                    // the freshly loaded banner would be neither retained nor torn down: a leak
                    // that survives for the process lifetime. FullScreenSlotCore already uses
                    // this unowned-until-admitted shape; this restores the parity.
                    val responseInfo = try {
                        platform.responseInfo(result.value)
                    } catch (t: Throwable) {
                        safelyDestroy(result.value)
                        throw t
                    }
                    val accepted = platform.withStateLock {
                        if (generation != requiredGeneration) false else {
                            banner = result.value
                            bannerResponseInfo = responseInfo
                            _loadState.value = AdLoadState.Loaded(responseInfo)
                            true
                        }
                    }
                    if (accepted) {
                        emit(AdEvent.Loaded(placement.id, responseInfo))
                        // Keep the old ad until the replacement is fully loaded and admitted.
                        if (previous !== result.value) previous?.let { platform.destroy(it) }
                        result.value
                    } else {
                        platform.destroy(result.value)
                        null
                    }
                }
                is AdAttemptResult.Failure -> {
                    // Leave the previously displayed ad on screen on a current-generation
                    // failure. A detached/cleared generation remains Idle. Use failOrRestore,
                    // not failIfCurrent: when a banner is still displayed, loadState must say
                    // Loaded, not Failed — otherwise a host reacting to Failed by hiding the
                    // slot would hide an ad that is, in fact, still on screen.
                    failOrRestore(requiredGeneration, result.error)
                    null
                }
            }
        } catch (e: CancellationException) {
            // Cancelled mid-load: the previously displayed ad (if any) stays as-is. If the
            // SDK still delivers the in-flight ad, the platform's own isActive guard
            // destroys it there, so nothing leaks.
            onCancelled(requiredGeneration)
            throw e
        } catch (t: Throwable) {
            // Catch Throwable, not just CancellationException. A throw from a beta SDK call
            // or a mapper must not escape with the state stuck at Loading: BannerAdView's
            // refresh loop awaits `loadState !is Loading`, so a Loading state that never
            // resolves silently kills refresh for that placement forever.
            //
            // The displayed banner is deliberately left alone — same reasoning as an
            // ordinary failed refresh (no blank flash) — so the state stays Loaded whenever
            // one is still on screen.
            failOrRestore(
                requiredGeneration,
                AdError.message(t.message ?: "Banner load failed unexpectedly.")
            )
            throw t
        }
    }

    fun clear() {
        val retired = platform.withStateLock { clearLocked() }
        retired?.let { platform.destroy(it) }
    }

    fun currentGeneration(): Long = platform.withStateLock { generation }

    fun isCurrentGeneration(requiredGeneration: Long): Boolean =
        platform.withStateLock { generation == requiredGeneration }

    /**
     * Publishes an event originating from a platform SDK callback (impression, click, paid).
     * Only the platform sees these; the core owns the flows they must reach.
     */
    fun emitPlatformEvent(event: AdEvent) = emit(event)

    /** Caller must hold the state lock. */
    private fun clearLocked(): V? {
        generation++
        val retired = banner
        banner = null
        bannerResponseInfo = null
        replayRequest = null
        _loadState.value = AdLoadState.Idle
        return retired
    }

    /**
     * Terminal state for a cancelled load: derive it from what the core still OWNS.
     *
     * Publishing [AdLoadState.Idle] unconditionally (what this did before) contradicted the
     * cancellation comment two lines up — the previous banner really does stay displayed, but
     * `BannerAdView` reads Idle as "cleared/destroyed, drop the reference" and blanked the
     * slot. A restarted `LaunchedEffect`, a resize or an explicit cancel would wipe a perfectly
     * live ad. This is the same inventory-blindness fixed for native-ad loads in the
     * pool; the banner never got the matching fix.
     */
    private fun onCancelled(requiredGeneration: Long) {
        platform.withStateLock {
            if (generation != requiredGeneration) return@withStateLock
            _loadState.value = if (banner != null) {
                AdLoadState.Loaded(bannerResponseInfo)
            } else {
                AdLoadState.Idle
            }
        }
    }

    /**
     * Terminal state for an unexpected (non-cancellation) failure: keep reporting [Loaded]
     * when a banner is still displayed, otherwise publish [AdLoadState.Failed].
     *
     * Decision and publication happen in ONE lock acquisition. Splitting them let `clear()`
     * — which takes only the state lock, never [loadMutex] — land in between, so recovery
     * republished Loaded over a generation that had just been retired and drained.
     */
    private fun failOrRestore(requiredGeneration: Long, error: AdError): AdLoadState {
        val restored = platform.withStateLock {
            if (generation == requiredGeneration && banner != null) {
                _loadState.value = AdLoadState.Loaded(bannerResponseInfo)
                true
            } else {
                false
            }
        }
        return if (restored) _loadState.value else failIfCurrent(requiredGeneration, error)
    }

    /**
     * Teardown that cannot itself derail an in-flight failure path. Used where the core is
     * discarding a banner it has decided not to own; a throwing `destroy` there would replace
     * the original failure with a confusing secondary one. Mirrors
     * `FullScreenSlotCore.safelyDestroyAd` and the native coordinator's destruction path.
     */
    private fun safelyDestroy(banner: V) {
        try {
            platform.destroy(banner)
        } catch (t: Throwable) {
            AdLogger.e("Failed to destroy banner for ${placement.id}.", t)
        }
    }

    private fun failIfCurrent(requiredGeneration: Long, error: AdError): AdLoadState {
        val published = platform.withStateLock {
            if (generation != requiredGeneration) false else {
                _loadState.value = AdLoadState.Failed(error)
                true
            }
        }
        if (published) emit(AdEvent.LoadFailed(placement.id, error))
        return _loadState.value
    }

    private fun emit(event: AdEvent) {
        _events.emitOrLogDrop(event, "BannerCore(${placement.id})")
        globalEvents.emitOrLogDrop(event, "BannerCore(${placement.id}) global")
    }

    private data class ResolvedBannerRequest<S : Any>(
        val size: S,
        val sizePolicy: AdSizePolicy,
        val requestOptions: AdRequestOptions
    )

    private class Box<T>(val value: T?)
}
