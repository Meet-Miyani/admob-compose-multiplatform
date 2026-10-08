package dev.avinya.ads.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * How long a show waits for the app to count as foreground before giving up.
 *
 * A return to the foreground reaches the SDK (Android's process ON_START, iOS's
 * WillEnterForeground) a few milliseconds before the OS finishes ranking the app as the top,
 * active app. Google's SDK checks that ranking itself and refuses, with a policy-violation
 * error, any full-screen ad shown in that gap. One second covers the gap on slow devices
 * without letting an ad appear after the user has already settled into the app.
 */
internal val FOREGROUND_PRESENTATION_TIMEOUT: Duration = 1.seconds

/** How often the foreground check is repeated while waiting. */
internal val FOREGROUND_PRESENTATION_POLL: Duration = 50.milliseconds

/**
 * Returns true once [isForeground] reports true, checking first without any delay and then
 * every [pollInterval]; returns false if it is still false after [timeout].
 *
 * Polling rather than a lifecycle callback, because the condition that matters is the one
 * Google's SDK reads at show time (on Android, the process importance from ActivityManager),
 * which no lifecycle event announces.
 */
internal suspend fun awaitForegroundForPresentation(
    timeout: Duration = FOREGROUND_PRESENTATION_TIMEOUT,
    pollInterval: Duration = FOREGROUND_PRESENTATION_POLL,
    isForeground: suspend () -> Boolean,
): Boolean {
    if (isForeground()) return true
    var waited = Duration.ZERO
    while (waited < timeout) {
        val step = minOf(pollInterval, timeout - waited)
        delay(step)
        waited += step
        if (isForeground()) return true
    }
    return false
}
