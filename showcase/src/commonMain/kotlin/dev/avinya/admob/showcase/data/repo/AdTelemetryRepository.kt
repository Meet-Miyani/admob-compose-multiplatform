package dev.avinya.admob.showcase.data.repo

import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdFormat
import dev.avinya.admob.showcase.core.time.Clock
import dev.avinya.admob.showcase.data.db.dao.TelemetryDao
import dev.avinya.admob.showcase.data.db.entity.AdEventEntity
import dev.avinya.admob.showcase.data.db.entity.PaidEventEntity
import dev.avinya.admob.showcase.data.db.entity.PolicyDecisionEntity
import dev.avinya.admob.showcase.domain.ad.AdDecision
import dev.avinya.admob.showcase.domain.ad.AppOpenDecision
import dev.avinya.admob.showcase.domain.telemetry.AdEventRow
import dev.avinya.admob.showcase.domain.telemetry.toRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Drains the SDK's [AdEvent] stream and the showcase's policy decisions into
 * the telemetry tables. The repository owns placement-id → format lookup so
 * the mappers stay pure, and it is the only place that knows both surfaces
 * exist.
 *
 * Reads are straight passthroughs to the DAO; the trim cap (500 rows) is
 * enforced inside the DAO's `@Transaction` insert-and-trim.
 *
 * [appScope] is retained so future background work (auto-flush, scheduled
 * aggregation) can launch without rewiring the graph. The current surface
 * is fully synchronous from the caller's perspective.
 */
class AdTelemetryRepository(
    private val telemetryDao: TelemetryDao,
    private val formatByPlacement: Map<String, AdFormat>,
    @Suppress("unused") private val appScope: CoroutineScope,
    private val clock: Clock,
) {

    /**
     * Records one [AdEvent] and, when relevant, its [AdEvent.Paid] twin.
     *
     * `Paid` is written to both `ad_events` and `paid_events` so the Events
     * tab can show "this impression paid" and the Revenue tab can sum
     * independently — collapsing them would force one view to lose data.
     */
    suspend fun record(event: AdEvent, at: Long = clock.nowMillis()) {
        val format = formatByPlacement[event.placementId]?.name ?: UNKNOWN_FORMAT
        telemetryDao.recordAdEvent(
            event.toRow(at = at).toEntity(format),
        )
        if (event is AdEvent.Paid) {
            recordPaid(event, at)
        }
    }

    /** Records a paid event row. Called from [record] and exposed for direct callers. */
    suspend fun recordPaid(event: AdEvent.Paid, at: Long = clock.nowMillis()) {
        telemetryDao.recordPaidEvent(
            PaidEventEntity(
                at = at,
                placementId = event.placementId,
                valueMicros = event.paidEvent.value.valueMicros,
                currency = event.paidEvent.value.currencyCode,
                precision = event.paidEvent.value.precision.name,
            ),
        )
    }

    /** Records one [AdDecision] for a placement. */
    suspend fun recordPolicyDecision(
        placementId: String,
        decision: AdDecision,
        at: Long = clock.nowMillis(),
    ) {
        val reason = (decision as? AdDecision.Suppress)?.reason?.name
        telemetryDao.recordPolicyDecision(
            PolicyDecisionEntity(
                at = at,
                placementId = placementId,
                decision = when (decision) {
                    AdDecision.Show -> "Show"
                    is AdDecision.Suppress -> "Suppress:${decision.reason.name}"
                },
                reason = reason,
            ),
        )
    }

    /** Records one [AppOpenDecision] for the app-open placement. */
    suspend fun recordAppOpenDecision(
        placementId: String,
        decision: AppOpenDecision,
        at: Long = clock.nowMillis(),
    ) {
        val reason = (decision as? AppOpenDecision.Suppress)?.reason?.name
        telemetryDao.recordPolicyDecision(
            PolicyDecisionEntity(
                at = at,
                placementId = placementId,
                decision = when (decision) {
                    AppOpenDecision.Show -> "Show"
                    is AppOpenDecision.Suppress -> "Suppress:${decision.reason.name}"
                },
                reason = reason,
            ),
        )
    }

    val adEvents: Flow<List<AdEventEntity>> = telemetryDao.recentAdEvents()
    val policyDecisions: Flow<List<PolicyDecisionEntity>> = telemetryDao.recentPolicyDecisions()
    val paidEvents: Flow<List<PaidEventEntity>> = telemetryDao.recentPaidEvents()

    private companion object {
        const val UNKNOWN_FORMAT = "Unknown"
    }
}

private fun AdEventRow.toEntity(format: String): AdEventEntity =
    AdEventEntity(
        at = at,
        placementId = placementId,
        format = format,
        type = type,
        detail = detail,
    )
