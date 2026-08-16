package dev.avinya.admob.showcase.ui.ad

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * Feed treatment: cover-led, for a hero-sized slot.
 *
 * @param surface the colour this ad sits on, painted onto the layout root so the iOS renderer has
 * an opaque surface to draw (see [rememberFeedRowAdLayout]). This treatment is rendered framed
 * inside a `Plane`, so the default is the raised surface rather than the canvas.
 */
@Composable
fun rememberFeedAdLayout(surface: Color = showcaseColors.surface): AdLayout {
    val palette = showcaseColors
    val headlineFamily = MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Serif
    return remember(palette, headlineFamily, surface) { feedAdLayout(palette, headlineFamily, surface) }
}

internal fun feedAdLayout(
    palette: ShowcasePalette,
    headlineFamily: FontFamily,
    surface: Color,
): AdLayout {
    val badge = badgeStyle(palette)
    return adLayout {
        column(modifier = AdModifier.fillMaxWidth().background(surface), spacing = 12.dp) {
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
                    style = badge,
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

            media(
                modifier = AdModifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .cornerRadius(20.dp),
            )

            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.Top,
                spacing = 12.dp,
            ) {
                icon(modifier = AdModifier.size(44.dp).cornerRadius(10.dp))
                column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                    headline(style = title(palette, headlineFamily), maxLines = 2)
                    body(style = body(palette), maxLines = 2)
                    row(spacing = 8.dp) {
                        starRating(style = caption(palette))
                        price(style = caption(palette))
                        store(style = caption(palette))
                    }
                }
            }

            callToAction(
                modifier = AdModifier.fillMaxWidth(),
                style = ctaStyle(palette),
            )
        }
    }
}

@Preview(
    name = "Light",
    showBackground = true,
    backgroundColor = 0xFFFBF9F5,
    widthDp = 390,
)
@Composable
private fun FeedAdLayoutLightPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Light) {
        Surface(color = showcaseColors.canvas) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                AdLayoutPreview(
                    layout = rememberFeedAdLayout(),
                    data = AdLayoutPreviewData.default,
                )
            }
        }
    }
}

@Preview(
    name = "Dark",
    showBackground = true,
    backgroundColor = 0xFF111110,
    widthDp = 390,
)
@Composable
private fun FeedAdLayoutDarkPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Dark) {
        Surface(color = showcaseColors.canvas) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                AdLayoutPreview(
                    layout = rememberFeedAdLayout(),
                    data = AdLayoutPreviewData.default,
                )
            }
        }
    }
}
