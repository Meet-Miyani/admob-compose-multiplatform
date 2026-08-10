package dev.avinya.admob.showcase.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavKey

/**
 * Retained navigation state for Fieldnotes.
 *
 * Manages an [outerStack] for full-screen destinations (`MainShellRoute`, `ArticleRoute`,
 * `SdkLabRoute`, etc.) and a [selectedTab] for top-level tab destinations.
 */
class ShowcaseNavigationState internal constructor(
    initialTab: ShowcaseTab,
    val outerStack: SnapshotStateList<NavKey>,
) {
    var selectedTab by mutableStateOf(initialTab)
        private set

    val currentRoute: NavKey
        get() = outerStack.last()

    fun select(tab: ShowcaseTab) {
        selectedTab = tab
    }

    fun push(key: NavKey) {
        outerStack.add(key)
    }

    fun pop(): Boolean {
        if (outerStack.size > 1) {
            outerStack.removeLast()
            return true
        }
        return false
    }

    companion object {
        fun initial(
            initialTab: ShowcaseTab = ShowcaseTab.Today,
            initialOuterKey: NavKey = MainShellRoute,
        ): ShowcaseNavigationState {
            return ShowcaseNavigationState(
                initialTab = initialTab,
                outerStack = mutableStateListOf(initialOuterKey),
            )
        }
    }
}

/**
 * [listSaver] that survives configuration changes (screen rotation, window resizing).
 */
internal val ShowcaseNavigationStateSaver = listSaver<ShowcaseNavigationState, Any>(
    save = { state ->
        buildList {
            add(state.selectedTab.ordinal)
            add(state.outerStack.map { saveableStateKey(it) })
        }
    },
    restore = { saved ->
        val selectedTabOrdinal = saved[0] as Int
        @Suppress("UNCHECKED_CAST")
        val savedKeys = saved[1] as List<String>
        val parsed = savedKeys.map { navKeyFromSaveableKey(it) }
        // A single unparseable entry (a route removed since the state was
        // saved, or a corrupted Bundle) would otherwise silently drop just
        // that entry via `mapNotNull`, leaving a stack with a hole in it and
        // a `currentRoute`/back-navigation that no longer matches what the
        // user actually had open. Falling back to a clean single-entry stack
        // is a visible, honest reset instead of a silently mangled one.
        val stack = mutableStateListOf<NavKey>()
        stack.addAll(
            if (parsed.isEmpty() || parsed.any { it == null }) {
                listOf(MainShellRoute)
            } else {
                @Suppress("UNCHECKED_CAST")
                parsed as List<NavKey>
            },
        )
        ShowcaseNavigationState(
            initialTab = ShowcaseTab.entries[selectedTabOrdinal],
            outerStack = stack,
        )
    },
)

@Composable
fun rememberShowcaseNavigationState(
    initialTab: ShowcaseTab = ShowcaseTab.Today,
): ShowcaseNavigationState {
    return rememberSaveable(saver = ShowcaseNavigationStateSaver) {
        ShowcaseNavigationState.initial(initialTab)
    }
}
