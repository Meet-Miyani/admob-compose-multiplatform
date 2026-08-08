package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.MonetizationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.data.db.entity.PaidEventEntity
import dev.avinya.admob.showcase.domain.telemetry.PlacementRevenue
import dev.avinya.admob.showcase.domain.telemetry.aggregateRevenue

/**
 * Revenue tab: per-placement aggregate (top earner first) and the raw paid
 * log.
 *
 * The two are kept separate so a reader can confirm an aggregate against its
 * source rows without leaving the tab. Currencies are never summed across
 * each other — see [aggregateRevenue].
 */
@Composable
fun RevenueTab(paidEvents: List<PaidEventEntity>, modifier: Modifier = Modifier) {
    if (paidEvents.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "No paid events yet. Impressions with revenue attached will appear here as they are recorded.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val rows = paidEvents.map { it.toPaidRow() }
    val aggregates = aggregateRevenue(rows)
    val byPlacement = paidEvents.groupBy { it.placementId to it.precision }
    val totalImpressions = aggregates.sumOf { it.impressions }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "revenue_overview_card") {
            RevenueOverviewCard(
                totalImpressions = totalImpressions,
                totalPlacements = aggregates.map { it.placementId }.distinct().size,
            )
        }

        item(key = "section_aggregate") {
            SectionHeading("Per placement breakdown")
        }
        items(aggregates, key = { "${it.placementId}-${it.currency}" }) { line ->
            AggregateRow(
                line = line,
                precisionLabel = byPlacement[line.placementId to line.currency]
                    ?.lastOrNull()?.precision
                    ?: UNKNOWN_PRECISION,
            )
        }

        item(key = "section_divider") {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        item(key = "section_raw") {
            SectionHeading("Raw paid events (${paidEvents.size})")
        }
        items(paidEvents, key = { it.id }) { event ->
            RawRow(event)
        }
    }
}

@Composable
private fun RevenueOverviewCard(
    totalImpressions: Int,
    totalPlacements: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MonetizationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text("Revenue & eCPM Overview", style = MaterialTheme.typography.titleMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Total Paid Impressions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$totalImpressions imp",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Active Placements",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$totalPlacements active",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

@Composable
private fun AggregateRow(line: PlacementRevenue, precisionLabel: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = line.placementId,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = formatMicrosAsCurrency(line.totalMicros, line.currency),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${line.impressions} impressions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Precision: $precisionLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RawRow(event: PaidEventEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.placementId,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = formatMicrosAsCurrency(event.valueMicros, event.currency),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.precision,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTimestamp(event.at),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
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

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/** Trivial mapper: the entity already holds every field the aggregate needs. */
private fun PaidEventEntity.toPaidRow(): dev.avinya.admob.showcase.domain.telemetry.PaidEventRow =
    dev.avinya.admob.showcase.domain.telemetry.PaidEventRow(
        placementId = placementId,
        valueMicros = valueMicros,
        currency = currency,
    )

private fun formatMicrosAsCurrency(micros: Long, currency: String): String {
    val whole = micros / MICROS_PER_UNIT
    val fraction = (micros % MICROS_PER_UNIT).toString().padStart(6, '0').trimEnd('0')
    return if (fraction.isEmpty()) {
        "$whole $currency"
    } else {
        "$whole.$fraction $currency"
    }
}

private const val MICROS_PER_UNIT: Long = 1_000_000L
private const val UNKNOWN_PRECISION: String = "Unknown"

