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

// Backward compatibility type aliases
typealias Feed = TodayRoute
typealias Store = DiscoverRoute
typealias Settings = ProfileRoute
typealias ArticleDetail = ArticleRoute
typealias Onboarding = OnboardingRoute

/** The bottom bar's destinations, in order. */
val TOP_LEVEL_KEYS: List<ShowcaseNavKey> = listOf(
    TodayRoute,
    DiscoverRoute,
    LibraryRoute,
    ProfileRoute,
)

/** Whether navigation chrome (bottom bar or side rail) is shown for [key]. */
fun showsNavigationChrome(key: NavKey): Boolean = key != OnboardingRoute
fun showsBottomBar(key: NavKey): Boolean = showsNavigationChrome(key)
