package dev.avinya.ads.internal

import dev.avinya.ads.AdError
import dev.avinya.ads.AdErrorCode
import dev.avinya.ads.AdInitializationConfigIdentity
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.nativead.NativeAdMemoryPolicy

/**
 * What a repeat `initialize()` should do, given what the process singleton already accepted.
 *
 * Extracted from the two platform managers, whose `appliedOutcome` bodies were byte-identical apart
 * from a log prefix. Both now adapt this one decision, which keeps them from drifting and — more
 * importantly — makes the semantics testable in `commonTest`: the platform managers reach
 * `MobileAds`/`GADMobileAds` through statics, so they cannot be exercised end-to-end without a
 * static-mocking dependency.
 */
internal sealed interface AppliedConfigurationDecision {

    /** No configuration has been accepted yet, so the caller should proceed with initialization. */
    data object NotApplied : AppliedConfigurationDecision

    /**
     * The request matches what was already applied; it is idempotent.
     *
     * @property publish the terminal status to republish, or `null` when initialization has not yet
     *   reached a terminal state and the caller should leave the current status alone.
     */
    data class Accepted(val publish: AdManagerStatus?) : AppliedConfigurationDecision

    /**
     * The request asks for a configuration the process singleton cannot adopt.
     *
     * @property publish the manager's **true** status, which must still be published. Publishing
     *   [rejection] instead would be a serious bug: `adRequestBlockedError()` blocks every ad
     *   request whenever the status is not [AdManagerStatus.Ready], so it would take down ad serving
     *   process-wide for the caller whose configuration *was* accepted.
     * @property rejection the non-retryable failure returned to **this** caller only, so a refused
     *   configuration stops looking like success.
     */
    data class Conflict(
        val publish: AdManagerStatus,
        val rejection: AdManagerStatus.Failed,
        val reason: String,
    ) : AppliedConfigurationDecision
}

/**
 * The ad SDKs initialize a process-wide singleton exactly once, so a second `initialize()` with
 * different values cannot be honoured. [reason] names the facet that conflicted.
 */
internal fun initializationConflictError(reason: String): AdError = AdError(
    code = AdErrorCode.INITIALIZATION_CONFLICT,
    message = "$reason The ad SDK configures a process-wide singleton that cannot be " +
        "reconfigured after initialization. Restart the process to apply a different " +
        "configuration, or call initialize() once with the configuration you want.",
)

/**
 * Decides the outcome of a repeat `initialize()`.
 *
 * @param appliedIdentity identity already accepted by the platform singleton, or `null` if none.
 * @param configuredNativePolicy native-ad memory policy already bound, or `null` if none.
 * @param appliedTerminalStatus terminal status recorded when the configuration was accepted.
 * @param currentStatus the manager's live status, used only when no terminal status was recorded.
 */
internal fun appliedConfigurationDecision(
    appliedIdentity: AdInitializationConfigIdentity?,
    requestedIdentity: AdInitializationConfigIdentity,
    configuredNativePolicy: NativeAdMemoryPolicy?,
    requestedNativePolicy: NativeAdMemoryPolicy,
    appliedTerminalStatus: AdManagerStatus?,
    currentStatus: AdManagerStatus,
): AppliedConfigurationDecision {
    if (appliedIdentity == null) return AppliedConfigurationDecision.NotApplied

    val truth = appliedTerminalStatus ?: currentStatus
    fun conflict(reason: String) = AppliedConfigurationDecision.Conflict(
        publish = truth,
        rejection = AdManagerStatus.Failed(
            error = initializationConflictError(reason),
            retryable = false,
        ),
        reason = reason,
    )

    if (appliedIdentity.platformAppId != requestedIdentity.platformAppId) {
        return conflict(
            "This AdManager already initialized with app ID " +
                "'${appliedIdentity.platformAppId}', but '${requestedIdentity.platformAppId}' " +
                "was requested."
        )
    }
    if (appliedIdentity.globalRequestConfiguration != requestedIdentity.globalRequestConfiguration) {
        return conflict(
            "This AdManager already initialized with a different global request configuration."
        )
    }
    if (configuredNativePolicy != null && configuredNativePolicy != requestedNativePolicy) {
        return conflict(
            "This AdManager already bound native ad memory policy $configuredNativePolicy, " +
                "but $requestedNativePolicy was requested."
        )
    }
    return AppliedConfigurationDecision.Accepted(appliedTerminalStatus)
}

/**
 * What a new native-initialization attempt should do, given what was already handed to the
 * process-global ad SDK.
 *
 * Distinct from [appliedConfigurationDecision], which answers the same question for a
 * *completed* initialization. This one covers the window in between: the native call has been
 * accepted but its callback has not arrived — or never will. A wrapper timeout may release the
 * waiter, but it cannot un-hand-off an irreversible call, so ownership must survive it.
 */
internal sealed interface NativeHandoffDecision {

    /** Nothing was handed off yet, or the same configuration is being retried. */
    data object Proceed : NativeHandoffDecision

    /**
     * A different configuration was already handed to the native singleton.
     *
     * @property rejection returned to **this** caller only. As with
     *   [AppliedConfigurationDecision.Conflict], it must NOT be published as the manager's
     *   status: `adRequestBlockedError()` blocks every ad request whenever the status is not
     *   [AdManagerStatus.Ready], which would take down ad serving process-wide.
     */
    data class Refuse(
        val rejection: AdManagerStatus.Failed,
        val reason: String,
    ) : NativeHandoffDecision
}

/**
 * Decides whether a native initialization attempt may proceed.
 *
 * A same-identity retry is always permitted: when GMA accepts a call and never calls back, the
 * documented recovery is to retry the same configuration, and both platform SDKs tolerate a
 * repeated initialize.
 */
internal fun nativeHandoffDecision(
    handedOffIdentity: AdInitializationConfigIdentity?,
    requestedIdentity: AdInitializationConfigIdentity,
): NativeHandoffDecision {
    if (handedOffIdentity == null || handedOffIdentity == requestedIdentity) {
        return NativeHandoffDecision.Proceed
    }
    val reason = if (handedOffIdentity.platformAppId != requestedIdentity.platformAppId) {
        "The ad SDK was already started with app ID '${handedOffIdentity.platformAppId}', but " +
            "'${requestedIdentity.platformAppId}' was requested."
    } else {
        "The ad SDK was already started with a different global request configuration."
    }
    return NativeHandoffDecision.Refuse(
        rejection = AdManagerStatus.Failed(
            error = initializationConflictError(reason),
            retryable = false,
        ),
        reason = reason,
    )
}

