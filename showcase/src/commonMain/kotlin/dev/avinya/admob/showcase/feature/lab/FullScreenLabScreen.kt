package dev.avinya.admob.showcase.feature.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.wallet.rewardGrantKey
import dev.avinya.admob.showcase.feature.rewards.message
import dev.avinya.admob.showcase.ui.ad.runRewarded
import dev.avinya.admob.showcase.ui.ad.suppressing
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.PrimaryButton
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.FullScreenAdController
import dev.avinya.ads.LocalAdManager
import kotlinx.coroutines.launch

/**
 * The full-screen formats, as a developer would exercise them.
 *
 * Every format gets the same shape: **Load**, then **Show**, with an honest
 * readiness line between them that reports what the controller is actually
 * doing — loading, ready with a cache count and expiry, failed with the error,
 * or idle. That readiness is derived from `loadState` rather than from
 * `isReady()`, because the latter is a plain function and Compose cannot
 * observe it; reading it directly is why the Show buttons here used to stay
 * disabled forever after a successful load.
 *
 * The reward *economy* is not here — it lives on the Rewards screen, where a
 * reader has an actual reason to watch one. This screen only proves the calls
 * behave, and reuses the same once-only grant accounting so the two cannot
 * drift apart.
 */
@Composable
fun FullScreenLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val suppressor = LocalAppOpenSuppressor.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val sessionId = remember { graph.clock.nowMillis().toString() }
    var sequence by remember { mutableStateOf(0) }
    var presenting by remember { mutableStateOf(false) }

    val status by adManager.status.collectAsState()
    val adsEnabled by graph.settings.adsMasterSwitch.collectAsState(initial = true)
    val balance by graph.wallet.balance().collectAsState(initial = 0)
    val canLoad = adsEnabled && status == AdManagerStatus.Ready

    val interstitial = remember(adManager) {
        adManager.interstitial(ShowcasePlacements.labInterstitial)
    }
    val rewarded = remember(adManager) { adManager.rewarded(ShowcasePlacements.labRewarded) }
    val rewardedInterstitial = remember(adManager) {
        adManager.rewardedInterstitial(ShowcasePlacements.labRewardedInterstitial)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LabScreen(
            title = "Full screen",
            subtitle = "Interstitial, rewarded, rewarded interstitial",
            onBack = onBack,
        ) {
            if (!canLoad) {
                item(key = "blocked") {
                    LabSection(
                        title = "Not loadable yet",
                        description = if (!adsEnabled) {
                            "Ads are switched off in Profile. Nothing here will load until " +
                                "they are turned back on."
                        } else {
                            "The SDK is not Ready — consent has not resolved, or " +
                                "initialisation is still running. See Diagnostics."
                        },
                    ) { }
                }
            }

            item(key = "interstitial") {
                FullScreenFormat(
                    title = "Interstitial",
                    description = "Load and Show are separate calls. Show stays disabled until " +
                        "the controller reports a cached ad.",
                    placement = ShowcasePlacements.labInterstitial,
                    controller = interstitial,
                    canLoad = canLoad,
                    busy = presenting,
                    onLoad = { scope.launch { interstitial.load() } },
                    onShow = {
                        presenting = true
                        scope.launch {
                            val result = suppressor.suppressing { interstitial.show() }
                            presenting = false
                            snackbar.showSnackbar("Interstitial: ${result::class.simpleName}")
                        }
                    },
                )
            }

            item(key = "rewarded") {
                FullScreenFormat(
                    title = "Rewarded",
                    description = "Credits through the same once-only accounting the Rewards " +
                        "screen uses, so a replayed callback still pays exactly once.",
                    placement = ShowcasePlacements.labRewarded,
                    controller = rewarded,
                    canLoad = canLoad,
                    busy = presenting,
                    onLoad = { scope.launch { rewarded.load() } },
                    onShow = {
                        presenting = true
                        scope.launch {
                            val outcome = suppressor.suppressing {
                                runRewarded(
                                    load = { rewarded.load() },
                                    show = { onReward -> rewarded.show(onRewardEarned = onReward) },
                                    wallet = graph.wallet,
                                    grantKey = rewardGrantKey(
                                        ShowcasePlacements.labRewarded.id,
                                        sessionId,
                                        ++sequence,
                                    ),
                                )
                            }
                            presenting = false
                            snackbar.showSnackbar(outcome.message())
                        }
                    },
                )
            }

            item(key = "rewarded_interstitial") {
                FullScreenFormat(
                    title = "Rewarded interstitial",
                    description = "The combined format, with identical grant accounting.",
                    placement = ShowcasePlacements.labRewardedInterstitial,
                    controller = rewardedInterstitial,
                    canLoad = canLoad,
                    busy = presenting,
                    onLoad = { scope.launch { rewardedInterstitial.load() } },
                    onShow = {
                        presenting = true
                        scope.launch {
                            val outcome = suppressor.suppressing {
                                runRewarded(
                                    load = { rewardedInterstitial.load() },
                                    show = { onReward ->
                                        rewardedInterstitial.show(onRewardEarned = onReward)
                                    },
                                    wallet = graph.wallet,
                                    grantKey = rewardGrantKey(
                                        ShowcasePlacements.labRewardedInterstitial.id,
                                        sessionId,
                                        ++sequence,
                                    ),
                                )
                            }
                            presenting = false
                            snackbar.showSnackbar(outcome.message())
                        }
                    },
                )
            }

            item(key = "wallet") {
                LabSection(
                    title = "Grant accounting",
                    description = "Read-only here. Earning and spending live on Rewards.",
                ) {
                    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                            StatRow(label = "Balance", value = "$balance coins")
                            StatRow(label = "Grant session", value = sessionId)
                            StatRow(label = "Grants this session", value = "$sequence")
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * One format's Load / Show pair plus its live state.
 *
 * Identical treatment for all three is the point: an integrator should be able
 * to read one block and know how every full-screen format behaves.
 */
@Composable
private fun FullScreenFormat(
    title: String,
    description: String,
    placement: AdPlacement,
    controller: FullScreenAdController,
    canLoad: Boolean,
    busy: Boolean,
    onLoad: () -> Unit,
    onShow: () -> Unit,
) {
    val readiness = rememberAdReadiness(controller)

    LabSection(title = title, description = description) {
        Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8)) {
            GhostButton(
                label = "Load",
                enabled = canLoad && !readiness.isLoading && !busy,
                onClick = onLoad,
            )
            PrimaryButton(
                label = "Show",
                enabled = readiness.isReady && !busy,
                loading = busy,
                onClick = onShow,
            )
        }
        SunkenPanel(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                StatRow(label = "Placement", value = placement.id)
                StatRow(label = "State", value = readiness.label())
            }
        }
    }
}
