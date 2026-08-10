package dev.avinya.admob.showcase.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor
import dev.avinya.admob.showcase.feature.article.ArticleScreen
import dev.avinya.admob.showcase.feature.lab.AppOpenLabScreen
import dev.avinya.admob.showcase.feature.lab.BannerLabScreen
import dev.avinya.admob.showcase.feature.lab.DiagnosticsLabScreen
import dev.avinya.admob.showcase.feature.lab.FullScreenLabScreen
import dev.avinya.admob.showcase.feature.lab.NativeLabScreen
import dev.avinya.admob.showcase.feature.lab.PrivacyLabScreen
import dev.avinya.admob.showcase.feature.lab.SdkLabScreen
import dev.avinya.admob.showcase.feature.onboarding.OnboardingScreen
import dev.avinya.admob.showcase.feature.rewards.RewardsScreen
import dev.avinya.admob.showcase.feature.shell.MainShellScreen
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * Wraps a route's content in the full-bleed canvas background every
 * full-screen destination in [ShowcaseNavHost] shares.
 */
@Composable
private fun CanvasSurface(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(showcaseColors.canvas),
    ) {
        content()
    }
}

/**
 * The app's outer navigation shell.
 *
 * Hosts the outer `NavDisplay` backed by `navigationState.outerStack`.
 * Manages full-screen flows (`OnboardingRoute`, `MainShellRoute`, `ArticleRoute`,
 * `RewardsRoute`, `SdkLabRoute`, etc.), providing full-screen detail slide transitions
 * over the main shell container.
 */
@Composable
fun ShowcaseNavHost(navigationState: ShowcaseNavigationState) {
    val saveableDecorator = rememberSaveableStateHolderNavEntryDecorator<NavKey>()
    val viewModelDecorator = rememberViewModelStoreNavEntryDecorator<NavKey>()
    val back: () -> Unit = { navigationState.pop() }
    val suppressor = LocalAppOpenSuppressor.current

    val spec = tween<Float>(Tokens.routeTransitionMillis)
    val slideSpec = tween<IntOffset>(Tokens.routeTransitionMillis)

    NavDisplay(
        backStack = navigationState.outerStack,
        modifier = Modifier
            .fillMaxSize()
            .background(showcaseColors.canvas),
        onBack = back,
        entryDecorators = listOf(saveableDecorator, viewModelDecorator),
        transitionSpec = {
            slideInHorizontally(slideSpec) { width -> width } togetherWith
                slideOutHorizontally(slideSpec) { width -> -width / 3 }
        },
        popTransitionSpec = {
            slideInHorizontally(slideSpec) { width -> -width / 3 } togetherWith
                slideOutHorizontally(slideSpec) { width -> width }
        },
        predictivePopTransitionSpec = {
            slideInHorizontally(slideSpec) { width -> -width / 3 } togetherWith
                slideOutHorizontally(slideSpec) { width -> width }
        },
        entryProvider = entryProvider {
            entry<MainShellRoute> {
                CanvasSurface {
                    MainShellScreen(navigationState = navigationState)
                }
            }
            entry<OnboardingRoute> {
                CanvasSurface {
                    LaunchedEffect(Unit) { suppressor.enter() }
                    DisposableEffect(Unit) { onDispose { suppressor.exit() } }
                    OnboardingScreen(onFinished = back)
                }
            }
            entry<ArticleRoute> { key ->
                CanvasSurface {
                    ArticleScreen(articleId = key.articleId, onBack = back)
                }
            }
            entry<RewardsRoute> {
                CanvasSurface {
                    RewardsScreen(onBack = back)
                }
            }
            entry<SdkLabRoute> {
                CanvasSurface {
                    SdkLabScreen(
                        onBack = back,
                        onOpenScenario = { navigationState.push(it) },
                    )
                }
            }
            entry<BannerLabRoute> {
                CanvasSurface {
                    BannerLabScreen(onBack = back)
                }
            }
            entry<NativeLabRoute> {
                CanvasSurface {
                    NativeLabScreen(onBack = back)
                }
            }
            entry<FullScreenLabRoute> {
                CanvasSurface {
                    FullScreenLabScreen(onBack = back)
                }
            }
            entry<AppOpenLabRoute> {
                CanvasSurface {
                    AppOpenLabScreen(onBack = back)
                }
            }
            entry<PrivacyLabRoute> {
                CanvasSurface {
                    PrivacyLabScreen(onBack = back)
                }
            }
            entry<DiagnosticsLabRoute> {
                CanvasSurface {
                    DiagnosticsLabScreen(onBack = back)
                }
            }
        },
    )
}
