@file:OptIn(
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)

package dev.avinya.admob.showcase.feature.discover

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class DiscoverViewModel(
    private val articles: ArticleRepository,
    private val settings: SettingsRepository,
    private val adManager: AdManager,
) : MviViewModel<DiscoverState, DiscoverIntent, DiscoverEffect>(DiscoverState()) {

    private val queryInput = MutableStateFlow("")
    private val sectionInput = MutableStateFlow<String?>(null)

    /**
     * The committed Discover context: the debounced, normalized query plus the
     * selected category. Every change to this identity retires the previous
     * feed flow (flatMapLatest cancels stale loads) and scopes a new session.
     */
    private val committed: Flow<DiscoverContext> = combine(
        queryInput.debounce(SEARCH_DEBOUNCE_MS),
        sectionInput,
        settings.adsMasterSwitch,
        adManager.status,
    ) { query, section, adsEnabled, status ->
        DiscoverContext(
            normalizedQuery = normalizeQuery(query),
            section = section,
            adsEnabled = adsEnabled,
            sdkReady = status == AdManagerStatus.Ready,
        )
    }.distinctUntilChanged()

    val feed: Flow<PagingData<FeedItem>> = committed.flatMapLatest { context ->
        articles.discoverPager(context.section, context.normalizedQuery).map { paging ->
            val items = paging.map<FeedItem.Article, FeedItem> { it }
            val sessionKey = context.nativeSessionKey
            if (sessionKey != null) items.withAdSlots(sessionKey) else items
        }
    }.cachedIn(viewModelScope)

    init {
        updateState { copy(feed = this@DiscoverViewModel.feed) }

        committed.onEach { context ->
            updateState {
                copy(
                    normalizedQuery = context.normalizedQuery,
                    selectedSection = context.section,
                    nativeSessionKey = context.nativeSessionKey,
                    adsEnabled = context.adsEnabled,
                    sdkReady = context.sdkReady,
                )
            }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            articles.sections().collect { sections ->
                updateState { copy(sections = sections) }
            }
        }

        viewModelScope.launch {
            articles.bookmarkedArticles().collect { bookmarked ->
                updateState { copy(bookmarkedIds = bookmarked.map { it.id }.toSet()) }
            }
        }
    }

    override fun onIntent(intent: DiscoverIntent) {
        when (intent) {
            is DiscoverIntent.QueryChanged -> {
                updateState { copy(query = intent.query) }
                queryInput.value = intent.query
                // Typing a query deselects the category filter: one active
                // context at a time keeps session identity unambiguous.
                if (intent.query.isNotBlank()) sectionInput.value = null
            }
            is DiscoverIntent.SectionSelected -> {
                // "All" is the absence of a section filter, not a section.
                val section = intent.section.takeUnless { it == FILTER_ALL }
                updateState { copy(query = "", selectedSection = section) }
                queryInput.value = ""
                sectionInput.value = section
            }
            is DiscoverIntent.OpenArticle -> emitEffect(DiscoverEffect.NavigateToArticle(intent.articleId))
            is DiscoverIntent.ToggleBookmark -> viewModelScope.launch {
                val isBookmarked = intent.articleId in state.value.bookmarkedIds
                articles.setBookmarked(intent.articleId, !isBookmarked)
            }
        }
    }
}

private data class DiscoverContext(
    val normalizedQuery: String,
    val section: String?,
    val adsEnabled: Boolean,
    val sdkReady: Boolean,
) {
    val showAds: Boolean get() = adsEnabled && sdkReady

    /**
     * Null only when ads cannot be shown at all.
     *
     * Every browse context — a search, a section, or "All" — is a distinct
     * session identity, so switching between them retires the previous
     * session's inventory instead of reusing it under a new heading.
     */
    val nativeSessionKey: String? get() = when {
        !showAds -> null
        normalizedQuery.isNotEmpty() -> discoverSearchSessionKey(normalizedQuery)
        section != null -> discoverCategorySessionKey(section)
        else -> DISCOVER_ALL_SESSION_KEY
    }
}

private fun PagingData<FeedItem>.withAdSlots(sessionKey: String): PagingData<FeedItem> =
    insertSeparators { before, _ ->
        val article = before as? FeedItem.Article ?: return@insertSeparators null
        if (FeedAdInserter.shouldInsertAfter(article.feedOrdinal)) {
            FeedItem.NativeAdSlot(discoverSlotKeyAfter(sessionKey, article.id))
        } else {
            null
        }
    }
