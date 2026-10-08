package dev.avinya.admob.showcase.ui.ad

import dev.avinya.ads.appopen.AppOpenConfig
import kotlin.time.Duration.Companion.seconds

/**
 * The showcase's one app-open configuration: `AppOpenHost` runs the coordinator with it and
 * the App Open lab displays it, so the two can no longer disagree.
 *
 * The cooldown and background thresholds are short so the behaviour can be observed by hand.
 * A production integration would use a cooldown of hours.
 */
internal val SHOWCASE_APP_OPEN_CONFIG: AppOpenConfig = AppOpenConfig(
    showOnColdStart = false,
    preloadOnStart = true,
    minBackgroundDuration = 4.seconds,
    cooldownBetweenShows = 15.seconds,
)

/**
 * Whether the host should load an app-open ad now.
 *
 * The coordinator preloads only in `start()`. When the showcase starts before ads are allowed
 * (onboarding, consent, the ads switch), that preload fails and nothing loads again until the
 * next return to the foreground, which then has nothing to show. Loading as soon as all three
 * gates open makes the first eligible return show an ad.
 */
internal fun shouldPreloadAppOpen(
    sdkReady: Boolean,
    canRequestAds: Boolean,
    adsEnabled: Boolean,
    alreadyLoaded: Boolean,
): Boolean = sdkReady && canRequestAds && adsEnabled && !alreadyLoaded
