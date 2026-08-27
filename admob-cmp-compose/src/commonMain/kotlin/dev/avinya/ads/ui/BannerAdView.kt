package dev.avinya.ads.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement

/**
 * A drop-in Composable that renders a banner ad for the given [placement].
 *
 * **How it works:**
 * This view is fully autonomous. Once [AdManager] initializes, the banner automatically
 * loads and sizes itself based on its container's width (using adaptive banners by default).
 *
 * **Example:**
 * ```kotlin
 * BannerAdView(
 *     placement = AdPlacement(
 *         id = "home_banner",
 *         format = AdFormat.Banner,
 *         androidAdUnitId = TestAdIds.ANDROID_BANNER,
 *         iosAdUnitId = TestAdIds.IOS_BANNER
 *     ),
 *     modifier = Modifier.fillMaxWidth()
 * )
 * ```
 *
 * @param placement The banner placement configuration. Must use [AdFormat.Banner].
 * @param modifier Modifier applied to the banner's container.
 * @param widthDp Override the measured width for adaptive banner sizing. When `null` (default), it measures its own container.
 * @param onEvent Optional callback to listen to lifecycle events like clicks or impressions.
 */
@Composable
public expect fun BannerAdView(
    placement: AdPlacement,
    modifier: Modifier = Modifier,
    widthDp: Int? = null,
    onEvent: (AdEvent) -> Unit = {}
)
