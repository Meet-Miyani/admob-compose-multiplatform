package dev.avinya.admob.showcase.data.repo

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import dev.avinya.admob.showcase.core.time.Clock
import dev.avinya.admob.showcase.data.db.dao.ArticleDao
import dev.avinya.admob.showcase.data.db.entity.ArticleEntity
import dev.avinya.admob.showcase.data.db.entity.BookmarkEntity
import dev.avinya.admob.showcase.data.db.entity.ReadingProgressEntity
import dev.avinya.admob.showcase.data.db.entity.UnlockEntity
import dev.avinya.admob.showcase.data.db.entity.UnlockSource
import dev.avinya.admob.showcase.data.seed.ArticleSeed
import dev.avinya.admob.showcase.domain.feed.FeedItem
import dev.avinya.admob.showcase.domain.library.LibraryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Reads and writes article content, bookmarks and reading progress. */
class ArticleRepository(
    private val articleDao: ArticleDao,
    private val clock: Clock,
) {

    /** Populates the database on first launch. A no-op afterwards. */
    suspend fun seedIfEmpty() {
        if (articleDao.count() == 0) {
            articleDao.insertAll(ArticleSeed.articles())
        }
    }

    suspend fun article(id: String): ArticleEntity? = articleDao.byId(id)

    suspend fun page(limit: Int, offset: Int): List<ArticleEntity> = articleDao.page(limit, offset)

    /**
     * The feed, 20 articles at a time.
     *
     * Emits only articles; ad slots are inserted downstream by
     * `FeedViewModel`, so the repository stays unaware of advertising.
     */
    fun feedPager(): Flow<PagingData<FeedItem.Article>> = Pager(
        config = PagingConfig(pageSize = FEED_PAGE_SIZE, enablePlaceholders = false),
        pagingSourceFactory = { articleDao.pagingSource() },
    ).flow.map { paging -> paging.map(ArticleEntity::toFeedArticle) }

    /**
     * Discover results for an optional [section] and a committed, normalized
     * [query]. Both are applied inside the SQL so paging stays lazy and
     * query changes re-run from the database instead of filtering in memory.
     */
    fun discoverPager(section: String?, query: String): Flow<PagingData<FeedItem.Article>> = Pager(
        config = PagingConfig(pageSize = FEED_PAGE_SIZE, enablePlaceholders = false),
        pagingSourceFactory = { articleDao.discoverPagingSource(section, query) },
    ).flow.map { paging -> paging.map(ArticleEntity::toFeedArticle) }

    /** Distinct editorial sections, alphabetically. */
    fun sections(): Flow<List<String>> = articleDao.sections()

    suspend fun setProgress(articleId: String, fraction: Float) {
        articleDao.upsertProgress(
            ReadingProgressEntity(
                articleId = articleId,
                scrollFraction = fraction.coerceIn(0f, 1f),
                updatedAt = clock.nowMillis(),
            ),
        )
    }

    suspend fun progress(articleId: String): Float =
        articleDao.progressFor(articleId)?.scrollFraction ?: 0f

    fun isBookmarked(articleId: String): Flow<Boolean> = articleDao.isBookmarked(articleId)

    fun bookmarkedArticles(): Flow<List<ArticleEntity>> = articleDao.bookmarkedArticles()

    suspend fun setBookmarked(articleId: String, bookmarked: Boolean) {
        if (bookmarked) {
            articleDao.addBookmark(BookmarkEntity(articleId = articleId, createdAt = clock.nowMillis()))
        } else {
            articleDao.removeBookmark(articleId)
        }
    }

    fun isUnlocked(articleId: String): Flow<Boolean> = articleDao.isUnlocked(articleId)

    /** Premium articles, with their current unlock state. */
    fun premiumCatalog(): Flow<List<PremiumArticle>> = combine(
        articleDao.premiumArticles(),
        articleDao.unlockedIds(),
    ) { articles, unlockedIds ->
        val unlocked = unlockedIds.toSet()
        articles.map { article ->
            PremiumArticle(
                id = article.id,
                title = article.title,
                section = article.section,
                costCoins = article.unlockCostCoins,
                isUnlocked = article.id in unlocked,
            )
        }
    }

    /** Records an unlock. `IGNORE` on the insert makes a repeat a no-op. */
    suspend fun unlock(articleId: String, source: UnlockSource) {
        articleDao.addUnlock(
            UnlockEntity(articleId = articleId, unlockedAt = clock.nowMillis(), source = source),
        )
    }

    fun library(): Flow<List<LibraryEntry>> = combine(
        articleDao.bookmarkedArticles(),
        articleDao.inProgressArticles(),
        articleDao.unlockedArticles(),
    ) { bookmarked, inProgress, unlocked ->
        bookmarked.map { it.toLibraryEntry(LibraryEntry.Kind.Bookmarked) } +
            inProgress.map { it.toLibraryEntry(LibraryEntry.Kind.InProgress) } +
            unlocked.map { it.toLibraryEntry(LibraryEntry.Kind.Unlocked) }
    }
}

/** 20 keeps the 126-article seed at six real pages. */
const val FEED_PAGE_SIZE: Int = 20

private fun ArticleEntity.toFeedArticle(): FeedItem.Article = FeedItem.Article(
    id = id,
    title = title,
    author = author,
    section = section,
    readTimeMinutes = readTimeMin,
    snippet = body.substringBefore("\n\n").take(140),
    isPremium = isPremium,
    feedOrdinal = feedOrdinal,
    isBookmarked = false,
)

data class PremiumArticle(
    val id: String,
    val title: String,
    val section: String,
    val costCoins: Int,
    val isUnlocked: Boolean,
)

private fun ArticleEntity.toLibraryEntry(kind: LibraryEntry.Kind) = LibraryEntry(
    articleId = id,
    title = title,
    section = section,
    readTimeMin = readTimeMin,
    kind = kind,
)
