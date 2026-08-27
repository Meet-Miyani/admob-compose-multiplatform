@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.appopen

import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdShowResult
import dev.avinya.ads.AppOpenAdController
import dev.avinya.ads.FullScreenPresenceAware
import dev.avinya.ads.internal.FullScreenPresentationArbiter
import dev.avinya.ads.internal.FullScreenStateLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Configuration for [AppOpenAdCoordinator].
 *
 * @param showOnColdStart Attempt to show an app-open ad during [start]. The
 *   load must complete within [coldStartTimeout] or the cold-start show is
 *   skipped — on most devices the first frame wins, so leave this off unless
 *   a splash screen covers it.
 * @param minBackgroundDuration Minimum time the app must have been
 *   backgrounded before a foreground show.
 * @param cooldownBetweenShows Minimum time between two coordinator-driven
 *   shows. [Duration.ZERO] disables the check.
 * @param preloadOnStart Preload an ad when [start] is called, without
 *   showing it. Useful for apps that do not want a cold-start show but
 *   want the ad ready for the next foreground event.
 * @param coldStartTimeout Maximum time allowed for the cold-start load
 *   before skipping the show. Default 5s.
 */
public data class AppOpenConfig(
    val showOnColdStart: Boolean = false,
    val minBackgroundDuration: Duration = 4.seconds,
    val cooldownBetweenShows: Duration = Duration.ZERO,
    val preloadOnStart: Boolean = true,
    val coldStartTimeout: Duration = 5.seconds
) {
    init {
        // Each of these silently changes the coordinator's policy rather than failing:
        // a negative or infinite threshold makes every foreground transition qualify or none of
        // them; a negative cooldown disables the rate limit entirely; a non-positive cold-start
        // timeout abandons the load before it starts, and an infinite one never gives up.
        require(minBackgroundDuration.isFinite() && minBackgroundDuration >= Duration.ZERO) {
            "AppOpenConfig.minBackgroundDuration must be finite and non-negative, was $minBackgroundDuration."
        }
        require(cooldownBetweenShows.isFinite() && cooldownBetweenShows >= Duration.ZERO) {
            "AppOpenConfig.cooldownBetweenShows must be finite and non-negative, was $cooldownBetweenShows."
        }
        require(coldStartTimeout.isFinite() && coldStartTimeout > Duration.ZERO) {
            "AppOpenConfig.coldStartTimeout must be finite and positive, was $coldStartTimeout."
        }
    }
}

/**
 * Orchestrates the standard app-open ad lifecycle: preload, show on return to
 * foreground (gated by background duration, cooldown, and SDK readiness), reload after
 * consumption. Set [isBlocked] while the user is in flows that must not be interrupted
 * (purchases, onboarding, another full-screen ad).
 */
public class AppOpenAdCoordinator internal constructor(
    private val manager: AdManager,
    private val controller: AppOpenAdController,
    private val config: AppOpenConfig = AppOpenConfig(),
    internal val foregroundEvents: Flow<Boolean>,
    internal val clock: () -> Instant
) {
    public constructor(
        manager: AdManager,
        controller: AppOpenAdController,
        config: AppOpenConfig = AppOpenConfig()
    ) : this(
        manager = manager,
        controller = controller,
        config = config,
        foregroundEvents = appForegroundState(),
        clock = { Clock.System.now() }
    )

    /**
     * Owns every coroutine the coordinator starts automatically.
     *
     * A child of the scope handed to [start], so the host cancelling its own scope still stops the
     * coordinator, while [stop] can cancel the coordinator's work without touching the host's. It
     * replaces the two individual job handles this class used to keep: those covered the foreground
     * collector and the cold-start preload, but the show/reload coroutines launched from
     * [onForeground] went straight onto the caller's scope and survived [stop] entirely.
     *
     * A supervisor so one failed automatic child cannot cancel its siblings or the host's scope.
     */
    private var lifecycle: CoroutineScope? = null
    private var lastShowInstant: Instant? = null
    private var backgroundedAtInstant: Instant? = null
    // This only serializes coordinator admission. The manager-wide presentation handle remains
    // the source of truth for whether an ad is actually on screen.
    private val showAdmissionLock = FullScreenStateLock()
    private var showInFlight: Boolean = false
    private var blocked: Boolean = false
    // Probe token held from admission until the instant we delegate to the controller. See
    // releaseProbeToken() for why it must NOT be held across controller.show().
    private var probeToken: FullScreenPresentationArbiter.PresentationToken? = null

    /**
     * When true, the coordinator skips all foreground-show attempts.
     * Set this during purchases, onboarding, or any full-screen flow that
     * must not be interrupted by a coordinator-driven app-open ad.
     */
    public var isBlocked: Boolean
        get() = showAdmissionLock.withLock { blocked }
        set(value) {
            showAdmissionLock.withLock { blocked = value }
        }

    /**
     * Starts the coordinator lifecycle. Preloads an ad (if configured) and
     * begins listening for foreground/background transitions to show the ad.
     *
     * @param scope The [CoroutineScope] the coordinator's own lifecycle scope is parented to.
     */
    public fun start(scope: CoroutineScope) {
        stop()
        val lifecycle = CoroutineScope(
            scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])
        )
        this.lifecycle = lifecycle
        if (config.preloadOnStart || config.showOnColdStart) {
            lifecycle.launch { preloadColdStart() }
        }
        lifecycle.launch {
            foregroundEvents.collect { foreground ->
                if (foreground) onForeground() else onBackground()
            }
        }
    }

    /**
     * Stops the coordinator lifecycle, cancelling **every** coroutine it started and preventing
     * further automatic show attempts until [start] is called again.
     *
     * Cancelling the lifecycle scope is what makes that "every" true. Previously this cancelled only
     * the foreground collector and the cold-start preload, so an automatic show or reload already
     * launched from a foreground transition ran to completion afterwards — and a restart could
     * overlap those stale children with the new lifecycle.
     */
    public fun stop() {
        lifecycle?.cancel()
        lifecycle = null
    }

    private suspend fun preloadColdStart() {
        if (config.showOnColdStart) {
            withTimeoutOrNull(config.coldStartTimeout) { controller.load() }
            // Never show on a background launch (push / background fetch): an app-open
            // ad shown while not actually in the foreground is a policy violation. The
            // ad stays cached for the next genuine foreground entry.
            if (isAppInForeground() && tryAcquireShowAdmission()) showNow()
        } else {
            controller.load()
        }
    }

    private fun elapsedSince(startInstant: Instant?): Duration {
        if (startInstant == null) return Duration.INFINITE
        val diff = clock() - startInstant
        return if (diff < Duration.ZERO) Duration.ZERO else diff
    }

    private fun onBackground() {
        backgroundedAtInstant = clock()
    }

    private suspend fun onForeground() {
        val backgroundDuration = backgroundedAtInstant?.let { elapsedSince(it) } ?: Duration.ZERO
        backgroundedAtInstant = null
        // Capture the lifecycle scope BEFORE acquiring admission. If stop() already cleared it,
        // skip the acquisition entirely — otherwise tryAcquireShowAdmission() takes the
        // process-wide probe token and sets showInFlight, but there is nothing to launch the work
        // that would release them.
        val activeScope = lifecycle ?: return
        if (backgroundDuration >= config.minBackgroundDuration && tryAcquireShowAdmission()) {
            activeScope.launch {
                showNow()
                if (!controller.isReady()) controller.load()
            }
        } else if (!controller.isReady()) {
            activeScope.launch { controller.load() }
        }
    }

    /**
     * Atomically checks coordinator admission and reserves the next show. Cold-start preload and
     * foreground collection may run in different scopes/threads, so checking [showInFlight] and
     * setting it must be one transition.
     */
    private fun tryAcquireShowAdmission(): Boolean = showAdmissionLock.withLock {
        if (!canShowNowLocked()) return@withLock false
        // Acquired last, after every cheap local check, so a rejected attempt never takes and
        // immediately re-releases the process-wide token.
        if (!tryAcquireProbeTokenLocked()) return@withLock false
        showInFlight = true
        true
    }

    private fun canShowNowLocked(): Boolean {
        if (blocked || showInFlight) return false
        if (manager.status.value != AdManagerStatus.Ready) return false
        val sinceLastShow = elapsedSince(lastShowInstant)
        if (sinceLastShow < config.cooldownBetweenShows) return false
        return controller.isReady()
    }

    /**
     * Claims the process-wide full-screen token, or returns false if another format holds it.
     *
     * This replaces a read of `isFullScreenPresenting.value`. That read was a TOCTOU: it was
     * taken under [showAdmissionLock], which shares nothing with any slot's locks, and was
     * already stale by the time `controller.show()` ran. Acquiring instead means no other
     * full-screen format can commit to a presentation between here and the delegation below.
     *
     * Caller must hold [showAdmissionLock].
     */
    private fun tryAcquireProbeTokenLocked(): Boolean {
        val arbiter = (manager as? FullScreenPresenceAware)?.fullScreenArbiter
        // A manager that does not implement the internal capability (the no-op manager, or a
        // third-party AdManager) cannot arbitrate. Preserve the previous permissive behavior
        // rather than deadlocking such a host into never showing an app-open ad.
            ?: return true
        val token = arbiter.tryAcquire(controller.placement.id, controller.placement.format)
            ?: return false
        probeToken = token
        return true
    }

    /**
     * Releases the probe token.
     *
     * MUST be called before delegating to `controller.show()`. The controller is itself a
     * full-screen slot and acquires the very same token inside its own `prepareShow`; holding
     * the probe across the delegation would make the slot lose to the coordinator every time
     * and return NotReady forever (the arbiter is first-come-first-served with no queueing).
     * The slot's acquisition is the authoritative one for the actual presentation window; the
     * probe exists only to stop the coordinator starting a show while another format presents.
     */
    private fun releaseProbeToken() {
        val token = probeToken ?: return
        probeToken = null
        (manager as? FullScreenPresenceAware)?.fullScreenArbiter?.release(token)
    }

    private suspend fun showNow() {
        try {
            currentCoroutineContext().ensureActive()
            // Hand the token back before delegating: the controller's own prepareShow acquires
            // it, and it is not reentrant. See releaseProbeToken().
            showAdmissionLock.withLock { releaseProbeToken() }
            val result = controller.show()
            if (result is AdShowResult.Shown) {
                showAdmissionLock.withLock {
                    lastShowInstant = clock()
                }
            }
        } finally {
            // Covers every terminal result plus direct throws and cancellation — including a
            // throw from ensureActive() before the release above, which would otherwise strand
            // the probe token and block every full-screen format process-wide. releaseProbeToken
            // is idempotent (it nulls the field first), so the double call is safe.
            showAdmissionLock.withLock {
                releaseProbeToken()
                showInFlight = false
            }
        }
    }
}
