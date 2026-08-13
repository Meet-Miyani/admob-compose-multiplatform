package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.data.db.entity.AdEventEntity
import dev.avinya.admob.showcase.data.db.entity.PolicyDecisionEntity
import dev.avinya.admob.showcase.ui.kit.Badge
import dev.avinya.admob.showcase.ui.kit.EmptyState
import dev.avinya.admob.showcase.ui.kit.Plane
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * Events tab: the rolling ad-event log interleaved with policy decisions,
 * newest first.
 *
 * The two sources share a single column so a reader can answer "why did this
 * ad show / not show" without flipping tabs. The order is `at DESC` per
 * source and a stable merge by timestamp — see [mergedRows].
 *
 * [isAndroid] triggers an explicit note about the missing native video
 * events. The note is load-bearing: without it, an empty video section
 * reads as a bug in *our* code, when it is actually a GMA Next-Gen SDK gap.
 */
@Composable
fun EventsTab(
    adEvents: List<AdEventEntity>,
    policyDecisions: List<PolicyDecisionEntity>,
    isAndroid: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (isAndroid) {
            AndroidVideoGapBanner()
        }
        if (adEvents.isEmpty() && policyDecisions.isEmpty()) {
            EmptyState(
                title = "No events yet",
                message = "Exercise the placements on this screen and they will appear here " +
                    "in real time.",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(Tokens.Spacing.s16),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
            ) {
                val rows = mergedRows(adEvents, policyDecisions)
                items(rows, key = { it.key() }) { row ->
                    EventRow(row)
                }
            }
        }
    }
}

@Composable
private fun AndroidVideoGapBanner() {
    val palette = showcaseColors
    SunkenPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Spacing.s16, vertical = Tokens.Spacing.s8),
    ) {
        Row(
            modifier = Modifier.padding(Tokens.Spacing.s12),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "VideoStarted / VideoPlayed / VideoPaused / VideoEnded / VideoMuted are " +
                    "not delivered on Android — the GMA Next-Gen SDK exposes no equivalent " +
                    "to iOS's GADVideoControllerDelegate. This is an upstream gap, not a " +
                    "showcase omission.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.inkMuted,
            )
        }
    }
}

@Composable
private fun EventRow(row: EventRow) {
    Plane(modifier = Modifier.fillMaxWidth(), elevation = Tokens.Elevation.flat) {
        Column(
            modifier = Modifier.padding(Tokens.Spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.placementId,
                    style = MaterialTheme.typography.titleSmall,
                    color = showcaseColors.ink,
                )
                EventTypeBadge(type = row.type)
            }

            if (row.reason != null) {
                Text(
                    text = row.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = showcaseColors.inkMuted,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimestamp(row.at),
                    style = ShowcaseType.numeric,
                    color = showcaseColors.inkFaint,
                )
            }
        }
    }
}

@Composable
private fun EventTypeBadge(type: String) {
    val palette = showcaseColors
    val badgeColor = when {
        type.contains("Fail", ignoreCase = true) ||
            type.contains("Suppress", ignoreCase = true) ||
            type.contains("Error", ignoreCase = true) -> palette.danger

        type.contains("Impression", ignoreCase = true) ||
            type.contains("Paid", ignoreCase = true) -> palette.success

        type.contains("Click", ignoreCase = true) ||
            type.contains("Opened", ignoreCase = true) -> palette.accent

        else -> palette.primary
    }

    Badge(text = type, color = badgeColor, container = palette.surfaceSunken)
}

private fun formatTimestamp(timestampMillis: Long): String {
    val totalSeconds = (timestampMillis / 1000) % 86400
    val hours = (totalSeconds / 3600) % 24
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val millis = timestampMillis % 1000
    val h = hours.toString().padStart(2, '0')
    val m = minutes.toString().padStart(2, '0')
    val s = seconds.toString().padStart(2, '0')
    val ms = millis.toString().padStart(3, '0')
    return "$h:$m:$s.$ms"
}

private data class EventRow(
    val id: Long,
    val at: Long,
    val placementId: String,
    val type: String,
    val reason: String?,
) {
    fun key(): String = "evt-$id-$at-$type"
}

private fun mergedRows(
    adEvents: List<AdEventEntity>,
    policyDecisions: List<PolicyDecisionEntity>,
): List<EventRow> {
    val fromEvents = adEvents.map { e ->
        EventRow(
            id = e.id,
            at = e.at,
            placementId = e.placementId,
            type = e.type,
            reason = e.detail,
        )
    }
    val fromDecisions = policyDecisions.map { d ->
        // decision is "Show" or "Suppress:Reason"; reason is the enum name.
        // Show them as "decision · reason" so a reader sees both halves.
        val reasonText = when {
            d.reason.isNullOrBlank() -> null
            d.decision.startsWith("Show") -> null
            else -> d.reason
        }
        EventRow(
            id = -d.id,
            at = d.at,
            placementId = d.placementId,
            type = d.decision,
            reason = reasonText,
        )
    }
    return (fromEvents + fromDecisions).sortedByDescending { it.at }
}

