package dev.avinya.ads

import dev.avinya.ads.internal.RequestConfigurationUpdater

/**
 * Outcome of [updateGlobalRequestConfiguration].
 */
public sealed interface RequestConfigurationUpdateResult {
    /**
     * The new configuration is in force for every subsequent ad request.
     *
     * @param invalidatedCachedAds true when cached inventory was dropped because a field that
     *   affects what the ad servers return changed. Ads already loaded were requested under
     *   the previous policy, so serving them afterwards would contradict the update that was
     *   just applied. Audio and test-device changes do not invalidate anything.
     */
    public data class Applied(val invalidatedCachedAds: Boolean) : RequestConfigurationUpdateResult

    /**
     * The manager has not initialized yet, so there is nothing to update.
     *
     * Pass the configuration to [AdManager.initialize] instead; this is not a failure.
     */
    public data object NotInitialized : RequestConfigurationUpdateResult

    /** This [AdManager] implementation cannot update request configuration. */
    public data object Unsupported : RequestConfigurationUpdateResult

    /** The platform SDK rejected the update; the previous configuration is still in force. */
    public data class Failed(val error: AdError) : RequestConfigurationUpdateResult
}

/**
 * Applies a new [GlobalRequestConfiguration] to every subsequent ad request.
 *
 * [AdManager.initialize] deliberately treats its whole configuration as process identity, so a
 * second call with different values is an `INITIALIZATION_CONFLICT` telling the caller to
 * restart the process. That is right for the app ID, which the platform SDK genuinely cannot
 * be told twice. It is wrong for request configuration: Android exposes
 * `MobileAds.setRequestConfiguration()` and iOS a mutable `requestConfiguration`, both
 * intended to be set at runtime. Before this existed, an app that had to change its content
 * rating or age treatment for a new profile — or merely turn the ad volume down — had no
 * supported way to do it.
 *
 * What does NOT change: the app ID stays immutable, `initialize()` keeps its existing conflict
 * semantics, and the native SDK is still initialized exactly once per process.
 *
 * Inventory loaded under the old policy is dropped when a serving-relevant field changes
 * (content rating, age treatment, publisher personalization, publisher first-party ID),
 * because those ads were requested under rules that no longer apply. Changing only
 * [GlobalRequestConfiguration.appMuted], [GlobalRequestConfiguration.appVolume] or
 * [GlobalRequestConfiguration.testDeviceIds] keeps the cache: none of them affects what the
 * ad servers returned.
 *
 * Serialized against `initialize()`, so a concurrent update and initialization cannot
 * interleave, and applied on the main thread like every other GMA call.
 *
 * ```kotlin
 * when (val result = adManager.updateGlobalRequestConfiguration(profileConfiguration)) {
 *     is RequestConfigurationUpdateResult.Applied -> if (result.invalidatedCachedAds) reloadAds()
 *     RequestConfigurationUpdateResult.NotInitialized -> adManager.initialize(config)
 *     RequestConfigurationUpdateResult.Unsupported -> Unit
 *     is RequestConfigurationUpdateResult.Failed -> report(result.error)
 * }
 * ```
 */
public suspend fun AdManager.updateGlobalRequestConfiguration(
    configuration: GlobalRequestConfiguration,
): RequestConfigurationUpdateResult {
    // An extension over an internal capability rather than a member: adding an abstract member
    // to AdManager would break every consumer that implements it for testing, and the public
    // ABI is frozen. A manager that does not implement the capability — the no-op manager, or
    // a third-party one — reports Unsupported rather than silently doing nothing.
    val updater = this as? RequestConfigurationUpdater ?: return RequestConfigurationUpdateResult.Unsupported
    return updater.updateGlobalRequestConfiguration(configuration)
}
