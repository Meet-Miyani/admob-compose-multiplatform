package dev.avinya.admob.showcase.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.feed.FeedAdInserter
import dev.avinya.admob.showcase.domain.feed.FeedItem
import dev.avinya.admob.showcase.ui.ad.rememberFeedRowAdLayout
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorHost
import dev.avinya.admob.showcase.ui.kit.CollapsingAppHeader
import dev.avinya.admob.showcase.ui.kit.EmptyState
import dev.avinya.admob.showcase.ui.kit.ErrorState
import dev.avinya.admob.showcase.ui.kit.LoadingState
import dev.avinya.admob.showcase.ui.kit.NativeAdCard
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.StoryCard
import dev.avinya.admob.showcase.ui.kit.StoryCardModel
import dev.avinya.admob.showcase.ui.kit.StoryTreatment
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.NativeAdSession
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.ui.rememberNativeAdFeedSession

/**
 * The curated chronological feed.
 *
 * One native session is created above item composition and keyed to the feed
 * revision, not to scroll position — so scrolling away and back returns to the
 * same ad identity, and a tab switch does not discard it. The SDK owns loading,
 * retention, eviction, and destruction; this screen only declares which slots
 * are currently in the window.
 */
@Composable
fun TodayScreen(
    onArticleClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val viewModel: TodayViewModel = viewModel {
        TodayViewModel(graph.articles, graph.settings, adManager)
    }

    val items = viewModel.feed.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val nativeSession = rememberNativeAdFeedSession(
        sessionKey = "today:${FeedAdInserter.TODAY_FEED_REVISION}",
        listState = listState,
        itemCount = items.itemCount,
        slotAt = { index ->
            (items.peek(index) as? FeedItem.NativeAdSlot)?.let { row ->
                NativeAdSlot(row.slotKey, ShowcasePlacements.feedNative)
            }
        },
    )

    val placements = remember { listOf(ShowcasePlacements.feedNative) }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TodayEffect.NavigateToArticle -> onArticleClick(effect.articleId)
            }
        }
    }

    InspectorHost(placements = placements) { inspectorEnabled, onOpenInspector ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(showcaseColors.canvas),
        ) {
            CollapsingAppHeader(
                title = "Today",
                subtitle = "Culture and technology, read daily",
                listState = listState,
                actions = {
                    InspectorEntryPoint(
                        enabled = inspectorEnabled,
                        onOpen = onOpenInspector,
                    )
                },
            )

            val refresh = items.loadState.refresh
            when {
                refresh is LoadState.Loading && items.itemCount == 0 ->
                    LoadingState(modifier = Modifier.padding(Tokens.Spacing.gutterCompact))

                refresh is LoadState.Error && items.itemCount == 0 ->
                    ErrorState(
                        title = "Couldn't load today's edition",
                        message = refresh.error.message
                            ?: "Something went wrong reading your local library.",
                        onAction = { items.retry() },
                    )

                items.itemCount == 0 ->
                    EmptyState(
                        title = "Nothing published yet",
                        message = "New stories appear here as they're added to your library.",
                        icon = Icons.Rounded.Newspaper,
                    )

                else -> TodayFeed(
                    items = items,
                    listState = listState,
                    bookmarkedIds = state.bookmarkedIds,
                    nativeSession = nativeSession,
                    onOpen = { viewModel.onIntent(TodayIntent.OpenArticle(it)) },
                    onBookmark = { viewModel.onIntent(TodayIntent.ToggleBookmark(it)) },
                )
            }
        }
    }
}

@Composable
private fun TodayFeed(
    items: LazyPagingItems<FeedItem>,
    listState: LazyListState,
    bookmarkedIds: Set<String>,
    nativeSession: NativeAdSession,
    onOpen: (String) -> Unit,
    onBookmark: (String) -> Unit,
) {
    // The sponsored row borrows the editorial row's exact geometry, so the feed
    // keeps one rhythm; the badge and CTA carry the disclosure.
    val rowAdLayout = rememberFeedRowAdLayout()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            start = Tokens.Spacing.gutterCompact,
            end = Tokens.Spacing.gutterCompact,
            top = Tokens.Spacing.s16,
            bottom = Tokens.Spacing.s48,
        ),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
    ) {
        items(
            count = items.itemCount,
            key = items.itemKey { it.key },
        ) { index ->
            when (val item = items[index]) {
                is FeedItem.Article -> {
                    // The first story leads the edition; the rest are ruled
                    // rows, so the feed reads as a page rather than a stack of
                    // identical cards.
                    val treatment = if (index == 0) StoryTreatment.Hero else StoryTreatment.Standard
                    Column {
                        StoryCard(
                            story = item.toStoryModel(),
                            treatment = treatment,
                            isBookmarked = item.id in bookmarkedIds,
                            onClick = { onOpen(item.id) },
                            onBookmark = { onBookmark(item.id) },
                        )
                        if (treatment == StoryTreatment.Standard) Rule()
                    }
                }

                is FeedItem.NativeAdSlot -> Column {
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

                null -> Unit
            }
        }

        if (items.loadState.append is LoadState.Loading) {
            item(key = "append_loading") {
                LoadingState(label = "Loading more", rows = 1)
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
