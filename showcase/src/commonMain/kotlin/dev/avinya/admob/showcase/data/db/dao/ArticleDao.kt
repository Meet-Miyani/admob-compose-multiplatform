package dev.avinya.admob.showcase.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import dev.avinya.admob.showcase.data.db.entity.ArticleEntity
import dev.avinya.admob.showcase.data.db.entity.BookmarkEntity
import dev.avinya.admob.showcase.data.db.entity.ReadingProgressEntity
import dev.avinya.admob.showcase.data.db.entity.UnlockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArticleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(articles: List<ArticleEntity>)

    @Query("SELECT id FROM articles ORDER BY publishedAt DESC")
    suspend fun allIds(): List<String>

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun count(): Int

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun byId(id: String): ArticleEntity?

    @Query("SELECT * FROM articles ORDER BY publishedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<ArticleEntity>

    /**
     * Feed order. `feedOrdinal` ascending is equivalent to `publishedAt`
     * descending — asserted by `ArticleSeedTest` — and sorting on the integer
     * keeps the ad-slot rule and the query agreeing on one ordering.
     */
    @Query("SELECT * FROM articles ORDER BY feedOrdinal ASC")
    fun pagingSource(): PagingSource<Int, ArticleEntity>

    /**
     * Discover results ordered by feed ordinal.
     *
     * [section] filters on exact category when non-null; [query] is the
     * committed, normalized search string matched across title, author,
     * section, and body. The empty query returns every article — the caller
     * only uses this as the category landing, never as a wildcard search.
     */
    @Query(
        "SELECT * FROM articles " +
            "WHERE (:section IS NULL OR section = :section) " +
            "AND (:query = '' OR title LIKE '%' || :query || '%' " +
            "OR author LIKE '%' || :query || '%' " +
            "OR section LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%') " +
            "ORDER BY feedOrdinal ASC",
    )
    fun discoverPagingSource(section: String?, query: String): PagingSource<Int, ArticleEntity>

    /** Distinct editorial sections, alphabetically. */
    @Query("SELECT DISTINCT section FROM articles ORDER BY section ASC")
    fun sections(): Flow<List<String>>

    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE articleId = :articleId")
    suspend fun progressFor(articleId: String): ReadingProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE articleId = :articleId")
    suspend fun removeBookmark(articleId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE articleId = :articleId)")
    fun isBookmarked(articleId: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addUnlock(unlock: UnlockEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM unlocks WHERE articleId = :articleId)")
    fun isUnlocked(articleId: String): Flow<Boolean>

    @Query("SELECT * FROM articles WHERE isPremium = 1 ORDER BY feedOrdinal ASC")
    fun premiumArticles(): Flow<List<ArticleEntity>>

    @Query(
        "SELECT a.* FROM articles a " +
            "INNER JOIN bookmarks b ON b.articleId = a.id " +
            "ORDER BY b.createdAt DESC",
    )
    fun bookmarkedArticles(): Flow<List<ArticleEntity>>

    @Query(
        "SELECT a.* FROM articles a " +
            "INNER JOIN reading_progress p ON p.articleId = a.id " +
            "WHERE p.scrollFraction > 0.0 AND p.scrollFraction < 1.0 " +
            "ORDER BY p.updatedAt DESC",
    )
    fun inProgressArticles(): Flow<List<ArticleEntity>>

    @Query(
        "SELECT a.* FROM articles a " +
            "INNER JOIN unlocks u ON u.articleId = a.id " +
            "ORDER BY u.unlockedAt DESC",
    )
    fun unlockedArticles(): Flow<List<ArticleEntity>>

    @Query("SELECT articleId FROM unlocks")
    fun unlockedIds(): Flow<List<String>>
}
