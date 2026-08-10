package dev.avinya.admob.showcase.nav

import androidx.navigation3.runtime.NavKey

/**
 * Every destination in the showcase.
 *
 * Keys are plain data, not `@Serializable`: `rememberNavBackStack` would
 * require kotlinx-serialization, which is not an approved dependency. The
 * consequence is that the backstack does not survive process death.
 */
sealed interface ShowcaseNavKey : NavKey {
    val label: String get() = ""
}

data object MainShellRoute : ShowcaseNavKey {
    override val label: String = "Main Shell"
}

data object TodayRoute : ShowcaseNavKey {
    override val label: String = "Today"
}

data object DiscoverRoute : ShowcaseNavKey {
    override val label: String = "Discover"
}

data object LibraryRoute : ShowcaseNavKey {
    override val label: String = "Library"
}

data object ProfileRoute : ShowcaseNavKey {
    override val label: String = "Profile"
}

data class ArticleRoute(val articleId: String) : ShowcaseNavKey {
    override val label: String = "Article"
}

data object RewardsRoute : ShowcaseNavKey {
    override val label: String = "Rewards"
}

data object SdkLabRoute : ShowcaseNavKey {
    override val label: String = "SDK Lab"
}

data object BannerLabRoute : ShowcaseNavKey {
    override val label: String = "Banner Ads"
}

data object NativeLabRoute : ShowcaseNavKey {
    override val label: String = "Native Ads"
}

data object FullScreenLabRoute : ShowcaseNavKey {
    override val label: String = "Full Screen Ads"
}

data object AppOpenLabRoute : ShowcaseNavKey {
    override val label: String = "App Open"
}

data object PrivacyLabRoute : ShowcaseNavKey {
    override val label: String = "Privacy & Consent"
}

data object DiagnosticsLabRoute : ShowcaseNavKey {
    override val label: String = "Diagnostics"
}

data object OnboardingRoute : ShowcaseNavKey {
    override val label: String = "Welcome"
}

/**
 * Top-level navigation tabs. Each owns a persistent back stack rooted at [root].
 */
enum class ShowcaseTab(val root: NavKey) {
    Today(TodayRoute),
    Discover(DiscoverRoute),
    Library(LibraryRoute),
    Profile(ProfileRoute),
}

/** The bottom bar's destinations, in order. */
val TOP_LEVEL_KEYS: List<ShowcaseNavKey> = listOf(
    TodayRoute,
    DiscoverRoute,
    LibraryRoute,
    ProfileRoute,
)

/**
 * Every argument-less (singleton) destination.
 *
 * Single source of truth for [navKeyFromSaveableKey] — add a new object route
 * here once instead of hand-writing a second parse branch that can silently
 * drift out of sync with the sealed interface.
 */
internal val SINGLETON_ROUTES: List<ShowcaseNavKey> = listOf(
    MainShellRoute,
    TodayRoute,
    DiscoverRoute,
    LibraryRoute,
    ProfileRoute,
    RewardsRoute,
    SdkLabRoute,
    BannerLabRoute,
    NativeLabRoute,
    FullScreenLabRoute,
    AppOpenLabRoute,
    PrivacyLabRoute,
    DiagnosticsLabRoute,
    OnboardingRoute,
)

private const val ARTICLE_ROUTE_PREFIX = "ArticleRoute:"

/**
 * A stable, Bundle-storable identity for [key].
 *
 * `SaveableStateHolder` rejects keys Android cannot put in a Bundle, so the
 * route objects themselves cannot be used directly. [ArticleRoute] gets an
 * explicit colon-delimited encoding rather than its data-class `toString()`
 * so a paren in [ArticleRoute.articleId] can't corrupt the parse in
 * [navKeyFromSaveableKey].
 */
fun saveableStateKey(key: NavKey): String = when (key) {
    is ArticleRoute -> "$ARTICLE_ROUTE_PREFIX${key.articleId}"
    else -> key.toString()
}

/**
 * Inflates a [NavKey] from its [saveableStateKey] string representation, or
 * `null` if [key] doesn't match any known route.
 */
fun navKeyFromSaveableKey(key: String): NavKey? = when {
    key.startsWith(ARTICLE_ROUTE_PREFIX) -> ArticleRoute(key.removePrefix(ARTICLE_ROUTE_PREFIX))
    else -> SINGLETON_ROUTES.firstOrNull { it.toString() == key }
}

/**
 * Whether [key] is the root of its tab.
 *
 * Anything else was pushed, and therefore must render a back affordance. iOS
 * has no system back out of a Compose `NavDisplay`, so a pushed screen without
 * one is a dead end.
 */
fun isTabRoot(key: NavKey): Boolean = key in TOP_LEVEL_KEYS

/**
 * Whether navigation chrome (bottom bar or side rail) is shown for [key].
 *
 * Only tab roots keep the chrome. A pushed detail — an article, Rewards, a Lab
 * scenario — takes the whole window and slides in over the tab it came from,
 * which is what makes "back" mean one unambiguous thing on both platforms.
 */
fun showsNavigationChrome(key: NavKey): Boolean = isTabRoot(key)
