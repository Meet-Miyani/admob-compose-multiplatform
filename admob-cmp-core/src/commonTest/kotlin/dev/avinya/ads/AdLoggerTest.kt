package dev.avinya.ads

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdLoggerTest {

    private val originalMinLevel = AdLogger.minLevel
    private val originalSink = AdLogger.sink

    @BeforeTest
    fun setUp() {
        AdLogger.minLevel = AdLogLevel.Verbose
    }

    @AfterTest
    fun tearDown() {
        // AdLogger's mutable properties are process-wide, so a test that leaves a sink
        // installed would leak into unrelated tests run in the same process.
        AdLogger.sink = originalSink
        AdLogger.minLevel = originalMinLevel
    }

    @Test
    fun `a throwing sink does not propagate out of AdLogger`() {
        var invocations = 0
        AdLogger.sink = AdLogSink { _, _, _, _ ->
            invocations++
            error("boom, host sink is misbehaving")
        }

        // Must not throw: a public SDK cannot let untrusted host callback code corrupt its
        // own control flow (a state machine transition, a native callback, a cleanup path).
        AdLogger.e("something happened")

        assertEquals(1, invocations, "the sink must still have been given the chance to run")
    }

    @Test
    fun `a sink that always throws does not recurse into itself`() {
        var invocations = 0
        AdLogger.sink = AdLogSink { _, _, _, _ ->
            invocations++
            error("boom")
        }

        // If the failure path routed back through dispatch()/sink instead of the platform
        // logger directly, a permanently-throwing sink would recurse without bound.
        AdLogger.e("first")
        AdLogger.e("second")

        assertEquals(2, invocations, "each call must invoke the sink exactly once, not recurse")
    }

    @Test
    fun `a non-throwing sink still receives the message normally`() {
        val received = mutableListOf<String>()
        AdLogger.sink = AdLogSink { _, _, message, _ -> received += message }

        AdLogger.w("routine warning")

        assertTrue(received.contains("routine warning"))
    }
}
