package dev.avinya.admob.showcase.di

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.avinya.ads.AdManager
import dev.avinya.admob.showcase.core.time.Clock
import dev.avinya.admob.showcase.core.time.SystemClock
import dev.avinya.admob.showcase.data.db.ShowcaseDatabase
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.data.repo.AdStateRepository
import dev.avinya.admob.showcase.data.repo.AdTelemetryRepository
import dev.avinya.admob.showcase.data.repo.ArticleRepository
import dev.avinya.admob.showcase.data.repo.WalletRepository
import dev.avinya.admob.showcase.domain.ad.AdStartupController
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.ad.AppOpenSuppressor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

/**
 * Manual dependency graph, constructed once per process.
 *
 * Hand-rolled rather than Koin or Hilt: the graph is small, and a demo whose
 * point is to be read benefits from wiring you can follow by eye.
 */
class AppGraph(storage: PlatformStorage) {

    val clock: Clock = SystemClock

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: ShowcaseDatabase = storage.databaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        // Destructive is correct *here* and only here: every row in the
        // database at this point is regenerable seed content, so a real
        // Migration would be ceremony with no user-visible benefit.
        //
        // This stops being acceptable from Phase 5 onward, when the wallet
        // holds coins the user earned by watching ads. Any schema change after
        // that needs a real Migration.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

    private val preferences: DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath { storage.dataStorePath().toPath() }

    val settings: SettingsRepository = SettingsRepository(preferences)

    val articles: ArticleRepository = ArticleRepository(database.articleDao(), clock)

    val wallet: WalletRepository = WalletRepository(database.walletDao(), clock)

    // Captured once at graph construction. Cold-start grace means cold start,
    // not cold launch — the value resets every process, which is the right
    // semantic for the interstitial's first 30s.
    private val coldStartAt: Long = clock.nowMillis()

    val adState: AdStateRepository = AdStateRepository(preferences, clock, coldStartAt)

    val startup: AdStartupController = AdStartupController(settings, appScope)

    /**
     * Drained from `adManager.events` by [startTelemetry]. Owns placement-id
     * → format lookup, so the mappers stay pure and the DAO never sees an
     * unknown id as a null.
     */
    val telemetry: AdTelemetryRepository = AdTelemetryRepository(
        telemetryDao = database.telemetryDao(),
        formatByPlacement = ShowcasePlacements.allPlacements.associate { it.id to it.format },
        appScope = appScope,
        clock = clock,
    )

    private var telemetryStarted = false

    /**
     * Launches a single app-scoped collector over [AdManager.events]. Called
     * once from `ShowcaseApp` after the `LocalAdManager` provider is in
     * place; calling it more than once would double-record every event.
     */
    fun startTelemetry(adManager: AdManager) {
        if (telemetryStarted) return
        telemetryStarted = true
        appScope.launch {
            adManager.events.collect { event ->
                telemetry.record(event, clock.nowMillis())
            }
        }
    }

    companion object {
        fun get(storage: PlatformStorage): AppGraph {
            return getOrCreateAppGraph { AppGraph(storage) }
        }

        internal fun resetForTesting() {
            resetAppGraphForTesting()
        }
    }
}

internal expect fun getOrCreateAppGraph(create: () -> AppGraph): AppGraph
internal expect fun resetAppGraphForTesting()

/**
 * Set by [dev.avinya.admob.showcase.ShowcaseApp]. Reading it outside that
 * subtree is a programming error, so there is no default.
 */
val LocalAppGraph: ProvidableCompositionLocal<AppGraph> = compositionLocalOf {
    error("LocalAppGraph accessed outside ShowcaseApp")
}

/**
 * Lets a flow declare that an app-open ad must not appear over it. The Store
 * uses this for coin-unlock transactions; Phase 6's coordinator binds
 * [AppOpenSuppressor.isBlocked] to the SDK's app-open gate.
 *
 * Same access rule as [LocalAppGraph]: provided once in `ShowcaseApp` and
 * unresolved anywhere else is a programming error.
 */
val LocalAppOpenSuppressor: ProvidableCompositionLocal<AppOpenSuppressor> = compositionLocalOf {
    error("LocalAppOpenSuppressor accessed outside ShowcaseApp")
}

/**
 * Remembers the process-wide [AppGraph]. Called once from
 * [dev.avinya.admob.showcase.ShowcaseApp] and exposed through [LocalAppGraph]
 * so the rest of the composition can read repositories without taking them as
 * parameters.
 */
@Composable
internal fun rememberAppGraph(): AppGraph {
    val storage = rememberPlatformStorage()
    return remember { AppGraph.get(storage) }
}

