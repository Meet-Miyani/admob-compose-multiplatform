package dev.avinya.admob.showcase.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * A raised plane: fill, hairline, and one soft shadow.
 *
 * This is the only elevation idiom in the app. Material's tonal elevation is
 * switched off in the theme, so depth reads the same in light and dark instead
 * of turning dark surfaces progressively grey.
 */
@Composable
fun Plane(
    modifier: Modifier = Modifier,
    shape: Shape = ShowcaseShapes.card,
    color: Color = showcaseColors.surface,
    elevation: Dp = Tokens.Elevation.raised,
    bordered: Boolean = true,
    content: @Composable () -> Unit,
) {
    val palette = showcaseColors
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = if (palette.isDark) 0.6f else 0.12f),
                spotColor = Color.Black.copy(alpha = if (palette.isDark) 0.6f else 0.16f),
            )
            .clip(shape)
            .background(color)
            .then(
                if (bordered) Modifier.border(Tokens.hairline, palette.hairline, shape) else Modifier,
            ),
    ) {
        content()
    }
}

/**
 * A recessed panel — used for read-only groupings such as diagnostics rows,
 * where a raised plane would overstate the content's importance.
 */
@Composable
fun SunkenPanel(
    modifier: Modifier = Modifier,
    shape: Shape = ShowcaseShapes.control,
    content: @Composable () -> Unit,
) {
    val palette = showcaseColors
    Box(
        modifier = modifier
            .clip(shape)
            .background(palette.surfaceSunken)
            .border(Tokens.hairline, palette.hairline, shape),
    ) {
        content()
    }
}

/** A hairline rule. The design language's main separator. */
@Composable
fun Rule(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    inset: Dp = 0.dp,
) {
    val palette = showcaseColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(Tokens.hairline)
            .background(if (strong) palette.hairlineStrong else palette.hairline),
    )
}

/**
 * Press feedback for cards and rows.
 *
 * Cards settle rather than ripple: a ripple is a Material signature and reads
 * wrong on iOS, while a small uniform scale reads as "pressed" on both.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    scaleWhenPressed: Float = Tokens.pressedScale,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) scaleWhenPressed else 1f,
        label = "pressScale",
    )
    return this
        .scale(scale)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )
}
