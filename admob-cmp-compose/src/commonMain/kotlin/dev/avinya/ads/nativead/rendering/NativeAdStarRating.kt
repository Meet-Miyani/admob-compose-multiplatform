package dev.avinya.ads.nativead.rendering

import kotlin.math.round

/**
 * The text a `starRating` asset renders as — on Android, on iOS, and in `AdLayoutPreview`.
 *
 * The three renderers used to disagree completely: Android built a `RatingBar` (five system stars
 * that silently discarded the node's [dev.avinya.ads.nativead.layout.AdTextStyle] — no colour, no
 * font, no size), iOS built a label reading `"4.6 stars"`, and the preview drew `"★ 4.6"`. The same
 * layout therefore previewed as one thing and shipped as two others.
 *
 * A styled text rendering is what resolves that, and it is what Google's own native-ads guide
 * does (`"Rated $it"`): the star rating asset carries no prescribed presentation, so the only real
 * constraint is that the value is shown. Text is also the only form that can honour `AdTextStyle`
 * on every platform, which a `RatingBar` cannot.
 *
 * A single [STAR] glyph is used rather than a five-glyph row on purpose. A row has to round the
 * rating to whole or half stars — overstating a 4.6 as five filled stars — and a half-star glyph
 * has no dependable cross-platform code point, whereas U+2605 is present in the default system
 * fonts of both platforms.
 *
 * Returns `null` when there is no rating to show, which every renderer treats as a missing asset
 * and hands to the node's [dev.avinya.ads.nativead.layout.AdVisibilityPolicy].
 */
internal fun resolveStarRatingText(rating: Double?): String? {
    if (rating == null || !rating.isFinite()) return null
    return "$STAR ${formatStarRating(rating.coerceIn(0.0, MAX_STAR_RATING))}"
}

/**
 * Formats a rating to exactly one decimal place, without `java.text` — `commonMain` has to compile
 * for Kotlin/Native too, and `toString()` on a `Double` is not stable across those backends
 * (`4.6` can round-trip as `4.6000000000000005`, and whole values print as `5.0` on one platform
 * and `5` on another). Doing the rounding in integer arithmetic makes the output identical
 * everywhere, which is the whole point of resolving this in common code.
 */
private fun formatStarRating(rating: Double): String {
    val tenths = round(rating * 10.0).toLong()
    return "${tenths / 10}.${tenths % 10}"
}

private const val STAR: String = "★"
private const val MAX_STAR_RATING: Double = 5.0
