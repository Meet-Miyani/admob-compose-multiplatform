package dev.avinya.admob.showcase.feature.lab

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.avinya.admob.showcase.StartupState
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.PrimaryButton
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.kit.ToggleRow
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.LocalAdManager
import kotlinx.coroutines.launch

/**
 * The developer read-out.
 *
 * SDK status and version, the placement mapping, the native memory governor's
 * live snapshot, and recent sanitised events — plus the controls that used to
 * clutter Profile: retry initialisation, the Google Ad Inspector, and the
 * telemetry toggle.
 */
@Composable
fun DiagnosticsLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val status by adManager.status.collectAsState()
    val startup by graph.startup.state.collectAsState()
    val nativeState by adManager.nativeAds.state.collectAsState()
    val recentEvents by remember(graph.telemetry) { graph.telemetry.adEvents }
        .collectAsState(initial = emptyList())
    val inspectorEnabled by graph.settings.inspectorEnabled.collectAsState(initial = true)
    val sdkVersion = remember(adManager) { adManager.diagnostics.sdkVersion() }
    // Guards retryAwaiting/openAdInspector against a double-tap firing the
    // SDK call twice concurrently — the deleted SettingsViewModel.run()
    // provided this via a `busy` state flag.
    var busy by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        LabScreen(
            title = "Diagnostics",
            subtitle = "SDK state, placements, and the native governor",
            onBack = onBack,
        ) {
            item(key = "sdk") {
                LabSection(
                    title = "SDK",
                    description = "Live manager status, version, and startup outcome.",
                ) {
                    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                            StatRow(label = "Status", value = status.toString())
                            StatRow(label = "Version", value = sdkVersion ?: "—")
                            StatRow(
                                label = "Startup",
                                value = when (val current = startup.startup) {
                                    is StartupState.Failed -> "Failed (${current.message})"
                                    StartupState.ConsentRequired -> "Consent required"
                                    else -> current.toString()
                                },
                                valueColor = when (startup.startup) {
                                    is StartupState.Failed -> palette.danger
                                    StartupState.ConsentRequired -> palette.accent
                                    else -> palette.ink
                                },
                            )
                        }
                    }
                    if (startup.startup !is StartupState.Ready) {
                        GhostButton(
                            label = "Retry initialisation",
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = graph.startup.retryAwaiting()
                                    busy = false
                                    snackbar.showSnackbar(
                                        if (result is StartupState.Ready) {
                                            "Ads re-initialised"
                                        } else {
                                            "Still not ready — check consent"
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }

            item(key = "test_mode") {
                LabSection(
                    title = "Test mode",
                    description = "Every placement in the catalog is constructed with " +
                        "strictTestMode, so supplying a production ad unit throws instead of " +
                        "quietly serving real ads from a sample.",
                ) {
                    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                            ShowcasePlacements.allPlacements.forEach { placement ->
                                StatRow(label = placement.id, value = placement.format.name)
                            }
                        }
                    }
                }
            }

            item(key = "native_governor") {
                LabSection(
                    title = "Native governor",
                    description = "The manager's live memory policy. Retention is the SDK's job, " +
                        "not the app's.",
                ) {
                    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                            StatRow(label = "Loaded ads", value = nativeState.loadedAds.toString())
                            StatRow(label = "Reserved loads", value = nativeState.reservedLoads.toString())
                            StatRow(label = "Active sessions", value = nativeState.activeSessions.toString())
                            StatRow(label = "Inactive sessions", value = nativeState.inactiveSessions.toString())
                            StatRow(label = "Hard limit", value = nativeState.hardLimit.toString())
                        }
                    }
                }
            }

            item(key = "tools") {
                LabSection(
                    title = "Tools",
                    description = "Google's own Ad Inspector, and this app's telemetry overlay.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8)) {
                        PrimaryButton(
                            label = "Open Google Ad Inspector",
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val opened = adManager.diagnostics.openAdInspector()
                                    busy = false
                                    if (!opened) {
                                        snackbar.showSnackbar("Ad Inspector is unavailable right now")
                                    }
                                }
                            },
                        )
                        ToggleRow(
                            label = "Telemetry inspector",
                            detail = "Shows the inspector button in each screen's header",
                            checked = inspectorEnabled,
                            onCheckedChange = {
                                scope.launch { graph.settings.setInspectorEnabled(it) }
                            },
                        )
                        GhostButton(
                            label = "Replay onboarding",
                            destructive = true,
                            onClick = {
                                scope.launch {
                                    graph.settings.setOnboardingComplete(false)
                                    snackbar.showSnackbar(
                                        "Onboarding will run on the next launch",
                                    )
                                }
                            },
                        )
                        Text(
                            text = "Clearing the onboarding flag is the only way to see the " +
                                "consent flow and the first-session app-open block again " +
                                "without wiping app data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = palette.inkMuted,
                        )
                    }
                }
            }

            item(key = "events") {
                LabSection(
                    title = "Recent events",
                    description = "The last sanitised ad events — no platform tokens, no ids.",
                ) {
                    if (recentEvents.isEmpty()) {
                        Text(
                            text = "No events recorded yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.inkMuted,
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            recentEvents.take(12).forEach { event ->
                                StatRow(
                                    label = "${event.format} · ${event.type}",
                                    value = event.placementId,
                                )
                                Rule()
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
