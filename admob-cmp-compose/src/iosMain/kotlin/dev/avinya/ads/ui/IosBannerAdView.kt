@file:OptIn(InternalAdMobCmpApi::class)
package dev.avinya.ads.ui

import dev.avinya.ads.InternalAdMobCmpApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.runtime.key
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdLoadState
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.BannerGeometry
import dev.avinya.ads.BannerRefreshPolicy
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.attachIosBanner
import dev.avinya.ads.currentIosBannerView
import dev.avinya.ads.detachIosBanner
import dev.avinya.ads.registerIosBannerGeometry
import dev.avinya.ads.appopen.iosAppForegroundState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import GoogleMobileAds.GADBannerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first


// See KDoc on the expect declaration in commonMain (ui/BannerAdView.kt).
@OptIn(ExperimentalForeignApi::class)
@Composable
public actual fun BannerAdView(placement: AdPlacement, modifier: Modifier, widthDp: Int?, onEvent: (AdEvent) -> Unit) {
    if (!placement.enabled || placement.format != AdFormat.Banner) return
    val sdk = LocalAdManager.current
    val status by sdk.status.collectAsState()
    val controller = remember(sdk, placement) { sdk.banner(placement) }
    var bannerView by remember(placement.id, placement.iosAdUnitId, widthDp, placement.bannerSizePolicy) {
        mutableStateOf<GADBannerView?>(null)
    }
    // remember the flow so recomposition doesn't rebuild the callbackFlow and re-register
    // NSNotificationCenter observers each time.
    val foregroundFlow = remember { iosAppForegroundState() }
    val isForeground by foregroundFlow.collectAsState(true)

    val currentOnEvent = rememberCurrentEventCallback(onEvent)

    LaunchedEffect(controller) {
        // collect, not collectLatest: collectLatest cancels the in-flight onEvent when a
        // new event arrives, so a rapid Impression -> Click silently dropped the impression.
        // onEvent is a plain callback with nothing to cancel.
        //
        // This is NOT the per-view event duplication issue — fixing that needs an
        // ad-instance identifier on the event model, a separate and larger change. This is a
        // local misuse of collectLatest in this composable.
        controller.events.collect(currentOnEvent)
    }

    // The controller owns the loaded view; the composable mirrors it into state so both
    // initial loads and refreshes (timer or manual controller.refresh()) re-attach.
    LaunchedEffect(controller) {
        controller.loadState.collect { state ->
            bannerView = when {
                state is AdLoadState.Loaded -> controller.currentIosBannerView()
                // Idle means cleared/detached and the GADBannerView has been torn down —
                // drop the reference so Compose stops rendering it. Reached both by an
                // explicit clear() and by the consent-revocation purge.
                // AdLoadState.Idle is a data object, so compare by value: Kotlin/Native
                // 2.3.20 miscompiles `is <data object>` on when-typed locals.
                state == AdLoadState.Idle -> null
                // Failed after a successful load keeps the previous banner on screen by
                // design (see the no-blank-flash comment in BannerCore), so a Failed state
                // must NOT null the reference.
                else -> bannerView
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        // BoxWithConstraints always provides maxWidth inside a laid-out composable. Do not add
        // a UIScreen.mainScreen fallback here: it is unreachable in practice and wrong wherever
        // it would fire (split view, Slide Over, popovers). The banner path holds no inline
        // screen reads.
        val resolvedWidth = remember(widthDp, maxWidth) {
            widthDp?.coerceAtLeast(1)
                ?: maxWidth.value.takeIf { it.isFinite() && it > 0f }?.toInt()?.coerceAtLeast(1)
                ?: 320
        }

        var isVisible by remember(placement.id) { mutableStateOf(true) }

        LaunchedEffect(status, controller, resolvedWidth, placement.requestOptions) {
            if (status != AdManagerStatus.Ready) return@LaunchedEffect
            // Manual policy: no automatic load; the consumer drives controller.refresh().
            // Still register the measured geometry so refresh() uses the container width
            // rather than failing for want of a prior load.
            if (placement.bannerRefreshPolicy is BannerRefreshPolicy.Manual) {
                controller.registerIosBannerGeometry(
                    BannerGeometry(resolvedWidth),
                    placement.bannerSizePolicy,
                    placement.requestOptions
                )
                return@LaunchedEffect
            }
            // The initial load MUST be gated on foreground, mirroring Android's
            // lifecycle >= STARTED gate. Ungated, a cold start into the background loads a
            // banner nobody can see and bills an impression for it.
            snapshotFlow { isForeground }.first { it }
            controller.load(
                geometry = BannerGeometry(resolvedWidth),
                sizePolicy = placement.bannerSizePolicy,
                requestOptions = placement.requestOptions
            )
        }

        // Refresh timer lives inside BoxWithConstraints so it reloads at the CURRENT
        // measured width after a layout change. Keyed on resolvedWidth so a size change
        // restarts it.
        LaunchedEffect(controller, placement.bannerRefreshPolicy, resolvedWidth, status) {
            val policy = placement.bannerRefreshPolicy
            if (status != AdManagerStatus.Ready) return@LaunchedEffect
            if (policy !is BannerRefreshPolicy.SdkManaged) return@LaunchedEffect
            while (true) {
                delay(policy.interval)
                // AWAIT the gate; never `if`-skip the cycle. Skipping waits another full
                // interval, costing an interval of stale inventory every time the app is
                // backgrounded or the banner scrolls off at the wrong moment. Android awaits
                // here too. isVisible is measured on-screen fraction: foreground only
                // tells us the SCREEN is visible, so a banner scrolled out of a lazy list
                // still passes it, and refreshing it bills an impression nobody could view
                // — what AdMob's viewability policy treats as invalid traffic.
                snapshotFlow { isForeground && isVisible }.first { it }
                // A refresh landing mid-load must NOT be dropped: wait for the in-flight
                // load to settle, then refresh promptly.
                snapshotFlow { controller.loadState.value !is AdLoadState.Loading }.first { it }
                controller.load(
                    geometry = BannerGeometry(resolvedWidth),
                    sizePolicy = placement.bannerSizePolicy,
                    requestOptions = placement.requestOptions
                )
            }
        }

        val currentBanner = bannerView
        if (currentBanner != null) key(currentBanner) {
            val bannerHeightDp = remember(currentBanner) {
                currentBanner.adSize.useContents { size.height }.toFloat()
            }
            UIKitView(
                factory = { currentBanner },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bannerHeightDp.dp)
                    .onBannerViewabilityChanged { viewable -> isVisible = viewable }
            )
        }

        // The controller is manager-owned and cached by placement id, so it may be shared
        // with another screen briefly mounted during a navigation transition. Use an
        // attachment refcount instead of clearing on dispose: attach() on enter, detach() on
        // exit; the controller tears down its view only when the LAST attachment leaves, so a
        // shared placement isn't blanked mid-transition, yet a load-once-abandoned placement
        // is still torn down deterministically.
        DisposableEffect(controller) {
            controller.attachIosBanner()
            onDispose {
                bannerView = null
                controller.detachIosBanner()
            }
        }
    }
}
