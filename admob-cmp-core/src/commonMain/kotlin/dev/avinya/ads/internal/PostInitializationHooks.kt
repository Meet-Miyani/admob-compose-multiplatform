package dev.avinya.ads.internal

import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdInitializationPhase
import dev.avinya.ads.AdLogger
import dev.avinya.ads.dispatchInitializationHooks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Runs the publisher's `AfterMobileAdsInitialize` hooks as an isolated post-commit step.
 *
 * These hooks are host code running after the process-wide ad SDK singleton has been initialized and
 * its identity committed. Nothing they do can un-initialize it, so their failure must not be
 * reported as an initialization failure.
 *
 * They MUST run after the identity commit, never before it. Dispatched earlier, a throwing hook
 * leaves the wrapper believing no configuration was applied while GMA is already running, and a
 * later `initialize()` with a different app ID then tries to reconfigure an immutable singleton.
 *
 * Failure is logged rather than tracked in a field. The health that matters — whether native
 * acceptance happened — is already the applied identity and terminal status; a hook failure only
 * needs to be *isolated* from those, and a write-only flag nothing reads would be dead weight.
 */
internal suspend fun dispatchAfterInitializeHooks(config: AdConfig) {
    try {
        config.dispatchInitializationHooks(AdInitializationPhase.AfterMobileAdsInitialize)
    } catch (cancellation: CancellationException) {
        // Distinguish the detached initialization scope being torn down (honour it) from a hook
        // that merely threw a CancellationException of its own (report it, like any other failure).
        if (!currentCoroutineContext().isActive) throw cancellation
        AdLogger.e(
            "An AfterMobileAdsInitialize hook was cancelled. The ad SDK stays initialized and " +
                "Ready; the hook will not be retried.",
            cancellation
        )
    } catch (hookFailure: Throwable) {
        AdLogger.e(
            "An AfterMobileAdsInitialize hook failed. The ad SDK stays initialized and Ready; " +
                "the hook will not be retried.",
            hookFailure
        )
    }
}
