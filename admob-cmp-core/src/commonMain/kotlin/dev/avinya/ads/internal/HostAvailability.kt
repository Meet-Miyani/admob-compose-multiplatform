package dev.avinya.ads.internal

import kotlin.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits up to [timeout] for [probe] to return a non-null host, polling every [pollInterval].
 * Returns null if it never does.
 *
 * ## Why polling rather than a callback
 * Android could observe `Application.ActivityLifecycleCallbacks`, but iOS has no notification
 * for "this view controller finished presenting" — `topViewController()` deliberately reports
 * null mid-transition. A probe loop is the only mechanism that works on both platforms, and
 * one shared primitive means one set of tests instead of two per-platform implementations.
 *
 * ## Why this is not a retry of the operation
 * This waits for the *host*, not for the UMP call. Once a host is in hand the caller makes
 * exactly one attempt; retrying the consent call itself is the consuming app's decision, not
 * the SDK's.
 */
internal suspend fun <T : Any> awaitHost(
    timeout: Duration,
    pollInterval: Duration = InitializationTimeouts.hostPoll,
    probe: suspend () -> T?,
): T? = probe() ?: withTimeoutOrNull(timeout) {
    var found: T? = null
    while (found == null) {
        delay(pollInterval)
        found = probe()
    }
    found
}

/**
 * Boolean form of [awaitHost]: waits up to [timeout] for [check] to become true.
 *
 * Used to gate the ATT prompt on the app actually being foreground. Deliberately a poll rather
 * than a wait on `appForegroundState()`: that flow only emits on transitions, and the iOS
 * foreground notification does not fire on a cold launch — so awaiting an edge would hang and
 * then silently skip the prompt. See "Why the foreground gate polls" in Design Decisions.
 */
internal suspend fun awaitCondition(
    timeout: Duration,
    pollInterval: Duration = InitializationTimeouts.hostPoll,
    check: suspend () -> Boolean,
): Boolean = awaitHost(timeout, pollInterval) { if (check()) true else null } != null
