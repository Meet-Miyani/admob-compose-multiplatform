package dev.avinya.ads.nativead.rendering

import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdInsets
import dev.avinya.ads.nativead.layout.AdModifier

internal fun resolveNativeAdBackgroundArgb(
    modifier: AdModifier,
    styleBackgroundArgb: Long?,
): Long? = modifier.backgroundArgb ?: styleBackgroundArgb

internal fun resolveNativeAdDrawableBackgroundArgb(
    modifier: AdModifier,
    styleBackgroundArgb: Long?,
): Long = resolveNativeAdBackgroundArgb(modifier, styleBackgroundArgb) ?: 0x00000000

internal fun resolveCallToActionContentInsets(
    modifier: AdModifier,
    style: AdButtonStyle,
): AdInsets = modifier.padding.takeUnless(::isZeroInset)
    ?: AdInsets(
        startDp = style.horizontalPaddingDp,
        endDp = style.horizontalPaddingDp,
    )

internal fun AdModifier.withoutPadding(): AdModifier = copy(padding = AdInsets())

private fun isZeroInset(insets: AdInsets): Boolean =
    insets.startDp == 0f && insets.topDp == 0f && insets.endDp == 0f && insets.bottomDp == 0f
