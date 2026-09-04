package dev.avinya.ads.internal

import dev.avinya.ads.AdLogger
import kotlin.concurrent.Volatile
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Orders the wrapper's consent operations without pretending to order UMP's.
 *
 * Two different things were previously conflated, and separating them is the whole point of
 * this class:
 *
 * 1. **Authoritative privacy state** — `canRequestAds` and the privacy-options requirement.
 *    These are READ back off the UMP singleton at callback time, so they are never stale and
 *    must be reconciled unconditionally, including from a callback that arrived after its
 *    waiter timed out. A late callback can carry a revocation; dropping it leaves the ad
 *    request gate open on stale privacy state until the next cold start.
 * 2. **Operation status** — the `ConsentStatus` a *particular* call publishes, including the
 *    synthesized `Failed(timeout)` one. This one IS ordered: a callback belonging to a
 *    superseded operation must not overwrite a newer operation's result. That is what
 *    [beginOperation]/[isCurrentOperation] enforce.
 *
 * [serializedExclusiveOfNativeConsentOperations] and [exclusiveOfForms] make the concurrency
 * contract explicit rather than delegating it to whatever the native UMP SDK happens to do about
 * a second form. UMP rejecting an overlapping form does not make the wrapper's status ordering safe.
 *
 * **Form ownership is two facts, not one.** [slotHoldsForm] covers the window between taking
 * the slot and touching UMP — that is what stops a double tap. [nativeFormHandoff] covers the
 * window UMP itself owns.
 *
 * **Native operations have two pins:** [nativeFormHandoff] (for forms) and [nativeInfoUpdate]
 * (for info updates). These pins outlive their calling coroutines: cancelling a caller does not
 * cancel the in-flight native UMP call, so a cancelled waiter is not evidence that the platform
 * is free. Only the operation's own callback — which every UMP callback reaches unconditionally
 * through [reconcileThenResumeIfActive] — or the backstop timeouts release them.
 *
 * **Threading:** every consent entry point already runs on `Dispatchers.Main.immediate`, and
 * UMP delivers its callbacks on the main thread on both platforms, so [currentGeneration] and
 * the pins are main-confined. `@Volatile` is for visibility only — matching
 * `GoogleAdManagerBase`'s own `appliedConfigIdentity` — not a substitute for that confinement.
 */
internal class ConsentOperationCoordinator(
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    @Volatile
    private var currentGeneration = 0L

    /** Set for as long as an operation holds the slot in order to present a form. */
    @Volatile
    private var slotHoldsForm = false

    /** Set from the moment UMP is handed a form until that form's callback releases it. */
    @Volatile
    private var nativeFormHandoff: FormHandoff? = null

    /** Set from the moment a native info update starts until its callback releases it. */
    @Volatile
    private var nativeInfoUpdate: InfoUpdate? = null

    /** Serializes the info-update / form sequences that may legitimately wait for each other. */
    private val operationMutex = Mutex()

    private class FormHandoff(val generation: Long, val markedAt: TimeMark)
    private class InfoUpdate(val generation: Long, val markedAt: TimeMark)

    /**
     * Claims the newest generation. Every consent entry point calls this once, up front —
     * including `resetConsentForDebug`, so a reset invalidates every outstanding callback.
     */
    fun beginOperation(): Long = ++currentGeneration

    /** Whether [generation] is still the newest operation and may therefore publish a status. */
    fun isCurrentOperation(generation: Long): Boolean = generation == currentGeneration

    /**
     * Records that UMP now owns a form on screen, on behalf of [generation].
     *
     * MUST be called immediately before the native present call and nowhere else: this is the
     * irreversible boundary, the consent-form counterpart of `GoogleAdManagerBase`'s
     * `markHandoff`. Past it, a cancelled or dead waiter no longer means "no form on screen",
     * so the slot cannot be inferred free from the coroutine's fate.
     */
    fun markFormPresented(generation: Long) {
        nativeFormHandoff = FormHandoff(generation, timeSource.markNow())
    }

    /**
     * Releases the form [generation] presented, if it is still the one on screen.
     *
     * Generation-matched so a superseded form's late callback cannot free a newer form's slot.
     * Call it from the native form callback — which runs whether or not the waiter survived —
     * not from the calling coroutine, which may already be gone.
     */
    fun releaseFormPresentation(generation: Long) {
        if (nativeFormHandoff?.generation == generation) nativeFormHandoff = null
    }

    /**
     * Records that a native info update has started, on behalf of [generation].
     */
    fun markInfoUpdateStarted(generation: Long) {
        nativeInfoUpdate = InfoUpdate(generation, timeSource.markNow())
    }

    /**
     * Releases the info update [generation] started.
     */
    fun releaseInfoUpdate(generation: Long) {
        if (nativeInfoUpdate?.generation == generation) nativeInfoUpdate = null
    }

    /**
     * Runs [block] with no other coordinated consent operation in flight, waiting if one is.
     *
     * For the info-update and consent-gathering paths, where a caller waiting a moment is
     * strictly better than a duplicate UMP round trip.
     */
    private suspend fun <T> serialized(block: suspend () -> T): T = operationMutex.withLock { block() }

    /**
     * Serialized execution that declines via [onBusy] if a native consent operation (form or info
     * update) is already in progress and has outlived its coroutine.
     */
    suspend fun <T> serializedExclusiveOfNativeConsentOperations(
        onBusy: () -> T,
        block: suspend () -> T,
    ): T {
        // Pre-lock, decline ONLY on a native form handoff -- a form can be on screen with no
        // mutex holder, so waiting for the lock would be waiting for nothing. Deliberately NOT
        // formIsLive(): slotHoldsForm means a gatherConsent holds the slot but has not presented
        // anything yet, and that operation still holds the mutex, so an info update must queue
        // behind it exactly as it always has rather than fail.
        if (liveHandoffOrNull() != null) return onBusy()

        return serialized {
            // Post-lock, re-check BOTH pins. A pin still live once the mutex has been acquired
            // can only belong to an operation whose waiter is gone (timed out or cancelled) while
            // UMP is still working -- the abandoned case, and the only one that declines. Two live
            // concurrent callers never reach here together, so they still queue and both run.
            if (liveHandoffOrNull() != null || liveInfoUpdateOrNull() != null) return@serialized onBusy()
            block()
        }
    }

    /**
     * Declines when a form-presenting operation holds the slot -- two forms cannot stack. The slot
     * is claimed by [presentsForm] on entry, NOT at the moment UMP puts a form on screen, so the
     * decline window opens while a gatherConsent is still awaiting a host and running its bounded
     * info update. That is deliberate: freeing the slot for that window would let a second caller
     * reach markFormPresented first and race two presentations.
     *
     * An operation that does not present a form never claims the slot (presentsForm = false: a
     * consent info update, at most
     * InitializationTimeouts.consentInfoUpdate), and ordering behind it is not a reason to fail:
     * the app UI stays interactive during that window, so a user who opens Settings during the
     * launch-time refresh must get the form, not "unavailable". Callers surface the false return
     * to a person -- see DiagnosticsTab, ProfileViewModel, PrivacyLabScreen -- so a spurious
     * decline is a spurious error message.
     */
    suspend fun <T> exclusiveOfForms(
        presentsForm: Boolean,
        onFormPresenting: () -> T,
        block: suspend () -> T,
    ): T {
        if (formIsLive()) return onFormPresenting()
        return operationMutex.withLock {
            // The second check is REQUIRED: a cancelled operation releases this mutex while UMP
            // is still presenting its form, so holding the lock no longer proves the screen is
            // free. A waiter that queued behind such an operation must decline here rather than
            // present a second form over the first.
            if (liveHandoffOrNull() != null) return@withLock onFormPresenting()
            if (presentsForm) slotHoldsForm = true
            try {
                block()
            } finally {
                // Only the slot fact unwinds with the coroutine. A handoff already made to UMP
                // survives it and is released by the form's own callback.
                slotHoldsForm = false
            }
        }
    }

    private fun formIsLive(): Boolean = slotHoldsForm || liveHandoffOrNull() != null

    /**
     * The handoff still believed to be on screen, or null once it has expired.
     *
     * UMP invoking a form callback is what normally frees the slot. Bounding the wait anyway is
     * the same reasoning [InitializationTimeouts.formPresentationPin] carries: a callback that
     * never arrives must not decline every consent operation for the rest of the process.
     */
    private fun liveHandoffOrNull(): FormHandoff? {
        val handoff = nativeFormHandoff ?: return null
        if (handoff.markedAt.elapsedNow() < InitializationTimeouts.formPresentationPin) return handoff
        AdLogger.w(
            "A UMP consent form was presented over ${InitializationTimeouts.formPresentationPin} ago " +
                "and never reported back. Releasing the consent form slot; if that form is still on " +
                "screen, a further consent operation may be refused by UMP itself."
        )
        nativeFormHandoff = null
        return null
    }

    private fun liveInfoUpdateOrNull(): InfoUpdate? {
        val infoUpdate = nativeInfoUpdate ?: return null
        if (infoUpdate.markedAt.elapsedNow() < InitializationTimeouts.infoUpdatePin) return infoUpdate
        AdLogger.w(
            "A native consent info update started over ${InitializationTimeouts.infoUpdatePin} ago " +
                "and never reported back. Releasing the info update slot."
        )
        nativeInfoUpdate = null
        return null
    }
}
