package dev.avinya.admob.showcase.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay

import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor
import dev.avinya.admob.showcase.feature.article.ArticleScreen
import dev.avinya.admob.showcase.feature.feed.FeedScreen
import dev.avinya.admob.showcase.feature.library.LibraryScreen
import dev.avinya.admob.showcase.feature.onboarding.OnboardingScreen
import dev.avinya.admob.showcase.feature.settings.SettingsScreen
import dev.avinya.admob.showcase.feature.store.StoreScreen

/**
 * The app's navigation shell.
 *
 * Real Nav3 entries matter here beyond tidiness: each entry owns a
 * `ViewModelStore` that is cleared on pop, which is what makes banner and
 * native ad disposal actually get exercised as the user moves around.
 */
@Composable
fun ShowcaseNavHost(backStack: SnapshotStateList<ShowcaseNavKey>) {
    val current = backStack.lastOrNull() ?: ShowcaseNavKey.Feed
    val suppressor = LocalAppOpenSuppressor.current

    Scaffold(
        bottomBar = {
            if (showsBottomBar(current)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    ) {
                        NavigationBar(
                            containerColor = Color.Transparent,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0.dp),
                        ) {
                            TOP_LEVEL_KEYS.forEach { key ->
                                val selected = current == key
                                val icon = when (key) {
                                    ShowcaseNavKey.Feed -> Icons.Rounded.Newspaper
                                    ShowcaseNavKey.Library -> Icons.Rounded.Bookmark
                                    ShowcaseNavKey.Store -> Icons.Rounded.Storefront
                                    ShowcaseNavKey.Settings -> Icons.Rounded.Settings
                                    else -> Icons.Rounded.Newspaper
                                }
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { switchTopLevel(backStack, key) },
                                    icon = {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = key.label,
                                        )
                                    },
                                    label = { Text(key.label) },
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
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize().padding(padding),
            onBack = { if (backStack.size > 1) backStack.removeLast() },
            entryDecorators = listOf(rememberViewModelStoreNavEntryDecorator()),
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
                entry<ShowcaseNavKey.Onboarding> {
                    // Hold suppression for the whole entry lifetime. onFinished
                    // clears the back stack and pushes Feed, which disposes this
                    // entry, so the suppression ends naturally without manual
                    // bookkeeping.
                    LaunchedEffect(Unit) { suppressor.enter() }
                    DisposableEffect(Unit) { onDispose { suppressor.exit() } }
                    OnboardingScreen(
                        onFinished = {
                            backStack.clear()
                            backStack.add(ShowcaseNavKey.Feed)
                        },
                    )
                }
                entry<ShowcaseNavKey.Feed> {
                    FeedScreen(
                        onArticleClick = { articleId ->
                            backStack.add(ShowcaseNavKey.ArticleDetail(articleId))
                        },
                    )
                }
                entry<ShowcaseNavKey.Library> {
                    LibraryScreen(
                        onArticleClick = { articleId ->
                            backStack.add(ShowcaseNavKey.ArticleDetail(articleId))
                        },
                        onExploreFeedClick = {
                            switchTopLevel(backStack, ShowcaseNavKey.Feed)
                        },
                    )
                }
                entry<ShowcaseNavKey.Store> { StoreScreen() }
                entry<ShowcaseNavKey.Settings> { SettingsScreen() }
                entry<ShowcaseNavKey.ArticleDetail> { key -> ArticleScreen(articleId = key.articleId, onBack = { backStack.removeLast() }) }
            },
        )
    }
}

/**
 * Switching tabs resets to a single-entry backstack rather than pushing.
 * Tabs are peers, so a back press from a tab should leave the app, not walk
 * a history of tab switches.
 */
private fun switchTopLevel(backStack: SnapshotStateList<ShowcaseNavKey>, key: ShowcaseNavKey) {
    if (backStack.size == 1 && backStack.first() == key) return
    backStack.clear()
    backStack.add(key)
}

@Composable
private fun PlaceholderScreen(name: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(name)
    }
}
