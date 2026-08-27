package dev.avinya.ads.appopen

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

// WillEnterForeground (not DidBecomeActive) so the signal fires only on true
// background-to-foreground returns — DidBecomeActive also fires after permission
// alerts and consent-form dismissals, which must not trigger app-open ads.
internal actual fun appForegroundState(): Flow<Boolean> = callbackFlow {
    // Observers must be (un)registered on the main thread regardless of which
    // dispatcher collects this flow — mirrors the Android actual. queue = null keeps
    // callback delivery on the posting thread, which for these two UIApplication
    // notifications is main.
    val (foregroundObserver, backgroundObserver) = withContext(Dispatchers.Main.immediate) {
        val fg = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = null
        ) { _ -> trySend(true) }
        val bg = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = null
        ) { _ -> trySend(false) }
        fg to bg
    }
    awaitClose {
        // awaitClose is not a suspend context, so hop via GCD — the iOS analogue of
        // the Handler(mainLooper).post in the Android actual.
        dispatch_async(dispatch_get_main_queue()) {
            NSNotificationCenter.defaultCenter.removeObserver(foregroundObserver)
            NSNotificationCenter.defaultCenter.removeObserver(backgroundObserver)
        }
    }
}

public fun iosAppForegroundState(): Flow<Boolean> = appForegroundState()

internal actual suspend fun isAppInForeground(): Boolean =
    withContext(Dispatchers.Main.immediate) {
        UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateActive
    }
