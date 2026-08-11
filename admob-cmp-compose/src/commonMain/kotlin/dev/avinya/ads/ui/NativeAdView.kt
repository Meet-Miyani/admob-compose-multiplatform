package dev.avinya.ads.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.avinya.ads.AdError
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdTemplates

/**
 * A Composable that displays a native ad bound to a specific [NativeAdSession].
 * 
 * **How it works:**
 * Unlike banners, native ads separate "loading" from "rendering". The [NativeAdSession]
 * preloads the ads in the background. When an ad is ready for this specific [slotKey], 
 * this view binds to it and renders the UI using your provided [layout].
 * 
 * While the ad is loading, it displays the [loading] placeholder. If it fails, it displays
 * the [failure] composable.
 *
 * **Example:**
 * ```kotlin
 * NativeAdView(
 *     session = AdMob.manager.nativeAds.feedSession(),
 *     slotKey = "item_$index",
 *     placement = AdPlacement.Native("my_ad_unit_id"),
 *     layout = AdTemplates.mediaCard
 * )
 * ```
 * 
 * @param session The background session managing ad loads for this screen.
 * @param slotKey A unique identifier for this specific ad slot (e.g., a list index or item ID).
 * @param placement The native placement configuration.
 * @param layout The visual design of the ad (e.g., [AdTemplates.mediaCard] or a custom DSL layout).
 * @param modifier Modifier applied to the ad's container.
 * @param loading A placeholder Composable shown while the ad is loading or unallocated.
 * @param failure A fallback Composable shown if the ad fails to load.
 * @param onEvent Optional callback to listen to lifecycle events.
 */
@Composable
public expect fun NativeAdView(
    session: NativeAdSession,
    slotKey: String,
    placement: AdPlacement,
    layout: AdLayout = AdTemplates.mediaCard,
    modifier: Modifier = Modifier,
    loading: @Composable () -> Unit = { NativeAdLoadingPlaceholder() },
    failure: @Composable (AdError) -> Unit = {},
    onEvent: (AdEvent) -> Unit = {},
)

/** Stable empty content used until a platform renderer is available for a session slot. */
@Composable
public fun NativeAdLoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier)
}
