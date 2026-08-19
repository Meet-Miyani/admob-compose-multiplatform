package dev.avinya.ads.internal

import kotlin.time.Duration
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
 * bug, not a safeguard.
 */
internal object InitializationTimeouts {
    /** Native `MobileAds.initialize` / `GADMobileAds.start`. */
    val nativeInitialize: Duration = 30.seconds

    /** UMP `requestConsentInfoUpdate` — a network round trip with no user interaction. */
    val consentInfoUpdate: Duration = 20.seconds
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
