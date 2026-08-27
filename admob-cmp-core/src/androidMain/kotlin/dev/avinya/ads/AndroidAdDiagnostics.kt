package dev.avinya.ads

import android.app.Activity
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.common.OnAdInspectorClosedListener
import com.google.android.libraries.ads.mobile.sdk.initialization.AdapterStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal class AndroidAdDiagnostics(
    private val activityProvider: () -> Activity?
) : AdDiagnostics {
    private val snapshot = MutableStateFlow<DiagnosticsSnapshot?>(null)

    private data class DiagnosticsSnapshot(
        val sdkVersion: String?,
        val adapterStatuses: List<AdapterInitializationStatus>
    )

    /**
     * Reads GMA's version and adapter statuses. MUST be called on the main dispatcher. Called
     * once from [AndroidGoogleAdManager]'s native initialization, which runs on
     * `nativeInitializationScope` (`Dispatchers.Main.immediate`).
     */
    internal fun captureSnapshotOnMain() {
        snapshot.value = DiagnosticsSnapshot(
            sdkVersion = runCatching { MobileAds.getVersion().toString() }.getOrNull(),
            adapterStatuses = runCatching {
                MobileAds.getInitializationStatus().adapterStatusMap.map { (name, status) ->
                    AdapterInitializationStatus(
                        adapterName = name,
                        initialized = status.initializationState == AdapterStatus.InitializationState.COMPLETE,
                        latencyMillis = status.latency.toLong(),
                        description = status.description
                    )
                }
            }.getOrElse { emptyList() }
        )
    }

    override suspend fun openAdInspector(): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            MobileAds.openAdInspector(
                object : OnAdInspectorClosedListener {
                    override fun onAdInspectorClosed(error: com.google.android.libraries.ads.mobile.sdk.common.AdInspectorError?) {
                        continuation.resume(error == null)
                    }
                }
            )
        }
    }

    override suspend fun openDebugMenu(adUnitId: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            val activity = activityProvider() ?: return@withContext false
            MobileAds.openDebugMenu(activity, adUnitId)
            true
        }

    override fun sdkVersion(): String? = snapshot.value?.sdkVersion

    override fun adapterStatuses(): List<AdapterInitializationStatus> =
        snapshot.value?.adapterStatuses ?: emptyList()
}
