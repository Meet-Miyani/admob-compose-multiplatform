package dev.avinya.ads.debug.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdapterStartup
import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.NoOpAdManager
import dev.avinya.ads.adapterStartup
import dev.avinya.ads.debug.ui.DebugButton
import dev.avinya.ads.debug.ui.DebugCard
import dev.avinya.ads.debug.ui.DebugLabel
import dev.avinya.ads.debug.ui.DebugMono
import dev.avinya.ads.debug.ui.DebugPill
import dev.avinya.ads.debug.ui.DebugPropertyRow
import dev.avinya.ads.debug.ui.DebugText
import dev.avinya.ads.debug.ui.DebugTokens
import dev.avinya.ads.debug.ui.DebugType
import dev.avinya.ads.debug.ui.StatusStyle
import kotlinx.coroutines.launch

/**
 * GLOBAL CONSTRAINT 1 applies throughout this file: `AdManagerStatus.Idle`,
 * `ConsentRequired`, `Initializing`, `Ready` and `ConsentStatus.Unknown`, `Required`,
 * `NotRequired`, `Obtained` are all `data object`s. Compare with `==`, never `is`.
 */
private fun AdManagerStatus.label(): String = when {
    this == AdManagerStatus.Idle -> "idle"
    this == AdManagerStatus.Initializing -> "initializing"
    this == AdManagerStatus.Ready -> "ready"
    this == AdManagerStatus.ConsentRequired -> "consent required"
    this is AdManagerStatus.Disabled -> "disabled: $reason"
    this is AdManagerStatus.Failed -> "failed: ${error.message}"
    else -> "unknown"
}

private fun AdManagerStatus.status(): StatusStyle = when {
    this == AdManagerStatus.Ready -> StatusStyle.Loaded
    this == AdManagerStatus.Initializing -> StatusStyle.Loading
    this is AdManagerStatus.Failed -> StatusStyle.Failed
    else -> StatusStyle.Idle
}

private fun ConsentStatus.label(): String = when {
    this == ConsentStatus.Unknown -> "unknown"
    this == ConsentStatus.Required -> "required"
    this == ConsentStatus.NotRequired -> "not required"
    this == ConsentStatus.Obtained -> "obtained"
    this is ConsentStatus.Failed -> "failed: ${error.message}"
    else -> "unknown"
}

@Composable
internal fun DiagnosticsTab(manager: AdManager, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val managerStatus by manager.status.collectAsState()
    val adapterStartup by manager.adapterStartup.collectAsState()
    val consentStatus by manager.consent.status.collectAsState()
    val canRequestAds by manager.consent.canRequestAds.collectAsState()
    val privacyOptions by manager.consent.privacyOptionsRequirementStatus.collectAsState()
    var lastAction by remember { mutableStateOf<String?>(null) }
    var trackingStatus by remember { mutableStateOf(manager.tracking.status()) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(DebugTokens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(DebugTokens.SpaceMd),
    ) {
        // A NoOpAdManager fails every request with sdkNotReady. Without this the developer
        // debugs the SDK when the real problem is a missing LocalAdManager provider.
        if (manager === NoOpAdManager) {
            DebugCard {
                Row(horizontalArrangement = Arrangement.spacedBy(DebugTokens.SpaceSm)) {
                    DebugPill(StatusStyle.Failed)
                    DebugText("No AdManager provided", style = DebugType.Title)
                }
                DebugLabel(
                    "LocalAdManager is not set, so this screen is driving NoOpAdManager and " +
                        "every request will fail with sdkNotReady. Provide LocalAdManager, or " +
                        "pass `manager` to AdDebugScreen explicitly."
                )
            }
        }

        DebugCard {
            Row(horizontalArrangement = Arrangement.spacedBy(DebugTokens.SpaceSm)) {
                DebugText("SDK", style = DebugType.Title)
                DebugPill(managerStatus.status(), overrideLabel = managerStatus.label())
            }
            DebugPropertyRow("version", manager.diagnostics.sdkVersion() ?: "—")
        }

        DebugCard {
            DebugText("Adapters", style = DebugType.Title)
            // Keyed on adapterStartup too: Ready no longer waits for the adapters, so a list taken
            // at Ready can be partial, and the status does not change again when they finish.
            val adapters = remember(managerStatus, adapterStartup) { manager.diagnostics.adapterStatuses() }
            if (adapterStartup == AdapterStartup.InProgress) {
                DebugLabel("adapters still initializing — the list fills in when they report")
            }
            if (adapters.isEmpty()) {
                DebugLabel("no adapters reported — SDK may not be initialized yet")
            } else {
                adapters.forEach { adapter ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(DebugTokens.SpaceSm),
                    ) {
                        DebugPill(if (adapter.initialized) StatusStyle.Loaded else StatusStyle.Failed)
                        Column {
                            DebugMono(adapter.adapterName, primary = true)
                            DebugMono(adapter.description ?: "—")
                        }
                    }
                }
            }
        }

        DebugCard {
            DebugText("Consent", style = DebugType.Title)
            DebugPropertyRow("status", consentStatus.label())
            DebugPropertyRow("can request ads", canRequestAds.toString())
            DebugPropertyRow("privacy options", privacyOptions.toString())
            Row(horizontalArrangement = Arrangement.spacedBy(DebugTokens.SpaceXs)) {
                DebugButton(
                    label = "Privacy form",
                    onClick = {
                        scope.launch {
                            val ok = manager.consent.showPrivacyOptions()
                            lastAction = if (ok) "privacy form shown" else "privacy form unavailable"
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                DebugButton(
                    label = "Reset consent",
                    onClick = {
                        scope.launch {
                            val ok = manager.consent.resetConsentForDebug()
                            lastAction = if (ok) "consent reset" else "consent reset failed"
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        DebugCard {
            DebugText("App tracking", style = DebugType.Title)
            DebugPropertyRow("status", trackingStatus.toString())
            DebugButton(
                label = "Request authorization",
                onClick = { scope.launch { trackingStatus = manager.tracking.requestAuthorization() } },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        DebugCard {
            DebugText("Google tools", style = DebugType.Title)
            DebugButton(
                label = "Open Ad Inspector",
                onClick = {
                    scope.launch {
                        val ok = manager.diagnostics.openAdInspector()
                        lastAction = if (ok) "ad inspector opened" else "ad inspector unavailable"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            lastAction?.let { DebugMono(it, primary = true) }
        }
    }
}
