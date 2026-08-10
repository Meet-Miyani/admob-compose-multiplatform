package dev.avinya.admob.showcase.domain.lab

import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.nav.AppOpenLabRoute
import dev.avinya.admob.showcase.nav.BannerLabRoute
import dev.avinya.admob.showcase.nav.FullScreenLabRoute
import dev.avinya.admob.showcase.nav.NativeLabRoute
import dev.avinya.admob.showcase.nav.ShowcaseNavKey

/**
 * Every ad format the SDK can exercise, as a stable enum for coverage checks.
 *
 * Mirrors [AdFormat] so the Lab can assert that every public format has an
 * intentional, inspectable scenario without importing the SDK's format list
 * into a UI test.
 */
enum class SupportedAdFormat(val displayName: String) {
    Banner("Banner"),
    Interstitial("Interstitial"),
    Native("Native"),
    Rewarded("Rewarded"),
    RewardedInterstitial("Rewarded Interstitial"),
    AppOpen("App Open"),
    ;
}

/**
 * One intentional, user-triggered Lab scenario per supported format.
 *
 * [destination] is the route that hosts the scenario; [placement] is the
 * official test placement the scenario exercises; [description] is the
 * human-readable explanation shown on the Lab index. The coverage test
 * asserts this mapping is complete — a new SDK format must get a scenario
 * before it can ship silently.
 */
enum class SdkLabScenario(
    val format: SupportedAdFormat,
    val destination: ShowcaseNavKey,
    val placement: AdPlacement,
    val description: String,
) {
    Banner(
        format = SupportedAdFormat.Banner,
        destination = BannerLabRoute,
        placement = ShowcasePlacements.labBanner,
        description = "Anchored adaptive, fixed size, and the collapsible variant the reader uses.",
    ),
    Interstitial(
        format = SupportedAdFormat.Interstitial,
        destination = FullScreenLabRoute,
        placement = ShowcasePlacements.labInterstitial,
        description = "A full-screen interstitial with separate Load and Show actions and readiness state.",
    ),
    Native(
        format = SupportedAdFormat.Native,
        destination = NativeLabRoute,
        placement = ShowcasePlacements.labNative,
        description = "One creative through five layouts, with live validator findings and session controls.",
    ),
    Rewarded(
        format = SupportedAdFormat.Rewarded,
        destination = FullScreenLabRoute,
        placement = ShowcasePlacements.labRewarded,
        description = "Load and show with observable readiness; credits exactly once per grant key.",
    ),
    RewardedInterstitial(
        format = SupportedAdFormat.RewardedInterstitial,
        destination = FullScreenLabRoute,
        placement = ShowcasePlacements.labRewardedInterstitial,
        description = "The combined format, with identical once-only grant accounting.",
    ),
    AppOpen(
        format = SupportedAdFormat.AppOpen,
        destination = AppOpenLabRoute,
        placement = ShowcasePlacements.appOpen,
        description = "Live eligibility gates, the coordinator's cache, and the last recorded decision.",
    ),
    ;
}
