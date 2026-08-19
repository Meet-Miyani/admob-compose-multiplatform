package dev.avinya.ads.internal

import dev.avinya.ads.FullScreenAdOptions

/**
 * Applies and reverts the per-presentation ad audio override.
 *
 * Both implementations reach the platform ad SDK directly, so every call must observe the main
 * thread. The two halves are confined differently, deliberately:
 *
 *  - [applyOverrides] is confined by the **caller**. [FullScreenSlotCore] invokes it inside
 *    `withContext(NonCancellable + Dispatchers.Main.immediate)`, matching how it already hoists
 *    `canPresent()` onto Main. It is `suspend` so that confinement is observable from a test fake.
 *  - [AudioRestoreHandle.restore] is confined by **each implementation**. It runs from
 *    [FullScreenPresentationHandle]'s terminal close, which can be driven by an SDK callback
 *    thread, the caller's dispatcher, or the hand-off watchdog — there is no single caller to
 *    confine it. A common `expect fun runOnMainImmediate` is deliberately avoided: `commonTest`
 *    also compiles into `androidHostTest`, where touching `Looper` from the core audio path would
 *    break the existing slot tests.
 */
internal interface FullScreenAudioController {
    suspend fun applyOverrides(options: FullScreenAdOptions): AudioRestoreHandle?
}

/**
 * Reverts whatever [FullScreenAudioController.applyOverrides] changed.
 *
 * Owned by [FullScreenPresentationHandle] and invoked exactly once, immediately before the
 * arbiter token is released. Implementations must confine themselves to the main thread and must
 * revert only the properties that were actually overridden.
 */
internal fun interface AudioRestoreHandle {
    fun restore()
}
