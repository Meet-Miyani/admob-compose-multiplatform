@file:OptIn(InternalAdMobCmpApi::class)
package dev.avinya.ads.ui

import dev.avinya.ads.InternalAdMobCmpApi
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.LocalActivity
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
import androidx.compose.ui.Modifier

import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.snapshotFlow
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdLoadState
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdSizePolicy
import dev.avinya.ads.BannerGeometry
import dev.avinya.ads.BannerRefreshPolicy
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.attachAndroidBanner
import dev.avinya.ads.currentAndroidBannerAd
import dev.avinya.ads.currentAndroidBannerView
import dev.avinya.ads.detachAndroidBanner
import dev.avinya.ads.registerAndroidBannerGeometry
import dev.avinya.ads.screenWidthDp
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

// See KDoc on the expect declaration in commonMain (ui/BannerAdView.kt).
@Composable
public actual fun BannerAdView(placement: AdPlacement, modifier: Modifier, widthDp: Int?, onEvent: (AdEvent) -> Unit) {
    if (!placement.enabled || placement.format != AdFormat.Banner) return
    val activity = LocalActivity.current
    val sdk = LocalAdManager.current
    val status by sdk.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val controller = remember(sdk, placement) { sdk.banner(placement) }
    var bannerAd by remember(placement.id, placement.androidAdUnitId, widthDp, placement.bannerSizePolicy) {
        mutableStateOf<BannerAd?>(null)
    }

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

    // The controller owns the loaded ad; the composable mirrors it into state so both
    // initial loads and refreshes (timer or manual controller.refresh()) re-attach.
    LaunchedEffect(controller) {
        controller.loadState.collect { state ->
            bannerAd = when {
                state is AdLoadState.Loaded -> controller.currentAndroidBannerAd()
                // Idle means cleared/detached and the SDK object is destroyed — drop the
                // reference so Compose stops rendering a torn-down view. Reached both by an
                // explicit clear() and by the consent-revocation purge.
                // AdLoadState.Idle is a data object, so compare by value: Kotlin/Native
                // 2.3.20 miscompiles `is <data object>` on when-typed locals.
                state == AdLoadState.Idle -> null
                // Failed after a successful load keeps the previous banner on screen by
                // design (see the no-blank-flash comment in BannerCore), so a Failed state
                // must NOT null the reference.
                else -> bannerAd
            }
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val resolvedWidth = remember(activity, widthDp, maxWidth) {
            widthDp?.coerceAtLeast(1)
                ?: maxWidth.value.takeIf { it.isFinite() && it > 0f }?.roundToInt()?.coerceAtLeast(1)
                ?: activity?.screenWidthDp()?.coerceAtLeast(1)
                ?: 320
        }
        // Tracks whether the banner is actually on screen, so the refresh timer can
        // stop billing impressions for a banner scrolled out of view. Starts true so a
        // banner that is never moved (the common fixed-position case) refreshes without
        // waiting for a layout pass to prove visibility.
        var isVisible by remember(placement.id) { mutableStateOf(true) }

        LaunchedEffect(status, controller, resolvedWidth, placement.requestOptions) {
            if (status != AdManagerStatus.Ready) return@LaunchedEffect
            // Manual policy: no automatic load; the consumer drives controller.refresh().
            // Still register the measured geometry so refresh() uses the container width
            // rather than failing for want of a prior load.
            if (placement.bannerRefreshPolicy is BannerRefreshPolicy.Manual) {
                controller.registerAndroidBannerGeometry(
                    BannerGeometry(resolvedWidth),
                    placement.bannerSizePolicy,
                    placement.requestOptions
                )
                return@LaunchedEffect
            }
            snapshotFlow { lifecycleState }.first { it.isAtLeast(Lifecycle.State.STARTED) }
            controller.load(
                geometry = BannerGeometry(resolvedWidth),
                sizePolicy = placement.bannerSizePolicy,
                requestOptions = placement.requestOptions
            )
        }

        // Refresh timer lives inside BoxWithConstraints so it can reload at the CURRENT
        // measured width after a rotation/resize. Keyed on resolvedWidth so a size change
        // restarts it.
        LaunchedEffect(controller, placement.bannerRefreshPolicy, resolvedWidth, status) {
            val policy = placement.bannerRefreshPolicy
            if (status != AdManagerStatus.Ready) return@LaunchedEffect
            if (policy !is BannerRefreshPolicy.SdkManaged) return@LaunchedEffect
            while (true) {
                delay(policy.interval)
                // Refreshing a banner the user cannot see bills an impression nobody
                // had a chance to view, which is what AdMob's viewability policy
                // treats as invalid traffic. STARTED only tells us the *screen* is
                // foreground, so a banner scrolled out of a LazyColumn still passes
                // it — gate on measured on-screen visibility too, and wait for the
                // banner to come back rather than burning the cycle.
                snapshotFlow { lifecycleState.isAtLeast(Lifecycle.State.STARTED) && isVisible }
                    .first { it }
                // A refresh landing mid-load used to be dropped, costing a full
                // interval of blank/stale inventory on slow networks. Wait for the
                // in-flight load to settle and refresh promptly instead.
                snapshotFlow { controller.loadState.value !is AdLoadState.Loading }
                    .first { it }
                controller.load(
                    geometry = BannerGeometry(resolvedWidth),
                    sizePolicy = placement.bannerSizePolicy,
                    requestOptions = placement.requestOptions
                )
            }
        }

        val currentBanner = bannerAd
        val currentBannerView = controller.currentAndroidBannerView()
        val currentActivity = activity
        if (currentActivity != null && currentBanner != null && currentBannerView != null) {
            val loadedHeightDp = currentBanner.getAdSize().height
            val baseModifier = if (loadedHeightDp > 0) {
                Modifier.fillMaxWidth().height(loadedHeightDp.dp)
            } else {
                Modifier.fillMaxWidth().wrapContentHeight()
            }
            val bannerModifier = baseModifier.onBannerViewabilityChanged { viewable ->
                isVisible = viewable
            }
            AndroidView(
                factory = { context ->
                    FrameLayout(context).apply {
                        clipChildren = false
                        clipToPadding = false
                        bindBanner(currentActivity, currentBannerView, currentBanner)
                    }
                },
                update = { frame ->
                    frame.bindBanner(currentActivity, currentBannerView, currentBanner)
                },
                modifier = bannerModifier
            )
        }

        // The controller is manager-owned and cached by placement id, so it may be shared
        // with another screen briefly mounted during a navigation transition. Use an
        // attachment refcount instead of clearing on dispose: attach() on enter, detach() on
        // exit; the controller destroys its ad only when the LAST attachment leaves, so a
        // shared placement isn't blanked mid-transition, yet a load-once-abandoned placement
        // is still torn down deterministically.
        DisposableEffect(controller) {
            controller.attachAndroidBanner()
            onDispose {
                bannerAd = null
                controller.detachAndroidBanner()
            }
        }
    }
}

private fun FrameLayout.bindBanner(activity: Activity, bannerView: AdView, bannerAd: BannerAd) {
    if (bannerView.getBannerAd() !== bannerAd) {
        bannerView.registerBannerAd(bannerAd, activity)
    }
    if (bannerView.parent !== this) {
        bannerView.detachFromParent()
        removeAllViews()
        val adSize = bannerAd.getAdSize()
        val widthPx = adSize.getWidthInPixels(activity).takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val heightPx = adSize.getHeightInPixels(activity).takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        addView(
            bannerView,
            FrameLayout.LayoutParams(widthPx, heightPx, Gravity.CENTER)
        )
    }
}

private fun View.detachFromParent() {
    (parent as? ViewGroup)?.removeView(this)
}
