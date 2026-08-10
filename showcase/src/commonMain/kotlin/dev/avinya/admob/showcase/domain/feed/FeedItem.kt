package dev.avinya.admob.showcase.domain.feed

import dev.avinya.ads.AdPlacement
import dev.avinya.ads.nativead.NativeAdSlot as SessionSlot

/** One row in the feed: either real content or a native ad slot. */
sealed interface FeedItem {

    /** Stable identity for Compose's `key` and for Paging's diffing. */
    val key: String

    /**
     * A paged article as it appears in the feed.
     *
     * [feedOrdinal] is the article's position in the **un-inserted** feed — its
     * index before [FeedAdInserter] interleaves ad slots.
     */
    data class Article(
        val id: String,
        val title: String,
        val author: String,
        val section: String,
        val readTimeMinutes: Int,
        val snippet: String,
        val isPremium: Boolean,
        val feedOrdinal: Int,
        val isBookmarked: Boolean = false,
    ) : FeedItem {
        override val key: String get() = "article:$id"
    }

    /**
     * A native ad slot.
     *
     * [slotKey] comes from [FeedAdInserter.slotKeyAfter] and is derived from
     * the article this slot follows — never from a list position.
     */
    data class NativeAdSlot(val slotKey: String) : FeedItem {
        override val key: String get() = "native:$slotKey"

        fun sessionSlot(placement: AdPlacement): SessionSlot = SessionSlot(slotKey, placement)
    }
}
