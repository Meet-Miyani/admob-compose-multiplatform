package dev.avinya.admob.showcase.feature.today

import androidx.paging.PagingData
import dev.avinya.admob.showcase.domain.feed.FeedItem
import kotlinx.coroutines.flow.Flow

data class TodayState(
    val feed: Flow<PagingData<FeedItem>>? = null,
    val bookmarkedIds: Set<String> = emptySet(),
    val adsEnabled: Boolean = true,
    val sdkReady: Boolean = false,
    val isRefreshing: Boolean = false,
)

sealed interface TodayIntent {
    data class ToggleBookmark(val articleId: String) : TodayIntent
    data class OpenArticle(val articleId: String) : TodayIntent
    data object Refresh : TodayIntent
}

sealed interface TodayEffect {
    data class NavigateToArticle(val articleId: String) : TodayEffect
}
