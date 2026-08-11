package dev.avinya.admob.showcase.ui.ad

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdContentScale
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdImageStyle
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTextAlign
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.adLayout

/**
 * The app's own native ad layouts, written with the SDK's `adLayout {}` DSL.
 *
 * Two things these must get right, and both are policy rather than taste:
 *
 * - **`adBadge()` at the top.** An unlabelled native ad is a policy violation.
 *   The validator emits `missing_ad_badge` / `ad_attribution_not_at_top`.
 * - **`adChoices()` space reserved.** The AdChoices overlay is SDK-owned; a
 *   layout that leaves it nowhere to go earns `missing_ad_choices_space`.
 *
 * They are built per-theme rather than as top-level `val`s because the DSL
 * takes literal ARGB values. A layout baked with light-theme colours renders a
 * white card in a dark feed, which is precisely the bug the previous
 * hard-coded layouts had.
 */

private fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

/**
 * Feed treatment: cover-led, for a hero-sized slot.
 */
@Composable
fun rememberFeedAdLayout(): AdLayout {
    val palette = showcaseColors
    return remember(palette) { feedAdLayout(palette) }
}

/**
 * Feed-row treatment: the same geometry as `StoryCard`'s Standard row —
 * eyebrow, serif headline, two-line standfirst, meta line, and a square
 * thumbnail on the trailing edge.
 *
 * This is what the layout DSL is *for*. A native ad is a bag of assets, not a
 * fixed creative, so it can be composed into whatever shape the surrounding
 * list already uses. The result sits in the feed's rhythm instead of
 * interrupting it — while the SPONSORED badge, the advertiser name, and the
 * call-to-action button keep it unmistakably an ad rather than a disguised
 * story.
 */
@Composable
fun rememberFeedRowAdLayout(): AdLayout {
    val palette = showcaseColors
    return remember(palette) { feedRowAdLayout(palette) }
}

/**
 * Article treatment: text-led band with no large media, so it reads as an
 * interruption in the column rather than a second hero.
 */
@Composable
fun rememberInlineAdLayout(): AdLayout {
    val palette = showcaseColors
    return remember(palette) { inlineAdLayout(palette) }
}

internal fun feedAdLayout(palette: ShowcasePalette): AdLayout {
    val badge = badgeStyle(palette)
    return adLayout {
        column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.CenterVertically,
                spacing = 8.dp,
            ) {
                adBadge(
                    modifier = AdModifier
                        .background(palette.accentSoft)
                        .cornerRadius(4.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    text = "SPONSORED",
                    style = badge,
                )
                advertiser(
                    modifier = AdModifier.weight(1f),
                    style = caption(palette),
                    maxLines = 1,
                )
                adChoices(
                    modifier = AdModifier.size(20.dp),
                    visibilityPolicy = AdVisibilityPolicy.KeepSpace,
                )
            }

            media(
                modifier = AdModifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .cornerRadius(20.dp),
            )

            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.Top,
                spacing = 12.dp,
            ) {
                icon(modifier = AdModifier.size(44.dp).cornerRadius(10.dp))
                column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                    headline(style = title(palette), maxLines = 2)
                    body(style = body(palette), maxLines = 2)
                    row(spacing = 8.dp) {
                        starRating(style = caption(palette))
                        price(style = caption(palette))
                        store(style = caption(palette))
                    }
                }
            }

            callToAction(
                modifier = AdModifier.fillMaxWidth(),
                style = ctaStyle(palette),
            )
        }
    }
}

internal fun feedRowAdLayout(palette: ShowcasePalette): AdLayout = adLayout {
    // Mirrors `StoryCard`'s Standard row exactly: text column on the left with
    // eyebrow / headline / standfirst / meta, and a 120.dp square on the right,
    // both top-aligned with 16.dp between them.
    //
    // The 120dp size satisfies the GMA/AdMob requirement that native media views
    // are at least 120×120dp when video may play in them. Keeping the editorial
    // thumbnail and the sponsored thumbnail at the same size is what lets the
    // two rows share one visual rhythm without the ad slot standing out.
    row(
        modifier = AdModifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = AdAlignment.Vertical.Top,
        spacing = 16.dp,
    ) {
        column(modifier = AdModifier.weight(1f), spacing = 8.dp) {
            // The slot the editorial row gives its section label carries the ad
            // badge here — same position, same size, same tracking.
            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.CenterVertically,
                spacing = 6.dp,
            ) {
                adBadge(
                    // The DSL's default badge modifier draws a bordered box.
                    // An editorial eyebrow has no box, so this one is reset to
                    // bare text — the disclosure is the word, not the frame.
                    modifier = AdModifier.wrapContentSize(),
                    text = "SPONSORED",
                    style = AdTextStyle(
                        fontSizeSp = 11f,
                        colorArgb = palette.accent.argb(),
                        fontWeight = AdFontWeight.Bold,
                    ),
                )
                spacer(AdModifier.weight(1f))
                adChoices(
                    modifier = AdModifier.size(16.dp),
                    visibilityPolicy = AdVisibilityPolicy.KeepSpace,
                )
            }

            // Matches ShowcaseType.titleLarge — 21sp / medium / serif.
            headline(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(
                    fontSizeSp = 21f,
                    colorArgb = palette.ink.argb(),
                    fontWeight = AdFontWeight.Medium,
                    fontFamily = AdFontFamily.Serif,
                ),
                maxLines = 3,
            )

            // Matches ShowcaseType.bodySmall — 13sp, muted, two lines.
            body(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(fontSizeSp = 13f, colorArgb = palette.inkMuted.argb()),
                maxLines = 2,
            )

            // The editorial row's meta line is "author · read time". Here it is
            // the advertiser name, in the same position and the same style.
            //
            // No call-to-action button. It is not required — policy asks for the
            // attribution badge and AdChoices space, and the SDK's validator
            // checks exactly those — and a button is the one element an article
            // row has no counterpart for, so it was the last thing making a
            // sponsored row look different from an editorial one. The whole
            // native view stays clickable regardless.
            advertiser(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(fontSizeSp = 12f, colorArgb = palette.inkMuted.argb()),
                maxLines = 1,
                visibilityPolicy = AdVisibilityPolicy.HideWhenMissing,
            )
        }

        // The same 120.dp square, same 20.dp radius, as the editorial cover.
        // `icon` sits behind `media` as the fallback: an app-install creative
        // often ships only an icon, and an empty square would break the row.
        box(modifier = AdModifier.size(Tokens.feedThumbnail)) {
            icon(
                modifier = AdModifier.size(Tokens.feedThumbnail).cornerRadius(20.dp),
                style = AdImageStyle(
                    contentScale = AdContentScale.Crop,
                    backgroundArgb = palette.surfaceSunken.argb(),
                ),
            )
            media(
                modifier = AdModifier.size(Tokens.feedThumbnail).cornerRadius(20.dp),
                style = AdImageStyle(contentScale = AdContentScale.Crop),
                visibilityPolicy = AdVisibilityPolicy.HideWhenMissing,
            )
        }
    }
}

internal fun inlineAdLayout(palette: ShowcasePalette): AdLayout = adLayout {
    column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 8.dp,
        ) {
            adBadge(
                modifier = AdModifier
                    .background(palette.accentSoft)
                    .cornerRadius(4.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                text = "SPONSORED",
                style = badgeStyle(palette),
            )
            advertiser(
                modifier = AdModifier.weight(1f),
                style = caption(palette),
                maxLines = 1,
            )
            adChoices(
                modifier = AdModifier.size(20.dp),
                visibilityPolicy = AdVisibilityPolicy.KeepSpace,
            )
        }
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 12.dp,
        ) {
            icon(modifier = AdModifier.size(48.dp).cornerRadius(10.dp))
            column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                headline(style = title(palette), maxLines = 2)
                body(style = body(palette), maxLines = 2)
            }
        }
        callToAction(
            modifier = AdModifier.fillMaxWidth(),
            style = ctaStyle(palette),
        )
    }
}

private fun badgeStyle(palette: ShowcasePalette) = AdTextStyle(
    fontSizeSp = 10f,
    colorArgb = palette.accent.argb(),
    fontWeight = AdFontWeight.Bold,
)

private fun title(palette: ShowcasePalette) = AdTextStyle(
    fontSizeSp = 17f,
    colorArgb = palette.ink.argb(),
    fontWeight = AdFontWeight.Bold,
)

private fun body(palette: ShowcasePalette) = AdTextStyle(
    fontSizeSp = 14f,
    colorArgb = palette.inkMuted.argb(),
)

private fun caption(palette: ShowcasePalette) = AdTextStyle(
    fontSizeSp = 12f,
    colorArgb = palette.inkFaint.argb(),
)

private fun ctaStyle(palette: ShowcasePalette) = AdButtonStyle(
    textStyle = AdTextStyle(
        fontSizeSp = 14f,
        colorArgb = palette.onAccentInk.argb(),
        fontWeight = AdFontWeight.Bold,
        textAlign = AdTextAlign.Center,
    ),
    backgroundArgb = palette.primary.argb(),
    cornerRadiusDp = 10f,
)
