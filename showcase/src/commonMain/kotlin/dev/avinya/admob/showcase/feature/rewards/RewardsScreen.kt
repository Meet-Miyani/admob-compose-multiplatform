package dev.avinya.admob.showcase.feature.rewards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.inspector.LocalInspectorPlacements
import dev.avinya.admob.showcase.ui.kit.AppHeader
import dev.avinya.admob.showcase.ui.kit.Badge
import dev.avinya.admob.showcase.ui.kit.EmptyState
import dev.avinya.admob.showcase.ui.kit.Eyebrow
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.NativeAdCard
import dev.avinya.admob.showcase.ui.kit.Plane
import dev.avinya.admob.showcase.ui.kit.PrimaryButton
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.SectionEyebrow
import dev.avinya.admob.showcase.ui.kit.SectionTitle
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import androidx.compose.runtime.CompositionLocalProvider
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.ui.rememberNativeAdSlotSession

/** Stable slot identity for the Rewards screen's single native ad. */
private const val SPONSORED_SLOT = "rewards:sponsored:1"

/**
 * Watch to earn, spend to unlock.
 *
 * The rewarded formats live here rather than in the Lab because this is the
 * only context in which a rewarded ad is defensible: the reader wants
 * something specific, the price is stated up front, and declining costs them
 * nothing. The Lab keeps a separate load/show/readiness demo for the same
 * formats, aimed at developers rather than readers.
 */
@Composable
fun RewardsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val suppressor = LocalAppOpenSuppressor.current
    val sessionId = remember { graph.clock.nowMillis().toString() }

    val viewModel: RewardsViewModel = viewModel {
        RewardsViewModel(
            wallet = graph.wallet,
            articles = graph.articles,
            adManager = adManager,
            suppressor = suppressor,
            sessionId = sessionId,
            settings = graph.settings,
        )
    }
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val placements = remember {
        listOf(
            ShowcasePlacements.rewardsRewarded,
            ShowcasePlacements.rewardsRewardedInterstitial,
            ShowcasePlacements.feedNative,
        )
    }

    val nativeSession = rememberNativeAdSlotSession(
        sessionKey = "rewards:sponsored",
        slot = remember { NativeAdSlot(SPONSORED_SLOT, ShowcasePlacements.feedNative) },
    )

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            val message = when (effect) {
                is RewardsEffect.RewardResult -> effect.outcome.message()
                is RewardsEffect.Unlocked -> "Unlocked “${effect.title}” — find it in your Library"
                is RewardsEffect.NeedMoreCoins ->
                    "You need ${effect.shortfall} more coins for that story"
            }
            snackbar.showSnackbar(message)
        }
    }

    CompositionLocalProvider(LocalInspectorPlacements provides placements) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(palette.canvas),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.canvas),
            ) {
                AppHeader(title = "Rewards", onBack = onBack)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = Tokens.Spacing.gutterCompact,
                        end = Tokens.Spacing.gutterCompact,
                        top = Tokens.Spacing.s16,
                        bottom = Tokens.Spacing.s48,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s24),
                ) {
                    item(key = "balance") {
                        BalanceCard(balance = state.balance)
                    }

                    item(key = "earn") {
                        Column {
                            SectionTitle(
                                title = "Earn coins",
                                caption = "Watch a short ad. Dismiss it early and nothing is charged — " +
                                    "or credited.",
                            )
                            if (!state.adsEnabled) {
                                UnavailableNote(
                                    "Ads are switched off in Profile, so there's nothing to watch.",
                                )
                            } else if (!state.sdkReady) {
                                UnavailableNote(
                                    "Waiting for consent and SDK initialisation to finish.",
                                )
                            }
                            PrimaryButton(
                                label = "Watch a rewarded video",
                                onClick = { viewModel.onIntent(RewardsIntent.Watch(RewardKind.Rewarded)) },
                                enabled = state.canWatch,
                                loading = state.phase == RewardPhase.Presenting,
                                icon = Icons.Rounded.PlayArrow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Tokens.Spacing.s12),
                            )
                            GhostButton(
                                label = "Watch a rewarded interstitial",
                                onClick = {
                                    viewModel.onIntent(
                                        RewardsIntent.Watch(RewardKind.RewardedInterstitial),
                                    )
                                },
                                enabled = state.canWatch,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Tokens.Spacing.s8),
                            )
                        }
                    }

                    // A third native treatment, in a non-feed context: the
                    // hero layout, framed as a card. Same session API, same
                    // placement pool, different composition — which is the
                    // argument for the layout DSL.
                    item(key = "sponsored") {
                        Column {
                            SectionTitle(
                                title = "Sponsored",
                                caption = "A native ad rendered with the hero layout",
                            )
                            NativeAdCard(
                                session = nativeSession,
                                slotKey = SPONSORED_SLOT,
                                placement = ShowcasePlacements.feedNative,
                                modifier = Modifier.padding(top = Tokens.Spacing.s12),
                            )
                        }
                    }

                    item(key = "unlock_header") {
                        SectionTitle(
                            title = "Premium stories",
                            caption = "Spend coins to unlock. Unlocked stories stay in your Library.",
                        )
                    }

                    if (state.premium.isEmpty()) {
                        item(key = "no_premium") {
                            EmptyState(
                                title = "Nothing to unlock",
                                message = "There are no premium stories in your library yet.",
                                icon = Icons.Rounded.Stars,
                            )
                        }
                    } else {
                        items(state.premium, key = { it.id }) { article ->
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Tokens.Spacing.s12),
                                    horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s16),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
                                    ) {
                                        SectionEyebrow(article.section)
                                        Text(
                                            text = article.title,
                                            style = ShowcaseType.titleMedium,
                                            color = palette.ink,
                                        )
                                    }

                                    if (article.isUnlocked) {
                                        Badge(
                                            text = "Unlocked",
                                            color = palette.success,
                                            container = palette.surfaceSunken,
                                        )
                                    } else {
                                        GhostButton(
                                            label = "${article.costCoins} coins",
                                            onClick = {
                                                viewModel.onIntent(RewardsIntent.Unlock(article))
                                            },
                                            enabled = state.balance >= article.costCoins,
                                        )
                                    }
                                }
                                Rule()
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BalanceCard(balance: Int) {
    val palette = showcaseColors
    Plane(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Tokens.Spacing.s20),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
        ) {
            Eyebrow(text = "Balance")
            Text(
                text = "$balance",
                style = ShowcaseType.displayLarge,
                color = palette.ink,
            )
            Text(
                text = if (balance == 0) {
                    "Watch an ad to earn your first coins."
                } else {
                    "coins available to spend"
                },
                style = ShowcaseType.bodySmall,
                color = palette.inkMuted,
            )
        }
    }
}

@Composable
private fun UnavailableNote(message: String) {
    SunkenPanel(modifier = Modifier.fillMaxWidth().padding(top = Tokens.Spacing.s12)) {
        Text(
            text = message,
            style = ShowcaseType.bodySmall,
            color = showcaseColors.inkMuted,
            modifier = Modifier.padding(Tokens.Spacing.s12),
        )
    }
}
