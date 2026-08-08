package dev.avinya.admob.showcase.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.avinya.admob.showcase.ui.ad.feedNativeAdLayout
import dev.avinya.admob.showcase.ui.theme.FieldnotesTokens
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.ui.NativeAdView

/**
 * Editorial native ad container skinning NativeAdView.
 * Animates in with a 180ms fade/expand when slot state is renderable
 * (Ready, Retained, or Mounted). Does NOT render a full-height skeleton.
 */
@Composable
fun EditorialNativeAd(
    session: NativeAdSession,
    slotKey: String,
    placement: AdPlacement,
    modifier: Modifier = Modifier,
    layout: AdLayout = feedNativeAdLayout,
) {
    val sessionState by session.state.collectAsState()
    val slotState = sessionState.slots[slotKey] ?: NativeAdSlotState.Empty
    val renderable = slotState is NativeAdSlotState.Ready ||
        slotState is NativeAdSlotState.Retained ||
        slotState is NativeAdSlotState.Mounted

    AnimatedVisibility(
        visible = renderable,
        enter = fadeIn(tween(FieldnotesTokens.adRevealMillis)) +
            expandVertically(tween(FieldnotesTokens.adRevealMillis)),
        modifier = modifier,
    ) {
        NativeAdView(
            session = session,
            slotKey = slotKey,
            placement = placement,
            layout = layout,
        )
    }
}
