package dev.avinya.ads

import dev.avinya.ads.internal.ConsentOperationCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentOperationCoordinatorTest {

    @Test
    fun `the newest operation is the current one`() {
        val coordinator = ConsentOperationCoordinator()
        val first = coordinator.beginOperation()
        assertTrue(coordinator.isCurrentOperation(first))

        val second = coordinator.beginOperation()
        assertFalse(
            coordinator.isCurrentOperation(first),
            "a superseded operation must not be allowed to publish its status",
        )
        assertTrue(coordinator.isCurrentOperation(second))
    }

    @Test
    fun `generations are never reused`() {
        val coordinator = ConsentOperationCoordinator()
        val seen = (1..50).map { coordinator.beginOperation() }
        assertEquals(seen.size, seen.toSet().size, "generations must be monotonic and unique")
    }

    @Test
    fun `serialized runs one block at a time`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val order = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.serialized {
                order += "first-in"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-out"
            }
        }
        firstEntered.await()
        val second = launch { coordinator.serialized { order += "second-in" } }
        advanceUntilIdle()

        assertEquals(listOf("first-in"), order, "the second block must not overlap the first")
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first-in", "first-out", "second-in"), order)
    }

    @Test
    fun `exclusiveOfForms declines when a form is presenting`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondRan = false

        val first = launch {
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                true
            }
        }
        firstEntered.await()

        // A user double-tapping "Privacy options" must NOT be shown the form twice in a row.
        val declined = coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
            secondRan = true
            true
        }

        assertFalse(declined, "an overlapping form presentation must decline")
        assertFalse(secondRan, "the declined path must not run the block")
        releaseFirst.complete(Unit)
        first.join()
    }

    @Test
    fun `exclusiveOfForms waits for a non-form operation and then runs`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val order = mutableListOf<String>()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()

        val nonForm = launch {
            coordinator.serialized {
                order += "non-form-in"
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
                order += "non-form-out"
            }
        }
        nonFormEntered.await()

        val form = launch {
            val ran = coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                order += "form-ran"
                true
            }
            assertTrue(ran, "form must run after non-form operation finishes")
        }
        advanceUntilIdle()

        assertEquals(listOf("non-form-in"), order, "form must wait for non-form operation rather than declining")
        releaseNonForm.complete(Unit)
        nonForm.join()
        form.join()
        assertEquals(listOf("non-form-in", "non-form-out", "form-ran"), order)
    }

    @Test
    fun `exclusiveOfForms restores the form flag after normal completion`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        assertTrue(coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true })
        assertTrue(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a completed form operation must not leave the form flag stuck",
        )
    }

    @Test
    fun `exclusiveOfForms restores the form flag after a throw`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        runCatching {
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                error("form blew up")
            }
        }
        assertTrue(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a throwing form operation must restore the form flag",
        )
    }

    @Test
    fun `exclusiveOfForms restores the form flag after cancellation`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val formEntered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()

        val job = launch {
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                formEntered.complete(Unit)
                neverRelease.await()
                true
            }
        }
        formEntered.await()
        job.cancelAndJoin()

        assertTrue(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "cancellation must restore the form flag and unlock the mutex",
        )
    }
}
