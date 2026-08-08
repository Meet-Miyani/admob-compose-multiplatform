package dev.avinya.admob.showcase.ui.ad

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutValidator
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTextAlign
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.adLayout

/**
 * Horizontal-band native ad that reads as part of the article body rather
 * than a card on its own. No large media — just an icon, a short headline,
 * a 2-line body, and a full-width call to action.
 *
 * `adBadge()` is policy-required — the SDK's validator warns without it, and
 * shipping an unlabelled native ad is a policy violation, not a style choice.
 * It sits in the top row so the validator can confirm the badge is at the top
 * of the ad, not buried beneath the icon.
 */
val inlineNativeAdLayout: AdLayout = adLayout {
    column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 8.dp,
        ) {
            adBadge(
                modifier = AdModifier
                    .background(Color(0xFFF59E0B))
                    .cornerRadius(12.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                text = "SPONSORED",
                style = AdTextStyle(fontSizeSp = 10f, colorArgb = 0xFF000000, fontWeight = AdFontWeight.Bold),
            )
            advertiser(
                modifier = AdModifier.weight(1f),
                style = AdTextStyle.caption,
                maxLines = 1,
            )
        }
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 12.dp,
        ) {
            icon(modifier = AdModifier.size(48.dp).cornerRadius(8.dp))
            column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                headline(style = AdTextStyle.title, maxLines = 2)
                body(style = AdTextStyle.body, maxLines = 2)
            }
        }
        callToAction(
            modifier = AdModifier.fillMaxWidth(),
            style = AdButtonStyle(
                textStyle = AdTextStyle(14f, 0xFF000000, AdFontWeight.Bold, AdTextAlign.Center),
                backgroundArgb = 0xFFC6452D, // Fieldnotes accent
                cornerRadiusDp = 8f,
            ),
        )
    }
}

// Surface validation findings at module load so a bad layout fails to render
// visibly, not silently. Warnings are non-fatal but logged for the same
// reason FeedNativeAdLayout mentions: missing the ad badge is policy-grade.
@Suppress("unused")
private val inlineNativeAdLayoutReport: Unit? =
    AdLayoutValidator.validate(inlineNativeAdLayout.root)
        .takeIf { it.warnings.isNotEmpty() }
        ?.let { report ->
            println(
                "inlineNativeAdLayout validation warnings: " +
                    report.warnings.joinToString { "${it.code}@${it.nodePath}: ${it.message}" }
            )
        }
