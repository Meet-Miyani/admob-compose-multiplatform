package dev.avinya.ads

/**
 * How the SDK reacts when the app ID in [AdConfig] disagrees with the one declared in the
 * platform's own configuration (`AndroidManifest.xml` meta-data, `Info.plist`).
 *
 * The two declarations are consumed by different SDKs, which is why the default is graduated
 * rather than uniform:
 *
 * - On **iOS**, `Info.plist`'s `GADApplicationIdentifier` is what the native Google Mobile Ads
 *   SDK itself resolves at startup. If it is absent, ads cannot work at all.
 * - On **Android**, `AndroidManifest.xml`'s `com.google.android.gms.ads.APPLICATION_ID` is read
 *   by the User Messaging Platform SDK. GMA Next-Gen initializes from
 *   [AdConfig.androidAppId] directly and never reads it. A disagreement means consent and ads
 *   may resolve two different applications — a real misconfiguration, but not one that stops
 *   the SDK from serving.
 */
public enum class AppIdVerificationPolicy {

    /**
     * Log a warning and continue, whatever the finding.
     *
     * An escape hatch, not a recommendation: use it only if the SDK misreads your platform
     * declaration (an embedded bundle, a restricted package manager) and you have verified the
     * configuration is correct by other means.
     */
    WarnOnly,

    /**
     * Default. Fail initialization only when the platform's own SDK cannot function with the
     * declaration as found — today, a missing iOS `GADApplicationIdentifier`. Everything else
     * logs a warning and continues.
     */
    FailWhenUnusable,

    /**
     * Fail initialization on any missing or mismatched declaration, before UMP or GMA is
     * touched. Recommended for new integrations, and the intended default from 3.0.0.
     */
    Strict,
}

/**
 * Process-wide policy for the platform app-ID preflight check.
 *
 * **Threading:** an ordinary property with no synchronization, exactly like [AdLogger.sink].
 * Set it once during application startup, before the first `initialize()`.
 *
 * The default is [AppIdVerificationPolicy.FailWhenUnusable]. It is scheduled to become
 * [AppIdVerificationPolicy.Strict] in 3.0.0; set it explicitly now if you want the future
 * behaviour, or [AppIdVerificationPolicy.WarnOnly] if you want to log warnings and continue
 * whatever the finding.
 */
public object AdAppIdVerification {
    /** The policy applied on every `initialize()` attempt. */
    public var policy: AppIdVerificationPolicy = AppIdVerificationPolicy.FailWhenUnusable
}
