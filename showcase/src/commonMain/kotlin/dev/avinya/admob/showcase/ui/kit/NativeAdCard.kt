package dev.avinya.admob.showcase.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.avinya.admob.showcase.ui.ad.rememberFeedAdLayout
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.ui.NativeAdView

/**
 * The app's container around the SDK's `NativeAdView`.
 *
 * The rule this enforces is the one that keeps the feed believable: the slot
 * occupies **no space at all** until the SDK reports it renderable. A permanent
 * skeleton at full ad height would leave a hole in the feed whenever fill is
 * slow, consent is missing, or the network is down — and an ads showcase that
 * looks broken without ads teaches the wrong lesson.
 *
 * Scrolling away from a mounted slot detaches the platform view but does not
 * destroy the session-owned ad; that lifecycle belongs to the SDK, not here.
 */
@Composable
fun NativeAdCard(
    session: NativeAdSession,
    slotKey: String,
    placement: AdPlacement,
    modifier: Modifier = Modifier,
    layout: AdLayout = rememberFeedAdLayout(),
    framed: Boolean = true,
) {
    val sessionState by session.state.collectAsState()
    val slotState = sessionState.slots[slotKey] ?: NativeAdSlotState.Empty
    val renderable = slotState is NativeAdSlotState.Ready ||
        slotState is NativeAdSlotState.Retained ||
        slotState is NativeAdSlotState.Mounted

    AnimatedVisibility(
        visible = renderable,
        enter = fadeIn(tween(Tokens.adRevealMillis)) +
            expandVertically(tween(Tokens.adRevealMillis)),
        modifier = modifier,
    ) {
        if (framed) {
            Plane(modifier = Modifier.fillMaxWidth()) {
                NativeAdView(
                    session = session,
                    slotKey = slotKey,
                    placement = placement,
                    layout = layout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Tokens.Spacing.s16),
                )
            }
        } else {
            NativeAdView(
                session = session,
                slotKey = slotKey,
                placement = placement,
                layout = layout,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
