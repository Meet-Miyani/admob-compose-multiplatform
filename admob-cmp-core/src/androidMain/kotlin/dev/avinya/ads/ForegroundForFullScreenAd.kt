package dev.avinya.ads

import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.Process

/**
 * Whether Google's SDK will accept a full-screen show: the same three conditions GMA Next-Gen
 * 1.4.0 checks before presenting (`ads_mobile_sdk.t.a`). The screen is on, the keyguard is not
 * locked, and ActivityManager ranks this process as foreground (importance 100). Any other
 * state makes GMA refuse the show as a background show and log a policy violation.
 *
 * [importance] is null when this process is missing from the running-process list, which GMA
 * also treats as background.
 */
internal fun isForegroundForFullScreenAd(
    interactive: Boolean,
    keyguardLocked: Boolean,
    importance: Int?,
): Boolean = interactive &&
    !keyguardLocked &&
    importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND

/**
 * Reads the conditions for [isForegroundForFullScreenAd]. A missing system service or process
 * list counts as foreground, as it does in GMA's own check: the SDK must not block a show the
 * platform SDK itself would allow.
 */
internal fun Context.isForegroundForFullScreenAd(): Boolean {
    val keyguard = getSystemService(KeyguardManager::class.java) ?: return true
    val power = getSystemService(PowerManager::class.java) ?: return true
    val activityManager = getSystemService(ActivityManager::class.java) ?: return true
    val processes = activityManager.runningAppProcesses ?: return true
    val importance = processes.firstOrNull { it.pid == Process.myPid() }?.importance
    return isForegroundForFullScreenAd(power.isInteractive, keyguard.isKeyguardLocked, importance)
}
