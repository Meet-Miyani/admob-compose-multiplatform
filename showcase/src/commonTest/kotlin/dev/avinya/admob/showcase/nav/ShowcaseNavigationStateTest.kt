package dev.avinya.admob.showcase.nav

import kotlin.test.Test
import kotlin.test.assertEquals

class ShowcaseNavigationStateTest {

    @Test
    fun switchingTabs_preservesEachTabsBackStack() {
        val state = ShowcaseNavigationState.initial()

        state.push(ArticleRoute("today-42"))
        state.select(ShowcaseTab.Profile)
        state.push(SdkLabRoute)
        state.select(ShowcaseTab.Today)

        assertEquals(ArticleRoute("today-42"), state.currentRoute)
        state.select(ShowcaseTab.Profile)
        assertEquals(SdkLabRoute, state.currentRoute)
    }

    @Test
    fun reselectingCurrentTab_popsThatTabToRoot() {
        val state = ShowcaseNavigationState.initial()
        state.push(ArticleRoute("today-42"))

        state.select(ShowcaseTab.Today)

        assertEquals(TodayRoute, state.currentRoute)
    }
}
