package dev.avinya.admob.showcase.core.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Both SDKs print the same id, in two different sentences:
 *
 * ```
 * UserMessagingPlatform: Use new ConsentDebugSettings.Builder().addTestDeviceHashedId("<ID>") ...
 * GoogleMobileAds: Use RequestConfiguration.Builder().setTestDeviceIds(Arrays.asList("<ID>")) ...
 * ```
 *
 * Matching the quoted 32-hex payload catches either, so this keeps working if Google reworks the
 * surrounding prose or drops one of the two lines.
 */
private val LOGGED_ID = Regex("""addTestDeviceHashedId\("([0-9A-F]{32})"\)|setTestDeviceIds\([^)]*"([0-9A-F]{32})"""")

internal actual val supportsLoggedTestDeviceIdDetection: Boolean = true

/**
 * Reads the id out of this process's own log buffer.
 *
 * An app may run `logcat` for itself: since Jelly Bean the log daemon filters by uid, so this
 * returns only entries this app emitted — which is exactly where the SDKs printed the id. No
 * permission is required and no other app's logs are visible.
 *
 * `-d` dumps and exits rather than streaming, so this cannot hang. Failure of any kind — an OEM
 * that blocks the binary, a truncated buffer, no id logged yet — collapses to `null` so the caller
 * can tell the user where to look instead.
 */
internal actual suspend fun readLoggedTestDeviceId(): String? = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "brief").redirectErrorStream(true).start()
        val matched = process.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                LOGGED_ID.find(line)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            }.lastOrNull()
        }
        process.destroy()
        matched
    }.getOrNull()
}
