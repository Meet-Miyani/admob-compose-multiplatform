@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.avinya.admob.showcase.feature.today

import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.admob.showcase.core.mvi.MviViewModel
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.data.repo.ArticleRepository
import dev.avinya.admob.showcase.domain.feed.FeedAdInserter
import dev.avinya.admob.showcase.domain.feed.FeedItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class TodayViewModel(
    private val articles: ArticleRepository,
    private val settings: SettingsRepository,
    private val adManager: AdManager,
) : MviViewModel<TodayState, TodayIntent, TodayEffect>(TodayState()) {

    val feed: Flow<PagingData<FeedItem>> = combine(
        settings.adsMasterSwitch,
        adManager.status,
    ) { adsEnabled, status ->
        adsEnabled && status == AdManagerStatus.Ready
    }.distinctUntilChanged().flatMapLatest { showAds ->
        articles.feedPager().map { paging ->
            val items = paging.map<FeedItem.Article, FeedItem> { it }
            if (showAds) items.withAdSlots() else items
        }
    }.cachedIn(viewModelScope)

    init {
        updateState { copy(feed = this@TodayViewModel.feed) }

        combine(settings.adsMasterSwitch, adManager.status) { adsEnabled, status ->
            adsEnabled to (status == AdManagerStatus.Ready)
        }.distinctUntilChanged().onEach { (adsEnabled, sdkReady) ->
            updateState { copy(adsEnabled = adsEnabled, sdkReady = sdkReady) }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            articles.bookmarkedArticles().collect { bookmarked ->
                val ids = bookmarked.map { it.id }.toSet()
                updateState { copy(bookmarkedIds = ids) }
            }
        }
    }

    override fun onIntent(intent: TodayIntent) {
        when (intent) {
            is TodayIntent.ToggleBookmark -> viewModelScope.launch {
                val isBookmarked = intent.articleId in state.value.bookmarkedIds
                articles.setBookmarked(intent.articleId, !isBookmarked)
            }
            is TodayIntent.OpenArticle -> emitEffect(TodayEffect.NavigateToArticle(intent.articleId))
            TodayIntent.Refresh -> {
                // Handled in UI if refresh triggered
            }
        }
    }
}

private fun PagingData<FeedItem>.withAdSlots(): PagingData<FeedItem> =
    insertSeparators { before, _ ->
        val article = before as? FeedItem.Article ?: return@insertSeparators null
        if (FeedAdInserter.shouldInsertAfter(article.feedOrdinal)) {
            FeedItem.NativeAdSlot(FeedAdInserter.slotKeyAfter(article.id))
        } else {
            null
        }
    }
