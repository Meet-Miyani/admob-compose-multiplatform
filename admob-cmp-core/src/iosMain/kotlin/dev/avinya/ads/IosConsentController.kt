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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.awaitHost
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.ownedSnapshot

internal class IosConsentController(
    val onCanRequestAds: suspend (AdConfig) -> Unit
) : ConsentController {
    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacy = MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)
    // Retained so showPrivacyOptions() can resume initialization if the user grants
    // consent from the privacy form after an earlier denial.
    private var lastConfig: AdConfig? = null

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
        // Bounded: this is a non-interactive network round trip, so UMP accepting the call and never
        // calling back used to hang consent admission -- and therefore ad serving -- indefinitely.
        // Fails CLOSED: _canRequestAds stays false and the status becomes Failed, so no ad request
        // proceeds on an unknown consent state. The consent FORM and privacy options form stay
        // unbounded on purpose; a person is reading those.
        try {
            awaitNativeCallback(
                operation = "UMP requestConsentInfoUpdate",
                timeout = InitializationTimeouts.consentInfoUpdate
            ) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { }
                    consentInformation.requestConsentInfoUpdateWithParameters(buildParams(config)) { error ->
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
            UMPConsentForm.loadAndPresentIfRequiredFromViewController(rootVC) { error ->
                if (continuation.isActive) {
                    updatePrivacyState(consentInformation)
                    _canRequestAds.value = consentInformation.canRequestAds
                    _status.value = consentInformationStatus(consentInformation)
                    continuation.resume(Unit)
                }
            }
        }
        _status.value
    }

    override suspend fun showPrivacyOptions(): Boolean = withContext(Dispatchers.Main.immediate) {
        val rootVC = topViewController() ?: return@withContext false
        val success = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { }
            UMPConsentForm.presentPrivacyOptionsFormFromViewController(rootVC) { error ->
                if (continuation.isActive) continuation.resume(error == null)
            }
        }
        // The user may have changed their choices; refresh exposed state so consumers
        // don't read stale consent / canRequestAds values.
        val consentInformation = UMPConsentInformation.sharedInstance
        updatePrivacyState(consentInformation)
        _canRequestAds.value = consentInformation.canRequestAds
        _status.value = consentInformationStatus(consentInformation)
        // If the user granted consent from the privacy form after an earlier denial,
        // resume initialization — otherwise the SDK would stay uninitialized forever.
        if (_canRequestAds.value) lastConfig?.let { onCanRequestAds(it) }
        success
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
