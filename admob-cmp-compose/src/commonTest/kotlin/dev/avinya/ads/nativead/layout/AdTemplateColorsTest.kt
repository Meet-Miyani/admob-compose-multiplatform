package dev.avinya.ads.nativead.layout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The templates hardcoded a white card and the default black-on-white text presets, so they were
 * unusable in a dark UI — an app rendering its own dark theme got a white rectangle in its feed.
 * The colour-parameterised overloads fix that; these pin both halves of that change.
 */
class AdTemplateColorsTest {

    private val templates = listOf<Pair<String, (AdTemplateColors) -> AdLayout>>(
        "compact" to AdTemplates::compact,
        "medium" to AdTemplates::medium,
        "feedCard" to AdTemplates::feedCard,
    )

    private val defaults = mapOf(
        "compact" to AdTemplates.compact,
        "medium" to AdTemplates.medium,
        "feedCard" to AdTemplates.feedCard,
    )

    // The compatibility promise: adding the overloads must not have changed what the existing
    // public `val`s render, or every consumer's ads shift on upgrade.
    @Test
    fun `the light palette reproduces the templates exactly`() {
        templates.forEach { (name, build) ->
            assertEquals(
                defaults.getValue(name),
                build(AdTemplateColors.light),
                "$name(AdTemplateColors.light) must equal the AdTemplates.$name val",
            )
        }
    }

    @Test
    fun `the dark palette actually changes the templates`() {
        templates.forEach { (name, build) ->
            assertNotEquals(
                defaults.getValue(name),
                build(AdTemplateColors.dark),
                "$name did not consume the palette it was handed",
            )
        }
    }

    /**
     * A translucent root is the one thing the palette must never produce: iOS embeds the ad below
     * Compose's canvas and clears every pixel behind it, so a non-opaque root composites onto the
     * platform backdrop instead of the app's surface. `AdLayoutValidator` warns about it, and a
     * built-in palette should never be what trips that warning.
     */
    @Test
    fun `every built-in palette yields an opaque root that the validator accepts`() {
        listOf("light" to AdTemplateColors.light, "dark" to AdTemplateColors.dark)
            .forEach { (paletteName, palette) ->
                templates.forEach { (name, build) ->
                    val layout = build(palette)
                    assertTrue(
                        layout.validation.errors.isEmpty(),
                        "$name($paletteName) has validation errors: ${layout.validation.errors}",
                    )
                    assertTrue(
                        layout.validation.warnings.none { it.code == "transparent_root_background" },
                        "$name($paletteName) left the root transparent, which renders wrong on iOS",
                    )
                }
            }
    }

    @Test
    fun `the dark palette does not reuse the light surface`() {
        assertNotEquals(AdTemplateColors.light.surface, AdTemplateColors.dark.surface)
        assertNotEquals(AdTemplateColors.light.headline, AdTemplateColors.dark.headline)
    }

    @Test
    fun `mediaCard stays an alias of medium`() {
        assertEquals(AdTemplates.medium, AdTemplates.mediaCard)
    }
}
