package dev.avinya.admob.showcase.ui.kit

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/** How far the large title has collapsed: 0 = fully open, 1 = fully collapsed. */
private const val COLLAPSE_DISTANCE_PX = 160f

/**
 * The app's page header.
 *
 * One component covers both cases the app has: a top-level destination with a
 * wordmark and a large serif title, and a child route with a back action and a
 * compact title. Child routes *always* get [onBack] — a pushed screen with no
 * way out was the previous shell's worst bug, and it only shows up on iOS,
 * where there is no system back gesture out of a Compose `NavDisplay`.
 *
 * [collapseProgress] drives a continuous transition rather than a boolean
 * swap: the large title shrinks and fades out while the compact title fades in,
 * so scrolling a feed never snaps the chrome from one layout to another.
 */
@Composable
fun AppHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    wordmark: String? = null,
    onBack: (() -> Unit)? = null,
    collapseProgress: Float = 0f,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = showcaseColors
    val progress = collapseProgress.coerceIn(0f, 1f)
    val isChildRoute = onBack != null
    // A child route is permanently in its compact form.
    val collapsed = if (isChildRoute) 1f else progress

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.canvas)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(
                    start = if (isChildRoute) Tokens.Spacing.s4 else Tokens.Spacing.gutterCompact,
                    end = Tokens.Spacing.s8,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
        ) {
            if (onBack != null) {
                IconAction(
                    icon = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack,
                    tint = palette.ink,
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                // Wordmark and compact title occupy the same slot and cross-fade,
                // so neither one shifts the row's height as they swap.
                if (!isChildRoute) {
                    Text(
                        text = (wordmark ?: "").uppercase(),
                        style = ShowcaseType.wordmark,
                        color = palette.accent,
                        modifier = Modifier.alpha(1f - collapsed),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(collapsed),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s2),
                content = actions,
            )
        }

        if (!isChildRoute) {
            // The large title's *height* is animated as well as its opacity, so
            // the content below rises smoothly instead of jumping when the
            // header finishes collapsing.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(1f - collapsed)
                    .collapsingHeight(1f - collapsed)
                    .padding(
                        start = Tokens.Spacing.gutterCompact,
                        end = Tokens.Spacing.gutterCompact,
                        bottom = Tokens.Spacing.s12,
                    ),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s2),
            ) {
                Text(text = title, style = MaterialTheme.typography.displaySmall, color = palette.ink)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                }
            }
        }

        Rule()
    }
}

/**
 * Scales a composable's measured height by [fraction] without re-laying out its
 * contents, so a collapsing block shrinks smoothly instead of reflowing text
 * on every frame.
 */
private fun Modifier.collapsingHeight(fraction: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height * fraction.coerceIn(0f, 1f)).toInt()
    layout(placeable.width, height) {
        placeable.placeRelative(0, height - placeable.height)
    }
}

/**
 * A header bound to a list's scroll position.
 *
 * The collapse is proportional to how far the first item has scrolled, so the
 * transition tracks the finger rather than firing at a threshold.
 */
@Composable
fun CollapsingAppHeader(
    title: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    wordmark: String? = "Fieldnotes",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val rawProgress by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / COLLAPSE_DISTANCE_PX).coerceIn(0f, 1f)
            }
        }
    }
    // Smooth the tail of the gesture — a fling can jump the offset in big
    // steps, and the animation hides the discontinuity.
    val progress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(120),
        label = "headerCollapse",
    )

    AppHeader(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        wordmark = wordmark,
        collapseProgress = progress,
        actions = actions,
    )
}

/** A thin progress rule, used by the article reader. */
@Composable
fun ProgressRule(fraction: Float, modifier: Modifier = Modifier) {
    val palette = showcaseColors
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), label = "readingProgress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(palette.hairline),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animated)
                .height(2.dp)
                .background(palette.accent)
                .alpha(if (animated > 0f) 1f else 0f),
        )
    }
}
