package dev.avinya.ads.internal

import dev.avinya.ads.AdLogger
import dev.avinya.ads.AdapterInitializationStatus
import dev.avinya.ads.AdapterStartup
import dev.avinya.ads.NativeInitializationHandle
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Follows mediation adapter initialization after the SDK accepts requests, for
 * `AdManager.adapterStartup`.
 *
 * `Ready` waits for the adapters only up to `AdManager.adapterStartupWait`; this tracker owns
 * that bounded wait and, after `Ready`, the rest of the adapters' story: the refreshed
 * diagnostics snapshot, one log line per adapter, and the [AdapterStartup] state. It never
 * touches `AdManager.status`, which is why a late or missing adapter callback cannot make `Ready`
 * regress. Everything here runs on Main, like the native initialization that calls it.
 */
internal class AdapterStartupTracker(
    private val platformTag: String,
    /** The platform's `captureDiagnosticsSnapshotOnMain`. */
    private val captureSnapshot: () -> Unit,
    /** The diagnostics snapshot's adapter statuses, read after [captureSnapshot]. */
    private val adapterStatuses: () -> List<AdapterInitializationStatus>,
) {
    private val _state = MutableStateFlow<AdapterStartup>(AdapterStartup.NotStarted)
    val state: StateFlow<AdapterStartup> = _state.asStateFlow()

    /** The SDK accepts requests; the adapters are still starting, and Ready waits up to [wait] for them. */
    fun onAccepted(wait: Duration) {
        AdLogger.i("$platformTag MobileAds accepts ad requests. Waiting up to $wait for mediation adapters.")
        _state.value = AdapterStartup.InProgress
    }

    /**
     * Waits up to [wait] for the adapters, and records them if they report. Returns whether they
     * did. A zero wait still sees a callback that fired before the SDK accepted requests.
     */
    suspend fun awaitWithin(handle: NativeInitializationHandle, wait: Duration, acceptedAt: TimeMark): Boolean {
        val reported = handle.adapters.isCompleted ||
            (wait > Duration.ZERO && withTimeoutOrNull(wait) { handle.adapters.await() } != null)
        if (reported) {
            recordFinished(acceptedAt)
        } else {
            AdLogger.i(
                "$platformTag publishing Ready after waiting $wait; mediation adapters are still " +
                    "initializing and will report through adapterStartup."
            )
        }
        return reported
    }

    /**
     * After Ready: waits for the adapters until [InitializationTimeouts.nativeInitialize] has
     * passed since accept, then reports them, or reports that the SDK never did.
     */
    suspend fun awaitRest(handle: NativeInitializationHandle, acceptedAt: TimeMark) {
        val remaining = InitializationTimeouts.nativeInitialize - acceptedAt.elapsedNow()
        val reported = remaining > Duration.ZERO &&
            withTimeoutOrNull(remaining) { handle.adapters.await() } != null
        if (reported) {
            recordFinished(acceptedAt)
        } else {
            AdLogger.w(
                "$platformTag MobileAds never reported mediation adapter initialization within " +
                    "${InitializationTimeouts.nativeInitialize}. Ads keep loading; adapters that never " +
                    "started do not take part."
            )
            _state.value = AdapterStartup.NotReported(acceptedAt.elapsedNow())
        }
    }

    /** Refreshes the diagnostics snapshot, logs each adapter, and publishes [AdapterStartup.Finished]. */
    private fun recordFinished(acceptedAt: TimeMark) {
        val elapsed = acceptedAt.elapsedNow()
        captureSnapshot()
        val adapters = adapterStatuses()
        AdLogger.i(
            "$platformTag mediation adapters finished in ${elapsed.inWholeMilliseconds} ms: " +
                "${adapters.count { it.initialized }} of ${adapters.size} ready."
        )
        adapters.forEach { adapter ->
            val description = adapter.description?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            AdLogger.i(
                "$platformTag adapter ${adapter.adapterName}: " +
                    "${if (adapter.initialized) "ready" else "not ready"} in ${adapter.latencyMillis} ms$description"
            )
        }
        _state.value = AdapterStartup.Finished(adapters, elapsed)
    }
}
