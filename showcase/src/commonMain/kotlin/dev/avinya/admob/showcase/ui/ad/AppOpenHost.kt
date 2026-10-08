package dev.avinya.admob.showcase.ui.ad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.appopen.AppOpenAdCoordinator
import dev.avinya.admob.showcase.data.repo.AdTelemetryRepository
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.AppOpenDecision
import dev.avinya.admob.showcase.domain.ad.AppOpenEligibilityPolicy
import dev.avinya.admob.showcase.domain.ad.AppOpenEligibilitySnapshot
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import kotlin.time.Duration

/**
 * Hosts the process-wide [AppOpenAdCoordinator] and binds it to the showcase's
 * [AppOpenEligibilityPolicy].
 *
 * The full lifecycle lives here:
 *
 * - **Preload.** `preloadOnStart` warms an ad at startup so the next genuine
 *   foreground has one ready, and the host loads again as soon as ads become
 *   allowed (see [shouldPreloadAppOpen]), because a startup preload made before
 *   consent or the ads switch fails and is not retried until the next return. There is no cold-start show: on most devices the
 *   first frame wins the race, and an app-open ad that appears *after* the user
 *   is already reading is worse than none.
 * - **Show.** The coordinator watches foreground transitions and shows only after
 *   the background and cooldown thresholds in [ShowcaseAppOpenConfig].
 * - **Reload.** The coordinator reloads after each consumption.
 * - **Skip after a click.** `skipAfterAdClick` is on, so returning from an ad's
 *   landing page never lands on an app-open ad.
 * - **Block.** `isBlocked` is bound to the *policy decision*, not merely to the
 *   suppressor — onboarding, sensitive routes, screens that show ads, an unready
 *   SDK, and missing consent each veto independently, and every decision is recorded sanitised
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
    val controller = remember(adManager) { adManager.appOpen(ShowcasePlacements.appOpen) }
    val coordinator = remember(adManager) {
        AppOpenAdCoordinator(
            manager = adManager,
            controller = controller,
            config = ShowcaseAppOpenConfig,
        ).also {
            // A user coming back from an ad's landing page is not met by an app-open ad straight
            // away. Opt-in in the SDK; the showcase turns it on. Every banner and native ad here
            // already sits on a screen that blocks app-open ads, so this is defence in depth that
            // also covers a landing page left open over an unblocked screen.
            it.skipAfterAdClick = true
        }
    }
    val policy = remember { AppOpenEligibilityPolicy() }
    // Last decision written to Diagnostics. Switching between a tab with ads and one without
    // re-runs the effect below; only a change of decision is worth a telemetry row.
    var lastRecorded by remember { mutableStateOf<AppOpenDecision?>(null) }

    val status by adManager.status.collectAsState()
    val canRequestAds by adManager.consent.canRequestAds.collectAsState()
    val onboardingComplete by graph.settings.onboardingComplete.collectAsState(initial = null)
    val adsEnabled by graph.settings.adsMasterSwitch.collectAsState(initial = true)

    LaunchedEffect(coordinator) { coordinator.start(this) }
    DisposableEffect(coordinator) { onDispose { coordinator.stop() } }

    LaunchedEffect(
        coordinator,
        suppressor.isBlocked,
        suppressor.isOnAdScreen,
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
                minimumBackgroundDuration = ShowcaseAppOpenConfig.minBackgroundDuration,
                onScreenWithAds = suppressor.isOnAdScreen,
            ),
        )

        coordinator.isBlocked = decision is AppOpenDecision.Suppress
        if (decision != lastRecorded) {
            lastRecorded = decision
            telemetry.recordAppOpenDecision(ShowcasePlacements.appOpen.id, decision)
        }
    }

    LaunchedEffect(controller, status, canRequestAds, adsEnabled) {
        val sdkReady = status == AdManagerStatus.Ready
        if (shouldPreloadAppOpen(sdkReady, canRequestAds, adsEnabled, alreadyLoaded = controller.isReady())) {
            controller.load()
        }
    }

    content()
}
