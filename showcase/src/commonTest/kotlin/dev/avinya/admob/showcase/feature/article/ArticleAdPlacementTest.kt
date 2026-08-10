package dev.avinya.admob.showcase.feature.article

import dev.avinya.admob.showcase.domain.article.ArticleBlock
import dev.avinya.admob.showcase.domain.article.buildArticleBlocks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleAdPlacementTest {

    @Test
    fun shortArticle_hasNoInlineAd() {
        assertFalse(buildArticleBlocks(shortArticle(), "article-1").any { it is ArticleBlock.NativeAd })
    }

    @Test
    fun inlineAd_isAfterMeaningfulContent_notHeadline() {
        val blocks = buildArticleBlocks(longArticle(), "article-9")
        val ad = blocks.singleAd()
        assertEquals("article:article-9:inline-1", ad.slotKey)
        assertTrue(blocks.indexOf(ad) > 2)
    }

    @Test
    fun theSameArticleAlwaysYieldsTheSameSlotKey() {
        val first = buildArticleBlocks(longArticle(), "article-9").singleAd().slotKey
        val second = buildArticleBlocks(longArticle(), "article-9").singleAd().slotKey
        assertEquals(first, second)
    }

    private fun shortArticle(): String = buildString {
        repeat(2) {
            append("A short paragraph with modest content that stays well under the floor.")
            append("\n\n")
        }
    }

    private fun longArticle(): String = buildString {
        repeat(6) {
            append(
                "This paragraph carries enough editorial content to cross the substantial " +
                    "threshold: it talks about the boundary between the obvious approach and " +
                    "the correct one, and why the divergence only shows up under load.",
            )
            append("\n\n")
        }
    }
}

private fun List<ArticleBlock>.singleAd(): ArticleBlock.NativeAd =
    filterIsInstance<ArticleBlock.NativeAd>().single()
