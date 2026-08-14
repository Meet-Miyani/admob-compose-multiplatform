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
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdContentScale
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdImageStyle
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutPreview
import dev.avinya.ads.nativead.layout.AdLayoutPreviewData
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.adLayout

/**
 * Feed-row treatment: the same geometry as `StoryCard`'s Standard row —
 * eyebrow, serif headline, two-line standfirst, meta line, and a square
 * thumbnail on the trailing edge.
 */
@Composable
fun rememberFeedRowAdLayout(): AdLayout {
    val palette = showcaseColors
    val headlineFamily = MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Serif
    return remember(palette, headlineFamily) { feedRowAdLayout(palette, headlineFamily) }
}

internal fun feedRowAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout = adLayout {
    row(
        modifier = AdModifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = AdAlignment.Vertical.Top,
        spacing = 16.dp,
    ) {
        column(modifier = AdModifier.weight(1f), spacing = 8.dp) {
            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.CenterVertically,
                spacing = 6.dp,
            ) {
                adBadge(
                    modifier = AdModifier.wrapContentSize(),
                    text = "SPONSORED",
                    style = AdTextStyle(
                        fontSizeSp = 11f,
                        colorArgb = palette.accent.argb(),
                        fontWeight = AdFontWeight.Bold,
                    ),
                )
                spacer(AdModifier.weight(1f))
                adChoices(
                    modifier = AdModifier.size(16.dp),
                    visibilityPolicy = AdVisibilityPolicy.KeepSpace,
                )
            }

            headline(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(
                    fontSizeSp = 21f,
                    colorArgb = palette.ink.argb(),
                    fontWeight = AdFontWeight.Medium,
                    fontFamily = AdFontFamily.FromCompose(headlineFamily),
                ),
                maxLines = 3,
            )

            body(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(fontSizeSp = 13f, colorArgb = palette.inkMuted.argb()),
                maxLines = 2,
            )

            advertiser(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(fontSizeSp = 12f, colorArgb = palette.inkMuted.argb()),
                maxLines = 1,
                visibilityPolicy = AdVisibilityPolicy.HideWhenMissing,
            )
        }

        box(modifier = AdModifier.size(Tokens.feedThumbnail)) {
            icon(
                modifier = AdModifier.size(Tokens.feedThumbnail).cornerRadius(20.dp),
                style = AdImageStyle(
                    contentScale = AdContentScale.Crop,
                    backgroundArgb = palette.surfaceSunken.argb(),
                ),
            )
            media(
                modifier = AdModifier.size(Tokens.feedThumbnail).cornerRadius(20.dp),
                style = AdImageStyle(contentScale = AdContentScale.Crop),
                visibilityPolicy = AdVisibilityPolicy.HideWhenMissing,
            )
        }
    }
}

@Preview
@Composable
private fun FeedRowAdLayoutLightPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Light) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberFeedRowAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}

@Preview
@Composable
private fun FeedRowAdLayoutDarkPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Dark) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberFeedRowAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}
