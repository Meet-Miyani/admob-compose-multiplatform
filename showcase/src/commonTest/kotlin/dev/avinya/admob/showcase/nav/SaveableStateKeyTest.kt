package dev.avinya.admob.showcase.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SaveableStateKeyTest {

    @Test
    fun everyRouteProducesADistinctKey() {
        val routes = TOP_LEVEL_KEYS + listOf(
            OnboardingRoute,
            SdkLabRoute,
            BannerLabRoute,
            NativeLabRoute,
            FullScreenLabRoute,
            AppOpenLabRoute,
            PrivacyLabRoute,
            DiagnosticsLabRoute,
            ArticleRoute("a1"),
        )
        val keys = routes.map { saveableStateKey(it) }
        assertEquals(routes.size, keys.toSet().size)
    }

    @Test
    fun articleRoutesAreKeyedByArticleId() {
        assertEquals(saveableStateKey(ArticleRoute("a1")), saveableStateKey(ArticleRoute("a1")))
        assertNotEquals(saveableStateKey(ArticleRoute("a1")), saveableStateKey(ArticleRoute("a2")))
    }
}
