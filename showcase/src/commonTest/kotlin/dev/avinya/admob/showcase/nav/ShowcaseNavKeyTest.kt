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
    fun onlyTabRootsKeepNavigationChrome() {
        // A pushed detail takes the whole window and slides in over its parent,
        // so "back" means one unambiguous thing on both platforms.
        TOP_LEVEL_KEYS.forEach { root ->
            assertTrue(showsNavigationChrome(root), "$root is a tab root")
        }
        listOf(
            OnboardingRoute,
            ArticleRoute("a1"),
            RewardsRoute,
            SdkLabRoute,
            BannerLabRoute,
            NativeLabRoute,
            FullScreenLabRoute,
            AppOpenLabRoute,
            PrivacyLabRoute,
            DiagnosticsLabRoute,
        ).forEach { pushed ->
            assertFalse(showsNavigationChrome(pushed), "$pushed is not a tab root")
        }
    }
}
