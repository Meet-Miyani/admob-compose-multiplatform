package dev.avinya.ads

import dev.avinya.ads.nativead.NativeAdOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * Per-platform AdMob **ad-unit** IDs (not app IDs). Provide an ID for
 * each platform the app targets; the correct ID is resolved at runtime
 * via [forPlatform].
 */
public data class AdUnitIds(
    /** Android ad-unit ID (ca-app-pub-.../...). */
    val android: String,
    /** iOS ad-unit ID (ca-app-pub-.../...). */
    val ios: String
) {
    /** Returns the ad-unit ID for the given [platform]. */
    public fun forPlatform(platform: AdPlatform): String = when (platform) {
        AdPlatform.Android -> android
        AdPlatform.Ios -> ios
    }
}

/**
 * A blueprint for an ad slot in your app. 
 * 
 * Think of an [AdPlacement] as a 1:1 mapping to an Ad Unit in your AdMob dashboard. 
 * It holds the unique ID, the format (Banner, Native, Interstitial, etc.), and all 
 * the configuration (retry policies, timeouts, and cache sizes) needed to load the ad.
 *
 * You pass this configuration to [AdManager] (or directly to a Compose view like [BannerAdView]) 
 * to start loading ads.
 *
 * **Example:**
 * ```kotlin
 * val HomeBanner = AdPlacement(
 *     id = "home_banner",
 *     format = AdFormat.Banner,
 *     androidAdUnitId = "ca-app-pub-3940256099942544/6300978111",
 *     iosAdUnitId = "ca-app-pub-3940256099942544/2934735716"
 * )
 * ```
 *
 * @param id A unique identifier used to cache this placement's controller. (e.g., "home_banner")
 * @param format The ad format (Banner, Native, Interstitial, Rewarded, etc.).
 * @param adUnitIds Your AdMob ad unit IDs for iOS and Android.
 * @param requestOptions Optional custom targeting, keywords, or content URLs.
 * @param cachePolicy Controls how many ads to pre-load and how long they live in memory.
 * @param retryPolicy Controls how the SDK handles network failures when loading ads.
 * @param timeoutPolicy Enforces maximum wait times for loading and showing ads.
 * @param bannerSizePolicy Sizing strategy for banners (adaptive, fixed, fluid).
 * @param bannerRefreshPolicy Controls whether the banner auto-refreshes.
 * @param nativeOptions Layout and behavioral options for native ads.
 * @param fullScreenOptions Display options for full-screen ads.
 * @param enabled When false, the SDK completely ignores this placement. Useful for A/B testing or feature flags.
 * @param strictTestMode A safety net for debugging. When `true`, the SDK throws a hard exception if you accidentally pass a production Ad Unit ID instead of a Google Test ID. Only enable this in debug builds.
 * @throws IllegalArgumentException if [id] is blank, max cache size < 1, or if [strictTestMode] catches a live ad unit ID.
 */
public data class AdPlacement(
    val id: String,
    val format: AdFormat,
    val adUnitIds: AdUnitIds,
    val requestOptions: AdRequestOptions = AdRequestOptions(),
    val cachePolicy: AdCachePolicy = AdCachePolicy(),
    val retryPolicy: AdRetryPolicy = AdRetryPolicy(),
    val timeoutPolicy: AdTimeoutPolicy = AdTimeoutPolicy(),
    val bannerSizePolicy: AdSizePolicy = AdSizePolicy.LargeAnchoredAdaptive(),
    val bannerRefreshPolicy: BannerRefreshPolicy = BannerRefreshPolicy.AdServerManaged,
    val nativeOptions: NativeAdOptions = NativeAdOptions(),
    val fullScreenOptions: FullScreenAdOptions = FullScreenAdOptions(),
    val enabled: Boolean = true,
    val strictTestMode: Boolean = false
) {
    /**
     * Convenience constructor that accepts platform ad-unit IDs directly
     * instead of an [AdUnitIds] wrapper.
     *
     * @param id Unique identifier for this placement.
     * @param format The ad format.
     * @param androidAdUnitId Android ad-unit ID.
     * @param iosAdUnitId iOS ad-unit ID.
     * @param maxCacheSize Maximum number of cached ads (default 1). Must be >= 1.
     * @param enabled When false, the placement is skipped.
     * @param strictTestMode When true, the placement throws at construction if any ad unit id
     *   is not a Google test ad unit.
     * @throws IllegalArgumentException if [id] is blank, [maxCacheSize] < 1, or
     *   [strictTestMode] is enabled and any ad unit id is not a test unit.
     */
    public constructor(
        id: String,
        format: AdFormat,
        androidAdUnitId: String,
        iosAdUnitId: String,
        maxCacheSize: Int = 1,
        enabled: Boolean = true,
        strictTestMode: Boolean = false
    ) : this(
        id = id,
        format = format,
        adUnitIds = AdUnitIds(androidAdUnitId, iosAdUnitId),
        cachePolicy = AdCachePolicy(maxSize = maxCacheSize),
        enabled = enabled,
        strictTestMode = strictTestMode
    )

    init {
        require(id.isNotBlank()) { "AdPlacement.id must not be blank." }
        require(cachePolicy.maxSize >= 1) { "AdCachePolicy.maxSize must be at least 1." }
        // Fails CLOSED. AdDebugOptions.testMode only affects UMP — it does NOT make GMA
        // serve test ads (P0-7), so a developer trusting it requests REAL ads against
        // production ad units. That is invalid traffic, and invalid traffic gets AdMob
        // accounts suspended. A warning was not enough; this is a hard stop.
        if (strictTestMode) {
            val production = listOfNotNull(
                adUnitIds.android.takeUnless(TestAdIds::isTestAdUnitId),
                adUnitIds.ios.takeUnless(TestAdIds::isTestAdUnitId),
            )
            require(production.isEmpty()) {
                "AdPlacement '$id' has strictTestMode enabled but uses production ad unit id(s): " +
                    "${production.joinToString()}. Requesting real ads in a test/debug build is " +
                    "invalid traffic and risks AdMob account suspension. Use TestAdIds, or set " +
                    "strictTestMode = false if you deliberately want live ads here."
            }
        }
    }

    /** Convenience accessor for the Android ad-unit ID. */
    public val androidAdUnitId: String get() = adUnitIds.android
    /** Convenience accessor for the iOS ad-unit ID. */
    public val iosAdUnitId: String get() = adUnitIds.ios
    /** Convenience accessor for [AdCachePolicy.maxSize]. */
    public val maxCacheSize: Int get() = cachePolicy.maxSize
}

/**
 * Per-request targeting and configuration options for ad loads. Android-only
 * fields are silently ignored on iOS.
 */
public data class AdRequestOptions(
    /** Keywords for ad targeting. */
    val keywords: Set<String> = emptySet(),
    /** Content URL for contextual ad targeting. */
    val contentUrl: String? = null,
    /** Neighboring content URLs for contextual targeting. */
    val neighboringContentUrls: Set<String> = emptySet(),
    /** Custom request agent string. */
    val requestAgent: String? = null,
    /**
     * Ad Manager category exclusions. Mapped on Android via `addCategoryExclusion()`
     * and on iOS via `GAMRequest.categoryExclusions`.
     *
     * Requires an Ad Manager–enabled ad unit on iOS; standard AdMob units may ignore this field.
     */
    val categoryExclusions: Set<String> = emptySet(),
    /** Custom targeting key-value pairs. Mapped on both platforms (multi-value lists are comma-joined on iOS). */
    val customTargeting: Map<String, List<String>> = emptyMap(),
    /** Google-certified extras for mediation SDKs. */
    val googleExtras: Map<String, String> = emptyMap(),
    /**
     * Publisher-provided identifier for frequency capping, audience segmentation, and attribution.
     * Mapped on Android via `setPublisherProvidedId()` and on iOS via `GAMRequest.publisherProvidedID`.
     *
     * Requires an Ad Manager–enabled ad unit on iOS; standard AdMob units may ignore this field.
     */
    val publisherProvidedId: String? = null,
    /** Placement reporting id. Mapped on both platforms (`placementID` on iOS). */
    val placementId: Long? = null,
    /** **Android only.** Skip-uninitialized-adapters flag; ignored on iOS. */
    val skipUninitializedAdapters: Boolean = false
)

private fun requireFinitePositive(name: String, value: Duration) {
    require(value.isFinite() && value.isPositive()) {
        "$name must be finite and positive, was $value"
    }
}

/**
 * Caching policy for ad formats that support multiple cached ads (native,
 * full-screen). Cache is FIFO; ads are evicted when [maxSize] is exceeded
 * or when TTL (from [expirationPolicy]) expires.
 */
public data class AdCachePolicy(
    /** Maximum number of ads to cache. Must be >= 1. */
    val maxSize: Int = 1,
    /** TTL policy per ad format. */
    val expirationPolicy: AdExpirationPolicy = AdExpirationPolicy(),
    /** If true, preload a new ad after the current one is shown. */
    val reloadAfterShow: Boolean = false
) {
    init {
        require(maxSize >= 1) { "AdCachePolicy.maxSize must be at least 1." }
    }
}

/**
 * Time-to-live policy for cached ads. Defaults: full-screen 1 hour,
 * app-open 4 hours, native 1 hour.
 */
public data class AdExpirationPolicy(
    /** TTL for cached interstitial and rewarded ads. Default 1h. */
    val fullScreenTtl: Duration = 1.hours,
    /** TTL for cached app-open ads. Default 4h. */
    val appOpenTtl: Duration = 4.hours,
    /** TTL for cached native ads. Default 1h. */
    val nativeTtl: Duration = 1.hours
) {
    init {
        requireFinitePositive("AdExpirationPolicy.fullScreenTtl", fullScreenTtl)
        requireFinitePositive("AdExpirationPolicy.appOpenTtl", appOpenTtl)
        requireFinitePositive("AdExpirationPolicy.nativeTtl", nativeTtl)
    }
}

/**
 * Capped exponential-backoff retry policy for ad load failures. Only
 * retryable failures (network, timeout, internal) are retried;
 * non-retryable failures (no fill, consent) are not.
 */
public data class AdRetryPolicy(
    /** Total load attempts, including the initial attempt. Default 2 (one retry). */
    val maxAttempts: Int = 2,
    /** Initial delay before the first retry. Default 2s. */
    val initialDelay: Duration = 2.seconds,
    /** Maximum delay between retries. Default 30s. */
    val maxDelay: Duration = 30.seconds,
    /** Backoff multiplier applied after each attempt. Default 2.0. */
    val backoffMultiplier: Double = 2.0
) {
    init {
        require(maxAttempts >= 1) { "AdRetryPolicy.maxAttempts must be at least 1." }
        requireFinitePositive("AdRetryPolicy.initialDelay", initialDelay)
        requireFinitePositive("AdRetryPolicy.maxDelay", maxDelay)
        require(maxDelay >= initialDelay) {
            "AdRetryPolicy.maxDelay must be greater than or equal to initialDelay."
        }
        require(backoffMultiplier.isFinite() && backoffMultiplier >= 1.0) {
            "AdRetryPolicy.backoffMultiplier must be finite and at least 1.0."
        }
    }
}

/**
 * Defines the size of a banner ad. Choice depends on the desired ad format
 * and container constraints.
 */
public sealed interface AdSizePolicy {
    /** Larger anchored adaptive banner (up to 120dp). */
    public data class LargeAnchoredAdaptive(val collapsible: CollapsiblePlacement? = null) : AdSizePolicy
    /** Inline adaptive banner with an optional maximum height. */
    public data class InlineAdaptive(val maxHeightDp: Int? = null) : AdSizePolicy {
        init {
            if (maxHeightDp != null) {
                require(maxHeightDp > 0) { "InlineAdaptive maxHeightDp must be positive if specified." }
            }
        }
    }
    /** Fixed-size banner with explicit width and height in dp. */
    public data class Fixed(val widthDp: Int, val heightDp: Int) : AdSizePolicy {
        init {
            require(widthDp > 0 && heightDp > 0) { "Fixed widthDp and heightDp must be positive." }
        }
    }
    /** Fluid banner that fills available width with no fixed height. */
    public data object Fluid : AdSizePolicy
}

/**
 * Placement for a collapsible banner ad. [Top] collapses upward,
 * [Bottom] collapses downward when the ad is not visible.
 */
public enum class CollapsiblePlacement { Top, Bottom }

/**
 * Controls how banner ads are refreshed. Choose based on whether you want
 * server-driven, client-timer, or manual refresh.
 *
 * @see BannerAdController.refresh
 */
public sealed interface BannerRefreshPolicy {
    /** AdMob server controls refresh via the AdMob UI. No client-side timer. */
    public data object AdServerManaged : BannerRefreshPolicy

    /**
     * Client-side timer reload (app-driven, despite the historical name). Interval must be
     * between 30s and 120s per AdMob policy.
     *
     * Do **not** combine this with AdMob-UI auto-refresh on the same ad unit: the SDK already
     * refreshes a visible banner if a refresh rate is configured in the AdMob console, so a
     * client timer on top of that double-refreshes — producing impressions faster than
     * configured and risking AdMob invalid-traffic / refresh-rate enforcement. Use
     * [AdServerManaged] when the unit is set to auto-refresh in the console; use [SdkManaged]
     * only when console auto-refresh is off and you want the app to drive the cadence.
     *
     * @param interval Refresh interval (30s–120s).
     * @throws IllegalArgumentException if [interval] is outside 30s..120s.
     */
    public data class SdkManaged(val interval: Duration) : BannerRefreshPolicy {
        init {
            require(interval in 30.seconds..120.seconds) {
                "SdkManaged refresh interval must be between 30s and 120s per AdMob policy."
            }
        }
    }

    /** No automatic reload. Call [BannerAdController.refresh] manually. */
    public data object Manual : BannerRefreshPolicy
}
