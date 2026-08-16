package dev.avinya.ads.nativead.rendering

import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdButtonTextCase
import dev.avinya.ads.nativead.layout.AdImageStyle
import dev.avinya.ads.nativead.layout.AdInsets
import dev.avinya.ads.nativead.layout.AdModifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class NativeAdRendererResolutionTest {
    @Test
    fun `image background prefers modifier before style`() {
        assertEquals(
            0xFF112233,
            resolveNativeAdBackgroundArgb(
                modifier = AdModifier(backgroundArgb = 0xFF112233),
                styleBackgroundArgb = 0xFF445566,
            ),
        )
    }

    @Test
    fun `image background uses style when modifier has none`() {
        assertEquals(
            0xFF445566,
            resolveNativeAdBackgroundArgb(
                modifier = AdModifier.empty,
                styleBackgroundArgb = 0xFF445566,
            ),
        )
    }

    @Test
    fun `image background remains transparent when neither source supplies one`() {
        assertNull(
            resolveNativeAdBackgroundArgb(
                modifier = AdModifier.empty,
                styleBackgroundArgb = AdImageStyle().backgroundArgb,
            ),
        )
    }

    @Test
    fun `image style background survives a modifier border decoration`() {
        assertEquals(
            0xFFCC0000,
            resolveNativeAdDrawableBackgroundArgb(
                modifier = AdModifier(borderWidthDp = 1f, borderColorArgb = 0xFF000000),
                styleBackgroundArgb = 0xFFCC0000,
            ),
        )
    }

    @Test
    fun `image style background survives a modifier corner decoration`() {
        assertEquals(
            0xFFCC0000,
            resolveNativeAdDrawableBackgroundArgb(
                modifier = AdModifier(cornerRadiusDp = 8f),
                styleBackgroundArgb = 0xFFCC0000,
            ),
        )
    }

    @Test
    fun `CTA modifier padding is the complete content inset`() {
        assertEquals(
            AdInsets(startDp = 3f, topDp = 5f, endDp = 7f, bottomDp = 11f),
            resolveCallToActionContentInsets(
                modifier = AdModifier(padding = AdInsets(startDp = 3f, topDp = 5f, endDp = 7f, bottomDp = 11f)),
                style = AdButtonStyle(horizontalPaddingDp = 24f),
            ),
        )
    }

    // The vertical inset must be non-zero. Both renderers now size the call-to-action as text plus
    // these insets — a zero would collapse the ad's main tap target to the height of its own text.
    // It only ever looked right because `android.widget.Button` imposed the OEM theme's minimum
    // height, which is the platform-dependence that change removed.
    @Test
    fun `CTA style fallback pads both axes`() {
        val insets = resolveCallToActionContentInsets(
            modifier = AdModifier.empty,
            style = AdButtonStyle(horizontalPaddingDp = 24f),
        )
        assertEquals(24f, insets.startDp)
        assertEquals(24f, insets.endDp)
        assertTrue(insets.topDp > 0f, "vertical inset must not be zero, was ${insets.topDp}")
        assertEquals(insets.topDp, insets.bottomDp, "the button must be vertically symmetric")
    }

    // A node's own padding replaces the style fallback outright, which is how a layout asks for a
    // taller or tighter button without an ABI-breaking addition to AdButtonStyle.
    @Test
    fun `a node's own padding overrides the style fallback`() {
        assertEquals(
            AdInsets(startDp = 4f, topDp = 30f, endDp = 4f, bottomDp = 30f),
            resolveCallToActionContentInsets(
                modifier = AdModifier.empty.padding(horizontal = 4.dp, vertical = 30.dp),
                style = AdButtonStyle(horizontalPaddingDp = 24f),
            ),
        )
    }
}

/**
 * A call-to-action is the one node whose surface has two possible sources — its `AdButtonStyle` and
 * its own `AdModifier`. These pin the precedence between them, so that setting a background and a
 * corner radius on the node behaves the way the DSL implies rather than being overwritten by the
 * style on some code paths and not others.
 */
class CallToActionCornerRadiusTest {

    @Test
    fun `the style radius applies when the node asks for nothing`() {
        assertEquals(
            8f,
            resolveCallToActionCornerRadiusDp(AdModifier.empty, AdButtonStyle(cornerRadiusDp = 8f)),
        )
    }

    @Test
    fun `the node's corner radius overrides the style`() {
        assertEquals(
            20f,
            resolveCallToActionCornerRadiusDp(
                AdModifier.empty.cornerRadius(20.dp),
                AdButtonStyle(cornerRadiusDp = 8f),
            ),
        )
    }

    @Test
    fun `an explicit rounded clip overrides the style`() {
        assertEquals(
            16f,
            resolveCallToActionCornerRadiusDp(
                AdModifier.empty.clipRounded(16.dp),
                AdButtonStyle(cornerRadiusDp = 8f),
            ),
        )
    }

    @Test
    fun `cornerRadius wins over borderRadius just as it does for the root`() {
        assertEquals(
            20f,
            resolveCallToActionCornerRadiusDp(
                AdModifier(cornerRadiusDp = 20f, borderRadiusDp = 4f),
                AdButtonStyle(cornerRadiusDp = 8f),
            ),
        )
    }
}

class CallToActionTextCaseTest {

    @Test
    fun asProvidedNeverRewritesTheCreativesLabel() {
        assertEquals("INSTALL", resolveCallToActionText("INSTALL", AdButtonTextCase.AsProvided))
        assertEquals("Book now", resolveCallToActionText("Book now", AdButtonTextCase.AsProvided))
    }

    @Test
    fun sentenceCaseRewritesAllCapsLabels() {
        assertEquals("Install", resolveCallToActionText("INSTALL", AdButtonTextCase.SentenceCase))
        assertEquals("Learn more", resolveCallToActionText("LEARN MORE", AdButtonTextCase.SentenceCase))
    }

    @Test
    fun sentenceCaseLeavesMixedCaseLabelsAlone() {
        // Deliberate capitalisation must survive: these already carry case information.
        assertEquals("Book now", resolveCallToActionText("Book now", AdButtonTextCase.SentenceCase))
        assertEquals("Shop at H&M", resolveCallToActionText("Shop at H&M", AdButtonTextCase.SentenceCase))
        assertEquals("iBooks", resolveCallToActionText("iBooks", AdButtonTextCase.SentenceCase))
    }

    @Test
    fun sentenceCaseCapitalisesTheFirstLetterNotTheFirstCharacter() {
        // A leading symbol must not swallow the capital that belongs to the first word.
        assertEquals("→ Install", resolveCallToActionText("→ INSTALL", AdButtonTextCase.SentenceCase))
        assertEquals("  Install", resolveCallToActionText("  INSTALL", AdButtonTextCase.SentenceCase))
    }

    @Test
    fun sentenceCaseIsSafeOnLabelsWithoutLetters() {
        assertEquals("", resolveCallToActionText("", AdButtonTextCase.SentenceCase))
        assertEquals("$9.99", resolveCallToActionText("$9.99", AdButtonTextCase.SentenceCase))
    }
}
