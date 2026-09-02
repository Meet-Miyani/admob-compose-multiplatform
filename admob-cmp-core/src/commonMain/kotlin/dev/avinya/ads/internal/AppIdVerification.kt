package dev.avinya.ads.internal

import dev.avinya.ads.AdError
import dev.avinya.ads.AdErrorCode
import dev.avinya.ads.AppIdVerificationPolicy

/**
 * The app ID declared in the platform's own configuration source (`AndroidManifest.xml`
 * meta-data, `Info.plist`), independent of [AdConfig][dev.avinya.ads.AdConfig].
 *
 * A plain nullable `String` can't distinguish two very different situations: "the key is
 * genuinely absent from the manifest/plist" (a real configuration gap worth flagging) versus
 * "the platform read itself failed for unrelated reasons" (a permission issue, a test
 * environment with no package manager, an unexpected exception) which is not evidence of
 * misconfiguration and must never be reported as one. [Missing] and [Unknown] keep those apart.
 */
internal sealed interface DeclaredAppId {
    /** The declared value was read successfully. */
    data class Present(val value: String) : DeclaredAppId

    /** The read succeeded, but no value is set for the key — a real configuration gap. */
    data object Missing : DeclaredAppId

    /** The value could not be determined (read failure, unsupported environment). Never a warning. */
    data object Unknown : DeclaredAppId
}

/**
 * Builds a warning message when [declared] does not agree with what
 * [AdConfig][dev.avinya.ads.AdConfig] was configured with, or `null` when there is nothing to
 * warn about.
 *
 * What "agree" means, and what actually consumes [declared], differs by platform — see
 * [declaredAppIdConsumerDescription] for the per-platform explanation baked into the message.
 * On Android, `AndroidManifest.xml`'s `APPLICATION_ID` meta-data is read by the User Messaging
 * Platform SDK for consent, while GMA Next-Gen itself initializes with `AdConfig.androidAppId`
 * directly and never touches that manifest value — so an Android mismatch here means GMA and
 * UMP could resolve two different apps' identities, not that "GMA will use the wrong one". On
 * iOS, `Info.plist`'s `GADApplicationIdentifier` really is what the native Google Mobile Ads
 * SDK itself resolves and uses at startup.
 *
 * This is a **warning, not a hard failure** in both the mismatch and the missing case: a
 * configuration gap caught here must not turn into an initialization outage for every ad
 * format. [DeclaredAppId.Unknown] is deliberately never a warning — an unreadable value is not
 * evidence of misconfiguration this check can usefully report on.
 *
 * IDs are redacted to their last 6 characters: full ad app IDs should not be written to logs
 * verbatim.
 *
 * @param declaredAppIdSource human-readable description of where [declared] is read from.
 * @param declaredAppIdConsumerDescription human-readable description of what actually consumes
 *   [declared] and how that relates to [configuredAppId] -- see the platform-specific text
 *   above.
 */
internal fun appIdConfigurationWarningOrNull(
    configuredAppId: String,
    declared: DeclaredAppId,
    declaredAppIdSource: String,
    declaredAppIdConsumerDescription: String,
): String? = when (declared) {
    DeclaredAppId.Unknown -> null
    DeclaredAppId.Missing -> "AdConfig is configured with app ID (…${configuredAppId.takeLast(6)}), " +
        "but $declaredAppIdSource has no value set. $declaredAppIdConsumerDescription This is a " +
        "configuration gap, not just a mismatch -- fix it even though the SDK does not fail " +
        "initialization for it."
    is DeclaredAppId.Present -> {
        if (declared.value == configuredAppId) {
            null
        } else {
            "AdConfig's app ID (…${configuredAppId.takeLast(6)}) does not match the value declared " +
                "in $declaredAppIdSource (…${declared.value.takeLast(6)}). " +
                "$declaredAppIdConsumerDescription This usually means AdConfig and " +
                "$declaredAppIdSource were updated independently."
        }
    }
}

/**
 * What the app-ID preflight decided.
 *
 * Split from [appIdConfigurationWarningOrNull] so the MESSAGE and the SEVERITY are separate
 * concerns: the same text is emitted whether the finding warns or blocks, and only the policy
 * decides which. Logging is observability; this type is enforcement.
 */
internal sealed interface AppIdVerdict {
    data object Ok : AppIdVerdict
    data class Warn(val message: String) : AppIdVerdict
    data class Fail(val message: String) : AppIdVerdict
}

/**
 * Applies [policy] to the platform declaration.
 *
 * @param requiredByPlatformSdk whether the platform's OWN ad SDK resolves its identity from
 *   [declared]. True on iOS (`GADApplicationIdentifier`), false on Android (where the manifest
 *   value is UMP's and GMA Next-Gen reads `AdConfig` instead). This is what makes a missing
 *   declaration fatal on one platform and merely wrong on the other.
 */
internal fun appIdVerdict(
    configuredAppId: String,
    declared: DeclaredAppId,
    declaredAppIdSource: String,
    declaredAppIdConsumerDescription: String,
    requiredByPlatformSdk: Boolean,
    policy: AppIdVerificationPolicy,
): AppIdVerdict {
    val message = appIdConfigurationWarningOrNull(
        configuredAppId,
        declared,
        declaredAppIdSource,
        declaredAppIdConsumerDescription,
    ) ?: return AppIdVerdict.Ok

    val unusable = requiredByPlatformSdk && declared is DeclaredAppId.Missing
    val fails = when (policy) {
        AppIdVerificationPolicy.WarnOnly -> false
        AppIdVerificationPolicy.FailWhenUnusable -> unusable
        AppIdVerificationPolicy.Strict -> true
    }
    return if (fails) AppIdVerdict.Fail(message) else AppIdVerdict.Warn(message)
}

/** The typed error a failed preflight publishes. Never retryable: the configuration must change. */
internal fun appIdPreflightError(message: String): AdError = AdError(
    code = AdErrorCode.APP_ID_INVALID,
    message = message,
)

