package dev.avinya.admob.showcase.feature.lab

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.LocalAdManager

/**
 * The app-open scenario.
 *
 * App-open is the one format with no button to press: it fires on a genuine
 * return to the foreground, or not at all. That makes it the format most
 * likely to look broken during an integration — "I added it and nothing
 * happened" — so this screen shows every gate individually, live, plus the
 * decision the policy last recorded.
 *
 * Previously the Lab index sent App Open to Diagnostics, which demonstrated
 * nothing about it.
 */
@Composable
fun AppOpenLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val suppressor = LocalAppOpenSuppressor.current

    val status by adManager.status.collectAsState()
    val canRequestAds by adManager.consent.canRequestAds.collectAsState()
    val onboardingComplete by graph.settings.onboardingComplete.collectAsState(initial = false)
    val adsEnabled by graph.settings.adsMasterSwitch.collectAsState(initial = true)
    val decisions by graph.telemetry.policyDecisions.collectAsState(initial = emptyList())

    val controller = remember(adManager) { adManager.appOpen(ShowcasePlacements.appOpen) }
    val readiness = rememberAdReadiness(controller)

    val lastDecision = decisions.firstOrNull { it.placementId == ShowcasePlacements.appOpen.id }

    LabScreen(
        title = "App open",
        subtitle = "The format with no button",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "how") {
            LabSection(
                title = "How to see it",
                description = "There is nothing to tap. Send the app to the background, wait " +
                    "at least 4 seconds, then return — the coordinator shows a preloaded ad on " +
                    "the next genuine foreground, at most once a minute.",
            ) {
                SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                        StatRow(label = "Min background", value = "4s")
                        StatRow(label = "Show cooldown", value = "60s")
                        StatRow(label = "Cold start show", value = "off")
                        StatRow(label = "Preload on start", value = "on")
                    }
                }
                Text(
                    text = "A production integration would use a far longer cooldown — hours, " +
                        "not a minute. These values are short so the behaviour is observable " +
                        "by hand.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted,
                )
            }
        }

        item(key = "gates") {
            LabSection(
                title = "Eligibility",
                description = "Every gate is evaluated independently. All must pass before the " +
                    "coordinator is unblocked.",
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Gate(
                        label = "Onboarding complete",
                        detail = "Never during a first session",
                        passing = onboardingComplete,
                    )
                    Gate(
                        label = "Not on a sensitive route",
                        detail = "Onboarding, privacy flows, another full-screen ad",
                        passing = !suppressor.isBlocked,
                    )
                    Gate(
                        label = "SDK ready",
                        detail = status.toString(),
                        passing = status == AdManagerStatus.Ready,
                    )
                    Gate(
                        label = "Ads enabled",
                        detail = "The master switch in Profile",
                        passing = adsEnabled,
                    )
                    Gate(
                        label = "Consent allows requests",
                        detail = "canRequestAds",
                        passing = canRequestAds,
                    )
                }
            }
        }

        item(key = "cache") {
            LabSection(
                title = "Controller",
                description = "The coordinator preloads on start and reloads after each " +
                    "consumption, so a foreground event rarely waits on a network round trip.",
            ) {
                SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                        StatRow(label = "Placement", value = ShowcasePlacements.appOpen.id)
                        StatRow(label = "State", value = readiness.label())
                    }
                }
            }
        }

        item(key = "decisions") {
            LabSection(
                title = "Recorded decisions",
                description = "Every evaluation is written to Diagnostics with its reason, so " +
                    "an ad that did not appear can always be accounted for.",
            ) {
                if (lastDecision == null) {
                    Text(
                        text = "No decision recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted,
                    )
                } else {
                    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                            StatRow(
                                label = "Latest",
                                value = lastDecision.decision,
                                valueColor = if (lastDecision.decision == "Show") {
                                    palette.success
                                } else {
                                    palette.inkMuted
                                },
                            )
                            lastDecision.reason?.let {
                                StatRow(label = "Reason", value = it)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One eligibility gate, pass or fail, with the value that decided it. */
@Composable
private fun Gate(label: String, detail: String, passing: Boolean) {
    val palette = showcaseColors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Tokens.Spacing.s8),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (passing) palette.success else palette.danger),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = palette.ink)
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            }
            Text(
                text = if (passing) "pass" else "blocks",
                style = ShowcaseType.numeric,
                color = if (passing) palette.success else palette.danger,
            )
        }
        Rule()
    }
}
