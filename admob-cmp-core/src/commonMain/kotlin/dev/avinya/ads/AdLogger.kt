package dev.avinya.ads

/**
 * Severity level for an [AdLogger] message.
 *
 * Ordinal order is significant: [AdLogger.minLevel] filters by comparing ordinals, so
 * `Verbose < Debug < Info < Warn < Error`.
 */
public enum class AdLogLevel { Verbose, Debug, Info, Warn, Error }

internal expect object AdPlatformLogger {
    fun log(level: AdLogLevel, tag: String, message: String, throwable: Throwable?)
}

/**
 * Receives every [AdLogger] message at or above [AdLogger.minLevel].
 *
 * Setting [AdLogger.sink] REPLACES the platform log (`android.util.Log` / `NSLog`) for
 * messages at or above [AdLogger.minLevel] — it does not tee to both. Call through to the
 * platform log yourself from inside the sink if you want both destinations.
 */
public fun interface AdLogSink {
    public fun log(level: AdLogLevel, tag: String, message: String, throwable: Throwable?)
}

/**
 * Internal diagnostic logging for the SDK's own operations — initialization, consent,
 * ad lifecycle, native-ad session management. This is NOT an analytics or crash-reporting
 * channel; it exists so a developer can see what the SDK is doing.
 *
 * **Threading:** [minLevel] and [sink] are ordinary properties with no synchronization.
 * Set them once, during application startup, before calling `initialize()`. The SDK begins
 * logging from multiple coroutines immediately once initialization starts, so mutating
 * either property after that point is a data race.
 *
 * **Default behavior:** with no [sink] set, every message at or above [minLevel] (default
 * [AdLogLevel.Verbose] — everything) goes to the platform log, tagged `"AdMobCMP"`:
 * `android.util.Log` on Android, `NSLog` on iOS.
 *
 * **Silencing in release:** set [minLevel] to [AdLogLevel.Warn] or [AdLogLevel.Error] during
 * app startup to drop routine diagnostic noise while keeping warnings/errors visible.
 *
 * **Routing to your own logger:** set [sink] to forward matching messages to Timber, OSLog,
 * a crash-reporter's breadcrumb trail, or any other destination — see [AdLogSink] for
 * whether this replaces or supplements the platform log.
 */
public object AdLogger {
    private const val TAG = "AdMobCMP"

    /** Messages below this level are dropped before reaching the platform log or [sink]. */
    public var minLevel: AdLogLevel = AdLogLevel.Verbose

    /** When set, receives every message at or above [minLevel] instead of the platform log. */
    public var sink: AdLogSink? = null

    public fun v(message: String) { dispatch(AdLogLevel.Verbose, message, null) }
    public fun d(message: String) { dispatch(AdLogLevel.Debug, message, null) }
    public fun i(message: String) { dispatch(AdLogLevel.Info, message, null) }
    public fun w(message: String) { dispatch(AdLogLevel.Warn, message, null) }
    public fun w(message: String, throwable: Throwable?) { dispatch(AdLogLevel.Warn, message, throwable) }
    public fun e(message: String) { dispatch(AdLogLevel.Error, message, null) }
    public fun e(message: String, throwable: Throwable?) { dispatch(AdLogLevel.Error, message, throwable) }

    private fun dispatch(level: AdLogLevel, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val activeSink = sink
        if (activeSink != null) {
            activeSink.log(level, TAG, message, throwable)
        } else {
            AdPlatformLogger.log(level, TAG, message, throwable)
        }
    }
}
