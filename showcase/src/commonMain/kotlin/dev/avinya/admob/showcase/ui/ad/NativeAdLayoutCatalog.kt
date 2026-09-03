@file:Suppress("UnusedPrivateMember")

package dev.avinya.admob.showcase.ui.ad

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.admob.showcase.ui.theme.ShowcaseTheme
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutPreview
import dev.avinya.ads.nativead.layout.AdLayoutPreviewData
import dev.avinya.ads.nativead.layout.AdTemplateColors
import dev.avinya.ads.nativead.layout.AdTemplates

/**
 * Every native ad layout this app can render, in one enumerable list.
 *
 * The list exists for `NativeAdLayoutCatalogTest`, which asserts each layout passes
 * [AdLayout.validation] in both palettes. An `@Preview` is a `private @Composable` no test can
 * enumerate, so eyeballing a preview proves nothing on CI; this enum is what turns "the layout
 * looked fine" into an assertion.
 *
 * Note the asymmetry with the previews below: all six layouts are catalogued, but only the three
 * `AdTemplates` ones are previewed *here*. The three Fieldnotes layouts are previewed next to the
 * composables that build them — see the `@Preview` functions at the bottom of `FeedAdLayout.kt`,
 * `FeedRowAdLayout.kt` and `InlineAdLayout.kt` — which is where a preview belongs. Previewing them
 * here as well would be a second definition of the same thing, free to drift from the first.
 *
 * `AdTemplates.compact`/`medium`/`feedCard` have no such home: they are `admob-cmp-compose` API,
 * not showcase composables, so there is no local function for their previews to sit beside. That
 * is the only reason this file carries previews at all.
 */
internal enum class ShowcaseNativeAdLayout(internal val label: String) {
    Compact("Compact"),
    Medium("Medium"),
    FeedCard("Feed card"),
    FieldnotesFeed("Fieldnotes feed"),
    FieldnotesRow("Fieldnotes row"),
    FieldnotesInline("Fieldnotes inline"),
    ;

    internal fun layout(
        palette: ShowcasePalette,
        headlineFamily: FontFamily,
    ): AdLayout {
        val templateColors = if (palette.isDark) AdTemplateColors.dark else AdTemplateColors.light
        return when (this) {
            Compact -> AdTemplates.compact(templateColors)
            Medium -> AdTemplates.medium(templateColors)
            FeedCard -> AdTemplates.feedCard(templateColors)
            FieldnotesFeed -> feedAdLayout(palette, headlineFamily, palette.surface)
            FieldnotesRow -> feedRowAdLayout(palette, headlineFamily, palette.canvas)
            FieldnotesInline -> inlineAdLayout(palette, headlineFamily, palette.surface)
        }
    }
}

@Composable
private fun NativeAdLayoutPreview(
    layoutCase: ShowcaseNativeAdLayout,
    themeMode: ThemeMode,
) {
    ShowcaseTheme(themeMode = themeMode) {
        val palette = showcaseColors
        val headlineFamily = MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Serif
        Surface(color = palette.canvas) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                AdLayoutPreview(
                    layout = layoutCase.layout(palette, headlineFamily),
                    modifier = Modifier.fillMaxWidth(),
                    data = AdLayoutPreviewData.default,
                )
            }
        }
    }
}

@Preview(
    name = "Compact - Light",
    group = "Ad templates",
    showBackground = true,
    backgroundColor = 0xFFFBF9F5,
    widthDp = 390,
)
@Composable
private fun CompactLightPreview() {
    NativeAdLayoutPreview(ShowcaseNativeAdLayout.Compact, ThemeMode.Light)
}

@Preview(
    name = "Compact - Dark",
    group = "Ad templates",
    showBackground = true,
    backgroundColor = 0xFF111110,
    widthDp = 390,
)
@Composable
private fun CompactDarkPreview() {
    NativeAdLayoutPreview(ShowcaseNativeAdLayout.Compact, ThemeMode.Dark)
}

@Preview(
    name = "Medium - Light",
    group = "Ad templates",
    showBackground = true,
    backgroundColor = 0xFFFBF9F5,
    widthDp = 390,
)
@Composable
private fun MediumLightPreview() {
    NativeAdLayoutPreview(ShowcaseNativeAdLayout.Medium, ThemeMode.Light)
}

@Preview(
    name = "Medium - Dark",
    group = "Ad templates",
    showBackground = true,
    backgroundColor = 0xFF111110,
    widthDp = 390,
)
@Composable
private fun MediumDarkPreview() {
    NativeAdLayoutPreview(ShowcaseNativeAdLayout.Medium, ThemeMode.Dark)
}

@Preview(
    name = "Feed card - Light",
    group = "Ad templates",
    showBackground = true,
    backgroundColor = 0xFFFBF9F5,
    widthDp = 390,
)
@Composable
private fun FeedCardLightPreview() {
    NativeAdLayoutPreview(ShowcaseNativeAdLayout.FeedCard, ThemeMode.Light)
}

@Preview(
    name = "Feed card - Dark",
    group = "Ad templates",
    showBackground = true,
    backgroundColor = 0xFF111110,
    widthDp = 390,
)
@Composable
private fun FeedCardDarkPreview() {
    NativeAdLayoutPreview(ShowcaseNativeAdLayout.FeedCard, ThemeMode.Dark)
}
