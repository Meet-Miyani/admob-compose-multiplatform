package dev.avinya.admob.showcase.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Science
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
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorHost
import androidx.compose.foundation.layout.Row
import dev.avinya.admob.showcase.ui.kit.ActionRow
import dev.avinya.admob.showcase.ui.kit.ChoiceRow
import dev.avinya.admob.showcase.ui.kit.CollapsingAppHeader
import dev.avinya.admob.showcase.ui.kit.Eyebrow
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.Plane
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.Section
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.kit.ToggleRow
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.LocalAdManager

/**
 * The reader's own settings.
 *
 * Everything here is something a real user would change. The developer surface
 * is one clearly-labelled row into SDK Lab, not an inline gallery of debug
 * switches.
 */
@Composable
fun ProfileScreen(
    onOpenRewards: () -> Unit,
    onOpenSdkLab: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val viewModel: ProfileViewModel = viewModel {
        ProfileViewModel(adManager, graph.settings, graph.wallet)
    }
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    val bookmarked by graph.articles.bookmarkedArticles().collectAsState(initial = emptyList())
    val savedCount = bookmarked.size
    val placements = remember { listOf(ShowcasePlacements.appOpen) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProfileEffect.Notice -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    InspectorHost(placements = placements) { inspectorEnabled, onOpenInspector ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(showcaseColors.canvas),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(showcaseColors.canvas),
            ) {
                CollapsingAppHeader(
                    title = "Profile",
                    subtitle = "Appearance, privacy, and rewards",
                    listState = listState,
                    actions = {
                        InspectorEntryPoint(
                            enabled = inspectorEnabled,
                            onOpen = onOpenInspector,
                        )
                    },
                )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = Tokens.Spacing.gutterCompact,
                        end = Tokens.Spacing.gutterCompact,
                        top = Tokens.Spacing.s16,
                        bottom = Tokens.Spacing.s48,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s32),
                ) {
                    item(key = "summary") {
                        ReaderSummary(
                            balance = state.balance,
                            saved = savedCount,
                            adsEnabled = state.adsEnabled,
                        )
                    }

                    item(key = "rewards") {
                        Section(title = "Rewards") {
                            ActionRow(
                                label = "Coins and unlocks",
                                detail = "Watch a rewarded ad to unlock premium stories",
                                trailing = "${state.balance} coins",
                                leading = Icons.Rounded.Stars,
                                onClick = onOpenRewards,
                            )
                        }
                    }

                    item(key = "appearance") {
                        Section(title = "Appearance") {
                            ThemeMode.entries.forEach { mode ->
                                ChoiceRow(
                                    label = mode.label(),
                                    selected = state.themeMode == mode,
                                    onClick = { viewModel.onIntent(ProfileIntent.SetThemeMode(mode)) },
                                )
                            }
                        }
                    }

                    item(key = "privacy") {
                        Section(
                            title = "Privacy",
                            caption = "Consent is gathered and stored by the SDK's own UMP flow",
                        ) {
                            ConsentSummary(
                                consentStatus = state.consentStatus.toString(),
                                canRequestAds = state.canRequestAds,
                                showManageButton = shouldShowPrivacyOptionsButton(state.privacyOptions),
                                busy = state.busy,
                                onManage = { viewModel.onIntent(ProfileIntent.ShowPrivacyOptions) },
                            )
                            ToggleRow(
                                label = "Show ads",
                                detail = "Turn this off and every placement disappears — " +
                                    "the app stays complete.",
                                checked = state.adsEnabled,
                                onCheckedChange = { viewModel.onIntent(ProfileIntent.SetAdsEnabled(it)) },
                                modifier = Modifier.padding(top = Tokens.Spacing.s12),
                            )
                        }
                    }

                    item(key = "developer") {
                        Section(title = "Developer") {
                            ActionRow(
                                label = "SDK Lab",
                                detail = "Every ad format, plus privacy and diagnostics tooling",
                                leading = Icons.Rounded.Science,
                                onClick = onOpenSdkLab,
                            )
                            Rule()
                            Text(
                                text = "Fieldnotes is a working demonstration of the admob-cmp " +
                                    "SDK. Every placement uses an official Google test ad unit.",
                                style = ShowcaseType.bodySmall,
                                color = showcaseColors.inkMuted,
                                modifier = Modifier.padding(top = Tokens.Spacing.s12),
                            )
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/**
 * A compact read-out of what the reader has accumulated.
 *
 * Profile used to open on a bare list of switches. Leading with the reader's
 * own numbers gives the screen a reason to exist beyond settings.
 */
@Composable
private fun ReaderSummary(balance: Int, saved: Int, adsEnabled: Boolean) {
    val palette = showcaseColors
    Plane(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(Tokens.Spacing.s20),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s24),
        ) {
            SummaryStat(value = "$balance", label = "Coins")
            SummaryStat(value = "$saved", label = "Saved")
            SummaryStat(
                value = if (adsEnabled) "On" else "Off",
                label = "Ads",
                emphasis = if (adsEnabled) palette.success else palette.inkMuted,
            )
        }
    }
}

@Composable
private fun SummaryStat(
    value: String,
    label: String,
    emphasis: androidx.compose.ui.graphics.Color = showcaseColors.ink,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s2)) {
        Text(text = value, style = ShowcaseType.headlineMedium, color = emphasis)
        Eyebrow(text = label, color = showcaseColors.inkFaint)
    }
}

@Composable
private fun ConsentSummary(
    consentStatus: String,
    canRequestAds: Boolean,
    showManageButton: Boolean,
    busy: Boolean,
    onManage: () -> Unit,
) {
    val palette = showcaseColors
    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(Tokens.Spacing.s16)) {
            StatRow(label = "Consent", value = consentStatus)
            StatRow(
                label = "Ads can load",
                value = if (canRequestAds) "Yes" else "No",
                valueColor = if (canRequestAds) palette.success else palette.inkMuted,
            )
            if (showManageButton) {
                GhostButton(
                    label = "Manage consent settings",
                    onClick = onManage,
                    enabled = !busy,
                    icon = Icons.Rounded.PrivacyTip,
                    modifier = Modifier.padding(top = Tokens.Spacing.s8),
                )
            } else {
                Text(
                    text = "No consent form is required for your region right now.",
                    style = ShowcaseType.bodySmall,
                    color = palette.inkMuted,
                    modifier = Modifier.padding(top = Tokens.Spacing.s4),
                )
            }
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.System -> "Follow system"
    ThemeMode.Light -> "Light"
    ThemeMode.Dark -> "Dark"
}
