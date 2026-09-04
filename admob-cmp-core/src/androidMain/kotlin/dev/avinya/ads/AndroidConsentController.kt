package dev.avinya.ads

import android.app.Activity
import android.content.Context
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
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class AndroidConsentController(
    private val activityProvider: () -> Activity?,
    private val appContext: Context,
    private val onCanRequestAds: suspend (AdConfig) -> Unit
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
    // showPrivacyOptions() was itself cancelled (navigation, rotation) before this runs.
    // Same shape as GoogleAdManagerBase.admissionScope: process-wide, never cancelled.
    private val consentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus {
        return withContext(Dispatchers.Main.immediate) {
            state.serializedExclusiveOfNativeConsentOperations(
                onBusy = {
                    ConsentStatus.Failed(
                        AdError.message("requestConsentInfoUpdate() ignored: another native consent operation is already in progress.")
                    )
                }
            ) {
                val ownedConfig = prepareConsentConfig(config)
                val generation = state.beginOperation()
                val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                    ?: return@serializedExclusiveOfNativeConsentOperations failNoActivity(generation)

                val outcome = updateWithActivity(
                    activity,
                    ownedConfig,
                    UserMessagingPlatform.getConsentInformation(appContext),
                    generation,
                )
                outcome.status
            }
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
        generation: Long,
    ): ConsentInfoUpdateOutcome {
        val params = buildConsentParams(activity, config)

        state.markInfoUpdateStarted(generation)
        try {
            awaitNativeCallback(
                operation = "UMP requestConsentInfoUpdate",
                timeout = InitializationTimeouts.consentInfoUpdate
            ) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    consentInformation.requestConsentInfoUpdate(
                        activity,
                        params,
                        {
                            // Resume with Unit, never with a status read here: the resumed value
                            // is evaluated BEFORE reconcileThenResumeIfActive runs its block, so
                            // anything read at this point is the PRE-reconciliation status. The
                            // outcome is built from state.status.value after the await returns.
                            reconcileThenResumeIfActive(continuation, Unit) {
                                // Released from the callback, not the waiter: a cancelled or
                                // timed-out caller does not end the native operation.
                                state.releaseInfoUpdate(generation)
                                state.reconcileAndPublish(
                                    privacyRequirement = privacyRequirementOf(consentInformation),
                                    canRequestAds = consentInformation.canRequestAds(),
                                    status = resolveConsentInfoUpdateStatus(
                                        error = null,
                                        nativeStatus = consentInformationStatus(consentInformation),
                                    ),
                                )
                            }
                        },
                        { formError ->
                            val mapped = AdError(
                                code = formError.errorCode.toString(),
                                message = formError.message,
                            )
                            reconcileThenResumeIfActive(continuation, Unit) {
                                state.releaseInfoUpdate(generation)
                                state.reconcileAndPublish(
                                    privacyRequirement = privacyRequirementOf(consentInformation),
                                    canRequestAds = consentInformation.canRequestAds(),
                                    status = resolveConsentInfoUpdateStatus(
                                        error = mapped,
                                        nativeStatus = consentInformationStatus(consentInformation),
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        } catch (timeout: NativeCallbackTimeoutException) {
            AdLogger.e("Android UMP consent info update timed out.", timeout)
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
                val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                    ?: return@exclusiveOfForms failNoActivity(generation)
                val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
                // The acquired Activity is reused rather than calling the public
                // requestConsentInfoUpdate, which would wait for a host a second time.
                val update = updateWithActivity(activity, owned, consentInformation, generation)
                if (update is ConsentInfoUpdateOutcome.TimedOut) return@exclusiveOfForms update.status
                // The irreversible boundary, mirroring GoogleAdManagerBase's markHandoff: past this
                // line UMP owns the form, and cancelling this coroutine no longer means the screen
                // is free. Released in the callback below, not by this coroutine's fate.
                state.markFormPresented(generation)
                val formStatus = suspendCancellableCoroutine<ConsentStatus?> { continuation ->
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        val errorStatus = if (formError != null) {
                            AdLogger.w("UMP consent form load/show failed: code=${formError.errorCode} message=${formError.message}")
                            ConsentStatus.Failed(AdError(code = formError.errorCode.toString(), message = formError.message))
                        } else null

                        // Reconciliation must not be conditional on the caller still being around —
                        // see reconcileThenResumeIfActive's KDoc. The form was shown and the user's
                        // decision (if any) is already persisted by UMP regardless of whether
                        // gatherConsent()'s caller is still listening.
                        reconcileThenResumeIfActive(continuation, errorStatus) {
                            state.releaseFormPresentation(generation)
                            state.reconcileAndPublish(
                                privacyRequirement = privacyRequirementOf(consentInformation),
                                canRequestAds = consentInformation.canRequestAds(),
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
                // Acquired inside the main hop, not before it -- matching requestConsentInfoUpdate
                // and gatherConsent: this reads Activity lifecycle state, which is main-thread-owned
                // (invariant 5).
                val activity = activityProvider() ?: return@exclusiveOfForms false
                val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
                // See gatherConsent: the pin belongs to UMP from here, not to this coroutine.
                state.markFormPresented(generation)
                suspendCancellableCoroutine { continuation ->
                    UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
                        // The user may have changed their choices in the form; refresh exposed
                        // state so consumers don't read stale consent / canRequestAds values.
                        // Must run even if the caller awaiting showPrivacyOptions() was
                        // cancelled — the form already closed and UMP already persisted
                        // whatever the user chose. See reconcileThenResumeIfActive's KDoc.
                        reconcileThenResumeIfActive(continuation, formError == null) {
                            state.releaseFormPresentation(generation)
                            state.reconcileAndPublish(
                                privacyRequirement = privacyRequirementOf(consentInformation),
                                canRequestAds = consentInformation.canRequestAds(),
                                status = consentInformationStatus(consentInformation),
                            )
                            // If the user granted consent from the privacy form after an
                            // earlier denial, resume initialization — otherwise the SDK would
                            // stay uninitialized forever. Launched on consentScope, not
                            // awaited here: this callback (and the reconciliation above) must
                            // complete regardless of whether the original caller is still
                            // around to observe it.
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
            // Acquired inside the main hop, not before it -- this reads Activity lifecycle state,
            // which is main-thread-owned (invariant 5), matching every other entry point here.
            activityProvider() ?: return@serializedExclusiveOfNativeConsentOperations false
            state.reset()
            UserMessagingPlatform.getConsentInformation(appContext).reset()
            true
        }
    }

    /**
     * The host was absent for the whole `InitializationTimeouts.consentHost` window.
     *
     * Kept as one helper so both consent entry points log identically and the public
     * [ConsentStatus.Failed] message stays byte-identical to what it has always been —
     * only the warning above it is new.
     */
    private fun failNoActivity(generation: Long): ConsentStatus {
        AdLogger.w(
            "No usable Android Activity for UMP consent after waiting " +
                "${InitializationTimeouts.consentHost}. Consent was not gathered on this attempt; " +
                "the host app should retry."
        )
        return state.publishOperationStatus(
            generation,
            ConsentStatus.Failed(AdError.message("No current Android Activity for UMP consent.")),
        )
    }

    private fun privacyRequirementOf(consentInformation: ConsentInformation): PrivacyOptionsRequirementStatus =
        when (consentInformation.privacyOptionsRequirementStatus) {
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED -> PrivacyOptionsRequirementStatus.Required
            ConsentInformation.PrivacyOptionsRequirementStatus.NOT_REQUIRED -> PrivacyOptionsRequirementStatus.NotRequired
            else -> PrivacyOptionsRequirementStatus.Unknown
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
