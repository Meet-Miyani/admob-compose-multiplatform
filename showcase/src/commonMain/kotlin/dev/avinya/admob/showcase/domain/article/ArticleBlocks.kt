package dev.avinya.admob.showcase.domain.article

/** One renderable unit of the article reader. */
sealed interface ArticleBlock {
    data class Paragraph(val text: String) : ArticleBlock
    data class NativeAd(val slotKey: String) : ArticleBlock
}

/**
 * Renders an article body as ordered blocks with at most one inline native ad.
 *
 * The ad follows the first substantial body section after at least two
 * paragraphs or 450 rendered characters, whichever boundary comes later.
 * Articles too short to reach that boundary get no ad at all.
 */
fun buildArticleBlocks(body: String, articleId: String): List<ArticleBlock> {
    val paragraphs = splitParagraphs(body)
    val anchorIndex = inlineAdAnchorIndex(paragraphs) ?: return paragraphs.map(ArticleBlock::Paragraph)

    return buildList {
        paragraphs.forEachIndexed { index, text ->
            add(ArticleBlock.Paragraph(text))
            if (index == anchorIndex) {
                add(ArticleBlock.NativeAd(inlineAdSlotKey(articleId)))
            }
        }
    }
}

/**
 * Index of the paragraph after which the inline ad is placed, or null when
 * the article never crosses the substantial-content boundary.
 *
 * The boundary is the later of: the second paragraph (so the ad is never
 * above the deck or inside the opening) and 450 accumulated characters
 * (so a wall of short paragraphs still earns the ad eventually).
 */
fun inlineAdAnchorIndex(paragraphs: List<String>): Int? {
    var accumulated = 0
    paragraphs.forEachIndexed { index, text ->
        accumulated += text.length
        if (index >= 2 && accumulated >= INLINE_AD_MIN_CHARS) return index
    }
    return null
}

/** Stable slot identity for the single inline ad of [articleId]. */
fun inlineAdSlotKey(articleId: String): String = "article:$articleId:inline-1"

private const val INLINE_AD_MIN_CHARS: Int = 450
