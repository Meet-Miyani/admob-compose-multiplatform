package dev.avinya.admob.showcase.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Locked Fieldnotes Light Palette.
 */
object FieldnotesLight {
    val page = Color(0xFFF5F0E6)
    val raisedSurface = Color(0xFFFCFAF5)
    val ink = Color(0xFF181713)
    val mutedInk = Color(0xFF6D685F)
    val rule = Color(0xFFD8D0C2)
    val accent = Color(0xFFC6452D)
    val accentContainer = Color(0xFFF3D7CF)
}

/**
 * Locked Fieldnotes Dark Palette.
 */
object FieldnotesDark {
    val page = Color(0xFF151411)
    val raisedSurface = Color(0xFF201E1A)
    val ink = Color(0xFFF3EEE4)
    val mutedInk = Color(0xFFAAA397)
    val rule = Color(0xFF3A3630)
    val accent = Color(0xFFF0785F)
    val accentContainer = Color(0xFF5A281F)
}

/** Semantic alpha helpers for disabled UI states. */
val Color.disabledContent: Color
    get() = copy(alpha = 0.38f)

val Color.disabledContainer: Color
    get() = copy(alpha = 0.12f)

// ============================================================================
// Material 3 Color Schemes
// ============================================================================
internal val ShowcaseLightColors = lightColorScheme(
    primary = FieldnotesLight.accent,
    onPrimary = FieldnotesLight.raisedSurface,
    primaryContainer = FieldnotesLight.accentContainer,
    onPrimaryContainer = FieldnotesDark.accentContainer,
    secondary = FieldnotesLight.accent,
    onSecondary = FieldnotesLight.raisedSurface,
    secondaryContainer = FieldnotesLight.accentContainer,
    onSecondaryContainer = FieldnotesDark.accentContainer,
    tertiary = FieldnotesLight.accent,
    onTertiary = FieldnotesLight.raisedSurface,
    tertiaryContainer = FieldnotesLight.accentContainer,
    onTertiaryContainer = FieldnotesDark.accentContainer,
    background = FieldnotesLight.page,
    onBackground = FieldnotesLight.ink,
    surface = FieldnotesLight.raisedSurface,
    onSurface = FieldnotesLight.ink,
    surfaceVariant = Color(0xFFEFE8DB),
    onSurfaceVariant = FieldnotesLight.mutedInk,
    surfaceTint = Color.Transparent,
    inverseSurface = FieldnotesDark.raisedSurface,
    inverseOnSurface = FieldnotesDark.ink,
    inversePrimary = FieldnotesDark.accent,
    error = FieldnotesLight.accent,
    onError = FieldnotesLight.raisedSurface,
    errorContainer = FieldnotesLight.accentContainer,
    onErrorContainer = FieldnotesDark.accentContainer,
    outline = FieldnotesLight.rule,
    outlineVariant = FieldnotesLight.rule,
    scrim = Color(0xFF000000),
    surfaceBright = FieldnotesLight.raisedSurface,
    surfaceDim = Color(0xFFEFE8DB),
    surfaceContainer = FieldnotesLight.raisedSurface,
    surfaceContainerHigh = FieldnotesLight.raisedSurface,
    surfaceContainerHighest = FieldnotesLight.raisedSurface,
    surfaceContainerLow = FieldnotesLight.page,
    surfaceContainerLowest = FieldnotesLight.raisedSurface,
)

internal val ShowcaseDarkColors = darkColorScheme(
    primary = FieldnotesDark.accent,
    onPrimary = FieldnotesDark.page,
    primaryContainer = FieldnotesDark.accentContainer,
    onPrimaryContainer = FieldnotesLight.accentContainer,
    secondary = FieldnotesDark.accent,
    onSecondary = FieldnotesDark.page,
    secondaryContainer = FieldnotesDark.accentContainer,
    onSecondaryContainer = FieldnotesLight.accentContainer,
    tertiary = FieldnotesDark.accent,
    onTertiary = FieldnotesDark.page,
    tertiaryContainer = FieldnotesDark.accentContainer,
    onTertiaryContainer = FieldnotesLight.accentContainer,
    background = FieldnotesDark.page,
    onBackground = FieldnotesDark.ink,
    surface = FieldnotesDark.raisedSurface,
    onSurface = FieldnotesDark.ink,
    surfaceVariant = Color(0xFF25221D),
    onSurfaceVariant = FieldnotesDark.mutedInk,
    surfaceTint = Color.Transparent,
    inverseSurface = FieldnotesLight.raisedSurface,
    inverseOnSurface = FieldnotesLight.ink,
    inversePrimary = FieldnotesLight.accent,
    error = FieldnotesDark.accent,
    onError = FieldnotesDark.page,
    errorContainer = FieldnotesDark.accentContainer,
    onErrorContainer = FieldnotesLight.accentContainer,
    outline = FieldnotesDark.rule,
    outlineVariant = FieldnotesDark.rule,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF282520),
    surfaceDim = FieldnotesDark.page,
    surfaceContainer = FieldnotesDark.raisedSurface,
    surfaceContainerHigh = Color(0xFF25221D),
    surfaceContainerHighest = Color(0xFF2C2923),
    surfaceContainerLow = Color(0xFF1B1916),
    surfaceContainerLowest = FieldnotesDark.page,
)

