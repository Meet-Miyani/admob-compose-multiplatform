@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.avinya.ads

import kotlinx.cinterop.useContents
import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

internal fun topViewController(): UIViewController? {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val windowScene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?: scenes.firstOrNull()
        ?: return null
    val window = windowScene.keyWindow
        ?: windowScene.windows.filterIsInstance<UIWindow>().firstOrNull()
        ?: return null
    var top = window.rootViewController ?: return null
    while (top.presentedViewController != null) {
        val presented = top.presentedViewController!!
        if (presented.isBeingDismissed()) break
        top = presented
    }
    if (top.isBeingDismissed() || top.isBeingPresented()) return null
    return top
}

/**
 * Best-effort viewport width in points for a headless banner load, or null when no single
 * width would be truthful.
 *
 * MUST read the **key window's** bounds, never `UIScreen.mainScreen.bounds`. In iPad split
 * view, Slide Over and popovers the app owns a window narrower than the screen, and a
 * screen-bounds read silently sizes every banner to the full display. Window bounds are correct
 * in exactly those cases.
 *
 * Returns null when more than one foreground-active window scene exists: with several live
 * windows of the same app there is no single "the" width, and guessing one is exactly what
 * this function must not do. Hosts should supply [BannerGeometry] instead — `BannerAdView`
 * does so automatically from its own measurement.
 */
internal fun keyWindowWidthDp(): Int? {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val foreground = scenes.filter { it.activationState == UISceneActivationStateForegroundActive }
    if (foreground.size > 1) return null
    val windowScene = foreground.firstOrNull() ?: scenes.singleOrNull() ?: return null
    val window = windowScene.keyWindow
        ?: windowScene.windows.filterIsInstance<UIWindow>().firstOrNull()
        ?: return null
    return window.bounds.useContents { size.width }.toInt().takeIf { it > 0 }
}
