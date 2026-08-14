package dev.avinya.ads.nativead.rendering

import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdButtonTextCase
import dev.avinya.ads.nativead.layout.AdImageStyle
import dev.avinya.ads.nativead.layout.AdInsets
import dev.avinya.ads.nativead.layout.AdModifier
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun `CTA style fallback has horizontal inset and zero vertical inset`() {
        assertEquals(
            AdInsets(startDp = 24f, topDp = 0f, endDp = 24f, bottomDp = 0f),
            resolveCallToActionContentInsets(
                modifier = AdModifier.empty,
                style = AdButtonStyle(horizontalPaddingDp = 24f),
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
