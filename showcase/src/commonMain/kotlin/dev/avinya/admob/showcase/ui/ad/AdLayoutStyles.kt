package dev.avinya.admob.showcase.ui.ad

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdTextAlign
import dev.avinya.ads.nativead.layout.AdTextStyle

internal fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

internal fun badgeStyle(palette: ShowcasePalette): AdTextStyle = AdTextStyle(
    fontSizeSp = 10f,
    colorArgb = palette.accent.argb(),
    fontWeight = AdFontWeight.Bold,
)

internal fun title(palette: ShowcasePalette, headlineFamily: FontFamily): AdTextStyle = AdTextStyle(
    fontSizeSp = 17f,
    colorArgb = palette.ink.argb(),
    fontWeight = AdFontWeight.Bold,
    fontFamily = AdFontFamily.FromCompose(headlineFamily),
)

internal fun body(palette: ShowcasePalette): AdTextStyle = AdTextStyle(
    fontSizeSp = 14f,
    colorArgb = palette.inkMuted.argb(),
)

internal fun caption(palette: ShowcasePalette): AdTextStyle = AdTextStyle(
    fontSizeSp = 12f,
    colorArgb = palette.inkFaint.argb(),
)

internal fun ctaStyle(palette: ShowcasePalette): AdButtonStyle = AdButtonStyle(
    textStyle = AdTextStyle(
        fontSizeSp = 14f,
        colorArgb = palette.onAccentInk.argb(),
        fontWeight = AdFontWeight.Bold,
        textAlign = AdTextAlign.Center,
    ),
    backgroundArgb = palette.primary.argb(),
    cornerRadiusDp = 10f,
)
