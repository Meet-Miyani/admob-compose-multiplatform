package dev.avinya.admob.showcase.feature.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.feed.FeedItem
import dev.avinya.admob.showcase.ui.ad.feedNativeAdLayout
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorSheet
import dev.avinya.admob.showcase.ui.inspector.LocalInspectorPlacements
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.ui.BannerAdView
import dev.avinya.ads.ui.NativeAdView
import dev.avinya.ads.ui.rememberNativeAdFeedSession
import kotlinx.coroutines.launch

@Composable
fun FeedScreen(onArticleClick: (String) -> Unit) {
    val adManager = LocalAdManager.current
    val graph = LocalAppGraph.current
    val viewModel: FeedViewModel = viewModel {
        FeedViewModel(graph.articles, graph.settings, adManager)
    }
    val items = viewModel.feed.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    val nativeSession = rememberNativeAdFeedSession(
        sessionKey = "showcase-feed",
        listState = listState,
        itemCount = items.itemCount,
        slotAt = { index ->
            (items[index] as? FeedItem.NativeAdSlot)
                ?.sessionSlot(ShowcasePlacements.feedNative)
        },
    )

    val inspectorEnabled by graph.settings.inspectorEnabled.collectAsState(initial = true)
    var showInspector by remember { mutableStateOf(false) }
    val placements = remember {
        listOf(ShowcasePlacements.feedBanner, ShowcasePlacements.feedNative)
    }

    val categories = remember {
        listOf("All", "Kotlin", "Compose", "Multiplatform", "Android", "iOS", "Tooling")
    }
    var selectedCategory by remember { mutableStateOf("All") }

    val bookmarkedArticles by graph.articles.bookmarkedArticles().collectAsState(initial = emptyList())
    val bookmarkedIds = remember(bookmarkedArticles) { bookmarkedArticles.map { it.id }.toSet() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is FeedEffect.NavigateToArticle -> onArticleClick(effect.articleId)
            }
        }
    }

    CompositionLocalProvider(LocalInspectorPlacements provides placements) {
        Column(modifier = Modifier.fillMaxSize()) {
            InspectorEntryPoint(
                title = "Feed",
                enabled = inspectorEnabled,
                onOpen = { showInspector = true },
            )

            // Category Filter Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { category ->
                    CategoryPill(
                        label = category,
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = category },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(
                    count = items.itemCount,
                    key = items.itemKey { it.key },
                ) { index ->
                    when (val item = items[index]) {
                        is FeedItem.Article -> {
                            if (matchesCategory(item.section, selectedCategory)) {
                                val isBookmarked = item.id in bookmarkedIds
                                ArticleCard(
                                    item = item,
                                    isBookmarked = isBookmarked,
                                    onBookmarkToggle = {
                                        coroutineScope.launch {
                                            graph.articles.setBookmarked(item.id, !isBookmarked)
                                        }
                                    },
                                    onClick = { viewModel.onIntent(FeedIntent.OpenArticle(item.id)) },
                                )
                            }
                        }
                        is FeedItem.NativeAdSlot -> {
                            NativeAdCard(session = nativeSession, slotKey = item.slotKey)
                        }
                        null -> Unit
                    }
                }
            }

            val state by viewModel.state.collectAsState()
            if (state.adsEnabled && state.sdkReady) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BannerAdView(
                            placement = ShowcasePlacements.feedBanner,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (showInspector) {
        InspectorSheet(placements = placements, onDismiss = { showInspector = false })
    }
}

@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun matchesCategory(section: String, category: String): Boolean {
    if (category == "All") return true
    return section.equals(category, ignoreCase = true) || section.contains(category, ignoreCase = true)
}

@Composable
private fun ArticleCard(
    item: FeedItem.Article,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: Section/Category badge + read time + bookmark toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = item.section.uppercase(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (item.isPremium) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.tertiary,
                        ) {
                            Text(
                                text = "PREMIUM",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Text(
                        text = "${item.readTimeMin} MIN READ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                IconButton(
                    onClick = onBookmarkToggle,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = if (isBookmarked) "Remove bookmark" else "Bookmark article",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Title: Bold titleMedium
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Snippet: bodyMedium with onSurfaceVariant color
            val snippet = item.snippet.ifBlank {
                "Explore essential concepts, architectural patterns, and practical insights for ${item.section} development."
            }
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Footer: Author info and published date
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = "Author",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = item.author,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text(
                    text = formatRelativeDate(item.feedOrdinal),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun NativeAdCard(
    session: dev.avinya.ads.nativead.NativeAdSession,
    slotKey: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        NativeAdView(
            session = session,
            slotKey = slotKey,
            placement = ShowcasePlacements.feedNative,
            layout = feedNativeAdLayout,
            modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp).padding(16.dp),
            loading = { Box(Modifier.fillMaxWidth().heightIn(min = 250.dp)) },
        )
    }
}

private fun formatRelativeDate(feedOrdinal: Int): String {
    if (feedOrdinal == 0) return "Just now"
    if (feedOrdinal < 24) return "${feedOrdinal}h ago"
    val days = feedOrdinal / 24
    return if (days == 1) "1 day ago" else "$days days ago"
}
