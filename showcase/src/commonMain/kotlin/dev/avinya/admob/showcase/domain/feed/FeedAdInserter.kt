package dev.avinya.admob.showcase.domain.feed

/**
 * Decides where native ad slots go in the feed, and what they are keyed by.
 *
 * Pure on purpose. Slot placement and key derivation are the two things a
 * feed integration most often gets wrong, and both are decidable from values
 * alone — no Paging, no Compose, no SDK.
 */
object FeedAdInserter {

    /** First ad after 4 articles (ordinal 3). */
    const val FIRST_AD_AFTER: Int = 4

    /** Repeat every 8 articles (ordinals 3, 11, 19, 27...). */
    const val REPEAT_INTERVAL: Int = 8

    /** Today feed revision string for slot key stability. */
    const val TODAY_FEED_REVISION: String = "seed-v1"

    /** True when an ad slot belongs immediately after the article at [feedOrdinal]. */
    fun shouldInsertAfter(feedOrdinal: Int): Boolean =
        feedOrdinal >= FIRST_AD_AFTER - 1 && (feedOrdinal - (FIRST_AD_AFTER - 1)) % REPEAT_INTERVAL == 0

    /**
     * The `itemKey` for the slot following [articleId].
     *
     * Derived from the article's identity, **never** from its position.
     */
    fun slotKeyAfter(articleId: String): String = "today:$TODAY_FEED_REVISION:$articleId"
}
