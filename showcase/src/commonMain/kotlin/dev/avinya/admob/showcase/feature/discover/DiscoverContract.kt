package dev.avinya.admob.showcase.feature.discover

import androidx.paging.PagingData
import dev.avinya.admob.showcase.domain.feed.FeedItem
import kotlinx.coroutines.flow.Flow

data class DiscoverState(
    val query: String = "",
    val normalizedQuery: String = "",
    val sections: List<String> = emptyList(),
    val selectedSection: String? = null,
    val nativeSessionKey: String? = null,
    val feed: Flow<PagingData<FeedItem>>? = null,
    val bookmarkedIds: Set<String> = emptySet(),
    val adsEnabled: Boolean = true,
    val sdkReady: Boolean = false,
)

sealed interface DiscoverIntent {
    data class QueryChanged(val query: String) : DiscoverIntent
    data class SectionSelected(val section: String) : DiscoverIntent
    data class OpenArticle(val articleId: String) : DiscoverIntent
    data class ToggleBookmark(val articleId: String) : DiscoverIntent
}

sealed interface DiscoverEffect {
    data class NavigateToArticle(val articleId: String) : DiscoverEffect
}

/** Debounce before a raw query becomes a committed, session-scoped search. */
const val SEARCH_DEBOUNCE_MS: Long = 250

/** Trim and collapse repeated whitespace; never match on the raw field text. */
internal fun normalizeQuery(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

/** Feed-session identity for a committed search query. */
internal fun discoverSearchSessionKey(query: String): String = "discover:search:$query"

/** Feed-session identity for a category browse context. */
internal fun discoverCategorySessionKey(section: String): String = "discover:category:$section"

/**
 * Feed-session identity for the unfiltered "All" context.
 *
 * Discover used to have a separate landing mode that rendered a grid of
 * sections and no results at all. One list with an "All" filter says the same
 * thing with a third of the UI, so "All" needed a session identity of its own.
 */
internal const val DISCOVER_ALL_SESSION_KEY: String = "discover:all"

/** Stable slot key anchored to the article, scoped to its query/category session. */
internal fun discoverSlotKeyAfter(sessionKey: String, articleId: String): String =
    "$sessionKey:$articleId"
