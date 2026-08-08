package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.data.db.entity.AdEventEntity
import dev.avinya.admob.showcase.data.db.entity.PolicyDecisionEntity

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
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "No events yet — exercise the placements on this screen and they will appear here in real time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "VideoStarted / VideoPlayed / VideoPaused / VideoEnded / VideoMuted are " +
                    "not delivered on Android — the GMA Next-Gen SDK exposes no equivalent " +
                    "to iOS's GADVideoControllerDelegate. This is an upstream gap, not a " +
                    "showcase omission.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun EventRow(row: EventRow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.placementId,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                EventTypeBadge(type = row.type)
            }

            if (row.reason != null) {
                Text(
                    text = row.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimestamp(row.at),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun EventTypeBadge(type: String) {
    val badgeColor = when {
        type.contains("Impression", ignoreCase = true) || type.contains("Paid", ignoreCase = true) -> MaterialTheme.colorScheme.primary
        type.contains("Click", ignoreCase = true) || type.contains("Opened", ignoreCase = true) -> MaterialTheme.colorScheme.tertiary
        type.contains("Loaded", ignoreCase = true) -> MaterialTheme.colorScheme.secondary
        type.contains("Fail", ignoreCase = true) ||
            type.contains("Suppress", ignoreCase = true) ||
            type.contains("Error", ignoreCase = true) -> MaterialTheme.colorScheme.error
        type.startsWith("Show") -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = badgeColor.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f)),
    ) {
        Text(
            text = type,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
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

