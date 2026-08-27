@file:OptIn(ExperimentalForeignApi::class)

package dev.avinya.ads

import GoogleMobileAds.GADAdapterInitializationStateReady
import GoogleMobileAds.GADAdapterStatus
import GoogleMobileAds.GADMobileAds
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.withContext
import kotlin.runCatching

internal class IosAdDiagnostics : AdDiagnostics {

    private val snapshot = MutableStateFlow<DiagnosticsSnapshot?>(null)

    private data class DiagnosticsSnapshot(
        val sdkVersion: String?,
        val adapterStatuses: List<AdapterInitializationStatus>
    )

    /**
     * Reads GMA's version and adapter statuses. MUST be called on the main
     * dispatcher — it touches GADMobileAds directly. Called once from
     * IosGoogleAdManager's initialization block, which runs on
     * nativeInitializationScope (Dispatchers.Main).
     */
    internal fun captureSnapshotOnMain() {
        snapshot.value = DiagnosticsSnapshot(
            sdkVersion = runCatching {
                GADMobileAds.sharedInstance.versionNumber
                    .useContents { "${majorVersion}.${minorVersion}.${patchVersion}" }
            }.getOrNull(),
            adapterStatuses = runCatching {
                val status = GADMobileAds.sharedInstance.initializationStatus
                    ?: return@runCatching emptyList()
                status.adapterStatusesByClassName.map { (name, adapterStatus) ->
                    val s = adapterStatus as? GADAdapterStatus
                    AdapterInitializationStatus(
                        adapterName = name as String,
                        initialized = s?.state == GADAdapterInitializationStateReady,
                        latencyMillis = (s?.latency?.times(1000.0))?.toLong() ?: 0L,
                        description = s?.description
                    )
                }
            }.getOrDefault(emptyList())
        )
    }

    override suspend fun openAdInspector(): Boolean = withContext(Dispatchers.Main.immediate) {
        val rootVC = topViewController() ?: return@withContext false
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { }
            GADMobileAds.sharedInstance.presentAdInspectorFromViewController(rootVC) { error ->
                if (continuation.isActive) continuation.resume(error == null)
            }
        }
    }

    override suspend fun openDebugMenu(adUnitId: String): Boolean {
        AdLogger.w("Debug menu is Android-only.")
        return false
    }

    override fun sdkVersion(): String? = snapshot.value?.sdkVersion

    override fun adapterStatuses(): List<AdapterInitializationStatus> =
        snapshot.value?.adapterStatuses ?: emptyList()
}
