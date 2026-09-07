package dev.avinya.ads.internal

import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.PrivacyOptionsRequirementStatus
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
internal class ConsentStateHolder(timeSource: TimeSource = TimeSource.Monotonic) {

    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacyOptionsRequirementStatus =
        MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)

    private val coordinator = ConsentOperationCoordinator(timeSource)

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
     * Invalidates all outstanding operations by claiming a new generation, and clears all three flows.
     */
    fun reset() {
        coordinator.beginOperation()
        _status.value = ConsentStatus.Unknown
        _privacyOptionsRequirementStatus.value = PrivacyOptionsRequirementStatus.Unknown
        _canRequestAds.value = false
    }
}
