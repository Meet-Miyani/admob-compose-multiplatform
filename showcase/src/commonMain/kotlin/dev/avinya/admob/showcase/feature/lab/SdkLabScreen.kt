package dev.avinya.admob.showcase.feature.lab

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.avinya.admob.showcase.domain.lab.SdkLabScenario
import dev.avinya.admob.showcase.nav.DiagnosticsLabRoute
import dev.avinya.admob.showcase.nav.PrivacyLabRoute
import dev.avinya.admob.showcase.nav.ShowcaseNavKey
import dev.avinya.admob.showcase.ui.kit.ActionRow
import dev.avinya.admob.showcase.ui.kit.Plane
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.SectionTitle
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.LocalAdManager

/**
 * The developer index.
 *
 * Every supported format has exactly one intentional scenario here, asserted by
 * `SdkLabCoverageTest` — a new SDK format cannot ship without one. The consumer
 * tabs stay free of demo controls precisely because this screen exists.
 */
@Composable
fun SdkLabScreen(
    onBack: () -> Unit,
    onOpenScenario: (ShowcaseNavKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    val adManager = LocalAdManager.current
    val status by adManager.status.collectAsState()

    LabScreen(
        title = "SDK Lab",
        subtitle = "Every format, deliberately exercised",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "health") {
            Plane(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(Tokens.Spacing.s16),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
                ) {
                    Text(
                        text = status.headline(),
                        style = MaterialTheme.typography.titleLarge,
                        color = when (status) {
                            is AdManagerStatus.Ready -> palette.success
                            is AdManagerStatus.Failed -> palette.danger
                            else -> palette.ink
                        },
                    )
                    Text(
                        text = "Every placement below uses an official Google test ad unit, " +
                            "with strict test mode on so a production id fails closed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                    )
                }
            }
        }

        item(key = "formats_header") {
            SectionTitle(title = "Ad formats")
        }

        items(SdkLabScenario.entries, key = { it.format.name }) { scenario ->
            Column {
                ActionRow(
                    label = scenario.format.displayName,
                    detail = scenario.description,
                    trailing = scenario.placement.id,
                    leading = Icons.Rounded.Science,
                    onClick = { onOpenScenario(scenario.destination) },
                )
                Rule()
            }
        }

        item(key = "tooling_header") {
            SectionTitle(title = "Tooling")
        }

        item(key = "tooling") {
            Column {
                ActionRow(
                    label = "Privacy & consent",
                    detail = "Consent state, debug geography, ATT, and initialisation order",
                    onClick = { onOpenScenario(PrivacyLabRoute) },
                )
                Rule()
                ActionRow(
                    label = "Diagnostics",
                    detail = "SDK status, placement mapping, native governor, recent events",
                    onClick = { onOpenScenario(DiagnosticsLabRoute) },
                )
                Rule()
            }
        }
    }
}

private fun AdManagerStatus.headline(): String = when (this) {
    is AdManagerStatus.Ready -> "Ready — ads may load"
    is AdManagerStatus.ConsentRequired -> "Consent required"
    is AdManagerStatus.Initializing -> "Initialising…"
    is AdManagerStatus.Idle -> "Idle"
    is AdManagerStatus.Disabled -> "Disabled: $reason"
    is AdManagerStatus.Failed -> "Failed: ${error.message}"
}
