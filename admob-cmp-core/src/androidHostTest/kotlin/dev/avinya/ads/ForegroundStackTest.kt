package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the ordering contract that fixed a stacked-Activity foreground regression.
 *
 * `CurrentActivityTracker` held a single reference, so `A started -> B started -> B stopped`
 * left it with nothing even though A was still in the foreground. Full-screen `canPresent`,
 * privacy UI, diagnostics and adaptive-banner sizing then reported no Activity until A
 * happened to receive another lifecycle callback.
 *
 * The ordering is tested here rather than through `CurrentActivityTracker` itself because
 * this source set has no Robolectric, so real `Activity` instances cannot be constructed.
 * [ForegroundStack] is generic precisely so the sequencing is verifiable without one.
 */
class ForegroundStackTest {

    private fun stack(usable: (String) -> Boolean = { true }) = ForegroundStack(usable)

    @Test
    fun `stopping the top activity falls back to the one still started beneath it`() {
        val a = "A"
        val b = "B"
        val stack = stack()

        stack.push(a)
        stack.push(b)
        assertEquals(b, stack.current())

        stack.remove(b)

        assertEquals(a, stack.current(), "A is still started and must become current again")
    }

    @Test
    fun `removing a background activity does not disturb the current one`() {
        val a = "A"
        val b = "B"
        val stack = stack()
        stack.push(a)
        stack.push(b)

        stack.remove(a)

        assertEquals(b, stack.current())
    }

    @Test
    fun `re-pushing an already tracked activity moves it to the top without duplicating`() {
        val a = "A"
        val b = "B"
        val stack = stack()
        stack.push(a)
        stack.push(b)

        stack.push(a)
        assertEquals(a, stack.current())

        // If the re-push had duplicated rather than moved, removing A once would leave a
        // stale A entry above B and current() would still answer A.
        stack.remove(a)
        assertEquals(b, stack.current())
    }

    @Test
    fun `an unusable activity is skipped in favour of the next usable one`() {
        val finishing = "FINISHING"
        val alive = "ALIVE"
        val stack = stack(usable = { it != finishing })
        stack.push(alive)
        stack.push(finishing)

        assertEquals(alive, stack.current(), "a finishing/destroyed activity must never be served")
    }

    @Test
    fun `an empty stack reports null`() {
        val stack = stack()
        assertNull(stack.current())

        val a = "A"
        stack.push(a)
        stack.remove(a)
        assertNull(stack.current())
    }
}
