package dev.avinya.ads

import dev.avinya.ads.internal.NativeAdDemandClass
import dev.avinya.ads.internal.NativeAdGovernor
import dev.avinya.ads.internal.NativeAdLoadReservation
import dev.avinya.ads.internal.NativeAdPriority
import dev.avinya.ads.internal.NativeAdRecordId
import dev.avinya.ads.internal.NativeMemoryPressure
import dev.avinya.ads.nativead.NativeAdMemoryPolicy
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Seeded stress runner for [NativeAdGovernor].
 *
 * The governor's KDoc claims `loadedRecordCount + reservedLoadCount <=
 * policy.hardLimit` holds "under every mutation". The handcrafted tests in
 * [NativeAdGovernorTest] prove that for the interleavings someone thought to
 * write down. This proves it for generated ones.
 *
 * Deterministic on purpose: no threads, no sleeps, no wall clock. Every run
 * uses a fixed seed, and any failure prints the seed and operation index so the
 * exact sequence replays. The governor's LRU is keyed off an incrementing
 * access ordinal rather than a clock, which is what makes this reproducible.
 */
class NativeAdGovernorStressTest {

    private companion object {
        const val SEEDS = 400
        const val OPERATIONS_PER_SEED = 120
    }

    /** How a reservation left the pending set. A reservation must reach exactly one. */
    private enum class Terminal { Admitted, Released, CancelledByReserve, CancelledByTrim }

    private class Harness(policy: NativeAdMemoryPolicy, val seed: Int) {
        val governor = NativeAdGovernor(policy)
        val pending = mutableListOf<NativeAdLoadReservation>()
        val live = mutableListOf<NativeAdRecordId>()
        val mounted = mutableSetOf<NativeAdRecordId>()
        val terminal = mutableMapOf<NativeAdLoadReservation, Terminal>()
        var minted = 0

        fun consume(reservation: NativeAdLoadReservation, how: Terminal, op: Int) {
            val prior = terminal.put(reservation, how)
            if (prior != null) {
                fail(
                    "seed=$seed op=$op: reservation ${reservation.id.value} reached two " +
                        "terminal states: $prior then $how"
                )
            }
            pending.remove(reservation)
        }

        fun forget(ids: List<NativeAdRecordId>) {
            live.removeAll(ids.toSet())
            mounted.removeAll(ids.toSet())
        }

        fun assertInvariant(op: Int, action: String) {
            val state = governor.state()
            if (state.loadedRecords + state.reservedLoads > state.hardLimit) {
                fail(
                    "seed=$seed op=$op after $action: capacity invariant broken — " +
                        "loadedRecords=${state.loadedRecords} + reservedLoads=${state.reservedLoads} " +
                        "> hardLimit=${state.hardLimit}"
                )
            }
            if (state.loadedRecords < 0 || state.reservedLoads < 0) {
                fail(
                    "seed=$seed op=$op after $action: negative accounting — " +
                        "loadedRecords=${state.loadedRecords} reservedLoads=${state.reservedLoads}"
                )
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun runSequence(seed: Int, policy: NativeAdMemoryPolicy) {
        val random = Random(seed)
        val h = Harness(policy, seed)
        val priorities = NativeAdPriority.entries.toList()

        repeat(OPERATIONS_PER_SEED) { op ->
            when (random.nextInt(10)) {
                0, 1 -> {
                    val decision = h.governor.reserve(
                        demandClass = NativeAdDemandClass.Visible,
                        priority = priorities.random(random),
                        count = 1 + random.nextInt(3),
                        allowPartial = random.nextBoolean(),
                    )
                    decision.cancelledReservations.forEach { h.consume(it, Terminal.CancelledByReserve, op) }
                    h.forget(decision.retiredRecordIds)
                    h.pending += decision.reservations
                    h.minted += decision.reservations.size
                    h.assertInvariant(op, "reserve(Visible)")
                }
                2 -> {
                    val decision = h.governor.reserve(
                        demandClass = NativeAdDemandClass.Speculative,
                        priority = priorities.random(random),
                        count = 1 + random.nextInt(2),
                        allowPartial = random.nextBoolean(),
                    )
                    decision.cancelledReservations.forEach { h.consume(it, Terminal.CancelledByReserve, op) }
                    h.forget(decision.retiredRecordIds)
                    h.pending += decision.reservations
                    h.minted += decision.reservations.size
                    h.assertInvariant(op, "reserve(Speculative)")
                }
                3, 4 -> {
                    if (h.pending.isNotEmpty()) {
                        val reservation = h.pending.random(random)
                        val id = h.governor.admit(reservation)
                        h.consume(reservation, Terminal.Admitted, op)
                        h.live += id
                        h.assertInvariant(op, "admit")
                    }
                }
                5 -> {
                    if (h.pending.isNotEmpty()) {
                        val reservation = h.pending.random(random)
                        h.governor.releaseReservation(reservation)
                        h.consume(reservation, Terminal.Released, op)
                        h.assertInvariant(op, "releaseReservation")
                    }
                }
                6 -> {
                    if (h.live.isNotEmpty()) {
                        val id = h.live.random(random)
                        h.governor.touch(id)
                        h.governor.reclassify(id, priorities.random(random))
                        h.assertInvariant(op, "touch+reclassify")
                    }
                }
                7 -> {
                    if (h.live.isNotEmpty()) {
                        val id = h.live.random(random)
                        val nowMounted = random.nextBoolean()
                        h.governor.setMounted(id, nowMounted)
                        if (nowMounted) h.mounted += id else h.mounted -= id
                        h.assertInvariant(op, "setMounted")
                    }
                }
                8 -> {
                    if (h.live.isNotEmpty()) {
                        val id = h.live.random(random)
                        h.governor.retire(id)
                        h.forget(listOf(id))
                        h.assertInvariant(op, "retire")
                    }
                }
                else -> {
                    val pressure =
                        if (random.nextBoolean()) NativeMemoryPressure.Moderate else NativeMemoryPressure.Critical
                    val result = h.governor.trim(pressure)
                    result.cancelledReservations.forEach { h.consume(it, Terminal.CancelledByTrim, op) }
                    h.forget(result.retiredRecordIds)
                    h.assertInvariant(op, "trim($pressure)")

                    if (pressure == NativeMemoryPressure.Critical) {
                        val state = h.governor.state()
                        if (state.reservedLoads != 0) {
                            fail(
                                "seed=$seed op=$op: trim(Critical) must cancel every pending " +
                                    "reservation, but reservedLoads=${state.reservedLoads}"
                            )
                        }
                        if (state.loadedRecords != h.mounted.size) {
                            fail(
                                "seed=$seed op=$op: trim(Critical) must retain exactly the mounted " +
                                    "records, but loadedRecords=${state.loadedRecords} and " +
                                    "${h.mounted.size} are mounted"
                            )
                        }
                    }
                }
            }
        }

        // Every reservation ever minted is either still pending or reached exactly
        // one terminal state. A reservation in neither set is a leaked native ad.
        val accounted = h.pending.size + h.terminal.size
        if (accounted != h.minted) {
            fail(
                "seed=$seed: reservation accounting lost permits — minted=${h.minted}, " +
                    "pending=${h.pending.size}, terminal=${h.terminal.size}"
            )
        }
    }

    @Test
    fun `the capacity invariant holds across generated operation sequences`() {
        val policy = NativeAdMemoryPolicy()
        for (seed in 0 until SEEDS) {
            runSequence(seed, policy)
        }
    }

    @Test
    fun `the capacity invariant holds when the soft and hard limits are equal`() {
        // softLimit == hardLimit removes the speculative headroom entirely, which
        // is the configuration most likely to expose an off-by-one in the cap
        // arithmetic.
        val policy = NativeAdMemoryPolicy(softLimit = 2, hardLimit = 2)
        for (seed in 0 until SEEDS) {
            runSequence(seed, policy)
        }
    }

    @Test
    fun `the capacity invariant holds at a hard limit of one`() {
        val policy = NativeAdMemoryPolicy(softLimit = 1, hardLimit = 1, inactiveSessionLimit = 1)
        for (seed in 0 until SEEDS) {
            runSequence(seed, policy)
        }
    }
}
