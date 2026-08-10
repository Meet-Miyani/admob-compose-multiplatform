package dev.avinya.admob.showcase.ui.ad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.appopen.AppOpenAdCoordinator
import dev.avinya.ads.appopen.AppOpenConfig
import dev.avinya.admob.showcase.data.repo.AdTelemetryRepository
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.AppOpenDecision
import dev.avinya.admob.showcase.domain.ad.AppOpenEligibilityPolicy
import dev.avinya.admob.showcase.domain.ad.AppOpenEligibilitySnapshot
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Minimum time backgrounded before a return to foreground may show an ad.
 *
 * Short enough to be demonstrable by hand — background the app, count to five,
 * come back. A production integration would use something longer.
 */
private val MIN_BACKGROUND = 4.seconds

/**
 * Minimum gap between two coordinator-driven shows.
 *
 * Deliberately short for a sample. Real apps should use hours; showing an
 * app-open ad on every single foreground is the fastest way to train users to
 * force-quit.
 */
private val SHOW_COOLDOWN = 15.seconds

/**
 * Hosts the process-wide [AppOpenAdCoordinator] and binds it to the showcase's
 * [AppOpenEligibilityPolicy].
 *
 * The full lifecycle lives here:
 *
 * - **Preload.** `preloadOnStart` warms an ad at startup so the next genuine
 *   foreground has one ready. There is no cold-start show: on most devices the
 *   first frame wins the race, and an app-open ad that appears *after* the user
 *   is already reading is worse than none.
 * - **Show.** The coordinator watches foreground transitions and shows only
 *   after [MIN_BACKGROUND] backgrounded and [SHOW_COOLDOWN] since the last one.
 * - **Reload.** The coordinator reloads after each consumption.
 * - **Block.** `isBlocked` is bound to the *policy decision*, not merely to the
 *   suppressor — onboarding, sensitive routes, an unready SDK, and missing
 *   consent each veto independently, and every decision is recorded sanitised
 *   into Diagnostics so the behaviour is demonstrable rather than mysterious.
 */
@Composable
fun AppOpenHost(
    suppressor: AppOpenSuppressor,
    telemetry: AdTelemetryRepository,
    content: @Composable () -> Unit,
) {
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val coordinator = remember(adManager) {
        AppOpenAdCoordinator(
            manager = adManager,
            controller = adManager.appOpen(ShowcasePlacements.appOpen),
            config = AppOpenConfig(
                showOnColdStart = false,
                preloadOnStart = true,
                minBackgroundDuration = MIN_BACKGROUND,
                cooldownBetweenShows = SHOW_COOLDOWN,
            ),
        )
    }
    val policy = remember { AppOpenEligibilityPolicy() }

    val status by adManager.status.collectAsState()
    val canRequestAds by adManager.consent.canRequestAds.collectAsState()
    val onboardingComplete by graph.settings.onboardingComplete.collectAsState(initial = null)
    val adsEnabled by graph.settings.adsMasterSwitch.collectAsState(initial = true)

    LaunchedEffect(coordinator) { coordinator.start(this) }
    DisposableEffect(coordinator) { onDispose { coordinator.stop() } }

    LaunchedEffect(
        coordinator,
        suppressor.isBlocked,
        status,
        canRequestAds,
        onboardingComplete,
        adsEnabled,
    ) {
        val decision = policy.isEligible(
            AppOpenEligibilitySnapshot(
                // Null means the preference has not resolved yet — treat that
                // as "not complete", so a slow read can never let an ad slip in
                // ahead of onboarding.
                onboardingComplete = onboardingComplete == true,
                onSensitiveRoute = suppressor.isBlocked,
                // The suppressor is entered around every full-screen
                // presentation, so it already covers this case; keeping the
                // field distinct keeps the policy's reasons legible.
                fullScreenAdShowing = false,
                sdkReady = status == AdManagerStatus.Ready && adsEnabled,
                canRequestAds = canRequestAds,
                // The coordinator owns the real clock and enforces the
                // background-duration rule itself; pass infinity so the
                // policy's non-temporal gates decide the blocked state.
                backgroundDuration = Duration.INFINITE,
                minimumBackgroundDuration = MIN_BACKGROUND,
            ),
        )

        coordinator.isBlocked = decision is AppOpenDecision.Suppress
        telemetry.recordAppOpenDecision(ShowcasePlacements.appOpen.id, decision)
    }

    content()
}
