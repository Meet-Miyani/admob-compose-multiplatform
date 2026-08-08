package dev.avinya.admob.showcase.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.library.LibraryEntry
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorSheet
import dev.avinya.admob.showcase.ui.inspector.LocalInspectorPlacements
import dev.avinya.ads.AdPlacement
import kotlinx.coroutines.launch

/**
 * The user's owned-and-engaged content, grouped by why it's here.
 *
 * **No `BannerAdView`, no `NativeAdView`, no interstitial on this screen.** That
 * is the demonstration: a showcase that puts an ad on every screen teaches the
 * wrong lesson. Restraint is part of a good integration, and the screen says so
 * out loud.
 */
@Composable
fun LibraryScreen(
    onArticleClick: (String) -> Unit,
    onExploreFeedClick: () -> Unit = {},
) {
    val graph = LocalAppGraph.current
    val entries by graph.articles.library().collectAsState(initial = emptyList())
    val bookmarkedArticles by graph.articles.bookmarkedArticles().collectAsState(initial = emptyList())
    val bookmarkedIds = remember(bookmarkedArticles) { bookmarkedArticles.map { it.id }.toSet() }
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    val filterCategories = remember { listOf("All", "Bookmarked", "In Progress", "Unlocked") }
    var selectedFilter by remember { mutableStateOf("All") }

    val inspectorEnabled by graph.settings.inspectorEnabled.collectAsState(initial = true)
    var showInspector by remember { mutableStateOf(false) }
    // Library is deliberately ad-free; the Inspector still shows, but with an
    // empty Placements tab and just the events/revenue columns populated.
    val placements = remember { emptyList<AdPlacement>() }

    val filteredEntries = remember(entries, searchQuery, selectedFilter) {
        entries.filter { entry ->
            val matchesSearch = searchQuery.isBlank() ||
                entry.title.contains(searchQuery, ignoreCase = true) ||
                entry.section.contains(searchQuery, ignoreCase = true)
            val matchesCategory = when (selectedFilter) {
                "Bookmarked" -> entry.kind == LibraryEntry.Kind.Bookmarked
                "In Progress" -> entry.kind == LibraryEntry.Kind.InProgress
                "Unlocked" -> entry.kind == LibraryEntry.Kind.Unlocked
                else -> true
            }
            matchesSearch && matchesCategory
        }
    }

    CompositionLocalProvider(LocalInspectorPlacements provides placements) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "inspector_entry") {
                InspectorEntryPoint(
                    title = "Library",
                    enabled = inspectorEnabled,
                    onOpen = { showInspector = true },
                )
            }

            // Polished Search Bar
            item(key = "search_bar") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Search saved articles…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                        unfocusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                        focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    singleLine = true,
                )
            }

            // Category Filter Pills
            item(key = "filter_pills") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    filterCategories.forEach { category ->
                        LibraryFilterPill(
                            label = category,
                            selected = selectedFilter == category,
                            onClick = { selectedFilter = category },
                        )
                    }
                }
            }

            // Article Groupings or Filtered List
            LibraryEntry.Kind.entries.forEach { kind ->
                val group = filteredEntries.filter { it.kind == kind }
                if (group.isNotEmpty()) {
                    item(key = "header_$kind") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text(
                                text = kind.label(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            ) {
                                Text(
                                    text = group.size.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    items(group, key = { "${it.kind}_${it.articleId}" }) { entry ->
                        val isBookmarked = entry.articleId in bookmarkedIds
                        LibraryArticleCard(
                            entry = entry,
                            isBookmarked = isBookmarked,
                            onBookmarkToggle = {
                                coroutineScope.launch {
                                    graph.articles.setBookmarked(entry.articleId, !isBookmarked)
                                }
                            },
                            onClick = { onArticleClick(entry.articleId) },
                        )
                    }
                }
            }

            // Empty State
            if (filteredEntries.isEmpty()) {
                item(key = "empty_state") {
                    LibraryEmptyState(
                        isSearching = searchQuery.isNotEmpty(),
                        selectedFilter = selectedFilter,
                        onExploreFeed = onExploreFeedClick,
                        onClearFilter = {
                            searchQuery = ""
                            selectedFilter = "All"
                        },
                    )
                }
            }

            // Ad-Free Sanctuary Notice Card
            item(key = "sanctuary_card") {
                AdFreeSanctuaryCard()
            }
        }
    }

    if (showInspector) {
        InspectorSheet(placements = placements, onDismiss = { showInspector = false })
    }
}

@Composable
private fun LibraryFilterPill(
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

private fun LibraryEntry.Kind.label(): String = when (this) {
    LibraryEntry.Kind.Bookmarked -> "Bookmarks"
    LibraryEntry.Kind.InProgress -> "In Progress"
    LibraryEntry.Kind.Unlocked -> "Unlocked"
}

@Composable
private fun LibraryArticleCard(
    entry: LibraryEntry,
    isBookmarked: Boolean,
    onBookmarkToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val graph = LocalAppGraph.current
    var progress by remember(entry.articleId) { mutableFloatStateOf(0f) }

    LaunchedEffect(entry.articleId) {
        progress = graph.articles.progress(entry.articleId)
    }

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
            // Header Row: Category Badge + Read Time + Bookmark Toggle
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
                            text = entry.section.uppercase(),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (entry.kind == LibraryEntry.Kind.Unlocked) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.tertiary,
                        ) {
                            Text(
                                text = "UNLOCKED",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Text(
                        text = "${entry.readTimeMin} MIN READ",
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

            // Article Title
            Text(
                text = entry.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Reading Progress Bar
            if (progress > 0f || entry.kind == LibraryEntry.Kind.InProgress) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (progress >= 1f) "Completed" else "Reading Progress",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress.coerceIn(0.04f, 1f))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    isSearching: Boolean,
    selectedFilter: String,
    onExploreFeed: () -> Unit,
    onClearFilter: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = when {
                    isSearching -> "No Matching Articles"
                    selectedFilter != "All" -> "No $selectedFilter Articles"
                    else -> "Your Library is Empty"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    isSearching -> "No saved or unlocked articles match your search criteria."
                    selectedFilter != "All" -> "You don't have any $selectedFilter articles in your library yet."
                    else -> "Bookmark articles or unlock premium content from the feed to build your personal reading collection."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Spacer(modifier = Modifier.height(20.dp))
            if (isSearching || selectedFilter != "All") {
                OutlinedButton(
                    onClick = onClearFilter,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        if (isSearching) "Clear Search" else "Show All Articles",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Button(
                    onClick = onExploreFeed,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Explore Feed",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdFreeSanctuaryCard() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = "Ad-free sanctuary",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Ad-Free Sanctuary",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "The Library screen has zero ad placements by design. Ads belong where you discover and explore content, not where you manage what you already own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


