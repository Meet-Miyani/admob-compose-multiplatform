package dev.avinya.admob.showcase.ui.kit

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * Loading, empty, and error states.
 *
 * All three communicate without relying on colour, and all three are marked as
 * live regions so a screen reader announces the change rather than leaving the
 * user on a silent screen.
 */

/** Skeleton rows shaped like the content that is coming. */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String = "Loading stories",
    rows: Int = 3,
) {
    val palette = showcaseColors
    val transition = rememberInfiniteTransition(label = "skeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "skeletonPulse",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s24),
    ) {
        Text(text = label, style = ShowcaseType.labelMedium, color = palette.inkMuted)
        repeat(rows) { index ->
            Column(
                modifier = Modifier.fillMaxWidth().alpha(pulse),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
            ) {
                SkeletonBar(widthFraction = 0.22f, height = 10.dp)
                SkeletonBar(widthFraction = if (index % 2 == 0) 0.92f else 0.78f, height = 20.dp)
                SkeletonBar(widthFraction = 0.55f, height = 12.dp)
            }
        }
    }
}

@Composable
private fun SkeletonBar(widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(ShowcaseShapes.chip)
            .background(showcaseColors.surfaceSunken),
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateBlock(
        title = title,
        message = message,
        icon = icon,
        accent = showcaseColors.inkMuted,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    actionLabel: String? = "Try again",
    onAction: (() -> Unit)? = null,
) {
    StateBlock(
        title = title,
        message = message,
        icon = icon,
        accent = showcaseColors.danger,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}

@Composable
private fun StateBlock(
    title: String,
    message: String,
    icon: ImageVector?,
    accent: androidx.compose.ui.graphics.Color,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(vertical = Tokens.Spacing.s48, horizontal = Tokens.Spacing.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = title,
            style = ShowcaseType.headlineSmall,
            color = palette.ink,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = ShowcaseType.bodyMedium,
            color = palette.inkMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(320.dp),
        )
        if (actionLabel != null && onAction != null) {
            GhostButton(
                label = actionLabel,
                onClick = onAction,
                modifier = Modifier.padding(top = Tokens.Spacing.s4),
            )
        }
    }
}
