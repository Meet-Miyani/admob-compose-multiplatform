package dev.avinya.ads

import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where mediation adapter initialization stands.
 *
 * [AdManagerStatus.Ready] means the SDK accepts ad requests. It no longer means every mediation
 * adapter has finished starting: Ready comes once the adapters report back or
 * [AdManager.adapterStartupWait] has passed since the SDK started accepting requests, whichever
 * is first. This state follows the adapters on their own, for logging and for work that needs
 * their statuses.
 *
 * New states may be added in minor releases. Include an `else` branch when you switch on it.
 */
public sealed interface AdapterStartup {
    /** Native initialization has not started, or failed before the SDK accepted requests. */
    public data object NotStarted : AdapterStartup

    /** The SDK accepts requests; mediation adapters are still initializing. */
    public data object InProgress : AdapterStartup

    /**
     * The SDK's adapter-initialization callback arrived.
     *
     * @property adapters each adapter's status, as [AdDiagnostics.adapterStatuses] now reports.
     * @property elapsed from the moment the SDK accepted requests to the callback.
     */
    public data class Finished(
        val adapters: List<AdapterInitializationStatus>,
        val elapsed: Duration,
    ) : AdapterStartup

    /**
     * The SDK never reported adapter initialization within the wrapper's watchdog (40 seconds,
     * beyond the SDK's own documented 30). Ready is unaffected and ads keep loading; adapters that
     * never started simply do not take part.
     */
    public data class NotReported(val elapsed: Duration) : AdapterStartup

    /** This [AdManager] — [NoOpAdManager], or a custom implementation — does not report adapter startup. */
    public data object Unavailable : AdapterStartup
}

/**
 * Where mediation adapter initialization stands for this manager. See [AdapterStartup].
 *
 * An extension over an internal capability rather than a member, so [AdManager] gains no
 * abstract member. A manager without the capability reports [AdapterStartup.Unavailable].
 */
public val AdManager.adapterStartup: StateFlow<AdapterStartup>
    get() = (this as? AdapterStartupSource)?.adapterStartupState ?: UnavailableAdapterStartup

/** Capability behind [adapterStartup]. Implemented by the platform managers. */
internal interface AdapterStartupSource {
    val adapterStartupState: StateFlow<AdapterStartup>
}

/** Rejects an [AdManager.adapterStartupWait] outside zero to [AdManager.MAX_ADAPTER_STARTUP_WAIT]. */
internal fun requireValidAdapterStartupWait(wait: Duration) {
    require(wait.isFinite() && wait >= Duration.ZERO && wait <= AdManager.MAX_ADAPTER_STARTUP_WAIT) {
        "AdManager.adapterStartupWait must be between 0 and ${AdManager.MAX_ADAPTER_STARTUP_WAIT}, was $wait."
    }
}

private val UnavailableAdapterStartup: StateFlow<AdapterStartup> =
    MutableStateFlow<AdapterStartup>(AdapterStartup.Unavailable).asStateFlow()
