package dev.avinya.ads.nativead.rendering

import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdLayoutSize
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdNode

/**
 * How a container places a child along one axis.
 *
 * Every container in the DSL — `Row`, `Column`, `Box` — resolves to a pair of these, so the three
 * renderers can share one answer instead of each interpreting `AdAlignment` themselves. Android
 * read both axes and mapped them to gravity, the preview passed them to Compose, and iOS honoured
 * only a stack's cross axis and hard-coded `Box` to top-start. Resolving it here means a renderer
 * can still fail to *apply* a placement, but it can no longer compute a different one.
 */
internal enum class AdAxisPlacement { Start, Center, End }

internal fun AdAlignment.Horizontal.toPlacement(): AdAxisPlacement = when (this) {
    AdAlignment.Horizontal.Start -> AdAxisPlacement.Start
    AdAlignment.Horizontal.CenterHorizontally -> AdAxisPlacement.Center
    AdAlignment.Horizontal.End -> AdAxisPlacement.End
}

internal fun AdAlignment.Vertical.toPlacement(): AdAxisPlacement = when (this) {
    AdAlignment.Vertical.Top -> AdAxisPlacement.Start
    AdAlignment.Vertical.CenterVertically -> AdAxisPlacement.Center
    AdAlignment.Vertical.Bottom -> AdAxisPlacement.End
}

/** A `Row`'s cross axis is vertical, so its placement comes from the vertical alignment. */
internal fun rowCrossAxisPlacement(alignment: AdAlignment.Vertical): AdAxisPlacement =
    alignment.toPlacement()

/** A `Column`'s cross axis is horizontal, so its placement comes from the horizontal alignment. */
internal fun columnCrossAxisPlacement(alignment: AdAlignment.Horizontal): AdAxisPlacement =
    alignment.toPlacement()

/** A `Row`'s main axis is horizontal — this is the axis `Arrangement` packs children along. */
internal fun rowMainAxisPlacement(alignment: AdAlignment.Horizontal): AdAxisPlacement =
    alignment.toPlacement()

/** A `Column`'s main axis is vertical. */
internal fun columnMainAxisPlacement(alignment: AdAlignment.Vertical): AdAxisPlacement =
    alignment.toPlacement()

/** Horizontal half of a `Box`'s content alignment. */
internal fun AdAlignment.Box.horizontalPlacement(): AdAxisPlacement = when (this) {
    AdAlignment.Box.TopStart, AdAlignment.Box.CenterStart, AdAlignment.Box.BottomStart -> AdAxisPlacement.Start
    AdAlignment.Box.TopCenter, AdAlignment.Box.Center, AdAlignment.Box.BottomCenter -> AdAxisPlacement.Center
    AdAlignment.Box.TopEnd, AdAlignment.Box.CenterEnd, AdAlignment.Box.BottomEnd -> AdAxisPlacement.End
}

/** Vertical half of a `Box`'s content alignment. */
internal fun AdAlignment.Box.verticalPlacement(): AdAxisPlacement = when (this) {
    AdAlignment.Box.TopStart, AdAlignment.Box.TopCenter, AdAlignment.Box.TopEnd -> AdAxisPlacement.Start
    AdAlignment.Box.CenterStart, AdAlignment.Box.Center, AdAlignment.Box.CenterEnd -> AdAxisPlacement.Center
    AdAlignment.Box.BottomStart, AdAlignment.Box.BottomCenter, AdAlignment.Box.BottomEnd -> AdAxisPlacement.End
}

/**
 * True when [child] imposes a required limit on its own width, and so would drag a `Column`'s
 * width down with it.
 *
 * A `Column` is laid out with `UIStackViewAlignmentFill`, which pins every arranged subview to
 * both cross-axis edges: a child carrying a required width would therefore propagate that width to
 * the column itself — a 44dp icon narrowing the whole column to 44dp. Compose has no such
 * coupling; a `Column` sizes to its widest child and places the fixed one per
 * `horizontalAlignment`. The iOS renderer restores that by putting these children in a container
 * the stack may stretch freely and placing the child inside it.
 *
 * Children that do *not* constrain their width are left alone, because `Fill` stretching them to
 * the column's width is exactly what gives a `UILabel` something to wrap against.
 *
 * This is a column-only concern. A `Row` is laid out at its actual `verticalAlignment`
 * (`Top`/`Center`/`Bottom`) rather than with `Fill`, so its children already keep their own heights
 * and nothing propagates upward.
 */
internal fun constrainsColumnCrossAxis(child: AdModifier): Boolean =
    child.width is AdLayoutSize.Fixed || child.maxWidthDp != null

/**
 * True when [child] asks for all the space its siblings do not take, along the stack's main axis.
 *
 * Two modifiers mean this, and they have to be treated alike. `weight` is the explicit one.
 * `AdLayoutSize.Match` is the implicit one: Compose measures a `Row` child's `fillMaxWidth()`
 * against the *remaining* width, so it behaves as a claim on the leftover space rather than on the
 * parent's full width. Reading only `weight` is what left `fillMaxWidth()` on a row child doing
 * nothing at all on iOS.
 */
internal fun claimsMainAxis(child: AdModifier, stackIsHorizontal: Boolean): Boolean {
    if (child.effectiveWeight != null) return true
    return (if (stackIsHorizontal) child.width else child.height) == AdLayoutSize.Match
}

/**
 * True when a stack's children leave main-axis slack for its arrangement to distribute.
 *
 * Compose resolves `Row(horizontalArrangement = spacedBy(gap, alignment))` against whatever space
 * the children do not claim. A child that claims the main axis takes all of it by definition, so
 * the alignment becomes unobservable — which is why applying it unconditionally on iOS would fight
 * those children rather than reproduce Compose.
 */
internal fun stackHasMainAxisSlack(children: List<AdNode>, stackIsHorizontal: Boolean): Boolean =
    children.none { claimsMainAxis(it.modifier, stackIsHorizontal) }

/**
 * A main-axis size relationship between two weighted children, as a multiplier.
 *
 * Compose divides a stack's free space between weighted children *in proportion* to their weights.
 * `UIStackView` has no equivalent: the iOS renderer used to approximate it by dropping the hugging
 * and compression-resistance priorities of weighted children, which makes them absorb slack but
 * says nothing about the ratio between them — `weight(1f)` beside `weight(2f)` came out 1:1.
 *
 * Expressing each weighted child as a multiple of the first weighted one turns that into an exact
 * Auto Layout relationship, which is the only form `NSLayoutConstraint` can enforce.
 */
internal data class AdWeightRatio(
    /** Index into the stack's children of the child being constrained. */
    val childIndex: Int,
    /** Index of the weighted child it is measured against — always the first weighted child. */
    val referenceIndex: Int,
    /** [childIndex]'s main-axis size as a multiple of [referenceIndex]'s. */
    val multiplier: Double,
)

/**
 * The ratio constraints needed to reproduce Compose's proportional weights, or an empty list when
 * fewer than two children are weighted and there is no ratio to enforce.
 */
internal fun resolveWeightRatios(children: List<AdNode>): List<AdWeightRatio> {
    val weighted = children
        .mapIndexedNotNull { index, child -> child.modifier.effectiveWeight?.let { index to it } }
    val (referenceIndex, referenceWeight) = weighted.firstOrNull() ?: return emptyList()
    return weighted.drop(1).map { (index, weight) ->
        AdWeightRatio(
            childIndex = index,
            referenceIndex = referenceIndex,
            multiplier = weight.toDouble() / referenceWeight.toDouble(),
        )
    }
}

/**
 * The child's main-axis weight, or `null` when it claims none.
 *
 * `AdModifier.weight()` already discards values `<= 0`, but `AdModifier` is a public data class, so
 * `AdModifier(weight = 0f)` and `copy(weight = -1f)` reach the renderers unchecked. Every consumer
 * of a weight has to apply the same rule or they disagree about what the layout means: the iOS
 * stack treated a zero weight as weighted (handing that child all the slack) while
 * [resolveWeightRatios] ignored it, and `AdLayoutPreview` passed it straight to Compose's
 * `Modifier.weight`, whose `require(weight > 0)` turned a malformed layout into a crash.
 *
 * `isFinite` is part of the same guard: a NaN weight is neither `> 0` nor safe to divide by.
 */
internal val AdModifier.effectiveWeight: Float?
    get() = weight?.takeIf { it > 0f && it.isFinite() }
