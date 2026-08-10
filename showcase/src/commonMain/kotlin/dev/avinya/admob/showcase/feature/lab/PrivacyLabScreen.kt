package dev.avinya.admob.showcase.feature.lab

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
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.feature.profile.shouldShowPrivacyOptionsButton
import dev.avinya.admob.showcase.ui.kit.ChoiceRow
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.PrimaryButton
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdTrackingAuthorization
import dev.avinya.ads.ConsentDebugGeography
import dev.avinya.ads.LocalAdManager
import kotlinx.coroutines.launch

/**
 * Consent, tracking, and the initialisation order that makes them work.
 *
 * The debug controls that used to sit in Profile live here: geography
 * override, consent reset, and the ATT prompt. A consumer settings screen is
 * the wrong home for them, but a developer needs them one tap away.
 */
@Composable
fun PrivacyLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val consentStatus by adManager.consent.status.collectAsState()
    val privacyOptions by adManager.consent.privacyOptionsRequirementStatus.collectAsState()
    val canRequestAds by adManager.consent.canRequestAds.collectAsState()
    val storedGeography by graph.settings.consentDebugGeography.collectAsState(
        initial = ConsentDebugGeography.Disabled.name,
    )
    var tracking by remember { mutableStateOf(adManager.tracking.status()) }
    // Guards showPrivacyOptions/resetConsentForDebug/requestAuthorization
    // against a double-tap firing the SDK call twice concurrently — the
    // deleted SettingsViewModel.run() provided this via a `busy` state flag.
    var busy by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        LabScreen(
            title = "Privacy & consent",
            subtitle = "UMP state and the debug controls that drive it",
            onBack = onBack,
        ) {
            item(key = "consent") {
                LabSection(
                    title = "Consent state",
                    description = "Live from the SDK's consent controller.",
                ) {
                    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                            StatRow(label = "Status", value = consentStatus.toString())
                            StatRow(
                                label = "Can request ads",
                                value = if (canRequestAds) "Yes" else "No",
                                valueColor = if (canRequestAds) palette.success else palette.inkMuted,
                            )
                            StatRow(label = "Privacy options", value = privacyOptions.toString())
                        }
                    }
                }
            }

            item(key = "privacy_options") {
                LabSection(
                    title = "Privacy options form",
                    description = "Offered only when the SDK reports the requirement as Required — " +
                        "gating on ConsentStatus.Obtained instead is the common mistake, and it " +
                        "puts a dead button in front of users outside the EEA.",
                ) {
                    if (shouldShowPrivacyOptionsButton(privacyOptions)) {
                        PrimaryButton(
                            label = "Show privacy options",
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val shown = adManager.consent.showPrivacyOptions()
                                    busy = false
                                    if (!shown) snackbar.showSnackbar("The SDK declined to show the form")
                                }
                            },
                        )
                    } else {
                        Text(
                            text = "Not required for this region and consent state.",
                            style = ShowcaseType.bodyMedium,
                            color = palette.inkMuted,
                        )
                    }
                }
            }

            item(key = "geography") {
                LabSection(
                    title = "Debug geography",
                    description = "Forces UMP to behave as if the device were in this region. " +
                        "Applies on the next launch, or now via Diagnostics → retry.",
                ) {
                    Column {
                        ConsentDebugGeography.entries.forEach { geography ->
                            ChoiceRow(
                                label = geography.name,
                                selected = storedGeography == geography.name,
                                onClick = {
                                    scope.launch {
                                        graph.settings.setConsentDebugGeography(geography.name)
                                        snackbar.showSnackbar("Saved — applies on next launch")
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item(key = "reset") {
                LabSection(
                    title = "Reset",
                    description = "Clears the stored consent so the form appears again on relaunch.",
                ) {
                    GhostButton(
                        label = "Reset consent state",
                        destructive = true,
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                adManager.consent.resetConsentForDebug()
                                busy = false
                                snackbar.showSnackbar("Consent reset — relaunch to see the form")
                            }
                        },
                    )
                }
            }

            item(key = "att") {
                LabSection(
                    title = "App Tracking Transparency",
                    description = "iOS only. Android reports NotApplicable, and that is shown " +
                        "rather than hidden.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8)) {
                        LabStatus(text = tracking.toString())
                        if (tracking == AdTrackingAuthorization.NotDetermined) {
                            PrimaryButton(
                                label = "Request tracking permission",
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        tracking = adManager.tracking.requestAuthorization()
                                        busy = false
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item(key = "order") {
                LabSection(
                    title = "Initialisation order",
                    description = "This order is load-bearing, not cosmetic: requesting ads " +
                        "before ATT resolves permanently forfeits the IDFA for those requests.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4)) {
                        listOf(
                            "Request a consent info update",
                            "Gather consent; show the UMP form if required",
                            "Request ATT authorisation (iOS)",
                            "Initialise Mobile Ads with the gathered state",
                            "Load placements only once status is Ready",
                        ).forEachIndexed { index, step ->
                            Text(
                                text = "${index + 1}.  $step",
                                style = ShowcaseType.bodyMedium,
                                color = palette.ink,
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}
