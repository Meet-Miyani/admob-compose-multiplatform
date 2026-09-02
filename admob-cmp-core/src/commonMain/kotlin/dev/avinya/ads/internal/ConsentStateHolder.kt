package dev.avinya.ads.internal

import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.PrivacyOptionsRequirementStatus
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
 * **Accepted limitation:** Cancelling `gatherConsent` while the UMP form is on screen releases the
 * coordinator slot even though UMP still owns the form; a newer operation can then suppress the
 * form's eventual status. This self-heals (`canRequestAds` still reconciles unconditionally, and the
 * newer operation republishes from the UMP singleton), and holding the slot across a cancelled,
 * unbounded form risks a permanent lock — a strictly worse failure.
 */
internal class ConsentStateHolder {

    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacyOptionsRequirementStatus =
        MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)

    private val coordinator = ConsentOperationCoordinator()

    val status: StateFlow<ConsentStatus> = _status.asStateFlow()
    val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> =
        _privacyOptionsRequirementStatus.asStateFlow()
    val canRequestAds: StateFlow<Boolean> = _canRequestAds.asStateFlow()

    fun beginOperation(): Long = coordinator.beginOperation()

    suspend fun <T> serialized(block: suspend () -> T): T = coordinator.serialized(block)

    suspend fun <T> exclusiveOfForms(
        presentsForm: Boolean,
        onFormPresenting: () -> T,
        block: suspend () -> T,
    ): T = coordinator.exclusiveOfForms(presentsForm, onFormPresenting, block)

    /**
     * Reconciles authoritative privacy truth unconditionally and publishes [status] if [generation]
     * is still the current operation. Returns the resulting [status] value.
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
