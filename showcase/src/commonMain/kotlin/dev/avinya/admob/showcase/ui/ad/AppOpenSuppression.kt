package dev.avinya.admob.showcase.ui.ad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor

/**
 * Lets a flow or a screen declare that an app-open ad must not appear over it.
 *
 * Two independent reasons, each depth-counted rather than boolean so that two
 * overlapping holders never release each other on the way out:
 *
 * - [isBlocked]: a sensitive flow (onboarding, a privacy form, another
 *   full-screen ad) is in progress.
 * - [isOnAdScreen]: a screen that shows banner or native ads is visible.
 *   Google's placement guidance says not to show an app-open ad on top of
 *   other ads, such as content with banner ads.
 *
 * `AppOpenHost` turns both into the coordinator's `isBlocked`, through
 * `AppOpenEligibilityPolicy` so that each has its own suppression reason.
 */
class AppOpenSuppressor {
    private var depth by mutableStateOf(0)

    val isBlocked: Boolean get() = depth > 0

    fun enter() { depth++ }

    fun exit() { depth = (depth - 1).coerceAtLeast(0) }

    private var adScreenDepth by mutableStateOf(0)

    val isOnAdScreen: Boolean get() = adScreenDepth > 0

    fun enterAdScreen() { adScreenDepth++ }

    fun exitAdScreen() { adScreenDepth = (adScreenDepth - 1).coerceAtLeast(0) }
}

/**
 * Marks the calling screen as one that shows ads, for as long as it is composed.
 *
 * Call it once at the top of any screen that renders a banner or native ad. A
 * return to the foreground onto such a screen then shows no app-open ad. Apps
 * that want app-open ads over these screens should instead cover them with a
 * loading screen on resume, which is the placement Google prefers.
 */
@Composable
fun BlockAppOpenOnAdScreen(suppressor: AppOpenSuppressor = LocalAppOpenSuppressor.current) {
    DisposableEffect(suppressor) {
        suppressor.enterAdScreen()
        onDispose { suppressor.exitAdScreen() }
    }
}

/** Runs [block] with app-open ads suppressed, restoring state even on failure. */
suspend fun <T> AppOpenSuppressor.suppressing(block: suspend () -> T): T {
    enter()
    return try {
        block()
    } finally {
        // A suppression that leaks silently disables app-open ads for the rest
        // of the session, so the restore must survive an exception.
        exit()
    }
}
