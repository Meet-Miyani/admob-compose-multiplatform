@file:OptIn(ExperimentalAtomicApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.avinya.ads.nativead

import GoogleMobileAds.GADNativeAd
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.InternalAdMobCmpApi
import dev.avinya.ads.internal.NativeAdSessionRenderOwner
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@InternalAdMobCmpApi
public interface IosNativeAdRenderLease {
    public val adInstanceId: String
    public val ad: GADNativeAd
    public fun release()
}

@InternalAdMobCmpApi
public fun NativeAdSession.acquireIosNativeAdRenderLease(
    slotKey: String,
    placement: AdPlacement,
    rendererId: String,
): IosNativeAdRenderLease? {
    val owner = (this as? NativeAdSessionRenderOwner<LoadedNativeAd>)?.owner ?: return null
    val record = owner.acquireRender(slotKey, placement, rendererId, this) ?: return null
    return object : IosNativeAdRenderLease {
        // Atomic CAS, not a plain Boolean. The public lease can be released from more than one
        // callback or thread, and two concurrent release() calls could both pass a non-atomic
        // guard and invoke owner release twice. Downstream record-identity checks reduced the
        // blast radius, but the lease's own exactly-once contract was not self-contained.
        // Mirrors RewardDelivery, which already uses this pattern for exactly-once delivery.
        private val released = AtomicBoolean(false)
        override val adInstanceId: String = record.adInstanceId
        override val ad: GADNativeAd = record.ad.ad
        override fun release() {
            if (!released.compareAndSet(expectedValue = false, newValue = true)) return
            owner.releaseRender(slotKey, placement, rendererId, record.recordId, this@acquireIosNativeAdRenderLease)
        }
    }
}
