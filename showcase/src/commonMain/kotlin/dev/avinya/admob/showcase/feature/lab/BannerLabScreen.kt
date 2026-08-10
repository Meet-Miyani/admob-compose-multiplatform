package dev.avinya.admob.showcase.feature.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.ui.kit.StatRow
import dev.avinya.admob.showcase.ui.kit.SunkenPanel
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdEvent
import dev.avinya.ads.ui.BannerAdView

/**
 * Every banner shape the app can produce, in one place.
 *
 * No banner sits in a scrolling feed — that is the argument, not an oversight.
 * The collapsible example here is the same placement the article reader
 * anchors below its content, so the Lab and the product cannot drift apart.
 */
@Composable
fun BannerLabScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastEvent by remember { mutableStateOf("No banner events yet") }

    LabScreen(
        title = "Banner",
        subtitle = "Adaptive and fixed sizes, with live events",
        onBack = onBack,
        modifier = modifier,
    ) {
        item(key = "adaptive") {
            LabSection(
                title = "Anchored adaptive",
                description = "Sizes itself to the container width. Refresh is ad-server managed.",
            ) {
                BannerAdView(
                    placement = ShowcasePlacements.labBanner,
                    modifier = Modifier.fillMaxWidth(),
                    onEvent = { lastEvent = it.label() },
                )
            }
        }

        item(key = "fixed") {
            LabSection(
                title = "Fixed 320×50",
                description = "A bounded preview area for the classic fixed size.",
            ) {
                BannerAdView(
                    placement = ShowcasePlacements.labBanner,
                    widthDp = 320,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp),
                    onEvent = { lastEvent = it.label() },
                )
            }
        }

        item(key = "collapsible") {
            LabSection(
                title = "Collapsible",
                description = "The exact placement the article reader anchors below its content: " +
                    "it opens to the larger creative once, then collapses to a slim bar. Anchored " +
                    "to the bottom, so it must be rendered at the bottom of the screen to behave.",
            ) {
                BannerAdView(
                    placement = ShowcasePlacements.articleBanner,
                    modifier = Modifier.fillMaxWidth(),
                    onEvent = { lastEvent = it.label() },
                )
            }
        }

        item(key = "events") {
            LabSection(
                title = "Events",
                description = "The most recent sanitised lifecycle event across the banners " +
                    "above — no platform tokens, no ad unit ids.",
            ) {
                SunkenPanel(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(Tokens.Spacing.s12)) {
                        StatRow(label = "Adaptive / fixed", value = ShowcasePlacements.labBanner.id)
                        StatRow(label = "Collapsible", value = ShowcasePlacements.articleBanner.id)
                        StatRow(label = "Last event", value = lastEvent)
                    }
                }
            }
        }
    }
}

private fun AdEvent.label(): String = when (this) {
    is AdEvent.Loaded -> "Loaded ($placementId)"
    is AdEvent.LoadFailed -> "Load failed ($placementId): ${error.message}"
    is AdEvent.Impression -> "Impression"
    is AdEvent.Clicked -> "Clicked"
    is AdEvent.Paid -> "Paid"
    else -> this::class.simpleName ?: "Event"
}
