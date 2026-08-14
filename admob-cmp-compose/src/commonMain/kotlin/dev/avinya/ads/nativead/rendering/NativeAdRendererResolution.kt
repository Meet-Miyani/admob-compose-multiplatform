package dev.avinya.ads.nativead.rendering

import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdButtonTextCase
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

/**
 * Applies [AdButtonStyle.textCase] to a creative's call-to-action label.
 *
 * Shared by every renderer (Android, iOS, preview) so a layout previews with the same wording it
 * ships with — the divergence that made the old media-background bug invisible until runtime.
 */
internal fun resolveCallToActionText(text: String, textCase: AdButtonTextCase): String =
    when (textCase) {
        AdButtonTextCase.AsProvided -> text
        AdButtonTextCase.SentenceCase -> text.sentenceCaseIfAllCaps()
    }

/**
 * Sentence-cases a string only when it carries no case information of its own — i.e. it contains
 * letters and every one of them is uppercase. Mixed-case labels are returned untouched so
 * deliberate capitalisation ("Shop at H&M") is never flattened.
 */
private fun String.sentenceCaseIfAllCaps(): String {
    val firstLetter = indexOfFirst(Char::isLetter)
    if (firstLetter < 0) return this
    if (any { it.isLetter() && it.isLowerCase() }) return this
    val lowered = lowercase()
    return lowered.substring(0, firstLetter) +
        lowered[firstLetter].uppercaseChar() +
        lowered.substring(firstLetter + 1)
}

internal fun AdModifier.withoutPadding(): AdModifier = copy(padding = AdInsets())

private fun isZeroInset(insets: AdInsets): Boolean =
    insets.startDp == 0f && insets.topDp == 0f && insets.endDp == 0f && insets.bottomDp == 0f
