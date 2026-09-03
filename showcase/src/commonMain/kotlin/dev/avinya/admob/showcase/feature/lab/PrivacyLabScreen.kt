package dev.avinya.admob.showcase.feature.lab

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import dev.avinya.admob.showcase.core.device.normalizeTestDeviceId
import dev.avinya.admob.showcase.core.device.readLoggedTestDeviceId
import dev.avinya.admob.showcase.core.device.supportsLoggedTestDeviceIdDetection
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.feature.profile.shouldShowPrivacyOptionsButton
import dev.avinya.admob.showcase.ui.kit.ChoiceRow
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.PrimaryButton
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
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
    val storedTestDeviceId by graph.settings.consentTestDeviceId.collectAsState(initial = null)
    var testDeviceIdInput by remember { mutableStateOf("") }
    LaunchedEffect(storedTestDeviceId) {
        testDeviceIdInput = storedTestDeviceId.orEmpty()
    }
    val normalizedTestDeviceId = normalizeTestDeviceId(testDeviceIdInput)
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
                            style = MaterialTheme.typography.bodyMedium,
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

            item(key = "testDevice") {
                LabSection(
                    title = "Test device",
                    description = "UMP applies the debug geography above ONLY to registered test " +
                        "devices. On an unregistered physical device an EEA override silently does " +
                        "nothing. Enter the 32-character id printed in logcat or the Xcode console.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8)) {
                        Text(
                            text = storedTestDeviceId
                                ?.let { "Registered: $it" }
                                ?: "Not registered — the debug geography above has no effect.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (storedTestDeviceId == null) palette.inkMuted else palette.ink,
                        )
                        TestDeviceIdField(
                            value = testDeviceIdInput,
                            onValueChange = { testDeviceIdInput = it },
                            enabled = !busy,
                        )
                        if (testDeviceIdInput.isNotBlank() && normalizedTestDeviceId == null) {
                            Text(
                                text = "Enter exactly 32 hexadecimal characters.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.danger,
                            )
                        }
                        PrimaryButton(
                            label = "Save test device ID",
                            enabled = !busy && normalizedTestDeviceId != null &&
                                normalizedTestDeviceId != storedTestDeviceId,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        val id = normalizedTestDeviceId ?: return@launch
                                        graph.settings.setConsentTestDeviceId(id)
                                        snackbar.showSnackbar("Registered — relaunch to apply")
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                        if (storedTestDeviceId != null) {
                            GhostButton(
                                label = "Remove test device ID",
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            graph.settings.setConsentTestDeviceId(null)
                                            testDeviceIdInput = ""
                                            snackbar.showSnackbar("Removed — relaunch to apply")
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            )
                        }
                        if (supportsLoggedTestDeviceIdDetection) {
                            GhostButton(
                                label = if (storedTestDeviceId == null) "Detect from SDK log" else "Re-detect",
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            val resolved = readLoggedTestDeviceId()?.let(::normalizeTestDeviceId)
                                            if (resolved == null) {
                                                snackbar.showSnackbar(
                                                    "No id logged yet. Load an ad or relaunch, then retry.",
                                                )
                                            } else {
                                                graph.settings.setConsentTestDeviceId(resolved)
                                                testDeviceIdInput = resolved
                                                snackbar.showSnackbar("Registered — relaunch to apply")
                                            }
                                        } finally {
                                            busy = false
                                        }
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
                    description = "Clears stored UMP consent. On the next launch, startup gathers " +
                        "again and UMP presents a form when required. Debug geography applies only " +
                        "when a test-device ID is registered above.",
                ) {
                    GhostButton(
                        label = "Reset consent state",
                        destructive = true,
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val reset = adManager.consent.resetConsentForDebug()
                                    snackbar.showSnackbar(
                                        if (reset) {
                                            "Consent reset — relaunch to review consent"
                                        } else {
                                            "A consent form is open. Dismiss it, then retry."
                                        },
                                    )
                                } finally {
                                    busy = false
                                }
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
                                style = MaterialTheme.typography.bodyMedium,
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

@Composable
private fun TestDeviceIdField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    val palette = showcaseColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.touchTarget)
            .clip(ShowcaseShapes.control)
            .background(palette.surfaceSunken)
            .border(Tokens.hairline, palette.hairline, ShowcaseShapes.control)
            .padding(horizontal = Tokens.Spacing.s12, vertical = Tokens.Spacing.s12),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = "32-character test-device ID",
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkFaint,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.ink),
            cursorBrush = SolidColor(palette.primary),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
