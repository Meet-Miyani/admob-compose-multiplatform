package dev.avinya.admob.showcase.domain.feed

import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.ads.nativead.NativeAdBatching
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedAdInsertionTest {

    @Test
    fun insertsFirstAdAfterFourArticles_thenEveryEight() {
        assertEquals(
            listOf(3, 11, 19, 27),
            (0 until 28).filter(FeedAdInserter::shouldInsertAfter),
        )
    }

    @Test
    fun doesNotInsertBeforeTheFirstFourArticles() {
        (0..2).forEach { assertFalse(FeedAdInserter.shouldInsertAfter(it), "unexpected slot at $it") }
    }

    @Test
    fun slotKey_isRevisionedAndAnchoredToArticleIdentity() {
        assertEquals(
            "today:seed-v1:article-011",
            FeedAdInserter.slotKeyAfter(articleId = "article-011"),
        )
    }

    @Test
    fun theSameArticleAlwaysYieldsTheSameSlotKey() {
        assertEquals(
            FeedAdInserter.slotKeyAfter("article-011"),
            FeedAdInserter.slotKeyAfter("article-011"),
        )
    }

    @Test
    fun aPrependDoesNotShiftExistingSlotKeys() {
        // THE case index-derived keys get wrong. article-011 sits at list
        // position 11 before a prepend and 14 after it; its slot key must not
        // change, or NativeAdView releases the pooled ad bound to that row and
        // acquires another for no reason — wasted inventory and visible flicker.
        val beforePrepend = FeedAdInserter.slotKeyAfter("article-011")
        val threeNewArticlesArrive = listOf("article-a", "article-b", "article-c")

        assertEquals(beforePrepend, FeedAdInserter.slotKeyAfter("article-011"))
        assertTrue(threeNewArticlesArrive.none { FeedAdInserter.slotKeyAfter(it) == beforePrepend })
    }

    @Test
    fun distinctArticlesNeverCollideOnAKey() {
        val keys = (0 until 200).map { FeedAdInserter.slotKeyAfter("article-$it") }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun nativeSlotMapsItsStableModelKeyToTheFeedPlacement() {
        val model = FeedItem.NativeAdSlot(FeedAdInserter.slotKeyAfter("article-011"))

        val slot = model.sessionSlot(ShowcasePlacements.feedNative)

        assertEquals("today:seed-v1:article-011", slot.key)
        assertEquals(ShowcasePlacements.feedNative, slot.placement)
    }

    @Test
    fun onlyTheFeedTestPlacementOptsIntoGoogleBatching() {
        assertEquals(NativeAdBatching.GoogleOnly, ShowcasePlacements.feedNative.nativeOptions.batching)
        assertEquals(NativeAdBatching.Sequential, ShowcasePlacements.articleNative.nativeOptions.batching)
    }
}
