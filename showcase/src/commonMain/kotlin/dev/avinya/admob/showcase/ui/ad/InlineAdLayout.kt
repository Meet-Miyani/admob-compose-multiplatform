package dev.avinya.admob.showcase.ui.ad

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.admob.showcase.ui.theme.ShowcaseTheme
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutPreview
import dev.avinya.ads.nativead.layout.AdLayoutPreviewData
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.adLayout

/**
 * Article treatment: text-led band with no large media, so it reads as an
 * interruption in the column rather than a second hero.
 */
@Composable
fun rememberInlineAdLayout(): AdLayout {
    val palette = showcaseColors
    val headlineFamily = MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Serif
    return remember(palette, headlineFamily) { inlineAdLayout(palette, headlineFamily) }
}

internal fun inlineAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout = adLayout {
    column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 8.dp,
        ) {
            adBadge(
                modifier = AdModifier
                    .background(palette.accentSoft)
                    .cornerRadius(4.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                text = "SPONSORED",
                style = badgeStyle(palette),
            )
            advertiser(
                modifier = AdModifier.weight(1f),
                style = caption(palette),
                maxLines = 1,
            )
            adChoices(
                modifier = AdModifier.size(20.dp),
                visibilityPolicy = AdVisibilityPolicy.KeepSpace,
            )
        }
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 12.dp,
        ) {
            icon(modifier = AdModifier.size(48.dp).cornerRadius(10.dp))
            column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                headline(style = title(palette, headlineFamily), maxLines = 2)
                body(style = body(palette), maxLines = 2)
            }
        }
        callToAction(
            modifier = AdModifier.fillMaxWidth(),
            style = ctaStyle(palette),
        )
    }
}

@Preview
@Composable
private fun InlineAdLayoutLightPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Light) {
        Surface(color = showcaseColors.canvas) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                AdLayoutPreview(
                    layout = rememberInlineAdLayout(),
                    data = AdLayoutPreviewData.default,
                )
            }
        }
    }
}

@Preview
@Composable
private fun InlineAdLayoutDarkPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Dark) {
        Surface(color = showcaseColors.canvas) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                AdLayoutPreview(
                    layout = rememberInlineAdLayout(),
                    data = AdLayoutPreviewData.default,
                )
            }
        }
    }
}
