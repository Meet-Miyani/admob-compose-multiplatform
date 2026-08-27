package dev.avinya.ads

import dev.avinya.ads.appopen.isAppInForeground
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.awaitCondition
import dev.avinya.ads.internal.awaitNativeCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import platform.AppTrackingTransparency.ATTrackingManager
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusAuthorized
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusDenied
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusNotDetermined
import platform.AppTrackingTransparency.ATTrackingManagerAuthorizationStatusRestricted

internal object IosTrackingController : AdTrackingController {

    override fun status(): AdTrackingAuthorization =
        when (ATTrackingManager.trackingAuthorizationStatus) {
            ATTrackingManagerAuthorizationStatusAuthorized -> AdTrackingAuthorization.Authorized
            ATTrackingManagerAuthorizationStatusDenied -> AdTrackingAuthorization.Denied
            ATTrackingManagerAuthorizationStatusRestricted -> AdTrackingAuthorization.Restricted
            ATTrackingManagerAuthorizationStatusNotDetermined -> AdTrackingAuthorization.NotDetermined
            else -> AdTrackingAuthorization.NotApplicable
        }

    /**
     * Requests ATT authorization, bounded on both sides (invariant 9).
     *
     * iOS does not present this prompt while the app is not foreground-active, and the completion
     * handler can never fire at all. Because ATT sits between UMP consent and `initialize()`
     * (invariant 11), an unbounded wait here means the SDK is never initialized — silently, for
     * the whole session.
     *
     * Timing out is the correct outcome rather than failing: a skipped prompt leaves the OS status
     * `NotDetermined`, so it is offered again on a later launch, and requests in the meantime
     * simply go out without the IDFA. That is a revenue cost; hanging is a total ad outage.
     */
    override suspend fun requestAuthorization(): AdTrackingAuthorization =
        // UIKit/ATT prompt presentation is main-thread only (CLAUDE.md invariant #5).
        withContext(Dispatchers.Main.immediate) {
            if (status() != AdTrackingAuthorization.NotDetermined) return@withContext status()
            // Polled, not awaited on appForegroundState(): that flow emits only on transitions and
            // its iOS notification does not fire on a cold launch, so awaiting an edge would hang
            // and then silently skip the prompt. See Design Decisions.
            if (!awaitCondition(InitializationTimeouts.attForeground) { isAppInForeground() }) {
                AdLogger.w(
                    "App did not become foreground-active within " +
                        "${InitializationTimeouts.attForeground}; skipping the ATT prompt this " +
                        "launch. Status stays ${status()} and the prompt will be offered again."
                )
                return@withContext status()
            }
            try {
                awaitNativeCallback(
                    operation = "ATTrackingManager.requestTrackingAuthorization",
                    timeout = InitializationTimeouts.attPrompt,
                ) {
                    suspendCancellableCoroutine { continuation ->
                        ATTrackingManager.requestTrackingAuthorizationWithCompletionHandler { _ ->
                            if (continuation.isActive) continuation.resume(status())
                        }
                    }
                }
            } catch (timeout: NativeCallbackTimeoutException) {
                AdLogger.w(
                    "ATT completion handler never fired within " +
                        "${InitializationTimeouts.attPrompt}; continuing with ${status()}. " +
                        "The prompt will be offered again on a later launch."
                )
                status()
            }
        }
}
