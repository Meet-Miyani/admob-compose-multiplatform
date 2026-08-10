package dev.avinya.admob.showcase.domain.article

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParagraphsTest {

    @Test
    fun splitsOnBlankLines() {
        assertEquals(listOf("one", "two", "three"), splitParagraphs("one\n\ntwo\n\nthree"))
    }

    @Test
    fun ignoresTrailingWhitespaceAndEmptyParagraphs() {
        assertEquals(listOf("one", "two"), splitParagraphs("one\n\n\n\ntwo\n\n  \n"))
    }

    @Test
    fun anchorWaitsForTwoParagraphsAndFourHundredFiftyCharacters() {
        // Four short paragraphs (280 chars) never reach the 450-character floor.
        val shortParagraphs = List(4) { "x".repeat(70) }
        assertNull(inlineAdAnchorIndex(shortParagraphs))

        // The fifth paragraph (index 4) crosses the character floor, and 4 >= 2.
        val crossing = shortParagraphs + listOf("y".repeat(250))
        assertEquals(4, inlineAdAnchorIndex(crossing))
    }

    @Test
    fun anchorNeverSitsInsideTheOpening() {
        // One enormous opening paragraph still cannot carry the ad before
        // paragraph index 2.
        val paragraphs = listOf("z".repeat(1_000), "a", "b", "c")
        assertEquals(2, inlineAdAnchorIndex(paragraphs))
    }

    @Test
    fun slotKeyIsAnchoredToArticleIdentity() {
        assertEquals("article:article-9:inline-1", inlineAdSlotKey("article-9"))
    }
}
