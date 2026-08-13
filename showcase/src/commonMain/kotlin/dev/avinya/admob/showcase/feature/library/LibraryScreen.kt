package dev.avinya.admob.showcase.feature.library

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.library.LibraryEntry
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorHost
import dev.avinya.admob.showcase.ui.kit.CollapsingAppHeader
import dev.avinya.admob.showcase.ui.kit.EmptyState
import dev.avinya.admob.showcase.ui.kit.PillTabs
import dev.avinya.admob.showcase.ui.kit.ProgressRule
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.SearchField
import dev.avinya.admob.showcase.ui.kit.SectionTitle
import dev.avinya.admob.showcase.ui.kit.StoryCard
import dev.avinya.admob.showcase.ui.kit.StoryCardModel
import dev.avinya.admob.showcase.ui.kit.StoryTreatment
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdPlacement
import kotlinx.coroutines.launch

private const val FILTER_ALL = "All"

/**
 * The reader's own content — saved, in progress, and unlocked.
 *
 * **No banner, no native slot, no interstitial on this screen, by design.** A
 * showcase that puts an ad on every surface teaches the wrong lesson, so the
 * one screen about content you already own stays clean, and says why at the
 * bottom rather than leaving it as an accident of implementation.
 */
@Composable
fun LibraryScreen(
    onArticleClick: (String) -> Unit,
    onExploreFeedClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val graph = LocalAppGraph.current
    val entries by graph.articles.library().collectAsState(initial = emptyList())
    val bookmarkedArticles by graph.articles.bookmarkedArticles().collectAsState(initial = emptyList())
    val bookmarkedIds = remember(bookmarkedArticles) { bookmarkedArticles.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedFilter by remember { mutableStateOf(FILTER_ALL) }
    val filters = remember {
        listOf(FILTER_ALL) + LibraryEntry.Kind.entries.map { it.label() }
    }

    // Library is deliberately ad-free, so the Inspector opens with an empty
    // Placements tab — which is itself the point being made.
    val placements = remember { emptyList<AdPlacement>() }

    val filtered = remember(entries, selectedFilter) {
        entries.filter { entry ->
            selectedFilter == FILTER_ALL || entry.kind.label() == selectedFilter
        }
    }

    InspectorHost(placements = placements) { inspectorEnabled, onOpenInspector ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(showcaseColors.canvas),
        ) {
            CollapsingAppHeader(
                title = "Library",
                subtitle = "${entries.size} saved, read, and unlocked",
                listState = listState,
                actions = {
                    InspectorEntryPoint(
                        enabled = inspectorEnabled,
                        onOpen = onOpenInspector,
                    )
                },
            )

            PillTabs(
                options = filters,
                selected = selectedFilter,
                onSelect = { selectedFilter = it },
                modifier = Modifier.padding(
                    top = Tokens.Spacing.s12,
                    bottom = Tokens.Spacing.s12,
                ),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(
                    start = Tokens.Spacing.gutterCompact,
                    end = Tokens.Spacing.gutterCompact,
                    bottom = Tokens.Spacing.s48,
                ),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
            ) {
                if (filtered.isEmpty()) {
                    item(key = "empty") {
                        LibraryEmpty(
                            filter = selectedFilter,
                            onExploreFeed = onExploreFeedClick,
                            onClearFilters = { selectedFilter = FILTER_ALL },
                        )
                    }
                } else {
                    items(filtered, key = { "${it.kind}_${it.articleId}" }) { entry ->
                        Column {
                            LibraryRow(
                                entry = entry,
                                isBookmarked = entry.articleId in bookmarkedIds,
                                onClick = { onArticleClick(entry.articleId) },
                                onBookmarkToggle = {
                                    scope.launch {
                                        graph.articles.setBookmarked(
                                            entry.articleId,
                                            entry.articleId !in bookmarkedIds,
                                        )
                                    }
                                },
                            )
                            Rule()
                        }
                    }
                }

                item(key = "ad_free_note") {
                    AdFreeNote(modifier = Modifier.padding(top = Tokens.Spacing.s32))
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    entry: LibraryEntry,
    isBookmarked: Boolean,
    onClick: () -> Unit,
    onBookmarkToggle: () -> Unit,
) {
    val graph = LocalAppGraph.current
    var progress by remember(entry.articleId) { mutableFloatStateOf(0f) }

    LaunchedEffect(entry.articleId) {
        progress = graph.articles.progress(entry.articleId)
    }

    Column {
        StoryCard(
            story = StoryCardModel(
                id = entry.articleId,
                title = entry.title,
                author = "",
                section = entry.section,
                readTimeMinutes = entry.readTimeMin,
                snippet = "",
                isPremium = entry.kind == LibraryEntry.Kind.Unlocked,
            ),
            treatment = StoryTreatment.Compact,
            isBookmarked = isBookmarked,
            onClick = onClick,
            onBookmark = onBookmarkToggle,
        )
        if (progress > 0f || entry.kind == LibraryEntry.Kind.InProgress) {
            ReadingProgress(
                progress = progress,
                modifier = Modifier.padding(bottom = Tokens.Spacing.s12),
            )
        }
    }
}

@Composable
private fun ReadingProgress(progress: Float, modifier: Modifier = Modifier) {
    val palette = showcaseColors
    val clamped = progress.coerceIn(0f, 1f)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgressRule(fraction = clamped, modifier = Modifier.weight(1f))
        Text(
            text = if (clamped >= 1f) "Finished" else "${(clamped * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = palette.inkMuted,
        )
    }
}

@Composable
private fun LibraryEmpty(
    filter: String,
    onExploreFeed: () -> Unit,
    onClearFilters: () -> Unit,
) {
    when {
        filter != FILTER_ALL -> EmptyState(
            title = "Nothing here yet",
            message = "You have no $filter stories in your library.",
            icon = Icons.Rounded.BookmarkBorder,
            actionLabel = "Show all",
            onAction = onClearFilters,
        )

        else -> EmptyState(
            title = "Your library is empty",
            message = "Bookmark a story, or unlock a premium one, and it will show up here.",
            icon = Icons.Rounded.BookmarkBorder,
            actionLabel = "Explore Today",
            onAction = onExploreFeed,
        )
    }
}

@Composable
private fun AdFreeNote(modifier: Modifier = Modifier) {
    val palette = showcaseColors
    Column(modifier = modifier.fillMaxWidth()) {
        Rule(strong = true)
        Row(
            modifier = Modifier.padding(top = Tokens.Spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
        ) {
            Icon(
                imageVector = Icons.Rounded.Shield,
                contentDescription = null,
                tint = palette.inkFaint,
                modifier = Modifier.padding(top = 2.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4)) {
                Text(
                    text = "No ads on this screen",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.ink,
                )
                Text(
                    text = "Ads belong where you discover and read, not where you " +
                        "manage what you already own. Restraint is part of a good " +
                        "integration, so this screen has zero placements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted,
                )
            }
        }
    }
}

private fun LibraryEntry.Kind.label(): String = when (this) {
    LibraryEntry.Kind.Bookmarked -> "Saved"
    LibraryEntry.Kind.InProgress -> "Reading"
    LibraryEntry.Kind.Unlocked -> "Unlocked"
}
