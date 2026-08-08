package dev.avinya.admob.showcase.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay

import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor
import dev.avinya.admob.showcase.feature.article.ArticleScreen
import dev.avinya.admob.showcase.feature.feed.FeedScreen
import dev.avinya.admob.showcase.feature.library.LibraryScreen
import dev.avinya.admob.showcase.feature.onboarding.OnboardingScreen
import dev.avinya.admob.showcase.feature.settings.SettingsScreen
import dev.avinya.admob.showcase.feature.store.StoreScreen
import dev.avinya.admob.showcase.ui.theme.FieldnotesTokens

/**
 * The app's navigation shell.
 *
 * Provides retained top-level navigation and grounded navigation chrome.
 * Uses a bottom [NavigationBar] on compact screens (< 840.dp) and a side
 * [NavigationRail] on expanded screens (>= 840.dp).
 */
@Composable
fun ShowcaseNavHost(navigationState: ShowcaseNavigationState) {
    val saveableStateHolder = rememberSaveableStateHolder()

    // Persistent ViewModelStoreNavEntryDecorator per top-level tab.
    // Retaining these at host scope ensures switching active tabs does not cause
    // inactive tab ViewModels or ad sessions to be cleared as "popped" routes.
    val todayDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val discoverDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val libraryDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val profileDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()

    val activeDecorator: NavEntryDecorator<NavKey> = when (navigationState.selectedTab) {
        ShowcaseTab.Today -> todayDecorator
        ShowcaseTab.Discover -> discoverDecorator
        ShowcaseTab.Library -> libraryDecorator
        ShowcaseTab.Profile -> profileDecorator
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= FieldnotesTokens.navigationRailBreakpoint
        val showChrome = showsNavigationChrome(navigationState.currentRoute)

        if (isExpanded) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (showChrome) {
                    NavigationRail {
                        ShowcaseTab.entries.forEach { tab ->
                            val selected = navigationState.selectedTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigationState.select(tab) },
                                icon = {
                                    Icon(
                                        imageVector = tabIcon(tab),
                                        contentDescription = tab.name,
                                    )
                                },
                                label = { Text(tab.name) },
                            )
                        }
                    }
                }
                NavDisplayContent(
                    navigationState = navigationState,
                    saveableStateHolder = saveableStateHolder,
                    activeDecorator = activeDecorator,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Scaffold(
                bottomBar = {
                    if (showChrome) {
                        NavigationBar {
                            ShowcaseTab.entries.forEach { tab ->
                                val selected = navigationState.selectedTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigationState.select(tab) },
                                    icon = {
                                        Icon(
                                            imageVector = tabIcon(tab),
                                            contentDescription = tab.name,
                                        )
                                    },
                                    label = { Text(tab.name) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                NavDisplayContent(
                    navigationState = navigationState,
                    saveableStateHolder = saveableStateHolder,
                    activeDecorator = activeDecorator,
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
        }
    }
}

@Composable
private fun NavDisplayContent(
    navigationState: ShowcaseNavigationState,
    saveableStateHolder: SaveableStateHolder,
    activeDecorator: NavEntryDecorator<NavKey>,
    modifier: Modifier = Modifier,
) {
    val suppressor = LocalAppOpenSuppressor.current

    NavDisplay(
        backStack = navigationState.currentStack,
        modifier = modifier,
        onBack = { navigationState.pop() },
        entryDecorators = listOf(activeDecorator),
        transitionSpec = {
            ContentTransform(
                fadeIn(animationSpec = tween(220)),
                fadeOut(animationSpec = tween(220)),
            )
        },
        popTransitionSpec = {
            ContentTransform(
                fadeIn(animationSpec = tween(220)),
                fadeOut(animationSpec = tween(220)),
            )
        },
        entryProvider = entryProvider {
            entry<OnboardingRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) {
                    LaunchedEffect(Unit) { suppressor.enter() }
                    DisposableEffect(Unit) { onDispose { suppressor.exit() } }
                    OnboardingScreen(
                        onFinished = {
                            navigationState.pop()
                        },
                    )
                }
            }
            entry<TodayRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) {
                    FeedScreen(
                        onArticleClick = { articleId ->
                            navigationState.push(ArticleRoute(articleId))
                        },
                    )
                }
            }
            entry<DiscoverRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) {
                    StoreScreen()
                }
            }
            entry<LibraryRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) {
                    LibraryScreen(
                        onArticleClick = { articleId ->
                            navigationState.push(ArticleRoute(articleId))
                        },
                        onExploreFeedClick = {
                            navigationState.select(ShowcaseTab.Today)
                        },
                    )
                }
            }
            entry<ProfileRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) {
                    SettingsScreen()
                }
            }
            entry<ArticleRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) {
                    ArticleScreen(
                        articleId = key.articleId,
                        onBack = { navigationState.pop() },
                    )
                }
            }
            entry<SdkLabRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) { PlaceholderScreen("SDK Lab") }
            }
            entry<BannerLabRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) { PlaceholderScreen("Banner Lab") }
            }
            entry<NativeLabRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) { PlaceholderScreen("Native Lab") }
            }
            entry<FullScreenLabRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) { PlaceholderScreen("Full Screen Lab") }
            }
            entry<PrivacyLabRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) { PlaceholderScreen("Privacy Lab") }
            }
            entry<DiagnosticsLabRoute> { key ->
                saveableStateHolder.SaveableStateProvider(key) { PlaceholderScreen("Diagnostics Lab") }
            }
        },
    )
}

private fun tabIcon(tab: ShowcaseTab) = when (tab) {
    ShowcaseTab.Today -> Icons.Rounded.Newspaper
    ShowcaseTab.Discover -> Icons.Rounded.Explore
    ShowcaseTab.Library -> Icons.Rounded.Bookmark
    ShowcaseTab.Profile -> Icons.Rounded.Person
}

@Composable
private fun PlaceholderScreen(name: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name)
    }
}
