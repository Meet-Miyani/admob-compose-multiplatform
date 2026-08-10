package dev.avinya.admob.showcase.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SectionAccentTest {

    @Test
    fun everySeededSectionGetsItsOwnHue() {
        // `data/seed/ArticleSeed.kt` ships exactly these six.
        val seeded = listOf("Kotlin", "Compose", "Multiplatform", "Android", "iOS", "Tooling")
        val slots = seeded.map(SectionAccent::indexOf)

        assertEquals(seeded.size, slots.toSet().size, "seeded sections must not share a hue")
        assertEquals(listOf(0, 1, 2, 3, 4, 5), slots)
    }

    @Test
    fun lookupIgnoresCaseAndSurroundingSpace() {
        assertEquals(SectionAccent.indexOf("Compose"), SectionAccent.indexOf("  compose "))
        assertEquals(SectionAccent.indexOf("iOS"), SectionAccent.indexOf("IOS"))
    }

    @Test
    fun unknownSectionsStayDeterministicAndInRange() {
        repeat(2) {
            assertEquals(SectionAccent.indexOf("Gradle"), SectionAccent.indexOf("Gradle"))
        }
        listOf("Gradle", "Wasm", "", "Server-side").forEach { section ->
            val slot = SectionAccent.indexOf(section)
            assertTrue(slot in 0..5, "'$section' resolved to out-of-range slot $slot")
        }
    }

    @Test
    fun resolvesAgainstTheActivePaletteHues() {
        val hues = ShowcaseLightPalette.sections
        assertEquals(hues.violet, SectionAccent.colorIn(hues, "Kotlin"))
        assertEquals(hues.ochre, SectionAccent.colorIn(hues, "Tooling"))
    }
}
