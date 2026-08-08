package dev.avinya.admob.showcase.ui.ad

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTextAlign
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.adLayout

/**
 * Card-shaped native ad for the feed: media-led, sized to sit among articles.
 *
 * `adBadge()` is policy-required — the SDK's validator warns without it, and
 * shipping an unlabelled native ad is a policy violation, not a style choice.
 */
val feedNativeAdLayout: AdLayout = adLayout {
    column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 8.dp,
        ) {
            icon(modifier = AdModifier.size(40.dp).cornerRadius(8.dp))
            column(modifier = AdModifier.weight(1f), spacing = 2.dp) {
                headline(style = AdTextStyle.title, maxLines = 2)
                row(spacing = 6.dp) {
                    advertiser(style = AdTextStyle.caption)
                    starRating(style = AdTextStyle.caption)
                }
            }
            adBadge(
                modifier = AdModifier
                    .background(Color(0xFFF59E0B))
                    .cornerRadius(12.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                text = "SPONSORED",
                style = AdTextStyle(fontSizeSp = 10f, colorArgb = 0xFF000000, fontWeight = AdFontWeight.Bold),
            )
        }
        media(modifier = AdModifier.fillMaxWidth().aspectRatio(16f / 9f).cornerRadius(8.dp))
        body(style = AdTextStyle.body, maxLines = 3)
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

