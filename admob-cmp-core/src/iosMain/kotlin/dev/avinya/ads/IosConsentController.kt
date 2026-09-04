@file:OptIn(ExperimentalForeignApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADMobileAds
import UserMessagingPlatform.UMPConsentForm
import UserMessagingPlatform.UMPConsentInformation
import UserMessagingPlatform.UMPConsentStatusNotRequired
import UserMessagingPlatform.UMPConsentStatusObtained
import UserMessagingPlatform.UMPConsentStatusRequired
import UserMessagingPlatform.UMPDebugGeography
import UserMessagingPlatform.UMPDebugGeographyEEA
import UserMessagingPlatform.UMPDebugGeographyOther
import UserMessagingPlatform.UMPDebugSettings
import UserMessagingPlatform.UMPPrivacyOptionsRequirementStatus
import UserMessagingPlatform.UMPPrivacyOptionsRequirementStatusNotRequired
import UserMessagingPlatform.UMPPrivacyOptionsRequirementStatusRequired
import UserMessagingPlatform.UMPRequestParameters
import dev.avinya.ads.internal.ConsentInfoUpdateOutcome
import dev.avinya.ads.internal.ConsentStateHolder
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.awaitHost
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.consentInfoUpdateTimeoutStatus
import dev.avinya.ads.internal.ownedSnapshot
import dev.avinya.ads.internal.reconcileThenResumeIfActive
import dev.avinya.ads.internal.resolveConsentInfoUpdateStatus
import dev.avinya.ads.internal.shouldResumeInitializationAfterPrivacyOptions
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class IosConsentController(
    val onCanRequestAds: suspend (AdConfig) -> Unit
) : ConsentController {
    // Kotlin initializes properties in declaration order, so the holder must exist before the
    // exposed flows delegate to it.
    private val state = ConsentStateHolder()

    override val status: StateFlow<ConsentStatus> = state.status
    override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> =
        state.privacyOptionsRequirementStatus
    override val canRequestAds: StateFlow<Boolean> = state.canRequestAds

    // Retained so showPrivacyOptions() can resume initialization if the user grants
    // consent from the privacy form after an earlier denial.
    private var lastConfig: AdConfig? = null
    // Owns the initialize() resume triggered by showPrivacyOptions() below, so a granted
    // consent decision still reaches initialize() even if the caller that awaited
    // showPrivacyOptions() was itself cancelled (navigation, view teardown) before this
    // runs. Same shape as GoogleAdManagerBase.admissionScope: process-wide, never cancelled.
    private val consentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus =
        withContext(Dispatchers.Main.immediate) {
            state.serializedExclusiveOfNativeConsentOperations(
                onBusy = {
                    ConsentStatus.Failed(
                        AdError.message("requestConsentInfoUpdate() ignored: another native consent operation is already in progress.")
                    )
                }
            ) {
                val owned = prepareConsentConfig(config)
                val outcome = performConsentInfoUpdate(
                    owned,
                    UMPConsentInformation.sharedInstance,
                    state.beginOperation(),
                )
                outcome.status
            }
        }

    private suspend fun prepareConsentConfig(config: AdConfig): AdConfig {
        val owned = config.ownedSnapshot()
        lastConfig = owned
        owned.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
        return owned
    }

    /**
     * The UMP info-update sequence, assuming the caller already holds the operation slot.
     *
     * Split out for the same reason Android's `updateWithActivity` is: [gatherConsent] must be
     * able to run an update without re-acquiring a slot it already holds. `Mutex` is not
     * reentrant, so routing [gatherConsent] back through the public [requestConsentInfoUpdate]
     * would deadlock.
     */
    private suspend fun performConsentInfoUpdate(
        config: AdConfig,
        consentInformation: UMPConsentInformation,
        generation: Long,
    ): ConsentInfoUpdateOutcome {
        state.markInfoUpdateStarted(generation)
        try {
            awaitNativeCallback(
                operation = "UMP requestConsentInfoUpdate",
                timeout = InitializationTimeouts.consentInfoUpdate
            ) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    // Kept deliberately, matching IosAdDiagnostics.openAdInspector: these continuations are captured
                    // by UMP ObjC completion blocks, and installing a cancellation handler is the established idiom
                    // here for that shape. Removing it is not a change this fix set needs to make -- if it is provably
                    // unnecessary, prove it and record the reason, do not delete it because the body looks empty.
                    continuation.invokeOnCancellation { }
                    consentInformation.requestConsentInfoUpdateWithParameters(buildParams(config)) { error ->
                        val mapped = error?.let {
                            AdError(
                                code = (it.code ?: 0).toString(),
                                message = it.localizedDescription ?: "Consent info update failed.",
                            )
                        }
                        reconcileThenResumeIfActive(continuation, Unit) {
                            state.releaseInfoUpdate(generation)
                            state.reconcileAndPublish(
                                privacyRequirement = privacyRequirementOf(consentInformation),
                                canRequestAds = consentInformation.canRequestAds,
                                status = resolveConsentInfoUpdateStatus(
                                    error = mapped,
                                    nativeStatus = consentInformationStatus(consentInformation),
                                ),
                            )
                        }
                    }
                }
            }
        } catch (timeout: NativeCallbackTimeoutException) {
            AdLogger.e("iOS UMP consent info update timed out.", timeout)
            val status = state.publishOperationStatus(generation, consentInfoUpdateTimeoutStatus(timeout.message))
            return ConsentInfoUpdateOutcome.TimedOut(status)
        }
        return ConsentInfoUpdateOutcome.Completed(state.status.value)
    }

    override suspend fun gatherConsent(config: AdConfig): ConsentStatus =
        withContext(Dispatchers.Main.immediate) {
            state.exclusiveOfForms(
                presentsForm = true,
                onFormPresenting = {
                    ConsentStatus.Failed(
                        AdError.message("gatherConsent() ignored: a consent form is already presenting.")
                    )
                },
            ) {
                val owned = prepareConsentConfig(config)
                val generation = state.beginOperation()
                val consentInformation = UMPConsentInformation.sharedInstance
                // Unlike Android, the iOS info update goes through UMPConsentInformation.sharedInstance
                // and needs no view controller, so there is no second host wait to eliminate. Only
                // the consent FORM below needs one, and it is acquired once, there. We call the
                // private performConsentInfoUpdate step directly rather than routing through the public
                // requestConsentInfoUpdate to avoid Mutex re-entrancy deadlocks on the operations slot.
                val update = performConsentInfoUpdate(owned, consentInformation, generation)
                if (update is ConsentInfoUpdateOutcome.TimedOut) return@exclusiveOfForms update.status
                // Waits rather than failing on the first null. topViewController() deliberately
                // reports null while the top controller is mid-presentation or mid-dismissal, which
                // is routine at launch — the host's Compose UIViewController is often still being
                // presented when a startup effect first runs.
                val rootVC = awaitHost(InitializationTimeouts.consentHost) { topViewController() }
                if (rootVC == null) {
                    AdLogger.w(
                        "No usable iOS root view controller for the UMP consent form after waiting " +
                            "${InitializationTimeouts.consentHost}. Consent was not gathered on this " +
                            "attempt; the host " +
                            "app should retry."
                    )
                    return@exclusiveOfForms state.publishOperationStatus(
                        generation,
                        ConsentStatus.Failed(AdError.message("No root view controller for consent form."))
                    )
                }
                // The irreversible boundary, mirroring GoogleAdManagerBase's markHandoff: past this
                // line UMP owns the form, and cancelling this coroutine no longer means the screen
                // is free. Released in the callback below, not by this coroutine's fate.
                state.markFormPresented(generation)
                val formStatus = suspendCancellableCoroutine<ConsentStatus?> { continuation ->
                    continuation.invokeOnCancellation { }
                    UMPConsentForm.loadAndPresentIfRequiredFromViewController(rootVC) { error ->
                        val errorStatus = if (error != null) {
                            AdLogger.w("UMP consent form load/show failed: code=${error.code} message=${error.localizedDescription}")
                            ConsentStatus.Failed(AdError(code = error.code.toString(), message = error.localizedDescription))
                        } else null

                        // Reconciliation must not be conditional on the caller still being around —
                        // see reconcileThenResumeIfActive's KDoc. The form was shown and the user's
                        // decision (if any) is already persisted by UMP regardless of whether
                        // gatherConsent()'s caller is still listening.
                        reconcileThenResumeIfActive(continuation, errorStatus) {
                            state.releaseFormPresentation(generation)
                            state.reconcileAndPublish(
                                privacyRequirement = privacyRequirementOf(consentInformation),
                                canRequestAds = consentInformation.canRequestAds,
                                status = consentInformationStatus(consentInformation),
                            )
                        }
                    }
                }
                formStatus ?: state.status.value
            }
        }

    override suspend fun showPrivacyOptions(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            state.exclusiveOfForms(
                presentsForm = true,
                onFormPresenting = {
                    AdLogger.w(
                        "showPrivacyOptions() ignored: another consent form is already " +
                            "presenting. Wait for it to dismiss before presenting privacy options."
                    )
                    false
                },
            ) {
                val generation = state.beginOperation()
                val rootVC = topViewController() ?: return@exclusiveOfForms false
                val consentInformation = UMPConsentInformation.sharedInstance
                // See gatherConsent: the pin belongs to UMP from here, not to this coroutine.
                state.markFormPresented(generation)
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { }
                    UMPConsentForm.presentPrivacyOptionsFormFromViewController(rootVC) { error ->
                        // The user may have changed their choices; refresh exposed state so
                        // consumers don't read stale consent / canRequestAds values. Must run even
                        // if the caller awaiting showPrivacyOptions() was cancelled — the form
                        // already closed and UMP already persisted whatever the user chose. See
                        // reconcileThenResumeIfActive's KDoc.
                        reconcileThenResumeIfActive(continuation, error == null) {
                            state.releaseFormPresentation(generation)
                            state.reconcileAndPublish(
                                privacyRequirement = privacyRequirementOf(consentInformation),
                                canRequestAds = consentInformation.canRequestAds,
                                status = consentInformationStatus(consentInformation),
                            )
                            // If the user granted consent from the privacy form after an earlier
                            // denial, resume initialization — otherwise the SDK would stay
                            // uninitialized forever. Launched on consentScope, not awaited here:
                            // this callback (and the reconciliation above) must complete
                            // regardless of whether the original caller is still around to
                            // observe it.
                            if (shouldResumeInitializationAfterPrivacyOptions(state.canRequestAds.value, lastConfig)) {
                                lastConfig?.let { config -> consentScope.launch { onCanRequestAds(config) } }
                            }
                        }
                    }
                }
            }
        }

    override suspend fun resetConsentForDebug(): Boolean = withContext(Dispatchers.Main.immediate) {
        state.serializedExclusiveOfNativeConsentOperations(
            onBusy = {
                AdLogger.w(
                    "resetConsentForDebug() ignored: a native consent operation is already in progress. " +
                        "Wait for it to finish before resetting consent."
                )
                false
            },
        ) {
            state.reset()
            UMPConsentInformation.sharedInstance.reset()
            true
        }
    }

    private fun buildParams(config: AdConfig): UMPRequestParameters {
        val params = UMPRequestParameters()
        params.tagForUnderAgeOfConsent = config.consentTagForUnderAgeOfConsent
        if (config.testMode) {
            val debug = UMPDebugSettings()
            val ids = (config.debugOptions.consentTestDeviceIds + config.testDeviceIds).distinct()
            if (ids.isNotEmpty()) debug.testDeviceIdentifiers = ids
            when (config.debugGeography) {
                ConsentDebugGeography.Eea -> debug.geography = UMPDebugGeographyEEA
                ConsentDebugGeography.NotEea -> debug.geography = UMPDebugGeographyOther
                ConsentDebugGeography.Disabled -> Unit
            }
            params.debugSettings = debug
        }
        return params
    }

    private fun privacyRequirementOf(consentInformation: UMPConsentInformation): PrivacyOptionsRequirementStatus =
        when (consentInformation.privacyOptionsRequirementStatus) {
            UMPPrivacyOptionsRequirementStatusRequired -> PrivacyOptionsRequirementStatus.Required
            UMPPrivacyOptionsRequirementStatusNotRequired -> PrivacyOptionsRequirementStatus.NotRequired
            else -> PrivacyOptionsRequirementStatus.Unknown
        }

    // Maps UMP's consent status (distinct from canRequestAds) so the public model
    // can surface NotRequired instead of collapsing everything to Obtained/Required.
    private fun consentInformationStatus(consentInformation: UMPConsentInformation): ConsentStatus =
        when (consentInformation.consentStatus) {
            UMPConsentStatusNotRequired -> ConsentStatus.NotRequired
            UMPConsentStatusObtained -> ConsentStatus.Obtained
            UMPConsentStatusRequired -> ConsentStatus.Required
            else -> ConsentStatus.Unknown
        }
}
