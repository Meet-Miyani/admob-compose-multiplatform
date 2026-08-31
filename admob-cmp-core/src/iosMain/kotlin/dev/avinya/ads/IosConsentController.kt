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
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.awaitHost
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.ownedSnapshot
import dev.avinya.ads.internal.reconcileThenResumeIfActive

internal class IosConsentController(
    val onCanRequestAds: suspend (AdConfig) -> Unit
) : ConsentController {
    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacy = MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)
    // Retained so showPrivacyOptions() can resume initialization if the user grants
    // consent from the privacy form after an earlier denial.
    private var lastConfig: AdConfig? = null
    // Owns the initialize() resume triggered by showPrivacyOptions() below, so a granted
    // consent decision still reaches initialize() even if the caller that awaited
    // showPrivacyOptions() was itself cancelled (navigation, view teardown) before this
    // runs. Same shape as GoogleAdManagerBase.admissionScope: process-wide, never cancelled.
    private val consentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val status: StateFlow<ConsentStatus> = _status
    override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> = _privacy
    override val canRequestAds: StateFlow<Boolean> = _canRequestAds

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus = withContext(Dispatchers.Main.immediate) {
        // Owned snapshot, matching AdManager.initialize(): lastConfig outlives this call and is
        // reused if showPrivacyOptions() later resumes initialization, so retaining the caller's
        // object let post-call mutation of its lists/hooks change UMP debug IDs and hook execution.
        val config = config.ownedSnapshot()
        lastConfig = config
        config.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
        val consentInformation = UMPConsentInformation.sharedInstance
        // MUST stay bounded: this is a non-interactive network round trip, and UMP can accept the
        // call and never call back, which hangs consent admission -- and therefore ad serving --
        // indefinitely.
        //
        // On timeout the status becomes Failed, but [canRequestAds] is deliberately NOT reset: it
        // keeps whatever the last COMPLETED refresh established. On a first run that is false, so a
        // cold start still admits nothing on an unknown consent state. On a later run it may be a
        // previously granted true, and a dropped network round trip is not evidence that consent was
        // withdrawn -- revoking admission on a blip would stop ad serving until the next successful
        // refresh, for no gain in consent correctness. Consent itself has not changed; only our
        // ability to re-confirm it has, and UMP has already persisted the user's actual choice.
        //
        // The consent FORM and privacy options form stay unbounded on purpose; a person is reading
        // those.
        try {
            awaitNativeCallback(
                operation = "UMP requestConsentInfoUpdate",
                timeout = InitializationTimeouts.consentInfoUpdate
            ) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { }
                    consentInformation.requestConsentInfoUpdateWithParameters(buildParams(config)) { error ->
                        // Deliberately still gated on isActive, NOT routed through
                        // reconcileThenResumeIfActive: this is the bounded, non-interactive path
                        // covered by the huge comment above -- canRequestAds/status must NOT be
                        // reconciled from a callback that arrives after awaitNativeCallback's
                        // internal withTimeoutOrNull has already cancelled this continuation and
                        // published ConsentStatus.Failed(timeout) below. Doing so unconditionally
                        // would let a late callback silently overwrite that terminal Failed status
                        // with a stale success, diverging from AndroidConsentController's
                        // equivalent path (updateWithActivity), which the comment above this
                        // function requires stay identical.
                        if (continuation.isActive) {
                            updatePrivacyState(consentInformation)
                            _canRequestAds.value = consentInformation.canRequestAds
                            val result = if (error == null) {
                                consentInformationStatus(consentInformation)
                            } else if (consentInformation.canRequestAds) {
                                consentInformationStatus(consentInformation)
                            } else {
                                ConsentStatus.Failed(AdError(code = (error.code ?: 0).toString(), message = error.localizedDescription ?: "Consent info update failed."))
                            }
                            _status.value = result
                            continuation.resume(Unit)
                        }
                    }
                }
            }
        } catch (timeout: NativeCallbackTimeoutException) {
            AdLogger.e("iOS UMP consent info update timed out.", timeout)
            _status.value = ConsentStatus.Failed(
                AdError.message(timeout.message ?: "UMP consent info update timed out.")
            )
        }
        _status.value
    }

    override suspend fun gatherConsent(config: AdConfig): ConsentStatus = withContext(Dispatchers.Main.immediate) {
        val consentInformation = UMPConsentInformation.sharedInstance
        // Unlike Android, this DOES route through the public requestConsentInfoUpdate. That is not
        // an oversight and should not be "harmonised": the iOS info update goes through
        // UMPConsentInformation.sharedInstance and needs no view controller, so there is no second
        // host wait to eliminate. Only the consent FORM below needs one, and it is acquired once,
        // there. On Android the equivalent call does need an Activity, which is why that platform
        // hoists the acquisition instead.
        val update = requestConsentInfoUpdate(config)
        if (update is ConsentStatus.Failed && !consentInformation.canRequestAds) return@withContext update
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
            _status.value = ConsentStatus.Failed(AdError.message("No root view controller for consent form."))
            return@withContext _status.value
        }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { }
            UMPConsentForm.loadAndPresentIfRequiredFromViewController(rootVC) {
                // Reconciliation must not be conditional on the caller still being around —
                // see reconcileThenResumeIfActive's KDoc. The form was shown and the user's
                // decision (if any) is already persisted by UMP regardless of whether
                // gatherConsent()'s caller is still listening.
                reconcileThenResumeIfActive(continuation, Unit) {
                    updatePrivacyState(consentInformation)
                    _canRequestAds.value = consentInformation.canRequestAds
                    _status.value = consentInformationStatus(consentInformation)
                }
            }
        }
        _status.value
    }

    override suspend fun showPrivacyOptions(): Boolean = withContext(Dispatchers.Main.immediate) {
        val rootVC = topViewController() ?: return@withContext false
        val consentInformation = UMPConsentInformation.sharedInstance
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { }
            UMPConsentForm.presentPrivacyOptionsFormFromViewController(rootVC) { error ->
                // The user may have changed their choices; refresh exposed state so
                // consumers don't read stale consent / canRequestAds values. Must run even
                // if the caller awaiting showPrivacyOptions() was cancelled — the form
                // already closed and UMP already persisted whatever the user chose. See
                // reconcileThenResumeIfActive's KDoc.
                reconcileThenResumeIfActive(continuation, error == null) {
                    updatePrivacyState(consentInformation)
                    _canRequestAds.value = consentInformation.canRequestAds
                    _status.value = consentInformationStatus(consentInformation)
                    // If the user granted consent from the privacy form after an earlier
                    // denial, resume initialization — otherwise the SDK would stay
                    // uninitialized forever. Launched on consentScope, not awaited here:
                    // this callback (and the reconciliation above) must complete
                    // regardless of whether the original caller is still around to
                    // observe it.
                    if (_canRequestAds.value) {
                        lastConfig?.let { config -> consentScope.launch { onCanRequestAds(config) } }
                    }
                }
            }
        }
    }

    override suspend fun resetConsentForDebug(): Boolean = withContext(Dispatchers.Main.immediate) {
        UMPConsentInformation.sharedInstance.reset()
        _status.value = ConsentStatus.Unknown
        _privacy.value = PrivacyOptionsRequirementStatus.Unknown
        _canRequestAds.value = false
        true
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

    private fun updatePrivacyState(consentInformation: UMPConsentInformation) {
        _privacy.value = when (consentInformation.privacyOptionsRequirementStatus) {
            UMPPrivacyOptionsRequirementStatusRequired -> PrivacyOptionsRequirementStatus.Required
            UMPPrivacyOptionsRequirementStatusNotRequired -> PrivacyOptionsRequirementStatus.NotRequired
            else -> PrivacyOptionsRequirementStatus.Unknown
        }
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
