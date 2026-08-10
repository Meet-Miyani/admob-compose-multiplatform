package dev.avinya.admob.showcase.nav

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShowcaseNavigationStateTest {

    @Test
    fun outerStack_initialStateIsMainShellRoute() {
        val state = ShowcaseNavigationState.initial()
        assertEquals(MainShellRoute, state.currentRoute)
        assertEquals(1, state.outerStack.size)
        assertEquals(ShowcaseTab.Today, state.selectedTab)
    }

    @Test
    fun pushAndPop_manipulatesOuterStack() {
        val state = ShowcaseNavigationState.initial()
        state.push(ArticleRoute("today-42"))

        assertEquals(ArticleRoute("today-42"), state.currentRoute)
        assertEquals(2, state.outerStack.size)

        val popped = state.pop()
        assertTrue(popped)
        assertEquals(MainShellRoute, state.currentRoute)
        assertEquals(1, state.outerStack.size)
    }

    @Test
    fun pop_fromRoot_returnsFalseAndLeavesRootIntact() {
        val state = ShowcaseNavigationState.initial()
        val result = state.pop()
        assertFalse(result)
        assertEquals(MainShellRoute, state.currentRoute)
        assertEquals(1, state.outerStack.size)
    }

    @Test
    fun selectingTab_updatesSelectedTabWithoutAffectingOuterStack() {
        val state = ShowcaseNavigationState.initial()
        state.select(ShowcaseTab.Profile)
        assertEquals(ShowcaseTab.Profile, state.selectedTab)

        state.select(ShowcaseTab.Discover)
        assertEquals(ShowcaseTab.Discover, state.selectedTab)
        assertEquals(MainShellRoute, state.currentRoute)
    }

    @Test
    fun saver_roundtrip_preservesOuterStackAndSelectedTab() {
        val original = ShowcaseNavigationState.initial(initialTab = ShowcaseTab.Profile)
        original.push(SdkLabRoute)

        val scope = SaverScope { true }
        val saved = with(ShowcaseNavigationStateSaver) { scope.save(original) }
            ?: error("save returned null")
        val restored = ShowcaseNavigationStateSaver.restore(saved)
            ?: error("restore returned null")

        assertEquals(ShowcaseTab.Profile, restored.selectedTab)
        assertEquals(SdkLabRoute, restored.currentRoute)
        assertEquals(2, restored.outerStack.size)
    }
}
