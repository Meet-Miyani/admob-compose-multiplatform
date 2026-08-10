package dev.avinya.admob.showcase.feature.discover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Mirrors [dev.avinya.admob.showcase.domain.feed.FeedAdInsertionTest]'s
 * coverage of `FeedAdInserter.slotKeyAfter` for Discover's own key
 * derivation, which [DiscoverViewModel] uses instead of the shared function
 * because Discover slots also need to be scoped to a search/category
 * session — see `withAdSlots` in DiscoverViewModel.kt.
 */
class DiscoverAdSlotKeyingTest {

    @Test
    fun theSameArticleInTheSameSessionAlwaysYieldsTheSameSlotKey() {
        val sessionKey = discoverCategorySessionKey("Tech")

        assertEquals(
            discoverSlotKeyAfter(sessionKey, "article-011"),
            discoverSlotKeyAfter(sessionKey, "article-011"),
        )
    }

    @Test
    fun aPrependDoesNotShiftExistingSlotKeys() {
        // Same case FeedAdInserter guards against: article-011 sits at a
        // different list position before/after a prepend, but the key is
        // derived from identity, not position, so pooled ad reuse is
        // unaffected.
        val sessionKey = DISCOVER_ALL_SESSION_KEY
        val beforePrepend = discoverSlotKeyAfter(sessionKey, "article-011")
        val threeNewArticlesArrive = listOf("article-a", "article-b", "article-c")

        assertEquals(beforePrepend, discoverSlotKeyAfter(sessionKey, "article-011"))
        assertTrue(
            threeNewArticlesArrive.none { discoverSlotKeyAfter(sessionKey, it) == beforePrepend },
        )
    }

    @Test
    fun distinctArticlesInTheSameSessionNeverCollideOnAKey() {
        val sessionKey = discoverSearchSessionKey("compose")
        val keys = (0 until 200).map { discoverSlotKeyAfter(sessionKey, "article-$it") }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun switchingSessionForTheSameArticleYieldsADifferentSlotKey() {
        // Search, category, and "All" are distinct browse contexts — see
        // DiscoverContext.nativeSessionKey — so the same article must not
        // reuse a slot key across them, or switching context would hand a
        // pooled ad view a key it thinks it already has state for.
        val allKey = discoverSlotKeyAfter(DISCOVER_ALL_SESSION_KEY, "article-011")
        val searchKey = discoverSlotKeyAfter(discoverSearchSessionKey("compose"), "article-011")
        val categoryKey = discoverSlotKeyAfter(discoverCategorySessionKey("Tech"), "article-011")

        val keys = listOf(allKey, searchKey, categoryKey)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun sessionKeysAreDistinctPerSearchQuery() {
        assertNotEquals(discoverSearchSessionKey("compose"), discoverSearchSessionKey("kotlin"))
    }

    @Test
    fun sessionKeysAreDistinctPerCategory() {
        assertNotEquals(discoverCategorySessionKey("Tech"), discoverCategorySessionKey("Business"))
    }

    @Test
    fun searchAndCategorySessionKeysNeverCollideEvenWithTheSameLabel() {
        // "Tech" as a search query and "Tech" as a category are different
        // browse contexts and must not share a session identity.
        assertNotEquals(discoverSearchSessionKey("Tech"), discoverCategorySessionKey("Tech"))
    }
}
