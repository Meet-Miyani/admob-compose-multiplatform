package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.kit.Badge
import dev.avinya.admob.showcase.ui.kit.EmptyState
import dev.avinya.admob.showcase.ui.kit.Eyebrow
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.Plane
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.pressable
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdLoadState
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.AdSizePolicy
import dev.avinya.ads.BannerRefreshPolicy
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.NativeAdSessionPolicy
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-placement config and live load state for whatever the surrounding screen
 * advertises through [LocalInspectorPlacements].
 *
 * Controllers are read from [LocalAdManager] lazily, so the controller shown
 * here is the same instance the screen's own `BannerAdView` / `NativeAdView`
 * is bound to — the state rendered is the state on screen, not a fresh one.
 */
@Composable
fun PlacementsTab(placements: List<AdPlacement>, modifier: Modifier = Modifier) {
    val manager = LocalAdManager.current
    if (placements.isEmpty()) {
        EmptyState(
            title = "No placements here",
            message = "This screen binds no ads. That is a design decision, not a gap.",
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Tokens.Spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
    ) {
        items(placements, key = { it.id }) { placement ->
            PlacementCard(placement = placement, manager = manager)
        }
    }
}

@Composable
private fun PlacementCard(placement: AdPlacement, manager: AdManager) {
    val palette = showcaseColors
    Plane(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Tokens.Spacing.s16),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = placement.id,
                    style = ShowcaseType.titleSmall,
                    color = palette.ink,
                )
                Badge(
                    text = placement.format.name,
                    color = palette.primary,
                    container = palette.primarySoft,
                )
            }

            Eyebrow(text = "Config", color = palette.inkFaint)
            CopyableUnitIdRow("Android unit", placement.adUnitIds.android)
            CopyableUnitIdRow("iOS unit", placement.adUnitIds.ios)
            when (placement.format) {
                AdFormat.Banner -> {
                    StatRow(label = "Size", value = placement.bannerSizePolicy.label())
                    StatRow(label = "Refresh", value = placement.bannerRefreshPolicy.label())
                }
                AdFormat.Native -> StatRow(
                    label = "Batching",
                    value = placement.nativeOptions.batching.name,
                )
                AdFormat.Interstitial,
                AdFormat.Rewarded,
                AdFormat.RewardedInterstitial,
                AdFormat.AppOpen,
                -> Unit
            }

            Rule()

            if (placement.format == AdFormat.Native) {
                NativeSessionLiveSection(placement, manager)
            } else {
                val loadState by rememberLoadState(placement, manager).collectAsState()
                LiveSection(loadState = loadState)
            }
        }
    }
}

@Composable
private fun LiveSection(loadState: AdLoadState) {
    val palette = showcaseColors
    Eyebrow(text = "Live state", color = palette.inkFaint)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.s4),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "Load status", style = ShowcaseType.bodyMedium, color = palette.inkMuted)
        AdLoadStateBadge(loadState)
    }

    when {
        loadState is AdLoadState.Loaded && loadState.responseInfo?.responseId != null ->
            StatRow(label = "Response id", value = loadState.responseInfo?.responseId ?: "—")

        loadState is AdLoadState.Failed -> {
            StatRow(label = "Error code", value = loadState.error.code ?: "—")
            Text(
                text = loadState.error.message,
                style = ShowcaseType.bodySmall,
                color = palette.danger,
            )
        }
    }
}

@Composable
private fun NativeSessionLiveSection(placement: AdPlacement, manager: AdManager) {
    val palette = showcaseColors
    val sessionKey = remember(placement.id) { "inspector-native:${placement.id}" }
    val slot = remember(placement) { NativeAdSlot("inspector-slot:${placement.id}", placement) }
    val policy = remember { NativeAdSessionPolicy(maxRetainedAds = 1, retainBehind = 0, prefetchAhead = 0) }
    val session = remember(manager, sessionKey, policy) { manager.nativeAds.session(sessionKey, policy) }
    val sessionState by session.state.collectAsState()
    val managerState by manager.nativeAds.state.collectAsState()

    LaunchedEffect(session, slot) { session.updateWindow(NativeAdWindow(visible = listOf(slot))) }
    DisposableEffect(session) { onDispose(session::deactivate) }

    Eyebrow(text = "Native session", color = palette.inkFaint)
    StatRow(label = "Session", value = if (sessionState.active) "active" else "inactive")
    StatRow(label = "Window", value = "visible: ${slot.key}")
    StatRow(label = "Slot", value = sessionState.slots[slot.key].label())
    StatRow(
        label = "Manager",
        value = "loaded ${managerState.loadedAds}, reserved ${managerState.reservedLoads}",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = Tokens.Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
    ) {
        GhostButton(label = "Deactivate", onClick = session::deactivate, modifier = Modifier.weight(1f))
        GhostButton(label = "Close", onClick = session::close, modifier = Modifier.weight(1f))
        GhostButton(
            label = "Clear all",
            destructive = true,
            onClick = manager.nativeAds::clear,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun NativeAdSlotState?.label(): String = when (this) {
    null, NativeAdSlotState.Empty -> "Empty"
    NativeAdSlotState.Loading -> "Loading"
    is NativeAdSlotState.Ready -> "Ready"
    is NativeAdSlotState.Mounted -> "Mounted"
    is NativeAdSlotState.Retained -> "Retained"
    is NativeAdSlotState.Failed -> "Failed"
}

@Composable
private fun AdLoadStateBadge(loadState: AdLoadState) {
    val palette = showcaseColors
    val (label, color) = when (loadState) {
        is AdLoadState.Loaded -> "Ready" to palette.success
        is AdLoadState.Loading -> "Loading" to palette.accent
        is AdLoadState.Failed -> "Error" to palette.danger
        is AdLoadState.Idle -> "Idle" to palette.inkFaint
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text = label.uppercase(), style = ShowcaseType.eyebrow, color = color)
    }
}

@Composable
private fun CopyableUnitIdRow(label: String, unitId: String) {
    val palette = showcaseColors
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Spacing.s4),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = ShowcaseType.bodyMedium,
            color = palette.inkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = unitId,
            style = ShowcaseType.labelSmall,
            color = palette.ink,
        )
        CopyButton(
            copied = copied,
            onCopy = {
                clipboardManager.setText(AnnotatedString(unitId))
                copied = true
            },
        )
    }
}

@Composable
private fun CopyButton(copied: Boolean, onCopy: () -> Unit) {
    val palette = showcaseColors
    val tint: Color = if (copied) palette.success else palette.inkFaint
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(ShowcaseShapes.chip)
            .background(palette.surfaceSunken)
            .border(Tokens.hairline, palette.hairline, ShowcaseShapes.chip)
            .pressable(onClick = onCopy, scaleWhenPressed = 0.88f),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) },
            label = "copyIcon",
        ) { isCopied ->
            Icon(
                imageVector = if (isCopied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = if (isCopied) "Copied" else "Copy ad unit id",
                tint = tint,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun rememberLoadState(placement: AdPlacement, manager: AdManager): StateFlow<AdLoadState> =
    remember(placement.id, manager) {
        when (placement.format) {
            AdFormat.Banner -> manager.banner(placement).loadState
            AdFormat.Native -> error("Native placements use NativeSessionLiveSection.")
            AdFormat.Interstitial -> manager.interstitial(placement).loadState
            AdFormat.Rewarded -> manager.rewarded(placement).loadState
            AdFormat.RewardedInterstitial -> manager.rewardedInterstitial(placement).loadState
            AdFormat.AppOpen -> manager.appOpen(placement).loadState
        }
    }

private fun AdSizePolicy.label(): String = when (this) {
    is AdSizePolicy.LargeAnchoredAdaptive -> "LargeAnchoredAdaptive" +
        (collapsible?.let { " (collapsible=${it.name})" } ?: "")
    is AdSizePolicy.InlineAdaptive -> "InlineAdaptive" +
        (maxHeightDp?.let { " (maxHeight=${it}dp)" } ?: "")
    is AdSizePolicy.Fixed -> "Fixed ${widthDp}x${heightDp}dp"
    is AdSizePolicy.Fluid -> "Fluid"
}

private fun BannerRefreshPolicy.label(): String = when (this) {
    is BannerRefreshPolicy.AdServerManaged -> "AdServerManaged"
    is BannerRefreshPolicy.SdkManaged -> "SdkManaged (${interval.inWholeSeconds}s)"
    is BannerRefreshPolicy.Manual -> "Manual"
}
