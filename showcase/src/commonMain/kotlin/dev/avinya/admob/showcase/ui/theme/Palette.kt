package dev.avinya.admob.showcase.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The showcase's own colour roles.
 *
 * Material's `ColorScheme` is still populated (see [ShowcaseLightColors]) so
 * that stock components pulled in from Material 3 — sheets, snackbars, text
 * selection — stay coherent. But the app's own component kit reads *this*
 * palette, because the design language needs roles Material does not have:
 * a sunken plane, two hairline weights, a third text weight, and a per-section
 * accent used both for taxonomy labels and for generated cover artwork.
 *
 * Two accents matter here. [primary] is the interactive colour — anything the
 * reader can press. [accent] is the editorial colour — the wordmark, section
 * rules, emphasis. Keeping them apart is what lets [danger] be a real red
 * instead of colliding with the brand, which is exactly what the previous
 * palette got wrong.
 */
@Immutable
data class ShowcasePalette(
    val canvas: Color,
    val surface: Color,
    val surfaceSunken: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val onAccentInk: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val primary: Color,
    val primarySoft: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val success: Color,
    val sections: SectionHues,
    val isDark: Boolean,
)

/**
 * One hue per editorial section, in the seed data's order.
 *
 * Six hues for six sections is not a coincidence — see
 * `data/seed/ArticleSeed.kt`. [SectionAccent] resolves a section name to one
 * of these deterministically, so a section keeps its colour across the feed,
 * its cover artwork, and its badge.
 */
@Immutable
data class SectionHues(
    val violet: Color,
    val teal: Color,
    val indigo: Color,
    val green: Color,
    val plum: Color,
    val ochre: Color,
) {
    val ordered: List<Color> get() = listOf(violet, teal, indigo, green, plum, ochre)
}

val ShowcaseLightPalette = ShowcasePalette(
    canvas = Color(0xFFFBF9F5),
    surface = Color(0xFFFFFFFF),
    surfaceSunken = Color(0xFFF2EEE7),
    ink = Color(0xFF15130F),
    inkMuted = Color(0xFF5D584F),
    inkFaint = Color(0xFF787264),
    onAccentInk = Color(0xFFFFFFFF),
    hairline = Color(0xFFE5DFD5),
    hairlineStrong = Color(0xFFCEC6B7),
    primary = Color(0xFF134E75),
    primarySoft = Color(0xFFD9E8F2),
    accent = Color(0xFF9A5A16),
    accentSoft = Color(0xFFF9EAD8),
    danger = Color(0xFFB3261E),
    dangerSoft = Color(0xFFF7DEDC),
    success = Color(0xFF2E6B4F),
    sections = SectionHues(
        violet = Color(0xFF5B41A8),
        teal = Color(0xFF0F6260),
        indigo = Color(0xFF2B5AA8),
        green = Color(0xFF2E6B4F),
        plum = Color(0xFF8E3B6B),
        ochre = Color(0xFF9A5A16),
    ),
    isDark = false,
)

val ShowcaseDarkPalette = ShowcasePalette(
    canvas = Color(0xFF111110),
    surface = Color(0xFF1A1A18),
    surfaceSunken = Color(0xFF0C0C0B),
    ink = Color(0xFFF2EFE8),
    inkMuted = Color(0xFFA8A296),
    inkFaint = Color(0xFF888276),
    onAccentInk = Color(0xFF111110),
    hairline = Color(0xFF2C2B27),
    hairlineStrong = Color(0xFF3E3C36),
    primary = Color(0xFF7FB6DC),
    primarySoft = Color(0xFF16303F),
    accent = Color(0xFFE5A44C),
    accentSoft = Color(0xFF3A2A14),
    danger = Color(0xFFF2857C),
    dangerSoft = Color(0xFF3F1B18),
    success = Color(0xFF79C4A0),
    sections = SectionHues(
        violet = Color(0xFFB9A6F0),
        teal = Color(0xFF6FC9C6),
        indigo = Color(0xFF93B4EE),
        green = Color(0xFF79C4A0),
        plum = Color(0xFFE39BC5),
        ochre = Color(0xFFE5A44C),
    ),
    isDark = true,
)

val LocalShowcasePalette = staticCompositionLocalOf { ShowcaseLightPalette }

/**
 * Material mapping.
 *
 * The kit does not read these, but Material components rendered inside the app
 * do. `surfaceTint` is transparent on purpose: tonal elevation would fight the
 * flat-plane-plus-shadow model the design language uses.
 */
internal val ShowcaseLightColors = lightColorScheme(
    primary = ShowcaseLightPalette.primary,
    onPrimary = ShowcaseLightPalette.onAccentInk,
    primaryContainer = ShowcaseLightPalette.primarySoft,
    onPrimaryContainer = ShowcaseLightPalette.primary,
    secondary = ShowcaseLightPalette.accent,
    onSecondary = ShowcaseLightPalette.onAccentInk,
    secondaryContainer = ShowcaseLightPalette.accentSoft,
    onSecondaryContainer = ShowcaseLightPalette.accent,
    tertiary = ShowcaseLightPalette.success,
    onTertiary = ShowcaseLightPalette.onAccentInk,
    background = ShowcaseLightPalette.canvas,
    onBackground = ShowcaseLightPalette.ink,
    surface = ShowcaseLightPalette.surface,
    onSurface = ShowcaseLightPalette.ink,
    surfaceVariant = ShowcaseLightPalette.surfaceSunken,
    onSurfaceVariant = ShowcaseLightPalette.inkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = ShowcaseDarkPalette.surface,
    inverseOnSurface = ShowcaseDarkPalette.ink,
    inversePrimary = ShowcaseDarkPalette.primary,
    error = ShowcaseLightPalette.danger,
    onError = ShowcaseLightPalette.onAccentInk,
    errorContainer = ShowcaseLightPalette.dangerSoft,
    onErrorContainer = ShowcaseLightPalette.danger,
    outline = ShowcaseLightPalette.hairlineStrong,
    outlineVariant = ShowcaseLightPalette.hairline,
    scrim = Color(0xFF000000),
    surfaceBright = ShowcaseLightPalette.surface,
    surfaceDim = ShowcaseLightPalette.surfaceSunken,
    surfaceContainer = ShowcaseLightPalette.surface,
    surfaceContainerHigh = ShowcaseLightPalette.surface,
    surfaceContainerHighest = ShowcaseLightPalette.surface,
    surfaceContainerLow = ShowcaseLightPalette.canvas,
    surfaceContainerLowest = ShowcaseLightPalette.canvas,
)

internal val ShowcaseDarkColors = darkColorScheme(
    primary = ShowcaseDarkPalette.primary,
    onPrimary = ShowcaseDarkPalette.onAccentInk,
    primaryContainer = ShowcaseDarkPalette.primarySoft,
    onPrimaryContainer = ShowcaseDarkPalette.primary,
    secondary = ShowcaseDarkPalette.accent,
    onSecondary = ShowcaseDarkPalette.onAccentInk,
    secondaryContainer = ShowcaseDarkPalette.accentSoft,
    onSecondaryContainer = ShowcaseDarkPalette.accent,
    tertiary = ShowcaseDarkPalette.success,
    onTertiary = ShowcaseDarkPalette.onAccentInk,
    background = ShowcaseDarkPalette.canvas,
    onBackground = ShowcaseDarkPalette.ink,
    surface = ShowcaseDarkPalette.surface,
    onSurface = ShowcaseDarkPalette.ink,
    surfaceVariant = ShowcaseDarkPalette.surfaceSunken,
    onSurfaceVariant = ShowcaseDarkPalette.inkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = ShowcaseLightPalette.surface,
    inverseOnSurface = ShowcaseLightPalette.ink,
    inversePrimary = ShowcaseLightPalette.primary,
    error = ShowcaseDarkPalette.danger,
    onError = ShowcaseDarkPalette.onAccentInk,
    errorContainer = ShowcaseDarkPalette.dangerSoft,
    onErrorContainer = ShowcaseDarkPalette.danger,
    outline = ShowcaseDarkPalette.hairlineStrong,
    outlineVariant = ShowcaseDarkPalette.hairline,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF232320),
    surfaceDim = ShowcaseDarkPalette.surfaceSunken,
    surfaceContainer = ShowcaseDarkPalette.surface,
    surfaceContainerHigh = Color(0xFF212120),
    surfaceContainerHighest = Color(0xFF272724),
    surfaceContainerLow = Color(0xFF161615),
    surfaceContainerLowest = ShowcaseDarkPalette.canvas,
)

/** Semantic alpha helpers for disabled UI states. */
val Color.disabledContent: Color
    get() = copy(alpha = 0.38f)

val Color.disabledContainer: Color
    get() = copy(alpha = 0.12f)
