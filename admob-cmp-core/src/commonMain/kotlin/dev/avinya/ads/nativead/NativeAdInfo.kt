package dev.avinya.ads.nativead

import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdResponseInfo

/**
 * Read-only facts about the native ad that fills a slot of a [NativeAdSession]: which ad it is,
 * and who served it. [NativeAdSession.adInfo] publishes one per slot.
 *
 * A plain class rather than a data class, so a minor release can add a property without a binary
 * break. Two instances are equal when all their properties are equal.
 */
public class NativeAdInfo(
    /**
     * Identifies this ad. The same value as `adInstanceId` on the [AdEvent.Impression],
     * [AdEvent.Clicked] and [AdEvent.Paid] events the ad reports, so those events can be matched
     * to its slot and to [responseInfo].
     *
     * Opaque, and unique within one `AdManager` for the life of the process: don't parse it or
     * store it across launches. To match an ad with Google's own reporting, use
     * [AdResponseInfo.responseId].
     */
    public val adInstanceId: String,
    /** The [AdPlacement.id] the ad was loaded for. */
    public val placementId: String,
    /**
     * What the Google Mobile Ads SDK reported when the ad loaded: the response ID, the ad source
     * that served it ([AdResponseInfo.loadedAdNetworkResponseInfo]) and every network in the
     * mediation chain. Captured once, at load; it is also what the ad's [AdEvent.Paid] carries.
     * Null when the SDK reported none.
     *
     * To group ads across platforms, use [dev.avinya.ads.AdNetworkResponseInfo.adSourceName]:
     * adapter class names differ between Android and iOS.
     */
    public val responseInfo: AdResponseInfo?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NativeAdInfo) return false
        return adInstanceId == other.adInstanceId &&
            placementId == other.placementId &&
            responseInfo == other.responseInfo
    }

    override fun hashCode(): Int {
        var result = adInstanceId.hashCode()
        result = 31 * result + placementId.hashCode()
        result = 31 * result + (responseInfo?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "NativeAdInfo(adInstanceId=$adInstanceId, placementId=$placementId, responseInfo=$responseInfo)"
}
