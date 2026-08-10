package dev.avinya.admob.showcase.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** User-selectable theme preference. Persisted by `SettingsRepository`. */
enum class ThemeMode {
    System,
    Light,
    Dark,
    ;

    companion object {
        val Default: ThemeMode = System
    }
}

/**
 * Resolves the preference against the platform setting.
 * Pure, so it is testable without Compose.
 */
fun ThemeMode.isDark(systemInDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemInDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Entry point for the design language.
 *
 * Provides both the app's own [ShowcasePalette] — which the component kit
 * reads — and a matching Material `ColorScheme`, so any Material component the
 * app still leans on (sheets, snackbars, progress indicators) stays coherent.
 */
@Composable
internal fun ShowcaseTheme(
    themeMode: ThemeMode = ThemeMode.Default,
    content: @Composable () -> Unit,
) {
    val dark = themeMode.isDark(systemInDark = isSystemInDarkTheme())
    val palette = if (dark) ShowcaseDarkPalette else ShowcaseLightPalette
    val colors = if (dark) ShowcaseDarkColors else ShowcaseLightColors

    CompositionLocalProvider(
        LocalShowcasePalette provides palette,
        LocalContentColor provides palette.ink,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = ShowcaseTypography,
            shapes = ShowcaseMaterialShapes,
            content = content,
        )
    }
}

/** Shorthand for the active palette. */
val showcaseColors: ShowcasePalette
    @Composable
    @ReadOnlyComposable
    get() = LocalShowcasePalette.current
