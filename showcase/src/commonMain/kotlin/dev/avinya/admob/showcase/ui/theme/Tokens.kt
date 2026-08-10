package dev.avinya.admob.showcase.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout, shape, and motion constants for the showcase's design language.
 *
 * The radius scale is deliberately wide. Small controls stay nearly square,
 * media and cards curve noticeably, and the sheet/tab-bar radius is large
 * enough to read as a distinct shape signature rather than as Material's
 * default. That spread is what keeps the app from looking like stock Material
 * on Android or a UIKit imitation on iOS.
 */
object Tokens {

    /** Width at or above which navigation moves from a bottom bar to a rail. */
    val navigationRailBreakpoint: Dp = 840.dp

    /** Fade/expand duration for revealing a loaded ad. */
    const val adRevealMillis: Int = 180

    /** Crossfade duration between routes. */
    const val routeTransitionMillis: Int = 220

    /** Scale a card settles to while pressed. */
    const val pressedScale: Float = 0.98f

    /** Reading measure cap for long-form body text. */
    val readingMeasure: Dp = 680.dp

    /** Minimum interactive target on every platform. */
    val touchTarget: Dp = 48.dp

    /**
     * Side length of the square thumbnail in an article feed row, and of the
     * native-ad media view in the matching sponsored row.
     *
     * Google Mobile Ads requires that a native media view is at least 120×120dp
     * (120×120 pt on iOS) when video may play inside it. Both [StoryCard]'s
     * Standard treatment and [feedRowAdLayout] reference this token so they
     * always stay in lockstep and the policy minimum is never accidentally
     * regressed.
     */
    val feedThumbnail: Dp = 120.dp

    object Spacing {
        val s2: Dp = 2.dp
        val s4: Dp = 4.dp
        val s8: Dp = 8.dp
        val s12: Dp = 12.dp
        val s16: Dp = 16.dp
        val s20: Dp = 20.dp
        val s24: Dp = 24.dp
        val s32: Dp = 32.dp
        val s48: Dp = 48.dp
        val s64: Dp = 64.dp

        /** Page gutter, compact widths. */
        val gutterCompact: Dp = 20.dp

        /** Page gutter, expanded widths. */
        val gutterExpanded: Dp = 32.dp
    }

    object Radii {
        /** Chips, badges, small inputs. */
        val small: Dp = 4.dp

        /** Buttons, rows, inline controls. */
        val medium: Dp = 10.dp

        /** Cards and media. */
        val large: Dp = 20.dp

        /** Sheets, the tab bar, anything that meets a window edge. */
        val xlarge: Dp = 28.dp
    }

    /** Shadow spread for the single elevation step the design language allows. */
    object Elevation {
        val flat: Dp = 0.dp
        val raised: Dp = 2.dp
        val floating: Dp = 12.dp
    }

    /** Hairline rule weight. */
    val hairline: Dp = 1.dp
}
