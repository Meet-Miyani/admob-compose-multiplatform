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
    fun `reconcileAndPublish updates privacy state for superseded generation while leaving status untouched`() {
        val holder = ConsentStateHolder()
        val stale = holder.beginOperation()
        val current = holder.beginOperation()

        holder.publishOperationStatus(current, ConsentStatus.Obtained)

        // Reconciliation is NOT generation-gated: it reads the UMP singleton, so it is current
        // no matter which operation's callback observed it. The STATUS is gated, so the stale
        // callback must reconcile privacy state without republishing its own status.
        val result = holder.reconcileAndPublish(
            generation = stale,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Required,
        )

        assertEquals(ConsentStatus.Obtained, result, "result should be current status value")
        assertEquals(ConsentStatus.Obtained, holder.status.value, "superseded status must not overwrite current status")
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
        val initial = holder.beginOperation()
        holder.reconcileAndPublish(
            generation = initial,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )
        assertTrue(holder.canRequestAds.value)

        val stale = holder.beginOperation()
        val current = holder.beginOperation()
        holder.publishOperationStatus(current, ConsentStatus.Obtained)

        // Stale callback observes revocation
        holder.reconcileAndPublish(
            generation = stale,
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = false,
            status = ConsentStatus.Required,
        )

        assertFalse(holder.canRequestAds.value, "revocation must close ad request gate immediately")
        assertEquals(PrivacyOptionsRequirementStatus.Required, holder.privacyOptionsRequirementStatus.value)
        assertEquals(ConsentStatus.Obtained, holder.status.value, "superseded status must not overwrite current status")
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
            holder.serialized {
                order += "first-in"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-out"
            }
        }
        firstEntered.await()
        val second = launch { holder.serialized { order += "second-in" } }
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
            holder.serialized {
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
    fun `exclusiveOfForms restores the form flag after cancellation`() = runTest {
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
            "cancellation must restore the form flag and unlock the mutex",
        )
    }

    /**
     * Accepted limitation (see plan): Cancelling gatherConsent while the UMP form is on screen
     * releases the coordinator slot even though UMP still owns the form; a newer operation can then
     * suppress the form's eventual status. This self-heals (canRequestAds still reconciles unconditionally,
     * and the newer operation republishes from the UMP singleton), and holding the slot across a
     * cancelled, unbounded form risks a permanent lock — a strictly worse failure.
     *
     * This test pins that after beginOperation() twice the older generation can still reconcile
     * privacy truth, but cannot publish its status over the newer operation.
     */
    @Test
    fun `a slot released by cancellation lets a newer operation supersede an in-flight form`() {
        val holder = ConsentStateHolder()
        val inFlightFormGen = holder.beginOperation()
        val newerOperationGen = holder.beginOperation()

        // Newer operation finishes first and sets status
        holder.publishOperationStatus(newerOperationGen, ConsentStatus.NotRequired)

        // In-flight form eventually calls back on its older generation
        holder.reconcileAndPublish(
            generation = inFlightFormGen,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        // Privacy state updated
        assertTrue(holder.canRequestAds.value)
        assertEquals(PrivacyOptionsRequirementStatus.NotRequired, holder.privacyOptionsRequirementStatus.value)
        // But status is NOT overwritten by the older form callback
        assertEquals(
            ConsentStatus.NotRequired,
            holder.status.value,
            "superseded form callback must not overwrite newer operation's status",
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

        val resetResult = holder.exclusiveOfForms(
            presentsForm = false,
            onFormPresenting = { false },
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
    fun `reset waits for a non-form holder and then runs`() = runTest {
        val holder = ConsentStateHolder()
        val order = mutableListOf<String>()
        val nonFormEntered = CompletableDeferred<Unit>()
        val releaseNonForm = CompletableDeferred<Unit>()

        val nonForm = launch {
            holder.serialized {
                order += "non-form-in"
                nonFormEntered.complete(Unit)
                releaseNonForm.await()
                order += "non-form-out"
            }
        }
        nonFormEntered.await()

        val reset = launch {
            val ran = holder.exclusiveOfForms(
                presentsForm = false,
                onFormPresenting = { false },
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
    fun `reset inside exclusiveOfForms still invalidates an outstanding generation`() = runTest {
        val holder = ConsentStateHolder()
        val outstanding = holder.beginOperation()
        holder.reconcileAndPublish(
            generation = outstanding,
            privacyRequirement = PrivacyOptionsRequirementStatus.Required,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )

        holder.exclusiveOfForms(
            presentsForm = false,
            onFormPresenting = { false },
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

        // An outstanding generation reconcileAndPublish cannot overwrite Unknown status
        holder.reconcileAndPublish(
            generation = outstanding,
            privacyRequirement = PrivacyOptionsRequirementStatus.NotRequired,
            canRequestAds = true,
            status = ConsentStatus.Obtained,
        )
        assertEquals(ConsentStatus.Unknown, holder.status.value)
    }
}
