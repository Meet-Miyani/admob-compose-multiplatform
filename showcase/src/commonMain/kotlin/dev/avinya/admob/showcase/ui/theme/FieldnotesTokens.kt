package dev.avinya.admob.showcase.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Locked Fieldnotes design system tokens for spacing, radii, breakpoints, and motion.
 */
object FieldnotesTokens {
    /** Breakpoint at or above which navigation uses a rail instead of a bottom bar. */
    val navigationRailBreakpoint: Dp = 840.dp

    /** Duration in milliseconds for ad reveal fade/size transitions. */
    const val adRevealMillis: Int = 180

    /** Corner radii scale. */
    object Radii {
        val structure: Dp = 0.dp
        val image: Dp = 8.dp
        val card: Dp = 12.dp
    }

    /** Spacing scale. */
    object Spacing {
        val s4: Dp = 4.dp
        val s8: Dp = 8.dp
        val s12: Dp = 12.dp
        val s16: Dp = 16.dp
        val s24: Dp = 24.dp
        val s32: Dp = 32.dp
        val s48: Dp = 48.dp

        val gutterCompact: Dp = 20.dp
        val gutterExpanded: Dp = 32.dp
    }

    // Top-level property aliases for direct convenience
    val structureRadius: Dp get() = Radii.structure
    val imageRadius: Dp get() = Radii.image
    val cardRadius: Dp get() = Radii.card

    val gutterCompact: Dp get() = Spacing.gutterCompact
    val gutterExpanded: Dp get() = Spacing.gutterExpanded
}
