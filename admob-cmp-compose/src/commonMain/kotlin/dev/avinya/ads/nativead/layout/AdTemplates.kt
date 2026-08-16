package dev.avinya.ads.nativead.layout

import androidx.compose.ui.unit.dp

/**
 * Ready-made, policy-compliant native ad layout templates. Each template
 * includes the required ad attribution badge and AdChoices space.
 *
 * Every template comes in two forms: a `val` carrying [AdTemplateColors.light], which is what the
 * templates have always rendered as, and a function taking an [AdTemplateColors] for apps that
 * theme. Pass [AdTemplateColors.dark] in a dark UI — the `val` forms paint an opaque white card
 * and will otherwise sit in a dark feed as a white rectangle.
 */
public object AdTemplates {
    /** Media card template. Alias for [medium]. */
    public val mediaCard: AdLayout get() = medium

    /** Compact horizontal layout with icon, headline, body, and CTA. Best for tight spaces. */
    public val compact: AdLayout = compact(AdTemplateColors.light)

    /** Medium card template with media, icon, headline, body, and CTA. Good for feeds. */
    public val medium: AdLayout = medium(AdTemplateColors.light)

    /** Full-width feed card with media, rich metadata, and CTA. Best for content feeds. */
    public val feedCard: AdLayout = feedCard(AdTemplateColors.light)

    /**
     * Compact horizontal layout with icon, headline, body, and CTA, in [colors].
     *
     * @param colors the palette to paint with; [AdTemplateColors.dark] for a dark UI.
     */
    public fun compact(colors: AdTemplateColors): AdLayout = adLayout {
        row(
            modifier = AdModifier.fillMaxWidth().padding(12.dp).background(colors.surface).cornerRadius(12.dp),
            spacing = 12.dp
        ) {
            icon(AdModifier.size(48.dp).cornerRadius(8.dp))
            column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                row(spacing = 6.dp) {
                    adBadge(modifier = colors.badgeModifier(), style = colors.badgeStyle())
                    headline(modifier = AdModifier.weight(1f), style = colors.headlineStyle())
                }
                body(maxLines = 2, style = colors.bodyStyle())
                advertiser(style = colors.captionStyle())
            }
            callToAction(style = colors.buttonStyle())
            adChoices(AdModifier.size(20.dp))
        }
    }

    /**
     * Medium card template with media, icon, headline, body, and CTA, in [colors].
     *
     * @param colors the palette to paint with; [AdTemplateColors.dark] for a dark UI.
     */
    public fun medium(colors: AdTemplateColors): AdLayout = adLayout {
        column(
            modifier = AdModifier.fillMaxWidth().padding(12.dp).background(colors.surface).cornerRadius(14.dp),
            spacing = 10.dp
        ) {
            box(modifier = AdModifier.fillMaxWidth()) {
                media()
                row(
                    modifier = AdModifier.fillMaxWidth().padding(8.dp),
                ) {
                    adBadge(modifier = colors.badgeModifier(), style = colors.badgeStyle())
                    spacer(AdModifier.weight(1f))
                    adChoices(
                        modifier = AdModifier.size(24.dp),
                        visibilityPolicy = AdVisibilityPolicy.KeepSpace
                    )
                }
            }
            row(spacing = 10.dp) {
                icon(AdModifier.size(44.dp).cornerRadius(8.dp))
                column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                    row(spacing = 6.dp) {
                        headline(modifier = AdModifier.weight(1f), style = colors.headlineStyle(), maxLines = 2)
                    }
                    body(maxLines = 2, style = colors.bodyStyle())
                    row(spacing = 8.dp) {
                        advertiser(style = colors.captionStyle())
                        starRating(style = colors.captionStyle())
                    }
                }
            }
            callToAction(modifier = AdModifier.fillMaxWidth(), style = colors.buttonStyle())
        }
    }

    /**
     * Full-width feed card with media, rich metadata, and CTA, in [colors].
     *
     * @param colors the palette to paint with; [AdTemplateColors.dark] for a dark UI.
     */
    public fun feedCard(colors: AdTemplateColors): AdLayout = adLayout {
        column(
            modifier = AdModifier.fillMaxWidth().padding(16.dp).background(colors.surface).cornerRadius(18.dp),
            spacing = 12.dp
        ) {
            box(modifier = AdModifier.fillMaxWidth()) {
                media()
                row(
                    modifier = AdModifier.fillMaxWidth().padding(8.dp),
                ) {
                    adBadge(modifier = colors.badgeModifier(), style = colors.badgeStyle())
                    spacer(AdModifier.weight(1f))
                    adChoices(AdModifier.size(24.dp))
                }
            }
            row(spacing = 12.dp) {
                icon(AdModifier.size(56.dp).cornerRadius(12.dp))
                column(modifier = AdModifier.weight(1f), spacing = 5.dp) {
                    row(spacing = 8.dp) {
                        headline(modifier = AdModifier.weight(1f), style = colors.headlineStyle(), maxLines = 2)
                    }
                    body(maxLines = 3, style = colors.bodyStyle())
                    row(spacing = 8.dp) {
                        advertiser(style = colors.captionStyle())
                        store(style = colors.captionStyle())
                        price(style = colors.captionStyle())
                        starRating(style = colors.captionStyle())
                    }
                }
            }
            callToAction(modifier = AdModifier.fillMaxWidth(), style = colors.buttonStyle())
        }
    }
}

// Each preset is recoloured rather than rebuilt, so a template keeps the size, weight and
// alignment of the shared `AdTextStyle`/`AdButtonStyle` presets and differs only in colour.

private fun AdTemplateColors.headlineStyle(): AdTextStyle =
    AdTextStyle.title.copy(colorArgb = headline.toArgbLong())

private fun AdTemplateColors.bodyStyle(): AdTextStyle =
    AdTextStyle.body.copy(colorArgb = body.toArgbLong())

private fun AdTemplateColors.captionStyle(): AdTextStyle =
    AdTextStyle.caption.copy(colorArgb = caption.toArgbLong())

private fun AdTemplateColors.badgeStyle(): AdTextStyle =
    AdTextStyle.badge.copy(colorArgb = badgeText.toArgbLong())

private fun AdTemplateColors.buttonStyle(): AdButtonStyle = AdButtonStyle.filled.copy(
    backgroundArgb = callToAction.toArgbLong(),
    textStyle = AdButtonStyle.filled.textStyle.copy(colorArgb = onCallToAction.toArgbLong()),
)

/** Mirrors the DSL's default badge modifier, with the border in the template's own outline colour. */
private fun AdTemplateColors.badgeModifier(): AdModifier = AdModifier
    .padding(horizontal = 6.dp, vertical = 2.dp)
    .sizeIn(minWidth = 15.dp, minHeight = 15.dp)
    .border(1.dp, badgeOutline, 3.dp)
