package dev.avinya.admob.showcase.feature.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avinya.admob.showcase.StartupState
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorSheet
import dev.avinya.admob.showcase.ui.inspector.LocalInspectorPlacements
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import dev.avinya.ads.AdTrackingAuthorization
import dev.avinya.ads.ConsentDebugGeography
import dev.avinya.ads.LocalAdManager

@Composable
fun SettingsScreen() {
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val viewModel: SettingsViewModel = viewModel {
        SettingsViewModel(adManager, graph.settings, graph.startup)
    }
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val inspectorEnabled by graph.settings.inspectorEnabled.collectAsState(initial = true)
    var showInspector by remember { mutableStateOf(false) }
    val placements = remember { listOf(ShowcasePlacements.appOpen) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SettingsEffect.Notice -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    CompositionLocalProvider(LocalInspectorPlacements provides placements) {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item {
                    InspectorEntryPoint(
                        title = "Settings",
                        enabled = inspectorEnabled,
                        onOpen = { showInspector = true },
                    )
                }

                // SDK Status Card
                item {
                    SdkStatusCard(
                        status = state.sdkStatus,
                        startup = state.startup,
                        consentStatus = state.consentStatus.toString(),
                        testDeviceId = state.debugGeography.name,
                        version = state.sdkVersion ?: "1.0.0",
                        canRequestAds = state.canRequestAds.toString(),
                        privacyOptions = state.privacyOptions.name,
                        showPrivacyButton = shouldShowPrivacyOptionsButton(state.privacyOptions),
                        busy = state.busy,
                        onManageConsent = { viewModel.onIntent(SettingsIntent.ShowPrivacyOptions) },
                        onRetryStartup = { viewModel.onIntent(SettingsIntent.RetryStartup) },
                    )
                }

                // Theme Selector Card with Segmented Chips
                item {
                    ThemeSelectorCard(
                        selectedMode = state.themeMode,
                        onSelectMode = { viewModel.onIntent(SettingsIntent.SetThemeMode(it)) },
                    )
                }

                // Inspector & Telemetry Toggle Card
                item {
                    InspectorToggleCard(
                        inspectorEnabled = state.inspectorEnabled,
                        adsEnabled = state.adsEnabled,
                        busy = state.busy,
                        onInspectorToggle = { viewModel.onIntent(SettingsIntent.SetInspectorEnabled(it)) },
                        onAdsToggle = { viewModel.onIntent(SettingsIntent.SetAdsEnabled(it)) },
                        onOpenAdInspector = { viewModel.onIntent(SettingsIntent.OpenAdInspector) },
                    )
                }

                // Consent Debugging Card
                item {
                    SettingsSection(title = "Consent Debugging", icon = Icons.Rounded.Security) {
                        Text(
                            "Debug geography forces UMP to behave as if the device were " +
                                "in the selected region. Applies on next launch.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        ConsentDebugGeography.entries.forEach { geography ->
                            RadioRow(
                                label = geography.name,
                                selected = state.debugGeography == geography,
                                onClick = { viewModel.onIntent(SettingsIntent.SetDebugGeography(geography)) },
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            enabled = !state.busy,
                            onClick = { viewModel.onIntent(SettingsIntent.ResetConsent) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary,
                            ),
                        ) { Text("Reset consent state") }
                    }
                }

                // Tracking Permission Card
                item {
                    SettingsSection(title = "App Tracking Transparency", icon = Icons.Rounded.BugReport) {
                        LabelledValue("Authorisation Status", state.tracking.name)
                        if (state.tracking == AdTrackingAuthorization.NotApplicable) {
                            Text(
                                "App Tracking Transparency is an iOS concept. Android " +
                                    "always reports NotApplicable.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Button(
                                onClick = { viewModel.onIntent(SettingsIntent.RequestTracking) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = Color.Black,
                                ),
                            ) {
                                Text("Request tracking permission")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInspector) {
        InspectorSheet(placements = placements, onDismiss = { showInspector = false })
    }
}

@Composable
private fun SdkStatusCard(
    status: String,
    startup: StartupState,
    consentStatus: String,
    testDeviceId: String,
    version: String,
    canRequestAds: String,
    privacyOptions: String,
    showPrivacyButton: Boolean,
    busy: Boolean,
    onManageConsent: () -> Unit,
    onRetryStartup: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text("SDK & Initialization", style = MaterialTheme.typography.titleMedium)
            }

            LabelledValue("SDK Status", status)
            LabelledValue(
                "Startup State",
                when (startup) {
                    is StartupState.Failed -> "Failed (${startup.message})"
                    else -> startup.toString()
                },
            )
            LabelledValue("SDK Version", version)
            LabelledValue("CMP Consent", consentStatus)
            LabelledValue("Can Request Ads", canRequestAds)
            LabelledValue("Privacy Options", privacyOptions)
            LabelledValue("Test Geography", testDeviceId)

            if (startup !is StartupState.Ready) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    enabled = !busy,
                    onClick = onRetryStartup,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("Retry Initialization")
                }
            }

            if (showPrivacyButton) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    enabled = !busy,
                    onClick = onManageConsent,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("Manage Consent Settings")
                }
            }
        }
    }
}

@Composable
private fun ThemeSelectorCard(
    selectedMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Appearance Theme", style = MaterialTheme.typography.titleMedium)

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ThemeSegmentPill(
                        label = "System",
                        icon = Icons.Rounded.PhoneAndroid,
                        selected = selectedMode == ThemeMode.System,
                        onClick = { onSelectMode(ThemeMode.System) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeSegmentPill(
                        label = "Light",
                        icon = Icons.Rounded.WbSunny,
                        selected = selectedMode == ThemeMode.Light,
                        onClick = { onSelectMode(ThemeMode.Light) },
                        modifier = Modifier.weight(1f),
                    )
                    ThemeSegmentPill(
                        label = "Dark",
                        icon = Icons.Rounded.NightsStay,
                        selected = selectedMode == ThemeMode.Dark,
                        onClick = { onSelectMode(ThemeMode.Dark) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSegmentPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label = "themePillBg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 200),
        label = "themePillContent",
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = backgroundColor,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
                color = contentColor,
            )
        }
    }
}

@Composable
private fun InspectorToggleCard(
    inspectorEnabled: Boolean,
    adsEnabled: Boolean,
    busy: Boolean,
    onInspectorToggle: (Boolean) -> Unit,
    onAdsToggle: (Boolean) -> Unit,
    onOpenAdInspector: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Ad Telemetry Inspector", style = MaterialTheme.typography.titleMedium)
                    // Live Telemetry Pulse Indicator
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (inspectorEnabled) MaterialTheme.colorScheme.primary else Color.Gray),
                    )
                }
                Switch(
                    checked = inspectorEnabled,
                    onCheckedChange = onInspectorToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }

            SwitchRow(
                label = "Show Placement Ads",
                checked = adsEnabled,
                onCheckedChange = onAdsToggle,
            )

            Text(
                "Turning ads off suppresses every placement locally without " +
                    "changing any SDK or consent state.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = onOpenAdInspector,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text("Open Google Ad Inspector")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun LabelledValue(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}


