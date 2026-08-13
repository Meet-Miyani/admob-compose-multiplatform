package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.data.db.entity.AdEventEntity
import dev.avinya.admob.showcase.data.db.entity.PaidEventEntity
import dev.avinya.admob.showcase.data.db.entity.PolicyDecisionEntity
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.Pill
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.SectionTitle
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.LocalAdManager
import kotlinx.coroutines.launch

/**
 * A live read-out of what the SDK is doing on the current screen.
 *
 * Scoped, not global: each screen provides its own `LocalInspectorPlacements`,
 * so the Placements tab shows what *this* surface actually binds. Library
 * provides an empty list, and the resulting empty tab is the point rather than
 * an omission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorSheet(
    placements: List<AdPlacement>,
    onDismiss: () -> Unit,
) {
    val palette = showcaseColors
    val graph = LocalAppGraph.current
    val manager = LocalAdManager.current
    val sdkStatus by manager.status.collectAsState()
    val consentStatus by manager.consent.status.collectAsState()
    val adEvents by graph.telemetry.adEvents.collectAsState(initial = emptyList())
    val policyDecisions by graph.telemetry.policyDecisions.collectAsState(initial = emptyList())
    val paidEvents by graph.telemetry.paidEvents.collectAsState(initial = emptyList())

    var tab by remember { mutableIntStateOf(0) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = ShowcaseShapes.bottomSheet,
        containerColor = palette.surface,
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = palette.hairlineStrong)
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            StatusStrip(sdkStatus = sdkStatus, consentStatus = consentStatus)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Tokens.Spacing.s16, vertical = Tokens.Spacing.s8),
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
            ) {
                TABS.forEachIndexed { index, title ->
                    Pill(
                        label = title,
                        selected = tab == index,
                        onClick = { tab = index },
                    )
                }
            }
            Rule()

            when (tab) {
                INDEX_PLACEMENTS -> PlacementsTab(placements = placements, modifier = tabModifier())
                INDEX_CONSENT -> ConsentStateTab(manager = manager, modifier = tabModifier())
                INDEX_TELEMETRY -> TelemetryLogsTab(
                    adEvents = adEvents,
                    policyDecisions = policyDecisions,
                    paidEvents = paidEvents,
                    isAndroid = isAndroid,
                    modifier = tabModifier(),
                )
            }
        }
    }
}

@Composable
private fun StatusStrip(sdkStatus: AdManagerStatus, consentStatus: ConsentStatus) {
    val palette = showcaseColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.s16, vertical = Tokens.Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(
            label = sdkStatus.label(),
            color = when (sdkStatus) {
                is AdManagerStatus.Ready -> palette.success
                is AdManagerStatus.Failed -> palette.danger
                else -> palette.accent
            },
        )
        StatusDot(
            label = consentStatus.label(),
            color = when (consentStatus) {
                ConsentStatus.Obtained, ConsentStatus.NotRequired -> palette.success
                is ConsentStatus.Failed -> palette.danger
                else -> palette.accent
            },
        )
    }
}

@Composable
private fun StatusDot(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = showcaseColors.inkMuted)
    }
}

@Composable
private fun ConsentStateTab(manager: AdManager, modifier: Modifier = Modifier) {
    val palette = showcaseColors
    val scope = rememberCoroutineScope()
    val sdkStatus by manager.status.collectAsState()
    val consentStatus by manager.consent.status.collectAsState()
    val canRequestAds by manager.consent.canRequestAds.collectAsState()
    val privacyOptions by manager.consent.privacyOptionsRequirementStatus.collectAsState()
    val trackingStatus by remember { mutableStateOf(manager.tracking.status()) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(Tokens.Spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s24),
    ) {
        item(key = "sdk") {
            Column {
                SectionTitle(title = "SDK")
                SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                        StatRow(label = "Status", value = sdkStatus.label())
                        StatRow(label = "Version", value = manager.diagnostics.sdkVersion() ?: "—")
                        val adapters = remember(sdkStatus) { manager.diagnostics.adapterStatuses() }
                        StatRow(label = "Adapters", value = adapters.size.toString())
                    }
                }
            }
        }

        item(key = "consent") {
            Column {
                SectionTitle(title = "Consent")
                SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                        StatRow(label = "Status", value = consentStatus.label())
                        StatRow(
                            label = "Can request ads",
                            value = if (canRequestAds) "Yes" else "No",
                            valueColor = if (canRequestAds) palette.success else palette.inkMuted,
                        )
                        StatRow(label = "Privacy options", value = privacyOptions.toString())
                        StatRow(label = "App tracking", value = trackingStatus.toString())
                    }
                }
            }
        }

        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8)) {
                GhostButton(
                    label = "Show privacy form",
                    onClick = { scope.launch { manager.consent.showPrivacyOptions() } },
                    modifier = Modifier.fillMaxWidth(),
                )
                GhostButton(
                    label = "Open Google Ad Inspector",
                    onClick = { scope.launch { manager.diagnostics.openAdInspector() } },
                    modifier = Modifier.fillMaxWidth(),
                )
                GhostButton(
                    label = "Reset consent (debug)",
                    destructive = true,
                    onClick = { scope.launch { manager.consent.resetConsentForDebug() } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TelemetryLogsTab(
    adEvents: List<AdEventEntity>,
    policyDecisions: List<PolicyDecisionEntity>,
    paidEvents: List<PaidEventEntity>,
    isAndroid: Boolean,
    modifier: Modifier = Modifier,
) {
    var log by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Tokens.Spacing.s16, vertical = Tokens.Spacing.s8),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        ) {
            Pill(
                label = "Events (${adEvents.size + policyDecisions.size})",
                selected = log == 0,
                onClick = { log = 0 },
            )
            Pill(
                label = "Revenue (${paidEvents.size})",
                selected = log == 1,
                onClick = { log = 1 },
            )
        }

        if (log == 0) {
            EventsTab(
                adEvents = adEvents,
                policyDecisions = policyDecisions,
                isAndroid = isAndroid,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            )
        } else {
            RevenueTab(
                paidEvents = paidEvents,
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            )
        }
    }
}

private fun ColumnScope.tabModifier(): Modifier = Modifier.fillMaxWidth().weight(1f, fill = false)

private fun AdManagerStatus.label(): String = when {
    this == AdManagerStatus.Ready -> "SDK ready"
    this == AdManagerStatus.Initializing -> "SDK initialising"
    this == AdManagerStatus.ConsentRequired -> "Consent required"
    this == AdManagerStatus.Idle -> "SDK idle"
    this is AdManagerStatus.Disabled -> "Disabled ($reason)"
    this is AdManagerStatus.Failed -> "Failed (${error.message})"
    else -> "Unknown"
}

private fun ConsentStatus.label(): String = when {
    this == ConsentStatus.Obtained -> "Consent obtained"
    this == ConsentStatus.NotRequired -> "Consent not required"
    this == ConsentStatus.Required -> "Consent required"
    this == ConsentStatus.Unknown -> "Consent unknown"
    this is ConsentStatus.Failed -> "Consent failed (${error.message})"
    else -> "Consent unknown"
}

private const val INDEX_PLACEMENTS = 0
private const val INDEX_CONSENT = 1
private const val INDEX_TELEMETRY = 2
private val TABS = listOf("Placements", "Consent", "Telemetry")
