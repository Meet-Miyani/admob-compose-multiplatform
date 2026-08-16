package dev.avinya.ads.nativead.rendering

import androidx.compose.ui.Modifier
import dev.avinya.ads.nativead.layout.AdClip
import dev.avinya.ads.nativead.layout.AdDisplay
import dev.avinya.ads.nativead.layout.AdInsets
import dev.avinya.ads.nativead.layout.AdLayoutSize
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.toComposeModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Asserts that `AdLayoutPreview` actually *consumes* each [AdModifier] property, rather than merely
 * that the property has been classified in `adModifierSurfaces`.
 *
 * `NativeAdModifierConformanceTest` fails the build when a new property goes unclassified, which
 * catches the property being forgotten but not the property being ignored — every divergence found
 * so far was a renderer that dropped a value it had been told about. This closes that gap for the
 * one renderer whose output is a plain value: `toComposeModifier` returns a `Modifier`, and a
 * property that changes nothing in the returned chain is a property the preview does not honour.
 *
 * The Android and iOS renderers build platform view hierarchies and cannot be covered this way
 * without Robolectric or on-device UI tests, neither of which this project has. Their equivalent
 * coverage is that both now read the same shared resolvers this package tests directly.
 */
class PreviewModifierConsumptionTest {

    private fun modifierFor(build: AdModifier.() -> AdModifier): Modifier =
        AdModifier.empty.build().toComposeModifier()

    private val empty: Modifier get() = AdModifier.empty.toComposeModifier()

    private fun assertConsumed(property: String, build: AdModifier.() -> AdModifier) {
        assertNotEquals(
            empty,
            modifierFor(build),
            "AdLayoutPreview ignores AdModifier.$property — it produces the same Compose modifier " +
                "as an empty AdModifier, so a layout setting it previews as though it had not.",
        )
    }

    @Test
    fun `an empty modifier stays empty`() {
        // The baseline the rest of these compare against: emitting alpha(1f)/zIndex(0f)/zero
        // padding unconditionally would make every property look consumed.
        assertEquals(Modifier, empty)
    }

    @Test
    fun `sizing properties are consumed`() {
        assertConsumed("width") { copy(width = AdLayoutSize.Match) }
        assertConsumed("height") { copy(height = AdLayoutSize.Match) }
        assertConsumed("minWidthDp") { copy(minWidthDp = 12f) }
        assertConsumed("minHeightDp") { copy(minHeightDp = 12f) }
        assertConsumed("maxWidthDp") { copy(maxWidthDp = 12f) }
        assertConsumed("maxHeightDp") { copy(maxHeightDp = 12f) }
        assertConsumed("aspectRatio") { copy(aspectRatio = 16f / 9f) }
    }

    @Test
    fun `spacing properties are consumed`() {
        assertConsumed("padding") { copy(padding = AdInsets(4f, 4f, 4f, 4f)) }
        assertConsumed("margin") { copy(margin = AdInsets(4f, 4f, 4f, 4f)) }
    }

    @Test
    fun `paint properties are consumed`() {
        assertConsumed("backgroundArgb") { copy(backgroundArgb = 0xFF101014) }
        assertConsumed("borderWidthDp") { copy(borderWidthDp = 1f, borderColorArgb = 0xFF777777) }
        assertConsumed("borderColorArgb") { copy(borderWidthDp = 1f, borderColorArgb = 0xFF777777) }
        assertConsumed("borderRadiusDp") { copy(borderWidthDp = 1f, borderColorArgb = 0xFF777777, borderRadiusDp = 3f) }
        assertConsumed("alpha") { copy(alpha = 0.5f) }
        assertConsumed("offsetXDp") { copy(offsetXDp = 4f) }
        assertConsumed("offsetYDp") { copy(offsetYDp = 4f) }
        assertConsumed("zIndex") { copy(zIndex = 2f) }
        assertConsumed("cornerRadiusDp") { copy(cornerRadiusDp = 8f) }
        assertConsumed("clipShape") { copy(clipShape = AdClip.Circle) }
        assertConsumed("elevationDp") { copy(elevationDp = 6f) }
    }

    // The gap this test class was written to find: `Invisible` was honoured by Android (setting
    // View.INVISIBLE) and by iOS (alpha 0) and dropped by the preview, which drew the node normally.
    @Test
    fun `an invisible node is drawn as empty space`() {
        assertConsumed("display") { copy(display = AdDisplay.Invisible) }
    }

    /**
     * `weight` is the one property `toComposeModifier` legitimately does not carry: Compose's
     * `weight` is only available inside a `Row`/`Column` scope, so `RenderAdLayoutPreviewNode`
     * applies it to the child from the parent's scope instead. Asserted here so the exemption stays
     * deliberate rather than becoming a hole someone widens.
     */
    @Test
    fun `weight is applied by the parent scope not by the node modifier`() {
        assertEquals(empty, modifierFor { copy(weight = 1f) })
    }

    /** `Gone` is consumed by the caller, which skips the node before building a modifier at all. */
    @Test
    fun `gone is applied by the caller not by the node modifier`() {
        assertEquals(empty, modifierFor { copy(display = AdDisplay.Gone) })
    }

    @Test
    fun `every classified property is either consumed here or exempt`() {
        val exempt = setOf("weight")
        val covered = setOf(
            "width", "height", "minWidthDp", "minHeightDp", "maxWidthDp", "maxHeightDp",
            "aspectRatio", "padding", "margin", "backgroundArgb", "borderWidthDp",
            "borderColorArgb", "borderRadiusDp", "alpha", "offsetXDp", "offsetYDp", "zIndex",
            "cornerRadiusDp", "clipShape", "elevationDp", "display",
        )
        assertEquals(
            emptySet(),
            declaredAdModifierPropertyNames() - covered - exempt,
            "New AdModifier properties are not covered by a preview-consumption assertion.",
        )
    }
}
