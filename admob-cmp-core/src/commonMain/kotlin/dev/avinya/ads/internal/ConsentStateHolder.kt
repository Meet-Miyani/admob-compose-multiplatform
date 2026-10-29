package dev.avinya.ads.internal

import dev.avinya.ads.AdLogger
import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.PrivacyOptionsRequirementStatus
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single shared owner of consent state across Android and iOS.
 *
 * The mutable flows are private and there is deliberately no reconcile-only entry point.
 * [reconcileAndPublish] is the ONLY way a native callback can update this state, so a platform
 * cannot refresh privacy truth while silently skipping the status — which is exactly how the two
 * controllers diverged when each owned its own copy of these helpers. Synthesized statuses that
 * have no callback behind them (a wrapper timeout, an unavailable host) go through
 * [publishOperationStatus]; there is no third way in.
 *
 * **Threading:** every consent entry point already runs on `Dispatchers.Main.immediate`, and
 * UMP delivers its callbacks on the main thread on both platforms, so these writes are
 * main-confined.
 *
 * **Form ownership outlives the caller.** Cancelling `gatherConsent` or `showPrivacyOptions` does
 * not dismiss the form the user is looking at, so the slot stays pinned to it — see
 * [ConsentOperationCoordinator.markFormPresented]. Nothing else touches UMP until that form's own
 * callback releases the pin, which it does whether or not the original waiter survived.
 *
 * **Accepted limitation:** a UMP form callback that never fires at all strands the pin, and every
 * consent operation is refused until [InitializationTimeouts.formPresentationPin] expires. That
 * window is the deliberate trade: an unbounded pin would refuse them for the life of the process,
 * and no pin at all would let a reset run `UMPConsentInformation.reset()` under a live form.
 */
internal class ConsentStateHolder(private val timeSource: TimeSource = TimeSource.Monotonic) {

    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacyOptionsRequirementStatus =
        MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)

    private val coordinator = ConsentOperationCoordinator(timeSource)

    // When an info update last reported success in this process. Main-confined like the rest of
    // this class. Only a success counts: after an error or a wrapper timeout, UMP has not
    // re-confirmed anything, so the next caller that needs this launch's update must ask again.
    private var lastSuccessfulInfoUpdate: TimeMark? = null

    val status: StateFlow<ConsentStatus> = _status.asStateFlow()
    val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> =
        _privacyOptionsRequirementStatus.asStateFlow()
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    fun beginOperation(): Long = coordinator.beginOperation()

    suspend fun <T> serializedExclusiveOfNativeConsentOperations(
        onBusy: () -> T,
        declineWhileFormSlotHeld: Boolean = false,
        block: suspend () -> T,
    ): T = coordinator.serializedExclusiveOfNativeConsentOperations(
        onBusy = onBusy,
        declineWhileFormSlotHeld = declineWhileFormSlotHeld,
        block = block,
    )

    suspend fun <T> exclusiveOfForms(
        presentsForm: Boolean,
        onFormPresenting: () -> T,
        block: suspend () -> T,
    ): T = coordinator.exclusiveOfForms(presentsForm, onFormPresenting, block)

    /** See [ConsentOperationCoordinator.markFormPresented]. */
    fun markFormPresented(generation: Long): Unit = coordinator.markFormPresented(generation)

    /** See [ConsentOperationCoordinator.releaseFormPresentation]. */
    fun releaseFormPresentation(generation: Long): Unit = coordinator.releaseFormPresentation(generation)

    /** See [ConsentOperationCoordinator.markInfoUpdateStarted]. */
    fun markInfoUpdateStarted(generation: Long): Unit = coordinator.markInfoUpdateStarted(generation)

    /** See [ConsentOperationCoordinator.releaseInfoUpdate]. */
    fun releaseInfoUpdate(generation: Long): Unit = coordinator.releaseInfoUpdate(generation)

    /** See [ConsentOperationCoordinator.isInfoUpdateOutstanding]. */
    fun isInfoUpdateOutstanding(generation: Long): Boolean = coordinator.isInfoUpdateOutstanding(generation)

    /** Records that a native info update reported success just now. Call from its success callback. */
    fun recordSuccessfulInfoUpdate() {
        lastSuccessfulInfoUpdate = timeSource.markNow()
    }

    /**
     * How long ago an info update last succeeded in this process, or null if none has.
     *
     * UMP asks for one update per app launch. `initialize(…, InitializeOnlyIfAlreadyAllowed)`
     * uses this to reuse the update `gatherConsent` or `requestConsentInfoUpdate` already made,
     * instead of a second network round trip in a row.
     */
    fun successfulInfoUpdateAge(): Duration? = lastSuccessfulInfoUpdate?.elapsedNow()

    /**
     * Both controllers' info-update entry point: [update] runs serialized against every other
     * native consent operation, or, with [reuseThisLaunch], an update that already succeeded in
     * this process is reused and no native call is made. [onReused] runs then, in place of the
     * config bookkeeping [update] would have done.
     *
     * Reuse is decided under the lock, after any in-flight `gatherConsent` has finished, since
     * that is the update this call would otherwise repeat; and again when a pin left by an
     * abandoned operation declines the call, since a pin stops a native call and reusing makes
     * none. [onBusy] answers only when there is nothing to reuse.
     */
    suspend fun serializedInfoUpdate(
        reuseThisLaunch: Boolean,
        onReused: () -> Unit,
        onBusy: () -> ConsentStatus,
        update: suspend () -> ConsentStatus,
    ): ConsentStatus {
        val reused = { if (reuseThisLaunch) reusedInfoUpdateOrNull(onReused) else null }
        return serializedExclusiveOfNativeConsentOperations(onBusy = { reused() ?: onBusy() }) {
            reused() ?: update()
        }
    }

    private fun reusedInfoUpdateOrNull(onReused: () -> Unit): ConsentStatus? {
        val age = successfulInfoUpdateAge() ?: return null
        AdLogger.i("Reusing the UMP consent info update that finished $age ago; no second request.")
        onReused()
        return _status.value
    }

    /** One line per native info update that reports back, on both platforms. */
    fun logInfoUpdateFinished(platformTag: String, elapsed: Duration, status: ConsentStatus) {
        AdLogger.i(
            "$platformTag UMP consent info update finished in ${elapsed.inWholeMilliseconds} ms. " +
                "status=$status canRequestAds=${_canRequestAds.value}"
        )
    }

    /**
     * Reconciles authoritative privacy truth UNCONDITIONALLY, then publishes [status] only if
     * [generation] is still the current operation. Returns the value [status] now holds.
     *
     * The two halves are gated differently, on purpose.
     *
     * **Truth is never dropped.** [privacyRequirement] and [canRequestAds] are read from the UMP
     * singleton by the callback itself, so they are current however late the callback is -- and a
     * late callback can carry a revocation. Dropping it would leave the admission gate open on
     * stale truth, so these publish whether or not this operation was superseded.
     *
     * **Status is ordered.** [status] is NOT truth in that sense: it describes the outcome of ONE
     * operation, and `resolveConsentInfoUpdateStatus` collapses any native error into
     * `Failed(error)`. Publishing that unconditionally lets a superseded operation's stale error
     * land on top of a newer operation's success -- e.g. a refresh that times out at
     * `consentInfoUpdate`, a retry that succeeds, and then the first call's late error callback
     * arriving last and overwriting the success with `Failed`. An older operation's failure
     * must not overwrite a newer operation's success. The generation gate is what prevents
     * that, and it is not interchangeable with the unconditional reconcile above.
     */
    fun reconcileAndPublish(
        generation: Long,
        privacyRequirement: PrivacyOptionsRequirementStatus,
        canRequestAds: Boolean,
        status: ConsentStatus,
    ): ConsentStatus {
        _privacyOptionsRequirementStatus.value = privacyRequirement
        _canRequestAds.value = canRequestAds
        if (coordinator.isCurrentOperation(generation)) {
            _status.value = status
        }
        return _status.value
    }

    /**
     * Publishes a synthesized status (e.g. timeout or missing host) under the generation gate.
     * Returns the [status] passed in.
     */
    fun publishOperationStatus(generation: Long, status: ConsentStatus): ConsentStatus {
        if (coordinator.isCurrentOperation(generation)) {
            _status.value = status
        }
        return status
    }

    /**
     * Invalidates all outstanding operations by claiming a new generation, clears all three
     * flows, and forgets the last successful info update.
     */
    fun reset() {
        coordinator.beginOperation()
        lastSuccessfulInfoUpdate = null
        _status.value = ConsentStatus.Unknown
        _privacyOptionsRequirementStatus.value = PrivacyOptionsRequirementStatus.Unknown
        _canRequestAds.value = false
    }
}
