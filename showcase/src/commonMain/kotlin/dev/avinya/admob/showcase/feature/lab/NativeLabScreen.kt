package dev.avinya.admob.showcase.feature.lab

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.ad.rememberFeedAdLayout
import dev.avinya.admob.showcase.ui.ad.rememberInlineAdLayout
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.NativeAdCard
import dev.avinya.admob.showcase.ui.kit.PillTabs
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import androidx.compose.material3.Text
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.nativead.NativeAdSlotState
import dev.avinya.ads.nativead.NativeAdWindow
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutValidator
import dev.avinya.ads.nativead.layout.AdTemplates
import dev.avinya.ads.ui.rememberNativeAdSlotSession
import kotlinx.coroutines.launch

/**
 * The native-layout gallery.
 *
 * One creative, one stable slot key, five different renderings of it — the
 * SDK's three ready-made templates plus the two this app writes with the
 * `adLayout {}` DSL. Switching layout does not reload the ad: the same
 * session-owned creative is re-bound to a new tree, which is the property that
 * makes the layout DSL worth having.
 *
 * The validator report is shown live, because the policy rules it enforces —
 * a visible ad badge at the top, reserved AdChoices space — are the ones an
 * integrator is most likely to get wrong.
 */
private enum class LabLayout(val label: String) {
    Compact("Compact"),
    Medium("Medium"),
    FeedCard("Feed card"),
    AppFeed("App: feed"),
    AppInline("App: inline"),
}

@Composable
fun NativeLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slotKey = remember { "lab:native:demo-1" }
    val slot = remember { NativeAdSlot(slotKey, ShowcasePlacements.labNative) }
    val session = rememberNativeAdSlotSession(sessionKey = "lab:native", slot = slot)
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf(LabLayout.AppFeed) }
    val appFeedLayout = rememberFeedAdLayout()
    val appInlineLayout = rememberInlineAdLayout()

    val layout: AdLayout = when (selected) {
        LabLayout.Compact -> AdTemplates.compact
        LabLayout.Medium -> AdTemplates.medium
        LabLayout.FeedCard -> AdTemplates.feedCard
        LabLayout.AppFeed -> appFeedLayout
        LabLayout.AppInline -> appInlineLayout
    }

    LabScreen(
        title = "Native",
        subtitle = "Layouts, session retention, and policy checks",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "layouts") {
            LabSection(
                title = "Layout",
                description = "The same loaded creative, re-bound to a different tree. " +
                    "Switching does not request a new ad.",
            ) {
                PillTabs(
                    options = LabLayout.entries.map { it.label },
                    selected = selected.label,
                    onSelect = { label ->
                        selected = LabLayout.entries.first { it.label == label }
                    },
                    contentPadding = 0.dp,
                )
            }
        }

        item(key = "preview") {
            LabSection(
                title = selected.label,
                description = "Rendered through the SDK's NativeAdView with the app's own container.",
            ) {
                NativeAdCard(
                    session = session,
                    slotKey = slotKey,
                    placement = ShowcasePlacements.labNative,
                    layout = layout,
                    framed = selected == LabLayout.AppFeed || selected == LabLayout.AppInline,
                    modifier = Modifier.fillMaxWidth(),
                )
                SlotStateNote(session = session, slotKey = slotKey)
            }
        }

        item(key = "validation") {
            LabSection(
                title = "Policy validation",
                description = "AdLayoutValidator findings for the selected layout.",
            ) {
                ValidationReport(layout)
            }
        }

        item(key = "session") {
            LabSection(
                title = "Session controls",
                description = "Closing releases the session's inventory; recreating binds the " +
                    "same stable slot key again, so retention is demonstrable rather than accidental.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8)) {
                    GhostButton(
                        label = "Deactivate",
                        onClick = { scope.launch { session.deactivate() } },
                    )
                    GhostButton(
                        label = "Close",
                        onClick = { scope.launch { session.close() } },
                        destructive = true,
                    )
                    GhostButton(
                        label = "Recreate",
                        onClick = {
                            session.updateWindow(
                                NativeAdWindow(
                                    visible = listOf(
                                        NativeAdSlot(slotKey, ShowcasePlacements.labNative),
                                    ),
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlotStateNote(session: NativeAdSession, slotKey: String) {
    val state by session.state.collectAsState()
    val slot = state.slots[slotKey]
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4)) {
        LabStatus(text = "slot: ${slot.label()}")
        LabStatus(
            text = "active: ${state.active} · tracked slots: ${state.slots.size}",
        )
        if (slot == null || slot is NativeAdSlotState.Empty) {
            Text(
                text = "Nothing is rendered until the SDK reports the slot renderable — " +
                    "no permanent placeholder, on purpose.",
                style = MaterialTheme.typography.bodySmall,
                color = showcaseColors.inkMuted,
            )
        }
    }
}

@Composable
private fun ValidationReport(layout: AdLayout) {
    val palette = showcaseColors
    val report = remember(layout) { AdLayoutValidator.validate(layout.root) }

    SunkenPanel(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(Tokens.Spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
        ) {
            if (report.errors.isEmpty() && report.warnings.isEmpty()) {
                Text(
                    text = "No findings — headline, ad badge, and AdChoices space are all present.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.success,
                )
            }
            report.errors.forEach { issue ->
                Text(
                    text = "error · ${issue.code}: ${issue.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.danger,
                )
            }
            report.warnings.forEach { issue ->
                Text(
                    text = "warning · ${issue.code}: ${issue.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.accent,
                )
            }
        }
    }
}

/** Exhaustive so a new SDK slot state is a compile error, not a blank label. */
private fun NativeAdSlotState?.label(): String = when (this) {
    null -> "not in window"
    NativeAdSlotState.Empty -> "Empty"
    NativeAdSlotState.Loading -> "Loading"
    is NativeAdSlotState.Ready -> "Ready"
    is NativeAdSlotState.Mounted -> "Mounted"
    is NativeAdSlotState.Retained -> "Retained"
    is NativeAdSlotState.Failed -> "Failed: ${error.message}"
}
