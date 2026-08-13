package dev.avinya.ads.nativead.rendering

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.platform.Font
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import platform.UIKit.UIFont

@OptIn(ExperimentalTextApi::class)
class IosComposeFontResolverTest {
    @Test
    fun cssWeightSelectionUsesExactAndComposeFallbackOrdering() {
        val fonts = listOf(loaded("300", 300), loaded("400", 400), loaded("500", 500), loaded("700", 700))

        assertEquals(300, selectClosestLoadedFont(fonts, FontWeight(200))?.weight?.weight)
        assertEquals(500, selectClosestLoadedFont(fonts, FontWeight(450))?.weight?.weight)
        assertEquals(500, selectClosestLoadedFont(fonts, FontWeight(500))?.weight?.weight)
        assertEquals(700, selectClosestLoadedFont(fonts, FontWeight(600))?.weight?.weight)
        assertEquals(700, selectClosestLoadedFont(fonts, FontWeight(800))?.weight?.weight)
    }

    @Test
    fun resolverRegistersIdenticalBytesOnlyOnce() {
        val family = FontFamily(loaded("newsreader", 400, byteArrayOf(1, 2, 3)))
        var registrations = 0
        val expected = UIFont.systemFontOfSize(18.0)
        val resolver = IosComposeFontResolver(
            register = { _, _ -> registrations++; "Newsreader-Regular" },
            construct = { _, _, _ -> expected },
            logFailure = {},
        )

        assertSame(expected, resolver.resolve(family, FontWeight.Normal, 18.0, Density(2f)))
        assertSame(expected, resolver.resolve(family, FontWeight.Normal, 22.0, Density(2f)))
        assertEquals(1, registrations)
    }

    @Test
    fun failureIsCachedLoggedOnceAndFallsBackToCaller() {
        val family = FontFamily(loaded("broken", 400, byteArrayOf(9)))
        var registrations = 0
        var logs = 0
        val resolver = IosComposeFontResolver(
            register = { _, _ -> registrations++; null },
            construct = { _, _, _ -> error("must not construct") },
            logFailure = { logs++ },
        )

        assertNull(resolver.resolve(family, FontWeight.Normal, 16.0, Density(2f)))
        assertNull(resolver.resolve(family, FontWeight.Normal, 16.0, Density(2f)))
        assertEquals(1, registrations)
        assertEquals(1, logs)
    }

    @Test
    fun explicitVariationWeightWinsAndRequestedWeightIsFallback() {
        val density = Density(2f, 1.25f)
        val explicit = loaded(
            identity = "variable",
            weight = 400,
            settings = FontVariation.Settings(FontVariation.weight(625)),
        )
        val withoutWeight = loaded(
            identity = "variable-default",
            weight = 400,
            settings = FontVariation.Settings(),
        )

        assertEquals(625f, explicit.resolvedVariableWeight(FontWeight.Medium, density))
        assertEquals(700f, withoutWeight.resolvedVariableWeight(FontWeight.Bold, density))
    }

    @Test
    fun duplicateCoreTextRegistrationIsAcceptedWhenUIKitCanResolveTheName() {
        assertTrue(registrationSucceeded(registered = false) { true })
    }

    @Test
    fun unsupportedComposeFamilyFallsBackAndLogsOnlyOnce() {
        var logs = 0
        val resolver = IosComposeFontResolver(
            register = { _, _ -> error("must not register") },
            construct = { _, _, _ -> error("must not construct") },
            logFailure = { logs++ },
        )

        assertNull(resolver.resolve(FontFamily.Serif, FontWeight.Normal, 16.0, Density(2f)))
        assertNull(resolver.resolve(FontFamily.Serif, FontWeight.Normal, 16.0, Density(2f)))
        assertEquals(1, logs)
    }

    private fun loaded(
        identity: String,
        weight: Int,
        data: ByteArray = byteArrayOf(weight.toByte()),
        settings: FontVariation.Settings = FontVariation.Settings(FontWeight(weight), FontStyle.Normal),
    ): LoadedFont = Font(
        identity = identity,
        getData = { data },
        weight = FontWeight(weight),
        style = FontStyle.Normal,
        variationSettings = settings,
    ) as LoadedFont
}
