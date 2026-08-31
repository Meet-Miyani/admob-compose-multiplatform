package dev.avinya.ads

import android.app.Activity
import android.content.Context
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.awaitHost
import dev.avinya.ads.internal.awaitNativeCallback
import dev.avinya.ads.internal.ownedSnapshot
import dev.avinya.ads.internal.reconcileThenResumeIfActive
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class AndroidConsentController(
    private val activityProvider: () -> Activity?,
    private val appContext: Context,
    private val onCanRequestAds: suspend (AdConfig) -> Unit
) : ConsentController {
    private val _status = MutableStateFlow<ConsentStatus>(ConsentStatus.Unknown)
    private val _privacy = MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
    private val _canRequestAds = MutableStateFlow(false)
    // Retained so showPrivacyOptions() can resume initialization if the user grants
    // consent from the privacy form after an earlier denial.
    private var lastConfig: AdConfig? = null
    // Owns the initialize() resume triggered by showPrivacyOptions() below, so a granted
    // consent decision still reaches initialize() even if the caller that awaited
    // showPrivacyOptions() was itself cancelled (navigation, rotation) before this runs.
    // Same shape as GoogleAdManagerBase.admissionScope: process-wide, never cancelled.
    private val consentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val status: StateFlow<ConsentStatus> = _status
    override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> = _privacy
    override val canRequestAds: StateFlow<Boolean> = _canRequestAds

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus {
        val ownedConfig = prepareConsentConfig(config)
        return withContext(Dispatchers.Main.immediate) {
            // Acquired inside the main hop, not before it: this reads Activity lifecycle state,
            // which is main-thread-owned (invariant 5). It waits rather than failing on the first
            // null because ForegroundStack legitimately empties for a few hundred milliseconds
            // during any Activity handoff.
            val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                ?: return@withContext failNoActivity()
            updateWithActivity(activity, ownedConfig, UserMessagingPlatform.getConsentInformation(appContext))
        }
    }

    /**
     * Takes ownership of the caller's config and fires the pre-consent hooks.
     *
     * Every public consent entry point must do exactly this before touching UMP, and they are the
     * only callers. Kept as one function so the two cannot drift: [gatherConsent] no longer routes
     * through [requestConsentInfoUpdate] — it already holds an acquired Activity and would
     * otherwise wait for a host a second time — so without this the preamble would live in two
     * places and a change to one would silently miss the other.
     *
     * The returned snapshot is what every downstream step must use. `lastConfig` outlives this
     * call and is reused if `showPrivacyOptions()` later resumes initialization, so retaining the
     * caller's own object would let post-call mutation of its lists or hooks change UMP debug IDs
     * and hook execution.
     */
    private suspend fun prepareConsentConfig(config: AdConfig): AdConfig {
        val owned = config.ownedSnapshot()
        lastConfig = owned
        owned.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
        return owned
    }

    /**
     * The UMP info-update sequence, given a host that has already been acquired.
     *
     * Split out so each PUBLIC consent entry point acquires the host exactly once. Do not
     * route [gatherConsent] back through [requestConsentInfoUpdate] — that re-acquires the
     * host, two waits for one logical operation. Callers are responsible for being on Main.
     */
    private suspend fun updateWithActivity(
        activity: Activity,
        config: AdConfig,
        consentInformation: ConsentInformation,
    ): ConsentStatus {
        val params = buildConsentParams(activity, config)
        // MUST stay bounded: this is a non-interactive network round trip, and UMP can accept
        // the call and never call back, which hangs consent admission — and therefore ad
        // serving — indefinitely.
        //
        // On timeout the status becomes Failed, but [canRequestAds] is deliberately NOT reset: it
        // keeps whatever the last COMPLETED refresh established. On a first run that is false, so a
        // cold start still admits nothing on an unknown consent state. On a later run it may be a
        // previously granted true, and a dropped network round trip is not evidence that consent
        // was withdrawn — revoking admission on a blip would stop ad serving until the next
        // successful refresh, for no gain in consent correctness. Consent itself has not changed;
        // only our ability to re-confirm it has, and UMP has already persisted the user's choice.
        //
        // Kept identical to `IosConsentController` on purpose: a consent-admission divergence
        // between the platforms is the kind of thing that is only discovered in production.
        //
        // The consent FORM and privacy options form below stay unbounded on purpose; a person is
        // reading those.
        val error = try {
            awaitNativeCallback(
                operation = "UMP requestConsentInfoUpdate",
                timeout = InitializationTimeouts.consentInfoUpdate
            ) {
                suspendCancellableCoroutine<AdError?> { continuation ->
                    consentInformation.requestConsentInfoUpdate(
                        activity,
                        params,
                        { if (continuation.isActive) continuation.resume(null) },
                        { if (continuation.isActive) continuation.resume(AdError(code = it.errorCode.toString(), message = it.message)) }
                    )
                }
            }
        } catch (timeout: NativeCallbackTimeoutException) {
            AdLogger.e("Android UMP consent info update timed out.", timeout)
            return fail(timeout.message ?: "UMP consent info update timed out.")
        }
        updatePrivacyState(consentInformation)
        _canRequestAds.value = consentInformation.canRequestAds()
        val status = if (error == null) {
            consentInformationStatus(consentInformation).also { _status.value = it }
        } else if (consentInformation.canRequestAds()) {
            consentInformationStatus(consentInformation).also { _status.value = it }
        } else {
            ConsentStatus.Failed(error).also { _status.value = it }
        }
        return status
    }

    override suspend fun gatherConsent(config: AdConfig): ConsentStatus =
        withContext(Dispatchers.Main.immediate) {
            val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                ?: return@withContext failNoActivity()
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
            // The acquired Activity is reused rather than calling the public
            // requestConsentInfoUpdate, which would wait for a host a second time.
            val ownedConfig = prepareConsentConfig(config)
            val update = updateWithActivity(activity, ownedConfig, consentInformation)
            if (update is ConsentStatus.Failed && !consentInformation.canRequestAds()) return@withContext update
            suspendCancellableCoroutine<Unit> { continuation ->
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                    if (formError != null) {
                        AdLogger.w("UMP consent form load/show failed: code=${formError.errorCode} message=${formError.message}")
                    }
                    // Reconciliation must not be conditional on the caller still being around —
                    // see reconcileThenResumeIfActive's KDoc. The form was shown and the user's
                    // decision (if any) is already persisted by UMP regardless of whether
                    // gatherConsent()'s caller is still listening.
                    reconcileThenResumeIfActive(continuation, Unit) {
                        updatePrivacyState(consentInformation)
                        _canRequestAds.value = consentInformation.canRequestAds()
                        _status.value = consentInformationStatus(consentInformation)
                    }
                }
            }
            _status.value
        }

    override suspend fun showPrivacyOptions(): Boolean =
        withContext(Dispatchers.Main.immediate) {
            // Acquired inside the main hop, not before it -- matching requestConsentInfoUpdate
            // and gatherConsent: this reads Activity lifecycle state, which is main-thread-owned
            // (invariant 5).
            val activity = activityProvider() ?: return@withContext false
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
            suspendCancellableCoroutine { continuation ->
                UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                    // The user may have changed their choices in the form; refresh exposed
                    // state so consumers don't read stale consent / canRequestAds values.
                    // Must run even if the caller awaiting showPrivacyOptions() was
                    // cancelled — the form already closed and UMP already persisted
                    // whatever the user chose. See reconcileThenResumeIfActive's KDoc.
                    reconcileThenResumeIfActive(continuation, formError == null) {
                        updatePrivacyState(consentInformation)
                        _canRequestAds.value = consentInformation.canRequestAds()
                        _status.value = consentInformationStatus(consentInformation)
                        // If the user granted consent from the privacy form after an
                        // earlier denial, resume initialization — otherwise the SDK would
                        // stay uninitialized forever. Launched on consentScope, not
                        // awaited here: this callback (and the reconciliation above) must
                        // complete regardless of whether the original caller is still
                        // around to observe it.
                        if (_canRequestAds.value) {
                            lastConfig?.let { config -> consentScope.launch { onCanRequestAds(config) } }
                        }
                    }
                }
            }
        }

    override suspend fun resetConsentForDebug(): Boolean {
        val activity = activityProvider() ?: return false
        withContext(Dispatchers.Main.immediate) {
            UserMessagingPlatform.getConsentInformation(appContext).reset()
        }
        _status.value = ConsentStatus.Unknown
        _privacy.value = PrivacyOptionsRequirementStatus.Unknown
        _canRequestAds.value = false
        return true
    }

    private fun fail(message: String): ConsentStatus =
        ConsentStatus.Failed(AdError.message(message)).also { _status.value = it }

    /**
     * The host was absent for the whole `InitializationTimeouts.consentHost` window.
     *
     * Kept as one helper so both consent entry points log identically and the public
     * [ConsentStatus.Failed] message stays byte-identical to what it has always been —
     * only the warning above it is new.
     */
    private fun failNoActivity(): ConsentStatus {
        AdLogger.w(
            "No usable Android Activity for UMP consent after waiting " +
                "${InitializationTimeouts.consentHost}. Consent was not gathered on this attempt; " +
                "the host app should retry."
        )
        return fail("No current Android Activity for UMP consent.")
    }

    private fun updatePrivacyState(consentInformation: ConsentInformation) {
        _privacy.value = when (consentInformation.privacyOptionsRequirementStatus) {
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED -> PrivacyOptionsRequirementStatus.Required
            ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED -> PrivacyOptionsRequirementStatus.NotRequired
            else -> PrivacyOptionsRequirementStatus.Unknown
        }
    }

    // Maps UMP's consent status (distinct from canRequestAds) so the public model
    // can surface NotRequired instead of collapsing everything to Obtained/Required.
    private fun consentInformationStatus(consentInformation: ConsentInformation): ConsentStatus =
        when (consentInformation.consentStatus) {
            ConsentInformation.ConsentStatus.NOT_REQUIRED -> ConsentStatus.NotRequired
            ConsentInformation.ConsentStatus.OBTAINED -> ConsentStatus.Obtained
            ConsentInformation.ConsentStatus.REQUIRED -> ConsentStatus.Required
            else -> ConsentStatus.Unknown
        }

    private fun buildConsentParams(activity: Activity, config: AdConfig): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        builder.setTagForUnderAgeOfConsent(config.consentTagForUnderAgeOfConsent)
        if (config.testMode && (config.testDeviceIds.isNotEmpty() || config.debugOptions.consentTestDeviceIds.isNotEmpty() || config.debugGeography != ConsentDebugGeography.Disabled)) {
            val debugBuilder = ConsentDebugSettings.Builder(activity)
            (config.debugOptions.consentTestDeviceIds + config.testDeviceIds).distinct().forEach(debugBuilder::addTestDeviceHashedId)
            when (config.debugGeography) {
                ConsentDebugGeography.Disabled -> Unit
                ConsentDebugGeography.Eea -> debugBuilder.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                ConsentDebugGeography.NotEea -> debugBuilder.setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER)
            }
            builder.setConsentDebugSettings(debugBuilder.build())
        }
        return builder.build()
    }
}
