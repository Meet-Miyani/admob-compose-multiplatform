package dev.avinya.ads

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Per-request load telemetry and cache expiry for full-screen formats (interstitial, rewarded,
 * rewarded interstitial and app open).
 *
 * [AdEvent] reports what happened to an ad. This reports what the SDK asked the ad network for:
 * every request it sent to the Google Mobile Ads SDK for these formats, with how long it took and
 * how it ended, and every cached ad that expired without being shown. Ad networks count an expired
 * ad as filled, so expiries lower the show rate without any other trace.
 *
 * Native ads are not reported here. Each native load is reported as [AdEvent.Loaded] or
 * [AdEvent.LoadFailed] on [AdManager.events], and the ad each slot holds, with its response info,
 * is in [dev.avinya.ads.nativead.NativeAdSession.adInfo].
 *
 * A hot stream, like [AdManager.events]: an entry nobody is collecting is dropped.
 *
 * New subtypes may be added in minor releases. Include an `else` branch when you switch on it.
 */
public sealed interface AdLoadTelemetry {
    /** The placement the request or the ad belongs to. */
    public val placementId: String

    /** Its format. */
    public val format: AdFormat

    /**
     * An ad request was sent to the platform SDK. Each retry attempt is its own request.
     *
     * A `load()` answered from the cache, one that waited for another load of the same slot and
     * then found it full, and one refused by the consent gate send nothing and report nothing.
     */
    public data class RequestStarted(
        override val placementId: String,
        override val format: AdFormat,
        /** Unique for this process; [RequestFinished] carries the same value. */
        val requestId: Long,
        val origin: AdLoadOrigin,
        /** 1 for the first attempt of a `load()`, 2 for its first retry, and so on. */
        val attempt: Int,
    ) : AdLoadTelemetry

    /** How a request ended. Exactly one per [RequestStarted], with the same [requestId]. */
    public data class RequestFinished(
        override val placementId: String,
        override val format: AdFormat,
        val requestId: Long,
        val origin: AdLoadOrigin,
        val attempt: Int,
        val duration: Duration,
        val outcome: AdRequestOutcome,
    ) : AdLoadTelemetry

    /**
     * A cached ad reached its TTL without being shown, and was destroyed. Exactly once per ad.
     *
     * Detected the next time the slot is used — `isReady()`, `availability()`, a load, a show,
     * or `clear()` — so [expiredAt] is the moment it expired, `loadedAt` plus the placement's TTL,
     * not the moment it was noticed. `clear()` and a consent withdrawal report only the ads already
     * past their TTL; the fresh ads they remove are not reported here.
     */
    public data class CachedAdExpired(
        override val placementId: String,
        override val format: AdFormat,
        val loadedAt: Instant,
        val expiredAt: Instant,
        val responseInfo: AdResponseInfo?,
    ) : AdLoadTelemetry
}

/**
 * How one ad request ended.
 *
 * New outcomes may be added in minor releases. Include an `else` branch when you switch on it.
 */
public sealed interface AdRequestOutcome {
    /** An ad was loaded and kept. */
    public data class Loaded(val responseInfo: AdResponseInfo?) : AdRequestOutcome

    /** The platform SDK reported a failure. */
    public data class Failed(val error: AdError) : AdRequestOutcome

    /** The placement's `loadTimeout` passed before the platform SDK answered. */
    public data object TimedOut : AdRequestOutcome

    /** The caller was cancelled before the platform SDK answered. */
    public data object Cancelled : AdRequestOutcome

    /**
     * An ad arrived but could not be kept — the cache was already full, the slot was cleared, or
     * the caller went away as it arrived — and was destroyed.
     */
    public data object Discarded : AdRequestOutcome
}

/**
 * What started an ad request.
 *
 * New origins may be added in minor releases. Include an `else` branch when you switch on it.
 */
public sealed interface AdLoadOrigin {
    /** `load()` or `preload()` called by the app; [tag] comes from an [AdLoadTag] in the calling coroutine. */
    public data class App(val tag: String?) : AdLoadOrigin

    /** `AdCachePolicy.reloadAfterShow` refilling the slot after a show. */
    public data object ReloadAfterShow : AdLoadOrigin

    /** `AppOpenAdCoordinator` at start: `preloadOnStart` or `showOnColdStart`. */
    public data object AppOpenStart : AdLoadOrigin

    /** `AppOpenAdCoordinator` loading after a return to the foreground. */
    public data object AppOpenAfterReturn : AdLoadOrigin
}

/**
 * Labels the app's own loads in [AdLoadTelemetry], without changing how they are called:
 *
 * ```kotlin
 * withContext(AdLoadTag("cold_start")) { controller.load() }
 * ```
 *
 * The tag is read once per `load()` call and reported as [AdLoadOrigin.App.tag].
 */
public class AdLoadTag(public val value: String) : AbstractCoroutineContextElement(AdLoadTag) {
    /** The [CoroutineContext.Key] of [AdLoadTag]. */
    public companion object Key : CoroutineContext.Key<AdLoadTag>

    override fun toString(): String = "AdLoadTag($value)"
}

/**
 * Per-request load telemetry and cache expiry for this manager's full-screen formats. See
 * [AdLoadTelemetry].
 *
 * An extension over an internal capability rather than a member, so [AdManager] gains no
 * abstract member. A manager without the capability ([NoOpAdManager], or a custom one) never
 * emits.
 */
public val AdManager.loadTelemetry: SharedFlow<AdLoadTelemetry>
    get() = (this as? LoadTelemetrySource)?.loadTelemetryFlow ?: SilentLoadTelemetry

/** Capability behind [loadTelemetry]. Implemented by the platform managers. */
internal interface LoadTelemetrySource {
    val loadTelemetryFlow: SharedFlow<AdLoadTelemetry>
}

private val SilentLoadTelemetry: SharedFlow<AdLoadTelemetry> =
    MutableSharedFlow<AdLoadTelemetry>().asSharedFlow()
