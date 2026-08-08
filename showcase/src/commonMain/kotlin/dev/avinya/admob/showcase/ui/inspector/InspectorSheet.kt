package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.data.db.entity.AdEventEntity
import dev.avinya.admob.showcase.data.db.entity.PaidEventEntity
import dev.avinya.admob.showcase.data.db.entity.PolicyDecisionEntity
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdPlacement
import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.LocalAdManager
import kotlinx.coroutines.launch

/**
 * Three-tab glass bottom sheet surfacing live ad placement config, consent status,
 * and telemetry logs for the current screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorSheet(
    placements: List<AdPlacement>,
    onDismiss: () -> Unit,
) {
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
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Status Badge Pills Header
            StatusBadgePills(
                sdkStatus = sdkStatus,
                consentStatus = consentStatus,
            )

            SecondaryTabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tab),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                divider = {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                },
            ) {
                TABS.forEachIndexed { index, title ->
                    Tab(
                        selected = tab == index,
                        onClick = { tab = index },
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Medium,
                                ),
                            )
                        },
                    )
                }
            }

            when (tab) {
                INDEX_PLACEMENTS -> PlacementsTab(
                    placements = placements,
                    modifier = tabModifier(),
                )
                INDEX_CONSENT -> ConsentStateTab(
                    manager = manager,
                    modifier = tabModifier(),
                )
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
private fun StatusBadgePills(
    sdkStatus: AdManagerStatus,
    consentStatus: ConsentStatus,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusBadgePill(
            label = if (sdkStatus == AdManagerStatus.Ready) "SDK READY" else "SDK INITIALIZING",
            color = MaterialTheme.colorScheme.primary,
        )
        StatusBadgePill(
            label = when (consentStatus) {
                ConsentStatus.Obtained -> "CONSENT OBTAINED"
                ConsentStatus.NotRequired -> "CONSENT NOT REQUIRED"
                ConsentStatus.Required -> "CONSENT REQUIRED"
                else -> "CONSENT UNKNOWN"
            },
            color = MaterialTheme.colorScheme.secondary,
        )
        StatusBadgePill(
            label = "LIVE AD",
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun StatusBadgePill(label: String, color: Color) {
    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = color,
            )
        }
    }
}

@Composable
private fun ConsentStateTab(manager: AdManager, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val sdkStatus by manager.status.collectAsState()
    val consentStatus by manager.consent.status.collectAsState()
    val canRequestAds by manager.consent.canRequestAds.collectAsState()
    val privacyOptions by manager.consent.privacyOptionsRequirementStatus.collectAsState()
    var trackingStatus by remember { mutableStateOf(manager.tracking.status()) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("SDK & Diagnostics", style = MaterialTheme.typography.titleMedium)
                    LabelledRow("SDK Status", sdkStatus.label())
                    LabelledRow("SDK Version", manager.diagnostics.sdkVersion() ?: "—")
                    val adapters = remember(sdkStatus) { manager.diagnostics.adapterStatuses() }
                    LabelledRow("Adapters Count", adapters.size.toString())
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("CMP Consent State", style = MaterialTheme.typography.titleMedium)
                    LabelledRow("Consent Status", consentStatus.label())
                    LabelledRow("Can Request Ads", canRequestAds.toString())
                    LabelledRow("Privacy Requirement", privacyOptions.toString())
                    LabelledRow("App Tracking", trackingStatus.toString())
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { scope.launch { manager.consent.showPrivacyOptions() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Show Privacy Form")
                }
                OutlinedButton(
                    onClick = { scope.launch { manager.consent.resetConsentForDebug() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Reset Consent (Debug)")
                }
                OutlinedButton(
                    onClick = { scope.launch { manager.diagnostics.openAdInspector() } },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Open Google Ad Inspector")
                }
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
    var selectedLogType by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedLogType == 0,
                onClick = { selectedLogType = 0 },
                label = { Text("Ad Events (${adEvents.size + policyDecisions.size})") },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedLogType == 0,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                ),
            )
            FilterChip(
                selected = selectedLogType == 1,
                onClick = { selectedLogType = 1 },
                label = { Text("Revenue / eCPM (${paidEvents.size})") },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selectedLogType == 1,
                    borderColor = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                ),
            )
        }

        if (selectedLogType == 0) {
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

@Composable
private fun LabelledRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ColumnScope.tabModifier(): Modifier = Modifier.fillMaxWidth().weight(1f, fill = false)

private fun AdManagerStatus.label(): String = when {
    this == AdManagerStatus.Ready -> "Ready"
    this == AdManagerStatus.Initializing -> "Initializing"
    this == AdManagerStatus.ConsentRequired -> "Consent Required"
    this == AdManagerStatus.Idle -> "Idle"
    this is AdManagerStatus.Disabled -> "Disabled ($reason)"
    this is AdManagerStatus.Failed -> "Failed (${error.message})"
    else -> "Unknown"
}

private fun ConsentStatus.label(): String = when {
    this == ConsentStatus.Obtained -> "Obtained"
    this == ConsentStatus.NotRequired -> "Not Required"
    this == ConsentStatus.Required -> "Required"
    this == ConsentStatus.Unknown -> "Unknown"
    this is ConsentStatus.Failed -> "Failed (${error.message})"
    else -> "Unknown"
}

private const val INDEX_PLACEMENTS = 0
private const val INDEX_CONSENT = 1
private const val INDEX_TELEMETRY = 2
private val TABS = listOf("Placements", "Consent State", "Telemetry Logs")


