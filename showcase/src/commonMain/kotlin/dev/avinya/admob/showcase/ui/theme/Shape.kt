package dev.avinya.admob.showcase.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * Shape roles for the component kit, derived from [Tokens.Radii].
 *
 * `bottomSheet` and `topSheet` curve only the edge that faces the content, so
 * a surface anchored to a window edge stays anchored instead of floating.
 */
object ShowcaseShapes {
    val chip = RoundedCornerShape(Tokens.Radii.small)
    val control = RoundedCornerShape(Tokens.Radii.medium)
    val card = RoundedCornerShape(Tokens.Radii.large)
    val media = RoundedCornerShape(Tokens.Radii.large)
    val sheet = RoundedCornerShape(Tokens.Radii.xlarge)

    val bottomSheet = RoundedCornerShape(
        topStart = Tokens.Radii.xlarge,
        topEnd = Tokens.Radii.xlarge,
    )

    /** Media that meets the top of a card: square above, curved below. */
    val mediaTop = RoundedCornerShape(
        bottomStart = Tokens.Radii.large,
        bottomEnd = Tokens.Radii.large,
    )
}

internal val ShowcaseMaterialShapes = Shapes(
    extraSmall = ShowcaseShapes.chip,
    small = ShowcaseShapes.control,
    medium = ShowcaseShapes.control,
    large = ShowcaseShapes.card,
    extraLarge = ShowcaseShapes.sheet,
)
