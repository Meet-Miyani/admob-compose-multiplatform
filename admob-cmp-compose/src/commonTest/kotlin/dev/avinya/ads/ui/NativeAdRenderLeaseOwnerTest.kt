package dev.avinya.ads.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NativeAdRenderLeaseOwnerTest {

    /** A lease stand-in: identity is what tells one record's lease from another's. */
    private class FakeLease(val adInstanceId: String)

    @Test
    fun `owner keeps returning the same lease while the record is unchanged`() {
        val lease = FakeLease("ad-1")
        val released = mutableListOf<FakeLease>()
        val owner = NativeAdRenderLeaseOwner(
            acquire = { lease },
            release = { released += it },
            identityOf = FakeLease::adInstanceId,
        )

        assertSame(lease, owner.lease())
        assertSame(lease, owner.lease())
        // Re-acquiring the SAME record must not look like a replacement, or every read would
        // release and re-mount the ad it is displaying.
        assertEquals(emptyList(), released)
    }

    /**
     * The defect this guards: the owner is remembered on session, slot, placement and
     * renderer, none of which change when the slot's record is replaced. A cached lease could
     * therefore outlive its ad and keep handing back a destroyed one, because the published
     * slot states carry no record identity for the composable to key on.
     */
    @Test
    fun `owner adopts the replacement lease and releases the stale one`() {
        val first = FakeLease("ad-1")
        val second = FakeLease("ad-2")
        var current: FakeLease = first
        val released = mutableListOf<FakeLease>()
        val owner = NativeAdRenderLeaseOwner(
            acquire = { current },
            release = { released += it },
            identityOf = FakeLease::adInstanceId,
        )
        assertSame(first, owner.lease())

        current = second

        assertSame(second, owner.lease(), "a replaced record must not keep serving the old ad")
        assertEquals(listOf(first), released, "the stale lease must be released, not dropped")
    }

    @Test
    fun `owner releases its lease when the record disappears`() {
        val lease = FakeLease("ad-1")
        var current: FakeLease? = lease
        val released = mutableListOf<FakeLease>()
        val owner = NativeAdRenderLeaseOwner(
            acquire = { current },
            release = { released += it },
            identityOf = FakeLease::adInstanceId,
        )
        assertSame(lease, owner.lease())

        current = null

        assertNull(owner.lease())
        assertEquals(listOf(lease), released)
    }

    @Test
    fun `owner retries a null acquisition on a later read`() {
        val expectedLease = FakeLease("ad-1")
        var acquisitions = 0
        val owner = NativeAdRenderLeaseOwner(
            acquire = {
                acquisitions++
                expectedLease.takeIf { acquisitions >= 2 }
            },
            release = {},
            identityOf = FakeLease::adInstanceId,
        )

        assertEquals(1, acquisitions)
        assertSame(expectedLease, owner.lease())
    }

    @Test
    fun `forgotten owner releases its acquired lease exactly once`() {
        val expectedLease = FakeLease("ad-1")
        val released = mutableListOf<FakeLease>()
        val owner = NativeAdRenderLeaseOwner(
            acquire = { expectedLease },
            release = { released += it },
            identityOf = FakeLease::adInstanceId,
        )

        owner.onForgotten()
        owner.onForgotten()
        owner.onAbandoned()

        assertEquals(listOf(expectedLease), released)
        assertNull(owner.lease())
    }

    @Test
    fun `abandoned owner releases its acquired lease exactly once`() {
        val expectedLease = FakeLease("ad-1")
        val released = mutableListOf<FakeLease>()
        val owner = NativeAdRenderLeaseOwner(
            acquire = { expectedLease },
            release = { released += it },
            identityOf = FakeLease::adInstanceId,
        )

        owner.onAbandoned()
        owner.onAbandoned()
        owner.onForgotten()

        assertEquals(listOf(expectedLease), released)
        assertNull(owner.lease())
    }
}
