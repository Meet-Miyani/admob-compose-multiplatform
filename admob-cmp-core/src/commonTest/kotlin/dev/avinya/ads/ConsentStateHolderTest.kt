package dev.avinya.ads

import dev.avinya.ads.internal.ConsentStateHolder
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
class ConsentStateHolderTest {

    @Test
    fun `reconcileAndPublish updates all three flows for the current generation`() {
        val holder = ConsentStateHolder()
        val generation = holder.beginOperation()

        val result = holder.reconcileAndPublish(
            generation = generation,
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Required,
        )

        assertEquals(ConsentStatus.Required, result)
        assertEquals(ConsentStatus.Required, holder.status.value)
        assertEquals(PrivacyOptionsRequirementStatus.Required, holder.privacyOptionsRequirementStatus.value)
        assertTrue(holder.canRequestAds.value)
    }

    @Test
    fun `reconcileAndPublish reconciles truth for a superseded generation but does not publish its status`() {
        val holder = ConsentStateHolder()
        val superseded = holder.beginOperation()
        val current = holder.beginOperation()

        holder.publishOperationStatus(current, ConsentStatus.Obtained)

        val result = holder.reconcileAndPublish(
            generation = superseded,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Required,
        )

        assertEquals(ConsentStatus.Obtained, result, "the result is what status actually holds, not the dropped value")
        assertEquals(
            ConsentStatus.Obtained,
            holder.status.value,
            "a superseded operation's status must never overwrite the current operation's",
        )
        // Truth is NOT gated: the callback reads it from the UMP singleton, so it is current
        // however late the callback is, and it can carry a revocation.
        assertEquals(PrivacyOptionsRequirementStatus.NotRequired, holder.privacyOptionsRequirementStatus.value)
        assertTrue(holder.canRequestAds.value)
    }


    @Test
    fun `a late callback on the same still-current generation replaces a timeout status`() {
        val holder = ConsentStateHolder()
        val generation = holder.beginOperation()

        // Waiter timed out and published Failed(timeout)...
        holder.publishOperationStatus(generation, ConsentStatus.Failed(AdError.message("timed out")))
        assertTrue(holder.status.value is ConsentStatus.Failed)

        // ...and later the callback for the SAME still-current generation completes with Obtained.
        val result = holder.reconcileAndPublish(
            generation = generation,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        assertEquals(ConsentStatus.Obtained, result)
        assertEquals(ConsentStatus.Obtained, holder.status.value)
        assertEquals(PrivacyOptionsRequirementStatus.NotRequired, holder.privacyOptionsRequirementStatus.value)
        assertTrue(holder.canRequestAds.value)
    }

    @Test
    fun `a revocation observed by reconcileAndPublish sets canRequestAds false regardless of generation`() {
        val holder = ConsentStateHolder()
        val first = holder.beginOperation()
        holder.reconcileAndPublish(
            generation = first,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )
        assertTrue(holder.canRequestAds.value)

        val current = holder.beginOperation()
        holder.publishOperationStatus(current, ConsentStatus.Obtained)

        // Stale callback from `first` observes a revocation on the UMP singleton.
        holder.reconcileAndPublish(
            generation = first,
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = false,
            status = ConsentStatus.Required,
        )

        assertFalse(holder.canRequestAds.value, "revocation must close ad request gate immediately")
        assertEquals(PrivacyOptionsRequirementStatus.Required, holder.privacyOptionsRequirementStatus.value)
        assertEquals(
            ConsentStatus.Obtained,
            holder.status.value,
            "truth is ungated, but a superseded operation's STATUS must not overwrite the current one's",
        )
    }

    @Test
    fun `reset claims a fresh generation and clears all three flows`() {
        val holder = ConsentStateHolder()
        val outstanding = holder.beginOperation()
        holder.reconcileAndPublish(
            generation = outstanding,
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        holder.reset()

        assertEquals(ConsentStatus.Unknown, holder.status.value)
        assertEquals(PrivacyOptionsRequirementStatus.Unknown, holder.privacyOptionsRequirementStatus.value)
        assertFalse(holder.canRequestAds.value)

        // A callback outstanding across a reset must not resurrect pre-reset status
        holder.publishOperationStatus(outstanding, ConsentStatus.Obtained)
        assertEquals(ConsentStatus.Unknown, holder.status.value)
    }

    @Test
    fun `serialized orders two blocks`() = runTest {
        val holder = ConsentStateHolder()
        val order = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            holder.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
                order += "first-in"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-out"
            }
        }
        firstEntered.await()
        val second = launch { holder.serializedExclusiveOfNativeConsentOperations(onBusy = {}) { order += "second-in" } }
        advanceUntilIdle()

        assertEquals(listOf("first-in"), order, "the second block must not overlap the first")
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first-in", "first-out", "second-in"), order)
    }

    @Test
    fun `exclusiveOfForms declines against a live form without running its block`() = runTest {
        val holder = ConsentStateHolder()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondRan = false

        val first = launch {
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                true
            }
        }
        firstEntered.await()

        val declined = holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
            secondRan = true
            true
        }

        assertFalse(declined, "an overlapping form presentation must decline")
        assertFalse(secondRan, "the declined path must not run the block")
        releaseFirst.complete(Unit)
        first.join()
    }

    @Test
    fun `exclusiveOfForms waits for a non-form holder and then runs`() = runTest {
        val holder = ConsentStateHolder()
        val order = mutableListOf<String>()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()

        val nonForm = launch {
            holder.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
                order += "non-form-in"
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
                order += "non-form-out"
            }
        }
        nonFormEntered.await()

        val form = launch {
            val ran = holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
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
        val holder = ConsentStateHolder()
        assertTrue(holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true })
        assertTrue(
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a completed form operation must not leave the form flag stuck",
        )
    }

    @Test
    fun `exclusiveOfForms restores the form flag after a throw`() = runTest {
        val holder = ConsentStateHolder()
        runCatching {
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                error("form blew up")
            }
        }
        assertTrue(
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "a throwing form operation must restore the form flag",
        )
    }

    @Test
    fun `cancellation before the native boundary restores the form flag`() = runTest {
        val holder = ConsentStateHolder()
        val formEntered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()

        val job = launch {
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                formEntered.complete(Unit)
                neverRelease.await()
                true
            }
        }
        formEntered.await()
        job.cancelAndJoin()

        assertTrue(
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "cancellation short of the native boundary must restore the form flag and unlock the mutex",
        )
    }

    /**
     * Cancelling gatherConsent does not dismiss the form the user is looking at, so the holder must
     * keep the slot pinned to it: nothing else may touch UMP -- least of all a reset -- until that
     * form's own callback reports back. This is the delegation the controllers depend on.
     */
    @Test
    fun `a cancelled in-flight form keeps the slot until its callback releases it`() = runTest {
        val holder = ConsentStateHolder()
        val formEntered = CompletableDeferred<Unit>()
        val neverRelease = CompletableDeferred<Unit>()
        var formGeneration = 0L
        var resetRan = false

        val job = launch {
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                formGeneration = holder.beginOperation()
                holder.markFormPresented(formGeneration)
                formEntered.complete(Unit)
                neverRelease.await()
                true
            }
        }
        formEntered.await()
        job.cancelAndJoin()

        val resetResult = holder.exclusiveOfForms(presentsForm = false, onFormPresenting = { false }) {
            resetRan = true
            holder.reset()
            true
        }
        assertFalse(resetResult, "a reset must not run under a form UMP is still presenting")
        assertFalse(resetRan, "reset block must not execute when declined")

        holder.releaseFormPresentation(formGeneration)

        assertTrue(
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true },
            "the form callback must free the slot",
        )
    }

    @Test
    fun `a superseded form callback reconciles privacy truth without publishing its status`() {
        val holder = ConsentStateHolder()
        val formGeneration = holder.beginOperation()
        val newerOperationGen = holder.beginOperation()

        // Newer operation finishes first and sets status
        holder.publishOperationStatus(newerOperationGen, ConsentStatus.NotRequired)

        // In-flight form eventually calls back on its older generation
        holder.reconcileAndPublish(
            generation = formGeneration,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        // Truth moves; the superseded status does not.
        assertTrue(holder.canRequestAds.value)
        assertEquals(PrivacyOptionsRequirementStatus.NotRequired, holder.privacyOptionsRequirementStatus.value)
        assertEquals(
            ConsentStatus.NotRequired,
            holder.status.value,
            "a superseded form callback must not overwrite the newer operation's status",
        )
    }

    @Test
    fun `reset declines while a form holds the slot`() = runTest {
        val holder = ConsentStateHolder()
        val formEntered = CompletableDeferred<Unit>()
        val releaseForm = CompletableDeferred<Unit>()
        var resetRan = false

        val form = launch {
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) {
                formEntered.complete(Unit)
                releaseForm.await()
                true
            }
        }
        formEntered.await()

        val resetResult = holder.serializedExclusiveOfNativeConsentOperations(
            onBusy = { false },
            declineWhileFormSlotHeld = true,
        ) {
            resetRan = true
            holder.reset()
            true
        }

        assertFalse(resetResult, "reset must decline while a form is presenting")
        assertFalse(resetRan, "reset block must not execute when declined")

        releaseForm.complete(Unit)
        form.join()
    }

    @Test
    fun `reset declines to a queued form caller that only holds the slot`() = runTest {
        val holder = ConsentStateHolder()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()
        var resetRan = false

        // Holds the mutex WITHOUT claiming the form slot, so the form caller below claims the slot
        // on entry and then queues. That is the window the mutex cannot cover on its own: nothing
        // is on screen, no handoff pin exists, and reset can legitimately win the lock first.
        val nonForm = launch {
            holder.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
            }
        }
        nonFormEntered.await()

        val form = launch {
            holder.exclusiveOfForms(presentsForm = true, onFormPresenting = { false }) { true }
        }
        advanceUntilIdle()

        val resetResult = holder.serializedExclusiveOfNativeConsentOperations(
            onBusy = { false },
            declineWhileFormSlotHeld = true,
        ) {
            resetRan = true
            holder.reset()
            true
        }

        assertFalse(resetResult, "reset must decline to a form caller that has claimed the slot")
        assertFalse(resetRan, "reset must not wipe UMP consent under a form that is about to present")

        releaseNonForm.complete(Unit)
        nonForm.join()
        form.join()
    }

    @Test
    fun `reset waits for a non-form holder and then runs`() = runTest {
        val holder = ConsentStateHolder()
        val order = mutableListOf<String>()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()

        val nonForm = launch {
            holder.serializedExclusiveOfNativeConsentOperations(onBusy = {}) {
                order += "non-form-in"
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
                order += "non-form-out"
            }
        }
        nonFormEntered.await()

        val reset = launch {
            val ran = holder.serializedExclusiveOfNativeConsentOperations(
                onBusy = { false },
                declineWhileFormSlotHeld = true,
            ) {
                order += "reset-ran"
                holder.reset()
                true
            }
            assertTrue(ran, "reset must run after non-form operation finishes")
        }
        advanceUntilIdle()

        assertEquals(listOf("non-form-in"), order, "reset must wait for non-form operation rather than declining")
        releaseNonForm.complete(Unit)
        nonForm.join()
        reset.join()
        assertEquals(listOf("non-form-in", "non-form-out", "reset-ran"), order)
        assertEquals(ConsentStatus.Unknown, holder.status.value)
    }

    @Test
    fun `reset still invalidates an outstanding generation`() = runTest {
        val holder = ConsentStateHolder()
        val outstanding = holder.beginOperation()
        holder.reconcileAndPublish(
            generation = outstanding,
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        holder.serializedExclusiveOfNativeConsentOperations(
            onBusy = { false },
            declineWhileFormSlotHeld = true,
        ) {
            holder.reset()
            true
        }

        assertEquals(ConsentStatus.Unknown, holder.status.value)
        assertEquals(PrivacyOptionsRequirementStatus.Unknown, holder.privacyOptionsRequirementStatus.value)
        assertFalse(holder.canRequestAds.value)

        // An outstanding generation cannot publish status after reset
        holder.publishOperationStatus(outstanding, ConsentStatus.Obtained)
        assertEquals(ConsentStatus.Unknown, holder.status.value)

        // reset() claims a fresh generation, so the outstanding one is superseded: its late
        // callback still reconciles truth, but it cannot republish a pre-reset status.
        holder.reconcileAndPublish(
            generation = outstanding,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )
        assertEquals(ConsentStatus.Unknown, holder.status.value, "a pre-reset generation cannot republish")
        assertEquals(PrivacyOptionsRequirementStatus.NotRequired, holder.privacyOptionsRequirementStatus.value)
        assertTrue(holder.canRequestAds.value, "truth reconciliation survives the reset")
    }
}
