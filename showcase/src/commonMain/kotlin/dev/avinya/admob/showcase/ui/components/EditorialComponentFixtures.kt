package dev.avinya.admob.showcase.ui.components

/**
 * Deterministic sample fixtures for editorial component testing and previews.
 */
object EditorialComponentFixtures {
    val sampleArticleHero = ArticleCardModel(
        id = "fixture-hero-1",
        title = "Architecting Compose Multiplatform Applications for Scale",
        author = "Elena Rostova",
        section = "Architecture",
        readTimeMinutes = 8,
        snippet = "Explore core architectural patterns, unidirectional data flow, and multi-module state management strategies for building resilient Compose Multiplatform apps.",
        isPremium = true,
    )

    val sampleArticleStandard = ArticleCardModel(
        id = "fixture-standard-1",
        title = "Modern State Management in Kotlin Multiplatform",
        author = "Marcus Vance",
        section = "Kotlin",
        readTimeMinutes = 5,
        snippet = "A deep dive into reactive state flows, coroutine scopes, and cross-platform architecture.",
        isPremium = false,
    )

    val sampleArticleCompact = ArticleCardModel(
        id = "fixture-compact-1",
        title = "Optimizing UI Recomposition Performance",
        author = "Sarah Chen",
        section = "Compose",
        readTimeMinutes = 3,
        snippet = "Quick tips and tools for identifying redundant recompositions in complex Compose hierarchies.",
        isPremium = false,
    )

    val sampleArticles = listOf(sampleArticleHero, sampleArticleStandard, sampleArticleCompact)
}
