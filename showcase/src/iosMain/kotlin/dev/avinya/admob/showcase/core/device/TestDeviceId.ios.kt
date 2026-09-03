package dev.avinya.admob.showcase.core.device

internal actual val supportsLoggedTestDeviceIdDetection: Boolean = false

/**
 * Not supported on iOS: there is no in-process equivalent of reading your own log buffer, so the
 * id has to be copied out of the Xcode console and entered in the Privacy lab. Returning `null`
 * keeps the unsupported platform boundary explicit.
 */
internal actual suspend fun readLoggedTestDeviceId(): String? = null
