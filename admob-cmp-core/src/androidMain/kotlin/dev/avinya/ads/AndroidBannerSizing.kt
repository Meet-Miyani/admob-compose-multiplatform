package dev.avinya.ads

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import kotlin.math.roundToInt

public fun Activity.screenWidthDp(): Int {
    val metrics = resources.displayMetrics
    return (metrics.widthPixels / metrics.density).roundToInt()
}

/**
 * Resolves [this] policy against the host-measured [widthDp].
 *
 * [widthDp] is the CONTAINER width and governs the adaptive policies only.
 * [AdSizePolicy.Fixed] carries its own explicit width, and `this.widthDp` below is deliberate:
 * the parameter shadows the receiver's property, so an unqualified `widthDp` inside that branch
 * silently resolved to the container width and requested a size the placement never asked for
 * (a 320x50 placement in a 400dp container was requested as 400x50, which is not a standard
 * banner size). `heightDp` was unaffected because nothing shadows it, which is what made the
 * result a hybrid rather than an obvious break.
 */
public fun AdSizePolicy.toAndroidAdSize(activity: Activity, widthDp: Int): AdSize = when (this) {
    is AdSizePolicy.LargeAnchoredAdaptive -> AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, widthDp)
    is AdSizePolicy.InlineAdaptive -> maxHeightDp?.let { AdSize.getInlineAdaptiveBannerAdSize(widthDp, it) }
        ?: AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(activity, widthDp)
    is AdSizePolicy.Fixed -> AdSize(this.widthDp, heightDp)
    is AdSizePolicy.Fluid -> AdSize.FLUID
}
