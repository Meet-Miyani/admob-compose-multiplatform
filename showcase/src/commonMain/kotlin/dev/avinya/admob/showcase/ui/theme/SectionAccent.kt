package dev.avinya.admob.showcase.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Maps an editorial section to a stable slot in the six-hue section palette.
 *
 * Stability is the whole point: a section must look the same in the feed, on
 * its cover artwork, in Discover's chips, and on the article page. The seed
 * data ships exactly six sections, so the common case is a direct hit; the
 * hash fallback keeps an unknown section deterministic rather than grey.
 */
object SectionAccent {

    private val known = listOf(
        "kotlin",
        "compose",
        "multiplatform",
        "android",
        "ios",
        "tooling",
    )

    /** Slot index in [SectionHues.ordered] for [section]. */
    fun indexOf(section: String): Int {
        val normalized = section.trim().lowercase()
        val direct = known.indexOf(normalized)
        if (direct >= 0) return direct
        if (normalized.isEmpty()) return 0
        // `String.hashCode` is specified in Kotlin, so this is stable across
        // platforms and across runs — unlike an identity hash. Mask rather than
        // `abs`, which stays negative for `Int.MIN_VALUE`.
        return (normalized.hashCode() and Int.MAX_VALUE) % known.size
    }

    fun colorIn(hues: SectionHues, section: String): Color = hues.ordered[indexOf(section)]
}

/** The current theme's hue for [section]. */
@Composable
@ReadOnlyComposable
fun sectionColor(section: String): Color =
    SectionAccent.colorIn(LocalShowcasePalette.current.sections, section)
