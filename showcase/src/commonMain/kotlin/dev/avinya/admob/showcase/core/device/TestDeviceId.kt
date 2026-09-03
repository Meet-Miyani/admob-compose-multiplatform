package dev.avinya.admob.showcase.core.device

private val TEST_DEVICE_ID = Regex("[0-9A-F]{32}")

/** Returns the canonical GMA/UMP test-device id, or null when [value] is not a 32-hex id. */
internal fun normalizeTestDeviceId(value: String): String? =
    value.trim().uppercase().takeIf(TEST_DEVICE_ID::matches)

/** Whether this platform can read the SDK-generated id from its own process log. */
internal expect val supportsLoggedTestDeviceIdDetection: Boolean

/**
 * Recovers this device's hashed test-device id from what GMA and UMP printed to the platform log.
 *
 * Deliberately NOT computed. Google documents reading this value off the console and never
 * publishes the derivation, so reproducing it means reverse-engineering an undocumented scheme —
 * and getting it wrong fails silently: UMP ignores an unregistered device without complaint, so a
 * plausible-looking but wrong id presents exactly as "the debug geography does nothing", which is
 * the bug this whole feature exists to remove. Reading the SDK's own output cannot drift from the
 * SDK, and when it finds nothing it says so.
 *
 * Returns `null` when no id has been logged yet — the SDK only prints it once an ad request or a
 * consent info update has actually run — or when the platform does not support reading it.
 */
internal expect suspend fun readLoggedTestDeviceId(): String?
