package dev.avinya.ads.nativead.layout

import androidx.compose.ui.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Proves the Compose preview renderer actually consumes each [AdModifier] property, rather than
 * merely having it classified in `adModifierSurfaces`.
 *
 * `toComposeModifier` is pure, so an ignored property is directly observable: setting it produces
 * a modifier chain identical to the empty one. That is what `margin`, `minWidthDp`, `minHeightDp`,
 * `maxWidthDp`, `maxHeightDp` and `elevationDp` all did — Android and iOS honoured them while
 * previews silently did not, so a layout using them previewed differently from what shipped.
 *
 * Android and iOS need the equivalent guard in their own source sets; their renderers build
 * platform view trees that cannot be inspected from `commonTest`.
 */
class AdLayoutPreviewCoverageTest {

    /**
     * Counts modifier elements rather than comparing `toString()`: several Compose elements fall
     * back to an identity-hash `toString`, which is neither stable across runs nor sensitive to
     * the values they carry. An empty [AdModifier] now yields an empty chain, so any property that
     * takes effect must add at least one element.
     */
    private fun AdModifier.elementCount(): Int = toComposeModifier().foldIn(0) { count, _ -> count + 1 }

    private fun assertConsumed(property: String, apply: (AdModifier) -> AdModifier) {
        assertEquals(0, AdModifier.empty.elementCount(), "an empty AdModifier should add no modifiers")
        assertNotEquals(
            0,
            apply(AdModifier.empty).elementCount(),
            "AdLayoutPreview ignores `$property`: it produces the same modifier chain as an " +
                "empty AdModifier, so previews diverge from what Android and iOS render.",
        )
    }

    @Test
    fun `sizing properties are consumed`() {
        assertConsumed("width") { it.copy(width = AdLayoutSize.Match) }
        assertConsumed("height") { it.copy(height = AdLayoutSize.Match) }
        assertConsumed("minWidthDp") { it.copy(minWidthDp = 24f) }
        assertConsumed("minHeightDp") { it.copy(minHeightDp = 24f) }
        assertConsumed("maxWidthDp") { it.copy(maxWidthDp = 240f) }
        assertConsumed("maxHeightDp") { it.copy(maxHeightDp = 240f) }
        assertConsumed("aspectRatio") { it.copy(aspectRatio = 16f / 9f) }
    }

    @Test
    fun `spacing properties are consumed`() {
        assertConsumed("padding") { it.copy(padding = AdInsets(8f, 8f, 8f, 8f)) }
        assertConsumed("margin") { it.copy(margin = AdInsets(8f, 8f, 8f, 8f)) }
    }

    @Test
    fun `appearance properties are consumed`() {
        assertConsumed("backgroundArgb") { it.copy(backgroundArgb = 0xFF101014) }
        assertConsumed("cornerRadiusDp") { it.copy(cornerRadiusDp = 18f) }
        assertConsumed("clipShape") { it.copy(clipShape = AdClip.RoundedCorner(18f)) }
        assertConsumed("elevationDp") { it.copy(elevationDp = 6f) }
        assertConsumed("alpha") { it.copy(alpha = 0.5f) }
        assertConsumed("border") { it.copy(borderWidthDp = 1f, borderColorArgb = 0xFF101014) }
    }

    @Test
    fun `placement properties are consumed`() {
        assertConsumed("offsetXDp") { it.copy(offsetXDp = 4f) }
        assertConsumed("offsetYDp") { it.copy(offsetYDp = 4f) }
        assertConsumed("zIndex") { it.copy(zIndex = 2f) }
    }
}

/**
 * Order matters as much as presence.
 *
 * `padding` applied before `background` insets the paint instead of being covered by it, so a
 * layout like `background(surface).padding(vertical = 12.dp)` previewed with transparent bands
 * while Android (`setPadding` over a background drawable) and iOS (stack layout margins inside the
 * host surface) both filled them.
 */
class AdLayoutPreviewOrderTest {

    private fun elementNames(modifier: AdModifier): List<String> =
        modifier.toComposeModifier().foldIn(mutableListOf<String>()) { names, element ->
            names.also { it += element::class.simpleName ?: element.toString() }
        }

    @Test
    fun `background is painted under padding rather than inset by it`() {
        val names = elementNames(
            AdModifier(backgroundArgb = 0xFF101014, padding = AdInsets(8f, 8f, 8f, 8f)),
        )
        val background = names.indexOfFirst { it.contains("Background", ignoreCase = true) }
        val padding = names.indexOfFirst { it.contains("Padding", ignoreCase = true) }
        assertTrue(background >= 0, "no background element in $names")
        assertTrue(padding >= 0, "no padding element in $names")
        assertTrue(
            background < padding,
            "background must precede padding so it paints under the inset; got $names",
        )
    }

    @Test
    fun `margin is applied outside the background`() {
        val names = elementNames(
            AdModifier(backgroundArgb = 0xFF101014, margin = AdInsets(8f, 8f, 8f, 8f)),
        )
        val background = names.indexOfFirst { it.contains("Background", ignoreCase = true) }
        val margin = names.indexOfFirst { it.contains("Padding", ignoreCase = true) }
        assertTrue(margin >= 0 && background >= 0, "expected margin and background in $names")
        assertTrue(margin < background, "margin must precede background; got $names")
    }
}
