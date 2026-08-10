package dev.avinya.admob.showcase.domain.ad

import dev.avinya.ads.AdCachePolicy
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdSizePolicy
import dev.avinya.ads.AdUnitIds
import dev.avinya.ads.BannerRefreshPolicy
import dev.avinya.ads.CollapsiblePlacement
import dev.avinya.ads.FullScreenAdOptions
import dev.avinya.ads.ServerSideVerificationOptions
import dev.avinya.ads.TestAdIds
import dev.avinya.ads.nativead.NativeAdBatching
import dev.avinya.ads.nativead.NativeAdOptions

/**
 * Every placement the showcase uses — a **static, finite** catalog.
 *
 * Controllers are cached per `AdPlacement.id` for the manager's lifetime and
 * are never evicted, so generated per-item ids leak permanently. The feed
 * serves per-item ads from the native pool keyed by `itemKey`, rather than
 * minting a placement per row.
 *
 * `strictTestMode = true` throws at construction if any of these ever points
 * at a production ad unit.
 *
 * Two rules keep this list honest, both enforced by `ShowcasePlacementsTest`:
 *
 * 1. **Every placement is rendered somewhere.** The Inspector's Placements tab
 *    builds a controller for each entry in [allPlacements], so an unused
 *    placement is not free — it loads inventory for a surface that does not
 *    exist. An earlier revision carried five such orphans.
 * 2. **No banner in a scrolling feed.** Today, Discover, and Library carry no
 *    banner: a banner welded to an infinite list is the integration this
 *    sample argues against. The reader's collapsible bottom banner is a
 *    different case — bounded content, one anchored slot, dismissible.
 */
object ShowcasePlacements {

    // ---- Consumer product ---------------------------------------------------

    /** Native slots inside the Today and Discover feeds. */
    val feedNative: AdPlacement = AdPlacement(
        id = "feed_native",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_NATIVE, ios = TestAdIds.IOS_NATIVE),
        cachePolicy = AdCachePolicy(maxSize = 5, reloadAfterShow = true),
        nativeOptions = NativeAdOptions(batching = NativeAdBatching.GoogleOnly),
        strictTestMode = true,
    )

    /** The single inline slot inside an article. */
    val articleNative: AdPlacement = AdPlacement(
        id = "article_native",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_NATIVE, ios = TestAdIds.IOS_NATIVE),
        cachePolicy = AdCachePolicy(maxSize = 2),
        strictTestMode = true,
    )

    /**
     * Collapsible banner anchored to the bottom of the article reader.
     *
     * Collapsible is the deliberate choice: it opens to the larger creative
     * once, then collapses to a slim bar the reader can dismiss out of the
     * way, so a long read is not permanently taxed. `AdServerManaged` refresh
     * keeps the cadence with the ad server rather than a hard-coded timer.
     */
    val articleBanner: AdPlacement = AdPlacement(
        id = "article_banner",
        format = AdFormat.Banner,
        adUnitIds = AdUnitIds(
            android = TestAdIds.ANDROID_COLLAPSIBLE_BANNER,
            ios = TestAdIds.IOS_COLLAPSIBLE_BANNER,
        ),
        bannerSizePolicy = AdSizePolicy.LargeAnchoredAdaptive(
            collapsible = CollapsiblePlacement.Bottom,
        ),
        bannerRefreshPolicy = BannerRefreshPolicy.AdServerManaged,
        strictTestMode = true,
    )

    /**
     * Interstitial shown when the reader *leaves* an article.
     *
     * Leaving is a natural break; opening is not. `AdPolicy` additionally caps
     * this to one interstitial every third article, no sooner than 60s after
     * the last one and never in the first 30s of a cold start — so the format
     * is demonstrated without the sample modelling a hostile integration.
     */
    val articleInterstitial: AdPlacement = AdPlacement(
        id = "article_interstitial",
        format = AdFormat.Interstitial,
        adUnitIds = AdUnitIds(
            android = TestAdIds.ANDROID_INTERSTITIAL,
            ios = TestAdIds.IOS_INTERSTITIAL,
        ),
        cachePolicy = AdCachePolicy(maxSize = 2, reloadAfterShow = true),
        strictTestMode = true,
    )

    /**
     * Rewarded ad that unlocks a single premium article.
     *
     * The clearest rewarded value exchange there is: the reader wants this
     * specific story, and the price is one ad rather than coins.
     */
    val articleUnlockRewarded: AdPlacement = AdPlacement(
        id = "article_unlock_rewarded",
        format = AdFormat.Rewarded,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_REWARDED, ios = TestAdIds.IOS_REWARDED),
        fullScreenOptions = FullScreenAdOptions(
            serverSideVerification = ServerSideVerificationOptions(
                userId = "showcase-demo-user",
                customData = "article_unlock_rewarded",
            ),
        ),
        strictTestMode = true,
    )

    /**
     * Watch-to-earn on the Rewards screen.
     *
     * The server-side verification options are set to show where they belong.
     * A real coin economy would verify the grant server-side before crediting;
     * standing up that endpoint is out of scope for a sample.
     */
    val rewardsRewarded: AdPlacement = AdPlacement(
        id = "rewards_rewarded",
        format = AdFormat.Rewarded,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_REWARDED, ios = TestAdIds.IOS_REWARDED),
        fullScreenOptions = FullScreenAdOptions(
            serverSideVerification = ServerSideVerificationOptions(
                userId = "showcase-demo-user",
                customData = "rewards_rewarded",
            ),
        ),
        strictTestMode = true,
    )

    /** The bonus-grant format on the Rewards screen. */
    val rewardsRewardedInterstitial: AdPlacement = AdPlacement(
        id = "rewards_rewarded_interstitial",
        format = AdFormat.RewardedInterstitial,
        adUnitIds = AdUnitIds(
            android = TestAdIds.ANDROID_REWARDED_INTERSTITIAL,
            ios = TestAdIds.IOS_REWARDED_INTERSTITIAL,
        ),
        fullScreenOptions = FullScreenAdOptions(
            serverSideVerification = ServerSideVerificationOptions(
                userId = "showcase-demo-user",
                customData = "rewards_rewarded_interstitial",
            ),
        ),
        strictTestMode = true,
    )

    /** Governed by `AppOpenEligibilityPolicy`; never shown during onboarding. */
    val appOpen: AdPlacement = AdPlacement(
        id = "app_open",
        format = AdFormat.AppOpen,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_APP_OPEN, ios = TestAdIds.IOS_APP_OPEN),
        strictTestMode = true,
    )

    // ---- SDK Lab ------------------------------------------------------------

    val labBanner: AdPlacement = AdPlacement(
        id = "lab_banner",
        format = AdFormat.Banner,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_BANNER, ios = TestAdIds.IOS_BANNER),
        bannerSizePolicy = AdSizePolicy.LargeAnchoredAdaptive(),
        bannerRefreshPolicy = BannerRefreshPolicy.AdServerManaged,
        strictTestMode = true,
    )

    val labNative: AdPlacement = AdPlacement(
        id = "lab_native",
        format = AdFormat.Native,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_NATIVE, ios = TestAdIds.IOS_NATIVE),
        cachePolicy = AdCachePolicy(maxSize = 2, reloadAfterShow = true),
        strictTestMode = true,
    )

    val labInterstitial: AdPlacement = AdPlacement(
        id = "lab_interstitial",
        format = AdFormat.Interstitial,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_INTERSTITIAL, ios = TestAdIds.IOS_INTERSTITIAL),
        cachePolicy = AdCachePolicy(maxSize = 1, reloadAfterShow = true),
        strictTestMode = true,
    )

    val labRewarded: AdPlacement = AdPlacement(
        id = "lab_rewarded",
        format = AdFormat.Rewarded,
        adUnitIds = AdUnitIds(android = TestAdIds.ANDROID_REWARDED, ios = TestAdIds.IOS_REWARDED),
        fullScreenOptions = FullScreenAdOptions(
            serverSideVerification = ServerSideVerificationOptions(
                userId = "showcase-demo-user",
                customData = "lab_rewarded",
            ),
        ),
        strictTestMode = true,
    )

    val labRewardedInterstitial: AdPlacement = AdPlacement(
        id = "lab_rewarded_interstitial",
        format = AdFormat.RewardedInterstitial,
        adUnitIds = AdUnitIds(
            android = TestAdIds.ANDROID_REWARDED_INTERSTITIAL,
            ios = TestAdIds.IOS_REWARDED_INTERSTITIAL,
        ),
        strictTestMode = true,
    )

    /**
     * The full catalog, in a stable order. Used by the Inspector / telemetry
     * pipeline to resolve `placementId -> AdFormat` without re-listing the
     * ids; controllers and pools are unaffected.
     */
    val allPlacements: List<AdPlacement> = listOf(
        feedNative,
        articleNative,
        articleBanner,
        articleInterstitial,
        articleUnlockRewarded,
        rewardsRewarded,
        rewardsRewardedInterstitial,
        appOpen,
        labBanner,
        labNative,
        labInterstitial,
        labRewarded,
        labRewardedInterstitial,
    )

    /** Placements rendered on consumer surfaces, outside the SDK Lab. */
    val consumerPlacements: List<AdPlacement> = listOf(
        feedNative,
        articleNative,
        articleBanner,
        articleInterstitial,
        articleUnlockRewarded,
        rewardsRewarded,
        rewardsRewardedInterstitial,
        appOpen,
    )
}
