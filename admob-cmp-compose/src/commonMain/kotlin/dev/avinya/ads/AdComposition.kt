package dev.avinya.ads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * An immutable, Compose-stable holder for a list of [AdPlacement]s.
 * Provides lookup by id via [AdPlacements.placement].
 *
 * [items] is defensively copied at construction. `@Immutable` is a promise to the Compose
 * runtime that nothing observable here can change after construction, and it acts on that
 * promise by skipping recomposition. Retaining the caller's `List` reference broke the
 * promise: passing a `MutableList` and mutating it later changed placement lookups behind
 * Compose's back, with no identity change to signal it.
 */
@Immutable
public class AdPlacements(items: List<AdPlacement>) {
    /** The placements held by this instance. */
    public val items: List<AdPlacement> = items.toList()
}

/**
 * CompositionLocal providing the active [AdManager] to the composable tree.
 * Defaults to [NoOpAdManager] when not provided (all loads fail with
 * [AdError.sdkNotReady]).
 */
public val LocalAdManager: ProvidableCompositionLocal<AdManager> = staticCompositionLocalOf { NoOpAdManager }
/**
 * CompositionLocal providing the configured [AdPlacements] list.
 * Defaults to an empty list when not provided.
 */
public val LocalAdPlacements: ProvidableCompositionLocal<AdPlacements> = staticCompositionLocalOf { AdPlacements(emptyList()) }

/**
 * Looks up an enabled placement by [id] from this list.
 * @return The matching [AdPlacement] if found and enabled, or null.
 */
public fun List<AdPlacement>.placement(id: String): AdPlacement? = firstOrNull { it.id == id && it.enabled }

/**
 * Looks up an enabled placement by [id] from this [AdPlacements] wrapper.
 * @return The matching [AdPlacement] if found and enabled, or null.
 */
public fun AdPlacements.placement(id: String): AdPlacement? = items.placement(id)

/**
 * Returns the process-wide singleton [AdManager] for use in Compose.
 * 
 * **Best Practice:**
 * Call this once at the root of your app and pass it down via [LocalAdManager] 
 * (using `CompositionLocalProvider`). This ensures all your nested screens and 
 * ad views share the same initialized SDK instance.
 *
 * On Android, this safely resolves the instance from the application context.
 */
@Composable
public expect fun rememberAdManager(): AdManager
