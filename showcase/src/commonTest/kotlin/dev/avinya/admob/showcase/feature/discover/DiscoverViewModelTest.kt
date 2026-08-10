package dev.avinya.admob.showcase.feature.discover

import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdDiagnostics
import dev.avinya.ads.AdEvent
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.AdTrackingAuthorization
import dev.avinya.ads.AdTrackingController
import dev.avinya.ads.AppOpenAdController
import dev.avinya.ads.BannerAdController
import dev.avinya.ads.ConsentController
import dev.avinya.ads.ConsentMode
import dev.avinya.ads.ConsentStatus
import dev.avinya.ads.InterstitialAdController
import dev.avinya.ads.NoOpAdManager
import dev.avinya.ads.PrivacyOptionsRequirementStatus
import dev.avinya.ads.RewardedAdController
import dev.avinya.ads.RewardedInterstitialAdController
import dev.avinya.ads.nativead.NativeAdManager
import dev.avinya.admob.showcase.core.time.Clock
import dev.avinya.admob.showcase.data.db.dao.ArticleDao
import dev.avinya.admob.showcase.data.db.entity.ArticleEntity
import dev.avinya.admob.showcase.data.db.entity.BookmarkEntity
import dev.avinya.admob.showcase.data.db.entity.ReadingProgressEntity
import dev.avinya.admob.showcase.data.db.entity.UnlockEntity
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.data.prefs.inMemoryPreferencesDataStore
import dev.avinya.admob.showcase.data.repo.ArticleRepository
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testViewModel(): DiscoverViewModel {
        val dao = FakeArticleDao()
        val repo = ArticleRepository(dao, object : Clock {
            override fun nowMillis(): Long = 0L
        })
        val settings = SettingsRepository(inMemoryPreferencesDataStore())
        return DiscoverViewModel(repo, settings, FakeAdManager())
    }

    @Test
    fun changingQuery_cancelsOldResults_andChangesSessionIdentity() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()

        viewModel.onIntent(DiscoverIntent.QueryChanged("compose"))
        advanceTimeBy(SEARCH_DEBOUNCE_MS)
        viewModel.onIntent(DiscoverIntent.QueryChanged("privacy"))
        advanceUntilIdle()

        assertEquals("privacy", viewModel.state.value.normalizedQuery)
        assertEquals("discover:search:privacy", viewModel.state.value.nativeSessionKey)
    }

    @Test
    fun queryNormalization_trimsAndCollapsesWhitespace() {
        assertEquals("compose multiplatform", normalizeQuery("  compose   multiplatform  "))
        assertEquals("", normalizeQuery("   "))
    }

    @Test
    fun unfilteredBrowse_usesTheAllSessionKey() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()

        // Discover no longer has a separate landing mode: with no query and no
        // section it browses everything, and that context gets its own session
        // identity so switching to a section retires it cleanly.
        assertEquals("", viewModel.state.value.normalizedQuery)
        assertEquals(DISCOVER_ALL_SESSION_KEY, viewModel.state.value.nativeSessionKey)
        assertEquals(listOf("Kotlin", "Privacy"), viewModel.state.value.sections)
    }

    @Test
    fun categorySelection_usesCategorySessionKey() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()

        viewModel.onIntent(DiscoverIntent.SectionSelected("Kotlin"))
        advanceUntilIdle()

        assertEquals("Kotlin", viewModel.state.value.selectedSection)
        assertEquals("discover:category:Kotlin", viewModel.state.value.nativeSessionKey)
    }

    @Test
    fun typingQuery_deselectsCategory() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()

        viewModel.onIntent(DiscoverIntent.SectionSelected("Kotlin"))
        advanceUntilIdle()
        viewModel.onIntent(DiscoverIntent.QueryChanged("compose"))
        advanceUntilIdle()

        assertEquals("compose", viewModel.state.value.normalizedQuery)
        assertNull(viewModel.state.value.selectedSection)
        assertEquals("discover:search:compose", viewModel.state.value.nativeSessionKey)
    }

    @Test
    fun clearingQuery_returnsToLanding() = runTest(dispatcher) {
        val viewModel = testViewModel()
        advanceUntilIdle()

        viewModel.onIntent(DiscoverIntent.QueryChanged("compose"))
        advanceUntilIdle()
        viewModel.onIntent(DiscoverIntent.QueryChanged(""))
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.normalizedQuery)
        assertEquals(DISCOVER_ALL_SESSION_KEY, viewModel.state.value.nativeSessionKey)
    }

    private class FakeArticleDao : ArticleDao {
        private val articles = (0 until 12).map { index ->
            ArticleEntity(
                id = "article-${index.toString().padStart(3, '0')}",
                title = if (index % 2 == 0) "Compose topic $index" else "Privacy topic $index",
                author = "Author $index",
                body = "Body of article $index with some content.",
                section = if (index % 2 == 0) "Kotlin" else "Privacy",
                publishedAt = 1_000L - index,
                readTimeMin = 4,
                isPremium = index == 6,
                unlockCostCoins = if (index == 6) 50 else 0,
                feedOrdinal = index,
            )
        }

        override fun discoverPagingSource(section: String?, query: String): PagingSource<Int, ArticleEntity> {
            val normalized = query.trim()
            val filtered = articles.filter { article ->
                (section == null || article.section == section) &&
                    (normalized.isEmpty() ||
                        article.title.contains(normalized, ignoreCase = true) ||
                        article.author.contains(normalized, ignoreCase = true) ||
                        article.section.contains(normalized, ignoreCase = true) ||
                        article.body.contains(normalized, ignoreCase = true))
            }
            return InMemoryPagingSource(filtered)
        }

        override fun sections(): Flow<List<String>> =
            flowOf(articles.map { it.section }.distinct().sorted())

        override fun bookmarkedArticles(): Flow<List<ArticleEntity>> = flowOf(emptyList())
        override fun pagingSource(): PagingSource<Int, ArticleEntity> = InMemoryPagingSource(articles)
        override fun premiumArticles(): Flow<List<ArticleEntity>> = flowOf(emptyList())
        override fun unlockedArticles(): Flow<List<ArticleEntity>> = flowOf(emptyList())
        override fun inProgressArticles(): Flow<List<ArticleEntity>> = flowOf(emptyList())
        override fun unlockedIds(): Flow<List<String>> = flowOf(emptyList())
        override fun isBookmarked(articleId: String): Flow<Boolean> = flowOf(false)
        override fun isUnlocked(articleId: String): Flow<Boolean> = flowOf(false)

        override suspend fun insertAll(articles: List<ArticleEntity>) = Unit
        override suspend fun allIds(): List<String> = emptyList()
        override suspend fun count(): Int = 0
        override suspend fun byId(id: String): ArticleEntity? = null
        override suspend fun page(limit: Int, offset: Int): List<ArticleEntity> = emptyList()
        override suspend fun upsertProgress(progress: ReadingProgressEntity) = Unit
        override suspend fun progressFor(articleId: String): ReadingProgressEntity? = null
        override suspend fun addBookmark(bookmark: BookmarkEntity) = Unit
        override suspend fun removeBookmark(articleId: String) = Unit
        override suspend fun addUnlock(unlock: UnlockEntity) = Unit
    }

    /** Minimal positional source over a fixed list; avoids Android-only `PagingSource.from`. */
    private class InMemoryPagingSource(
        private val items: List<ArticleEntity>,
    ) : PagingSource<Int, ArticleEntity>() {
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ArticleEntity> {
            val start = params.key ?: 0
            val end = (start + params.loadSize).coerceAtMost(items.size)
            if (start >= items.size) {
                return LoadResult.Page(
                    data = emptyList(),
                    prevKey = null,
                    nextKey = null,
                )
            }
            return LoadResult.Page(
                data = items.subList(start, end),
                prevKey = if (start > 0) (start - params.loadSize).coerceAtLeast(0) else null,
                nextKey = if (end < items.size) end else null,
            )
        }

        override fun getRefreshKey(state: PagingState<Int, ArticleEntity>): Int? = null
    }

    private open class FakeAdManager : AdManager {
        private val _status = MutableStateFlow<AdManagerStatus>(AdManagerStatus.Ready)
        override val status: StateFlow<AdManagerStatus> = _status.asStateFlow()
        override val events: SharedFlow<AdEvent> get() = NoOpAdManager.events
        override val diagnostics: AdDiagnostics get() = NoOpAdManager.diagnostics
        override val nativeAds: NativeAdManager get() = NoOpAdManager.nativeAds

        override val consent: ConsentController = object : ConsentController {
            override val status: StateFlow<ConsentStatus> = MutableStateFlow(ConsentStatus.Unknown)
            override val privacyOptionsRequirementStatus: StateFlow<PrivacyOptionsRequirementStatus> =
                MutableStateFlow(PrivacyOptionsRequirementStatus.Unknown)
            override val canRequestAds: StateFlow<Boolean> = MutableStateFlow(true)
            override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus = ConsentStatus.Obtained
            override suspend fun gatherConsent(config: AdConfig): ConsentStatus = ConsentStatus.Obtained
            override suspend fun showPrivacyOptions(): Boolean = true
            override suspend fun resetConsentForDebug(): Boolean = true
        }

        override val tracking: AdTrackingController = object : AdTrackingController {
            override fun status(): AdTrackingAuthorization = AdTrackingAuthorization.Authorized
            override suspend fun requestAuthorization(): AdTrackingAuthorization = AdTrackingAuthorization.Authorized
        }

        override suspend fun initialize(config: AdConfig, consentMode: ConsentMode): AdManagerStatus =
            status.value
        override fun banner(placement: dev.avinya.ads.AdPlacement): BannerAdController =
            NoOpAdManager.banner(placement)
        override fun interstitial(placement: dev.avinya.ads.AdPlacement): InterstitialAdController =
            NoOpAdManager.interstitial(placement)
        override fun rewarded(placement: dev.avinya.ads.AdPlacement): RewardedAdController =
            NoOpAdManager.rewarded(placement)
        override fun rewardedInterstitial(placement: dev.avinya.ads.AdPlacement): RewardedInterstitialAdController =
            NoOpAdManager.rewardedInterstitial(placement)
        override fun appOpen(placement: dev.avinya.ads.AdPlacement): AppOpenAdController =
            NoOpAdManager.appOpen(placement)
    }
}
