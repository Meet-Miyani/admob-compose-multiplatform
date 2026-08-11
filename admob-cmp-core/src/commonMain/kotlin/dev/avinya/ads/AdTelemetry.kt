package dev.avinya.ads


/**
 * Monetary value of an ad event, specified in micro-units of a currency.
 */
public data class AdValue(
    /** Value in micro-units (e.g., 1_000_000 = 1 unit of currency). */
    val valueMicros: Long,
    /** ISO 4217 currency code (e.g., "USD"). */
    val currencyCode: String,
    /** Precision of the reported value. */
    val precision: AdValuePrecision
)

/** Precision of an [AdValue] report. */
public enum class AdValuePrecision { Unknown, Estimated, PublisherProvided, Precise }

/**
 * Impression-level paid event with revenue data. Emitted via
 * [AdEvent.Paid].
 */
public data class PaidEvent(
    /** The placement that earned this revenue. */
    val placementId: String,
    /** Revenue value and currency. */
    val value: AdValue,
    /** Response info for the ad that earned this revenue. */
    val responseInfo: AdResponseInfo? = null
)

/**
 * Information about an ad response, including mediation chain details.
 */
public data class AdResponseInfo(
    /** Unique response identifier. */
    val responseId: String? = null,
    /** Class name of the adapter that loaded this response. */
    val adapterClassName: String? = null,
    /** Extras returned by the ad network. */
    val extras: Map<String, String> = emptyMap(),
    /** Response info for the winning ad network. */
    val loadedAdNetworkResponseInfo: AdNetworkResponseInfo? = null,
    /** Response info for all networks in the mediation chain. */
    val adNetworkResponseInfos: List<AdNetworkResponseInfo> = emptyList()
)

/**
 * Response info for a single ad network in the mediation chain.
 */
public data class AdNetworkResponseInfo(
    /** Class name of the adapter. */
    val adapterClassName: String? = null,
    /** Latency in milliseconds for this network's response. */
    val latencyMillis: Long? = null,
    /** Error, if this network failed to return an ad. */
    val error: AdError? = null,
    /** Name of the ad source. */
    val adSourceName: String? = null,
    /** ID of the ad source. */
    val adSourceId: String? = null,
    /** Instance name of the ad source. */
    val adSourceInstanceName: String? = null,
    /** Instance ID of the ad source. */
    val adSourceInstanceId: String? = null
)

/**
 * Initialization status of a mediation adapter.
 */
public data class AdapterInitializationStatus(
    /** Name of the adapter. */
    val adapterName: String,
    /** Whether the adapter initialized successfully. */
    val initialized: Boolean,
    /** Initialization latency in milliseconds. */
    val latencyMillis: Long? = null,
    /** Human-readable description of the adapter's initialization state. */
    val description: String? = null
)

/**
 * Lifecycle events emitted by ad controllers and pools. Subscribe via
 * [AdManager.events] or individual controller/pool [events] flows.
 */
public sealed interface AdEvent {
    /** The placement that produced this event, or null for global events. */
    public val placementId: String?

    /** An ad was loaded successfully. */
    public data class Loaded(
        override val placementId: String,
        val responseInfo: AdResponseInfo? = null
    ) : AdEvent

    /** An ad load failed. */
    public data class LoadFailed(
        override val placementId: String,
        val error: AdError
    ) : AdEvent

    /**
     * An ad impression was recorded.
     *
     * @property adInstanceId For native ads, this identifies the specific ad instance 
     *   that triggered the event. This allows views in a scrolling list to ignore events 
     *   belonging to other items in the same placement. For single-ad formats (like banners), 
     *   this is typically `null`.
     */
    public data class Impression(
        override val placementId: String,
        val adInstanceId: String? = null
    ) : AdEvent
    /** The ad was clicked. See [Impression.adInstanceId]. */
    public data class Clicked(
        override val placementId: String,
        val adInstanceId: String? = null
    ) : AdEvent
    /** A full-screen ad opened. */
    public data class OpenedFullScreen(override val placementId: String) : AdEvent
    /** A full-screen ad was dismissed. */
    public data class ClosedFullScreen(override val placementId: String) : AdEvent

    /** Show attempt failed. */
    public data class ShowFailed(
        override val placementId: String,
        val error: AdError
    ) : AdEvent

    /** A reward was earned (rewarded/rewarded-interstitial formats). */
    public data class RewardEarned(
        override val placementId: String,
        val reward: AdReward
    ) : AdEvent

    /** An impression-level paid event was recorded. See [Impression.adInstanceId]. */
    public data class Paid(
        override val placementId: String,
        val paidEvent: PaidEvent,
        val adInstanceId: String? = null
    ) : AdEvent

    /**
     * Emitted when native ad video playback starts for the first time.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidPlayVideo`.
     * - **Android:** Not yet emitted — the GMA Next-Gen SDK does not expose video lifecycle
     *   callbacks. Support will be added when that API ships upstream.
     */
    public data class VideoStarted(
        override val placementId: String,
        val adInstanceId: String? = null,
    ) : AdEvent

    /**
     * Emitted when native ad video playback resumes after being paused.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidPlayVideo`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoPlayed(
        override val placementId: String,
        val adInstanceId: String? = null,
    ) : AdEvent

    /**
     * Emitted when native ad video playback pauses.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidPauseVideo`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoPaused(
        override val placementId: String,
        val adInstanceId: String? = null,
    ) : AdEvent

    /**
     * Emitted when native ad video playback completes.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidEndVideoPlayback`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoEnded(
        override val placementId: String,
        val adInstanceId: String? = null,
    ) : AdEvent

    /**
     * Emitted when native ad video is muted or unmuted.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidMuteVideo` / `DidUnmuteVideo`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoMuted(
        override val placementId: String,
        val muted: Boolean,
        val adInstanceId: String? = null,
    ) : AdEvent
}
