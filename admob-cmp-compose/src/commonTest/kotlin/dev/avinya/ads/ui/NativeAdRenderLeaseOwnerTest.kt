package dev.avinya.ads.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class NativeAdRenderLeaseOwnerTest {
    @Test
    fun `owner acquires synchronously and preserves the lease across reads`() {
        val expectedLease = Any()
        var acquisitions = 0
        val owner = NativeAdRenderLeaseOwner(
            acquire = {
                acquisitions++
                expectedLease
            },
            release = {},
        )

        assertEquals(1, acquisitions)
        assertSame(expectedLease, owner.lease())
        assertSame(expectedLease, owner.lease())
        assertEquals(1, acquisitions)
    }

    @Test
    fun `owner retries a null acquisition on a later read`() {
        val expectedLease = Any()
        var acquisitions = 0
        val owner = NativeAdRenderLeaseOwner(
            acquire = {
                acquisitions++
                expectedLease.takeIf { acquisitions >= 2 }
            },
            release = {},
        )

        assertEquals(1, acquisitions)
        assertSame(expectedLease, owner.lease())
        assertEquals(2, acquisitions)
    }

    @Test
    fun `forgotten owner releases its acquired lease exactly once`() {
        val expectedLease = Any()
        val released = mutableListOf<Any>()
        val owner = NativeAdRenderLeaseOwner(
            acquire = { expectedLease },
            release = { released += it },
        )

        owner.onForgotten()
        owner.onForgotten()
        owner.onAbandoned()

        assertEquals(listOf(expectedLease), released)
        assertNull(owner.lease())
    }

    @Test
    fun `abandoned owner releases its acquired lease exactly once`() {
        val expectedLease = Any()
        val released = mutableListOf<Any>()
        val owner = NativeAdRenderLeaseOwner(
            acquire = { expectedLease },
            release = { released += it },
        )

        owner.onAbandoned()
        owner.onAbandoned()
        owner.onForgotten()

        assertEquals(listOf(expectedLease), released)
        assertNull(owner.lease())
    }
}
