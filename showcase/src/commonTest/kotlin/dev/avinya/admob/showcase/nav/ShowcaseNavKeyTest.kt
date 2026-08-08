package dev.avinya.admob.showcase.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowcaseNavKeyTest {

    @Test
    fun exposesExactlyFourTopLevelTabs() {
        assertEquals(
            listOf(
                ShowcaseTab.Today,
                ShowcaseTab.Discover,
                ShowcaseTab.Library,
                ShowcaseTab.Profile,
            ),
            ShowcaseTab.entries,
        )
    }

    @Test
    fun articleRouteIsNotATopLevelDestination() {
        assertTrue(TOP_LEVEL_KEYS.none { it is ArticleRoute })
    }

    @Test
    fun articleRouteKeysCompareByArticleId() {
        assertEquals(ArticleRoute("a1"), ArticleRoute("a1"))
        assertTrue(ArticleRoute("a1") != ArticleRoute("a2"))
    }

    @Test
    fun onboardingIsNotATopLevelDestination() {
        assertTrue(TOP_LEVEL_KEYS.none { it == OnboardingRoute })
    }

    @Test
    fun onboardingHidesNavigationChrome() {
        assertFalse(showsNavigationChrome(OnboardingRoute))
        assertTrue(showsNavigationChrome(TodayRoute))
        assertTrue(showsNavigationChrome(ArticleRoute("a1")))
    }
}
