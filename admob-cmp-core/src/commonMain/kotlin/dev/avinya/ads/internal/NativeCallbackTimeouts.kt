package dev.avinya.ads.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounds for the non-interactive native callbacks the SDK awaits during startup.
 *
 * Deliberately internal constants rather than an `AdConfig` knob: `AdConfig` is a public data class
 * whose constructor and `copy` are part of the frozen ABI, and these values only ever matter when
 * the platform SDK is itself misbehaving — accepting a call and never invoking its callback.
 *
 * Only *non-interactive* operations belong here. Anything that presents UI and waits for a person —
 * the UMP consent form, the privacy options form, the ad inspector — must stay unbounded, with
 * caller cancellation as its escape hatch. Timing out a form the user is still reading would be a
 * bug, not a safeguard. [formPresentationPin] is not an exception to that rule: it bounds how long
 * the wrapper keeps BELIEVING a form is on screen, never the form itself.
 */
internal object InitializationTimeouts {
    /** Native `MobileAds.initialize` / `GADMobileAds.start`. */
    val nativeInitialize: Duration = 30.seconds

    /**
     * GMA iOS documents that `startWithCompletionHandler` fires after setup completes *or* after
     * its own ~30-second internal bound, so an outer watchdog set to the same nominal value can
     * win the race and report a false failure for a slow mediation setup — exactly the case the
     * native fallback exists to resolve.
     */
    val nativeInitializeIos: Duration = 40.seconds

    /** UMP `requestConsentInfoUpdate` — a network round trip with no user interaction. */
    val consentInfoUpdate: Duration = 20.seconds

    /**
     * How long consent gathering waits for a usable platform host (Android `Activity`, iOS root
     * `UIViewController`) before giving up on this attempt.
     *
     * Two seconds because every window this exists to cover is sub-second: the gap between an ad
     * `Activity` stopping and the app `Activity` restarting, a configuration change, and an iOS
     * view controller finishing its presentation transition. Long enough to cover all three,
     * short enough that a genuinely backgrounded app does not sit here.
     */
    val consentHost: Duration = 2.seconds

    /** Gap between host probes. Invisible at [consentHost]'s scale. */
    val hostPoll: Duration = 50.milliseconds

    /**
     * How long to wait for the app to become foreground-active before abandoning the ATT prompt
     * for this launch. Five seconds covers a cold start that is still becoming active and the
     * Inactive window right after a consent form dismisses; longer means the app was genuinely
     * launched into the background, where the prompt cannot be presented at all.
     */
    val attForeground: Duration = 5.seconds

    /**
     * Backstop for the ATT completion handler itself. Generous, because a real user reading the
     * system dialog is inside this window — but bounded, because the handler is documented to
     * simply never fire in some states, and an unbounded wait here blocks initialize().
     */
    val attPrompt: Duration = 60.seconds

    /**
     * How long the consent-form slot stays pinned to a form UMP was handed but never reported
     * back on.
     *
     * The form itself is not timed out — a person is reading it, and cancelling their caller does
     * not dismiss it, which is exactly why the pin outlives the coroutine. What is bounded is the
     * wrapper's belief. Five minutes because a user reading a consent notice and choosing vendors
     * is comfortably inside it, while past it the app has almost certainly been backgrounded or
     * the callback is never coming — and refusing every consent operation for the rest of the
     * process is a worse outcome than the overlapping form the pin exists to prevent.
     */
    val formPresentationPin: Duration = 5.minutes

    /**
     * How long the native info update stays pinned.
     */
    val infoUpdatePin: Duration = 30.seconds
}

/**
 * Signals that a native callback never arrived.
 *
 * Intentionally **not** a `CancellationException`. That distinction is the whole point of this
 * type: the managers wrap their initialization paths in `catch (cancellation: CancellationException)`
 * arms that treat cancellation as "the caller walked away" and preserve the previous status. Since
 * `withTimeout` raises `TimeoutCancellationException`, which *is* a `CancellationException`, using it
 * here would make every timeout indistinguishable from a caller cancellation and silently restore the
 * old status instead of reporting a failure.
 */
internal class NativeCallbackTimeoutException(
    operation: String,
    timeout: Duration,
) : Exception(
    "$operation did not report a result within $timeout. The ad SDK accepted the call but never " +
        "invoked its callback."
)

/** Boxed so that a legitimately `null` result is never mistaken for a timeout. */
private class CallbackResult<T>(val value: T)

/**
 * Runs [block], failing with [NativeCallbackTimeoutException] if it does not finish within [timeout].
 *
 * Caller cancellation still propagates as `CancellationException`, unchanged — only the timeout is
 * reported as a distinct failure.
 */
internal suspend fun <T> awaitNativeCallback(
    operation: String,
    timeout: Duration,
    block: suspend () -> T,
): T {
    val result = withTimeoutOrNull(timeout) { CallbackResult(block()) }
        ?: throw NativeCallbackTimeoutException(operation, timeout)
    return result.value
}
