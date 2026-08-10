package dev.avinya.admob.showcase.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import dev.avinya.admob.showcase.feature.discover.DiscoverScreen
import dev.avinya.admob.showcase.feature.library.LibraryScreen
import dev.avinya.admob.showcase.feature.profile.ProfileScreen
import dev.avinya.admob.showcase.feature.today.TodayScreen
import dev.avinya.admob.showcase.nav.ArticleRoute
import dev.avinya.admob.showcase.nav.RewardsRoute
import dev.avinya.admob.showcase.nav.SdkLabRoute
import dev.avinya.admob.showcase.nav.ShowcaseNavigationState
import dev.avinya.admob.showcase.nav.ShowcaseTab
import dev.avinya.admob.showcase.ui.kit.AppNavRail
import dev.avinya.admob.showcase.ui.kit.AppTabBar
import dev.avinya.admob.showcase.ui.kit.TabItem
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * Main shell component displaying chrome (bottom tab bar or navigation rail)
 * and the retained tab content container for top-level tab screens.
 */
@Composable
fun MainShellScreen(navigationState: ShowcaseNavigationState) {
    val tabs = ShowcaseTab.entries.map { tab ->
        TabItem(
            label = tab.name,
            icon = when (tab) {
                ShowcaseTab.Today -> Icons.Rounded.Newspaper
                ShowcaseTab.Discover -> Icons.Rounded.Explore
                ShowcaseTab.Library -> Icons.Rounded.Bookmark
                ShowcaseTab.Profile -> Icons.Rounded.Person
            },
            selected = navigationState.selectedTab == tab,
            onSelect = { navigationState.select(tab) },
        )
    }

    val tabSaveableStateHolder = rememberSaveableStateHolder()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(showcaseColors.canvas),
    ) {
        val isExpanded = maxWidth >= Tokens.navigationRailBreakpoint

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavRail(tabs = tabs)
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    TabContent(navigationState, tabSaveableStateHolder)
                }
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TabContent(navigationState, tabSaveableStateHolder)
                }
                AppTabBar(tabs = tabs)
            }
        }
    }
}

@Composable
private fun TabContent(
    navigationState: ShowcaseNavigationState,
    tabSaveableStateHolder: SaveableStateHolder,
) {
    tabSaveableStateHolder.SaveableStateProvider(navigationState.selectedTab.name) {
        when (navigationState.selectedTab) {
            ShowcaseTab.Today -> TodayScreen(
                onArticleClick = { navigationState.push(ArticleRoute(it)) },
            )
            ShowcaseTab.Discover -> DiscoverScreen(
                onArticleClick = { navigationState.push(ArticleRoute(it)) },
            )
            ShowcaseTab.Library -> LibraryScreen(
                onArticleClick = { navigationState.push(ArticleRoute(it)) },
                onExploreFeedClick = { navigationState.select(ShowcaseTab.Today) },
            )
            ShowcaseTab.Profile -> ProfileScreen(
                onOpenRewards = { navigationState.push(RewardsRoute) },
                onOpenSdkLab = { navigationState.push(SdkLabRoute) },
            )
        }
    }
}
