@file:OptIn(ExperimentalAtomicApi::class)

package dev.avinya.ads.nativead

import com.google.android.libraries.ads.mobile.sdk.nativead.NativeAd
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.InternalAdMobCmpApi
import dev.avinya.ads.internal.NativeAdSessionRenderOwner
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@InternalAdMobCmpApi
public interface AndroidNativeAdRenderLease {
    public val adInstanceId: String
    public val ad: NativeAd
    public fun release()
}

@InternalAdMobCmpApi
public fun NativeAdSession.acquireAndroidRenderLease(
    slotKey: String,
    placement: AdPlacement,
    rendererId: String,
): AndroidNativeAdRenderLease? {
    val owner = (this as? NativeAdSessionRenderOwner<AndroidLoadedNativeAd>)?.owner ?: return null
    val record = owner.acquireRender(slotKey, placement, rendererId, this) ?: return null
    return object : AndroidNativeAdRenderLease {
        // Atomic CAS, not a plain Boolean. The public lease can be released from more than one
        // callback or thread, and two concurrent release() calls could both pass a non-atomic
        // guard and invoke owner release twice. Downstream record-identity checks reduced the
        // blast radius, but the lease's own exactly-once contract was not self-contained.
        // Mirrors RewardDelivery, which already uses this pattern for exactly-once delivery.
        private val released = AtomicBoolean(false)
        override val adInstanceId: String = record.adInstanceId
        override val ad: NativeAd = record.ad.ad
        override fun release() {
            if (!released.compareAndSet(expectedValue = false, newValue = true)) return
            owner.releaseRender(slotKey, placement, rendererId, record.recordId, this@acquireAndroidRenderLease)
        }
    }
}
