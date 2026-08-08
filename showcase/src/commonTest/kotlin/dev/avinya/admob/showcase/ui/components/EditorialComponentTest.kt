package dev.avinya.admob.showcase.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorialComponentTest {

    @Test
    fun articleCardModel_retainsProperties() {
        val model = EditorialComponentFixtures.sampleArticleHero
        assertEquals("fixture-hero-1", model.id)
        assertEquals("Architecting Compose Multiplatform Applications for Scale", model.title)
        assertEquals("Elena Rostova", model.author)
        assertEquals("Architecture", model.section)
        assertEquals(8, model.readTimeMinutes)
        assertTrue(model.isPremium)
    }

    @Test
    fun articleCardTreatments_enumHasAllCases() {
        val treatments = ArticleCardTreatment.entries
        assertEquals(3, treatments.size)
        assertTrue(treatments.contains(ArticleCardTreatment.Hero))
        assertTrue(treatments.contains(ArticleCardTreatment.Standard))
        assertTrue(treatments.contains(ArticleCardTreatment.Compact))
    }

    @Test
    fun fixtures_providesValidSamples() {
        assertEquals(3, EditorialComponentFixtures.sampleArticles.size)
        assertFalse(EditorialComponentFixtures.sampleArticleStandard.isPremium)
    }
}
