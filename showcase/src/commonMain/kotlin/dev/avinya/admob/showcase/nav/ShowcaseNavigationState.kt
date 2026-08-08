package dev.avinya.admob.showcase.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavKey

/**
 * Retained navigation state for Fieldnotes.
 *
 * Maintains one persistent stack per [ShowcaseTab], enabling retained state
 * across tab switches. Re-selecting the active tab pops that stack back to its root.
 */
class ShowcaseNavigationState internal constructor(
    initialTab: ShowcaseTab,
    private val stacks: Map<ShowcaseTab, SnapshotStateList<NavKey>>,
) {
    var selectedTab by mutableStateOf(initialTab)
        private set

    val currentStack: SnapshotStateList<NavKey>
        get() = stacks.getValue(selectedTab)

    val currentRoute: NavKey
        get() = currentStack.last()

    fun select(tab: ShowcaseTab) {
        if (tab == selectedTab) {
            retainRoot()
        } else {
            selectedTab = tab
        }
    }

    fun push(key: NavKey) {
        currentStack.add(key)
    }

    fun pop(): Boolean {
        val stack = currentStack
        if (stack.size > 1) {
            stack.removeLast()
            return true
        }
        return false
    }

    fun retainRoot() {
        val stack = currentStack
        while (stack.size > 1) {
            stack.removeLast()
        }
    }

    companion object {
        fun initial(initialTab: ShowcaseTab = ShowcaseTab.Today): ShowcaseNavigationState {
            val stacks = ShowcaseTab.entries.associateWith { tab ->
                mutableStateListOf(tab.root)
            }
            return ShowcaseNavigationState(
                initialTab = initialTab,
                stacks = stacks,
            )
        }
    }
}

@Composable
fun rememberShowcaseNavigationState(
    initialTab: ShowcaseTab = ShowcaseTab.Today,
): ShowcaseNavigationState {
    return remember {
        ShowcaseNavigationState.initial(initialTab)
    }
}

fun SnapshotStateList<NavKey>.retainRoot() {
    while (size > 1) {
        removeLast()
    }
}
