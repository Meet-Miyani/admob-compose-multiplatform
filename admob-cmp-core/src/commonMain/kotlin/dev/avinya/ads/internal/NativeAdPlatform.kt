@file:OptIn(dev.avinya.ads.InternalAdMobCmpApi::class)

package dev.avinya.ads.internal

import dev.avinya.ads.AdAttemptResult
import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdResponseInfo
import dev.avinya.ads.nativead.NativeMediaInfo


/**
 * Platform boundary for the native-ad coordinator. The shared coordinator
 * drives loads through this interface; the actual GMA / UMP integration
 * lives in the platform implementation (Android: `GMA Next-Gen`,
 * iOS: `GMA 13.x`).
 *
 * The platform implementation reads
 * [dev.avinya.ads.nativead.NativeAdOptions.batching] to decide between
 * the single-ad and multi-ad GMA overloads; the shared coordinator never
 * infers that choice from placement or session identity because it cannot
 * see the server-side mediation configuration.
 *
 * Contract:
 * - [load] is called once per platform call by the per-placement
 *   scheduler. It must return all resolved ads, the failure, or a
 *   partial result. The [generation] is the placement-level generation
 *   in force at the time the call was issued; the platform passes it
 *   through to its callback path so the coordinator can match a late
 *   callback to the right generation.
 * - [destroy] is called for every ad the coordinator decides to drop
 *   (stale generation, partial-batch remainder, top-up failure, expiry,
 *   clear, consent revocation). The platform implementation must
 *   release all native-side resources for [ad] and never let it be
 *   re-used.
 * - [responseInfo] and [mediaInfo] must return what the platform captured
 *   when the ad loaded, and must not call into the platform SDK. The
 *   coordinator reads them once per record, under its lock and off the
 *   main thread, when the ad is admitted to a slot or kept as a spare,
 *   and never asks the platform again for that ad.
 */
internal data class NativeAdPlatformBatch<A : Any>(
    val ads: List<A>,
    val unfilledError: AdError?,
)

internal data class NativeAdRenderRecord<A : Any>(
    val recordId: NativeAdRecordId,
    val adInstanceId: String,
    val ad: A,
    val mediaInfo: NativeMediaInfo?,
)

internal interface NativeAdPlatform<A : Any> {
    suspend fun load(
        placement: AdPlacement,
        count: Int,
        generation: Long,
    ): AdAttemptResult<NativeAdPlatformBatch<A>>

    suspend fun bindEvents(
        ad: A,
        adInstanceId: String,
        emit: (AdEvent) -> Unit,
    )

    fun destroy(ad: A)

    fun responseInfo(ad: A): AdResponseInfo?

    fun mediaInfo(ad: A): NativeMediaInfo?
}
