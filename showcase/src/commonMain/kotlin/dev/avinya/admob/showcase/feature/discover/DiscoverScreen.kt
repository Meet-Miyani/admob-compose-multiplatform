package dev.avinya.admob.showcase.feature.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.feed.FeedItem
import dev.avinya.admob.showcase.ui.ad.rememberFeedRowAdLayout
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorHost
import dev.avinya.admob.showcase.ui.kit.AppHeader
import dev.avinya.admob.showcase.ui.kit.EmptyState
import dev.avinya.admob.showcase.ui.kit.LoadingState
import dev.avinya.admob.showcase.ui.kit.NativeAdCard
import dev.avinya.admob.showcase.ui.kit.PillTabs
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.SearchField
import dev.avinya.admob.showcase.ui.kit.StoryCard
import dev.avinya.admob.showcase.ui.kit.StoryCardModel
import dev.avinya.admob.showcase.ui.kit.StoryTreatment
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.ui.rememberNativeAdFeedSession

/** The "everything" filter, shown first in the section row. */
internal const val FILTER_ALL = "All"

/**
 * Section browsing and local search.
 *
 * Ad sessions are scoped to a *committed* context — a debounced, normalised
 * query or a selected category — never to raw keystrokes, so typing "compose"
 * creates one session rather than seven. The landing page renders no slots and
 * therefore gets no session at all.
 */
@Composable
fun DiscoverScreen(
    onArticleClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val viewModel: DiscoverViewModel = viewModel {
        DiscoverViewModel(graph.articles, graph.settings, adManager)
    }

    val state by viewModel.state.collectAsState()
    val items = state.feed?.let { it.collectAsLazyPagingItems() }
    val listState = rememberLazyListState()

    val nativeSession = state.nativeSessionKey?.let { sessionKey ->
        if (items != null) {
            rememberNativeAdFeedSession(
                sessionKey = sessionKey,
                listState = listState,
                itemCount = items.itemCount,
                slotAt = { index ->
                    (items.peek(index) as? FeedItem.NativeAdSlot)?.let { row ->
                        NativeAdSlot(row.slotKey, ShowcasePlacements.feedNative)
                    }
                },
            )
        } else {
            null
        }
    }

    // Query history aging out: close the retired session so it cannot hold
    // inventory forever. Tab switches do NOT reach here — the session stays
    // deactivated-but-retained while its tab is selected, per the SDK contract.
    var previousSession by remember { mutableStateOf<NativeAdSession?>(null) }
    LaunchedEffect(nativeSession) {
        val retired = previousSession
        if (retired != null && retired !== nativeSession) retired.close()
        previousSession = nativeSession
    }

    val filters = remember(state.sections) { listOf(FILTER_ALL) + state.sections }
    val placements = remember { listOf(ShowcasePlacements.feedNative) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DiscoverEffect.NavigateToArticle -> onArticleClick(effect.articleId)
            }
        }
    }

    InspectorHost(placements = placements) { inspectorEnabled, onOpenInspector ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(showcaseColors.canvas),
        ) {
            AppHeader(
                title = "Discover",
                subtitle = "Browse by section, or search everything",
                wordmark = "Fieldnotes",
                actions = {
                    InspectorEntryPoint(
                        enabled = inspectorEnabled,
                        onOpen = onOpenInspector,
                    )
                },
            )

            SearchField(
                query = state.query,
                onQueryChange = { viewModel.onIntent(DiscoverIntent.QueryChanged(it)) },
                placeholder = "Search articles, authors, sections…",
                modifier = Modifier.padding(
                    horizontal = Tokens.Spacing.gutterCompact,
                    vertical = Tokens.Spacing.s12,
                ),
            )

            // One filter row, "All" first. Discover previously had a whole
            // second mode — a grid of section cards that showed no articles —
            // reachable only by clearing the search. One list is easier to
            // explain and easier to use.
            if (state.sections.isNotEmpty()) {
                PillTabs(
                    options = filters,
                    selected = state.selectedSection ?: FILTER_ALL,
                    onSelect = { viewModel.onIntent(DiscoverIntent.SectionSelected(it)) },
                    modifier = Modifier.padding(bottom = Tokens.Spacing.s12),
                )
            }

            if (items == null) {
                LoadingState(modifier = Modifier.padding(Tokens.Spacing.gutterCompact))
            } else {
                DiscoverResults(
                    items = items,
                    listState = listState,
                    nativeSession = nativeSession,
                    bookmarkedIds = state.bookmarkedIds,
                    query = state.normalizedQuery,
                    section = state.selectedSection,
                    onArticleClick = { viewModel.onIntent(DiscoverIntent.OpenArticle(it)) },
                    onBookmark = { viewModel.onIntent(DiscoverIntent.ToggleBookmark(it)) },
                )
            }
        }
    }
}

@Composable
private fun DiscoverResults(
    items: LazyPagingItems<FeedItem>,
    listState: LazyListState,
    nativeSession: NativeAdSession?,
    bookmarkedIds: Set<String>,
    query: String,
    section: String?,
    onArticleClick: (String) -> Unit,
    onBookmark: (String) -> Unit,
) {
    val rowAdLayout = rememberFeedRowAdLayout()
    val refresh = items.loadState.refresh
    if (refresh is LoadState.Loading && items.itemCount == 0) {
        LoadingState(
            label = "Searching",
            modifier = Modifier.padding(Tokens.Spacing.gutterCompact),
        )
        return
    }
    if (items.itemCount == 0) {
        EmptyState(
            title = "No matches",
            message = when {
                query.isNotEmpty() -> "Nothing in your library matches “$query”."
                section != null -> "The $section section has no stories yet."
                else -> "Nothing to show here."
            },
            icon = Icons.Rounded.SearchOff,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = Tokens.Spacing.gutterCompact,
            end = Tokens.Spacing.gutterCompact,
            bottom = Tokens.Spacing.s48,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey(FeedItem::key),
        ) { index ->
            when (val item = items[index]) {
                is FeedItem.Article -> Column {
                    StoryCard(
                        story = item.toStoryModel(),
                        treatment = StoryTreatment.Standard,
                        isBookmarked = item.id in bookmarkedIds,
                        onClick = { onArticleClick(item.id) },
                        onBookmark = { onBookmark(item.id) },
                    )
                    Rule()
                }

                is FeedItem.NativeAdSlot -> if (nativeSession != null) {
                    Column {
                        NativeAdCard(
                            session = nativeSession,
                            slotKey = item.slotKey,
                            placement = ShowcasePlacements.feedNative,
                            layout = rowAdLayout,
                            framed = false,
                            modifier = Modifier.padding(vertical = Tokens.Spacing.s12),
                        )
                        Rule()
                    }
                }

                null -> Unit
            }
        }
    }
}

private fun FeedItem.Article.toStoryModel(): StoryCardModel = StoryCardModel(
    id = id,
    title = title,
    author = author,
    section = section,
    readTimeMinutes = readTimeMinutes,
    snippet = snippet,
    isPremium = isPremium,
)
