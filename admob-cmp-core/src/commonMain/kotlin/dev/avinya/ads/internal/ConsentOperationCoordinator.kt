package dev.avinya.ads.internal

import kotlin.concurrent.Volatile
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
 * [serialized] and [exclusiveOfForms] then make the concurrency contract explicit rather than
 * delegating it to whatever the native UMP SDK happens to do about a second form. UMP
 * rejecting an overlapping form does not make the wrapper's status ordering safe.
 *
 * **Threading:** every consent entry point already runs on `Dispatchers.Main.immediate`, and
 * UMP delivers its callbacks on the main thread on both platforms, so [currentGeneration] is
 * main-confined. `@Volatile` is for visibility only — matching `GoogleAdManagerBase`'s own
 * `appliedConfigIdentity` — not a substitute for that confinement.
 */
internal class ConsentOperationCoordinator {

    @Volatile
    private var currentGeneration = 0L

    @Volatile
    private var presentingForm = false

    /** Serializes the info-update / form sequences that may legitimately wait for each other. */
    private val operationMutex = Mutex()

    /**
     * Claims the newest generation. Every consent entry point calls this once, up front —
     * including `resetConsentForDebug`, so a reset invalidates every outstanding callback.
     */
    fun beginOperation(): Long = ++currentGeneration

    /** Whether [generation] is still the newest operation and may therefore publish a status. */
    fun isCurrentOperation(generation: Long): Boolean = generation == currentGeneration

    /**
     * Runs [block] with no other coordinated consent operation in flight, waiting if one is.
     *
     * For the info-update and consent-gathering paths, where a caller waiting a moment is
     * strictly better than a duplicate UMP round trip.
     */
    suspend fun <T> serialized(block: suspend () -> T): T = operationMutex.withLock { block() }

    /**
     * Declines ONLY when a UMP form is already on screen -- two forms cannot stack. Anything else
     * holding the slot is a bounded, non-interactive operation (a consent info update, at most
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
        if (presentingForm) return onFormPresenting()
        return operationMutex.withLock {
            // No second check: presentingForm is only ever set inside this lock and restored in
            // the finally below, so holding the lock already proves it is false here.
            if (presentsForm) presentingForm = true
            try {
                block()
            } finally {
                presentingForm = false
            }
        }
    }
}
