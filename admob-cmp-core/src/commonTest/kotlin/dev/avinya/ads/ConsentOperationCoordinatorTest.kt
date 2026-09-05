package dev.avinya.ads

import dev.avinya.ads.internal.ConsentOperationCoordinator
import dev.avinya.ads.internal.InitializationTimeouts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
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
            coordinator.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
                order += "first-in"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-out"
            }
        }
        firstEntered.await()
        val second = launch { coordinator.serializedExclusiveOfNativeConsentOperations(onBusy = {}) { order += "second-in" } }
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
        var retainedConfig = "accepted-config"

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
            retainedConfig = "rejected-config"
            true
        }

        assertFalse(declined, "an overlapping form presentation must decline")
        assertFalse(secondRan, "the declined path must not run the block")
        assertEquals(
            "accepted-config",
            retainedConfig,
            "a declined form must not execute admitted config preparation",
        )
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
            coordinator.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
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
    fun `a second form caller queued behind a non-form operation declines`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val order = mutableListOf<String>()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()

        // A launch-time consent info update holds the mutex. It presents nothing, so it never
        // claims the form slot -- which is what used to let two form callers queue behind it.
        val nonForm = launch {
            coordinator.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
                order += "non-form-in"
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
            }
        }
        nonFormEntered.await()

        // The user double-taps "Privacy options" while that update is still in flight. Neither tap
        // can see a form on screen, because the first one has not presented anything yet.
        var firstPresented = false
        val firstTap = launch {
            firstPresented = coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                val generation = coordinator.beginOperation()
                coordinator.markFormPresented(generation)
                order += "first-presented"
                // UMP reports the dismissal, which is the only thing that frees the native pin.
                coordinator.releaseFormPresentation(generation)
                true
            }
        }
        var secondPresented = false
        val secondTap = launch {
            secondPresented = coordinator.exclusiveOfForms(
                presentsForm = true,
                onFormPresenting = {
                    order += "second-declined"
                    false
                },
            ) {
                order += "second-presented"
                true
            }
        }
        advanceUntilIdle()

        releaseNonForm.complete(Unit)
        nonForm.join()
        firstTap.join()
        secondTap.join()

        assertTrue(firstPresented, "the first tap must present once the queue drains")
        assertFalse(
            secondPresented,
            "the second tap must decline rather than present into a preload slot UMP just consumed",
        )
        assertEquals(
            listOf("non-form-in", "second-declined", "first-presented"),
            order,
            "the second tap must decline while queued, not run after the first form dismisses",
        )
    }

    @Test
    fun `cancellation while queued for the mutex releases the form slot`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()

        val nonForm = launch {
            coordinator.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
            }
        }
        nonFormEntered.await()

        // Claims the slot on entry and then waits for the mutex. The claim now spans that wait,
        // so its unwinding is the window this fix opened and the one worth pinning.
        var ran = false
        val queued = launch {
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                ran = true
                true
            }
        }
        advanceUntilIdle()
        queued.cancelAndJoin()

        releaseNonForm.complete(Unit)
        nonForm.join()

        assertFalse(ran, "the cancelled caller never reached the block")
        assertTrue(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a caller cancelled while queued must not strand the form slot",
        )
    }

    @Test
    fun `a non-form operation does not clear a queued form caller's claim`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()
        val formEntered = CompletableDeferred<Unit>()
        val releaseForm = CompletableDeferred<Unit>()

        // presentsForm = false, so this one holds the mutex WITHOUT claiming the slot -- and its
        // finally must therefore not clear a claim it never made. The claim now outlives the
        // mutex, so an unguarded release here would free the slot under the caller below.
        val nonForm = launch {
            coordinator.exclusiveOfForms(presentsForm = false, onFormPresenting = { false }) {
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
                true
            }
        }
        nonFormEntered.await()

        val form = launch {
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                formEntered.complete(Unit)
                // Deliberately short of the native boundary: no markFormPresented, so only the
                // slot -- not the handoff pin -- can decline the probe below.
                releaseForm.await()
                true
            }
        }
        advanceUntilIdle()

        releaseNonForm.complete(Unit)
        nonForm.join()
        formEntered.await()

        var probeDeclined = false
        var probeRan = false
        val probe = launch {
            probeDeclined = !coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                probeRan = true
                true
            }
        }
        advanceUntilIdle()

        assertTrue(
            probeDeclined,
            "a non-form operation completing must not free the slot a queued form caller claimed",
        )
        assertFalse(probeRan, "the probe must not run while a form operation owns the slot")

        releaseForm.complete(Unit)
        form.join()
        probe.join()
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
    fun `cancellation before the native boundary restores the form flag`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val formEntered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()

        // No markFormPresented: this operation died while still waiting for a host, so UMP was
        // never handed anything and nothing is on screen to protect.
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
            "cancellation short of the native boundary must restore the form flag and unlock the mutex",
        )
    }

    @Test
    fun `a cancelled caller keeps the form pinned until its callback releases it`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val formEntered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        var generation = 0L

        val job = launch {
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                generation = coordinator.beginOperation()
                coordinator.markFormPresented(generation)
                formEntered.complete(Unit)
                neverRelease.await()
                true
            }
        }
        formEntered.await()
        // Navigation, rotation, a dead viewModelScope: the waiter dies, the form does not.
        job.cancelAndJoin()

        assertFalse(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a cancelled caller must not free a form UMP is still presenting",
        )

        // UMP finally reports back, which is the only thing that means the screen is free.
        coordinator.releaseFormPresentation(generation)

        assertTrue(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "the form callback must free the slot",
        )
    }

    @Test
    fun `a superseded generation cannot release a newer form pin`() = runTest {
        val coordinator = ConsentOperationCoordinator()
        val stale = coordinator.beginOperation()
        val current = coordinator.beginOperation()
        coordinator.markFormPresented(current)

        coordinator.releaseFormPresentation(stale)

        assertFalse(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a superseded form's late callback must not free the form on screen",
        )
    }

    @Test
    fun `a stranded form pin expires at the backstop instead of declining forever`() = runTest {
        val timeSource = TestTimeSource()
        val coordinator = ConsentOperationCoordinator(timeSource)
        coordinator.markFormPresented(coordinator.beginOperation())

        timeSource += InitializationTimeouts.formPresentationPin - 1.seconds
        assertFalse(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "the pin must hold for the whole backstop window",
        )

        timeSource += 1.seconds
        assertTrue(
            coordinator.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a callback that never arrives must not decline consent forms for the life of the process",
        )
    }
}
