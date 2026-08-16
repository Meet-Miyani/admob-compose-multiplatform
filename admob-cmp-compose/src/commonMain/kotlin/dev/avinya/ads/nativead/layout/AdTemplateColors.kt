package dev.avinya.ads.nativead.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The colours an [AdTemplates] layout paints itself with.
 *
 * The built-in templates used to hardcode a white card and the default black-on-white text
 * presets, which made them unusable in a dark UI: an app rendering its own dark theme got a
 * white rectangle in the middle of its feed. Passing the palette in keeps the templates useful as
 * a starting point without forcing a Material dependency on every consumer of the SDK — the
 * `admob-cmp-compose` module deliberately depends only on Compose runtime, foundation and ui.
 *
 * [surface] must be **opaque**. On iOS the ad is embedded below Compose's canvas, which clears
 * every pixel it drew underneath, so a translucent root composites onto the platform backdrop
 * rather than onto the app's surface — the same thing `AdLayoutValidator`'s
 * `transparent_root_background` warning is about.
 *
 * @property surface the card background. Must be fully opaque.
 * @property headline colour for the headline.
 * @property body colour for the body copy.
 * @property caption colour for advertiser, price, store and star rating.
 * @property badgeText colour for the "Ad" attribution badge's text.
 * @property badgeOutline colour for the badge's border.
 * @property callToAction the call-to-action button's fill.
 * @property onCallToAction the call-to-action button's label, drawn on [callToAction].
 */
@Immutable
public data class AdTemplateColors(
    val surface: Color,
    val headline: Color,
    val body: Color,
    val caption: Color,
    val badgeText: Color,
    val badgeOutline: Color,
    val callToAction: Color,
    val onCallToAction: Color,
) {
    /** Built-in palettes. */
    public companion object {
        /**
         * The palette the templates have always shipped with, so
         * `AdTemplates.medium` and `AdTemplates.medium(AdTemplateColors.light)` are identical.
         */
        public val light: AdTemplateColors = AdTemplateColors(
            surface = Color(0xFFFFFFFF),
            headline = Color(0xFF111111),
            body = Color(0xFF3C4043),
            caption = Color(0xFF5F6368),
            badgeText = Color(0xFF333333),
            badgeOutline = Color(0xFF777777),
            callToAction = Color(0xFF1A73E8),
            onCallToAction = Color(0xFFFFFFFF),
        )

        /**
         * A dark counterpart, chosen so every foreground clears WCAG AA against [surface] — the ad
         * is the one part of the screen the app does not control the contents of, so it should not
         * be the one part that fails to be readable.
         */
        public val dark: AdTemplateColors = AdTemplateColors(
            surface = Color(0xFF1B1B1F),
            headline = Color(0xFFE6E1E5),
            body = Color(0xFFC9C5CA),
            caption = Color(0xFF9E9AA3),
            badgeText = Color(0xFFC9C5CA),
            badgeOutline = Color(0xFF8A8A8E),
            callToAction = Color(0xFF8AB4F8),
            onCallToAction = Color(0xFF06253F),
        )
    }
}
