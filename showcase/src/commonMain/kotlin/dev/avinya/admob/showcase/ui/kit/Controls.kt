package dev.avinya.admob.showcase.ui.kit

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * The app's controls.
 *
 * Every one is drawn from primitives rather than pulled from Material. That is
 * a deliberate cost: a Material `Switch` or `RadioButton` carries an
 * unmistakably Android silhouette, and this app has to look like one designed
 * product on both platforms.
 */

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val palette = showcaseColors
    val active = enabled && !loading
    val container by animateColorAsState(
        if (active) palette.primary else palette.primary.copy(alpha = 0.35f),
        label = "primaryButtonContainer",
    )

    Row(
        modifier = modifier
            .heightIn(min = Tokens.touchTarget)
            .clip(ShowcaseShapes.control)
            .background(container)
            .pressable(onClick = onClick, enabled = active)
            .semantics { role = Role.Button }
            .padding(horizontal = Tokens.Spacing.s20),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = palette.onAccentInk,
            )
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = palette.onAccentInk, modifier = Modifier.size(18.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = palette.onAccentInk)
    }
}

@Composable
fun GhostButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    val palette = showcaseColors
    val content = when {
        !enabled -> palette.inkFaint
        destructive -> palette.danger
        else -> palette.primary
    }

    Row(
        modifier = modifier
            .heightIn(min = Tokens.touchTarget)
            .clip(ShowcaseShapes.control)
            .border(Tokens.hairline, content.copy(alpha = 0.45f), ShowcaseShapes.control)
            .pressable(onClick = onClick, enabled = enabled)
            .semantics { role = Role.Button }
            .padding(horizontal = Tokens.Spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** A borderless text action — used inside section headings and rows. */
@Composable
fun TextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = showcaseColors
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) palette.primary else palette.inkFaint,
        modifier = modifier
            .clip(ShowcaseShapes.chip)
            .pressable(onClick = onClick, enabled = enabled)
            .semantics { role = Role.Button }
            .padding(horizontal = Tokens.Spacing.s8, vertical = Tokens.Spacing.s8),
    )
}

/** An icon-only action sized to a full touch target. */
@Composable
fun IconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = showcaseColors.ink,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(Tokens.touchTarget)
            .clip(CircleShape)
            .pressable(onClick = onClick, enabled = enabled, scaleWhenPressed = 0.88f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else showcaseColors.inkFaint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * A settings row that navigates somewhere.
 *
 * The chevron is the affordance. Without it a tappable row is indistinguishable
 * from a label, which is what the previous Profile screen shipped.
 */
@Composable
fun ActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    trailing: String? = null,
    leading: ImageVector? = null,
    enabled: Boolean = true,
) {
    val palette = showcaseColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.touchTarget)
            .clip(ShowcaseShapes.control)
            .pressable(onClick = onClick, enabled = enabled, scaleWhenPressed = 0.995f)
            .semantics { role = Role.Button }
            .padding(vertical = Tokens.Spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(
                imageVector = leading,
                contentDescription = null,
                tint = palette.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s2),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) palette.ink else palette.inkFaint,
            )
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            }
        }
        if (trailing != null) {
            Text(text = trailing, style = MaterialTheme.typography.labelMedium, color = palette.inkMuted)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.inkFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A label/value row for read-only facts. */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = showcaseColors.ink,
) {
    val palette = showcaseColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s16),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * A switch row with a hand-drawn track and thumb.
 *
 * The whole row is the target, and `stateDescription` carries the on/off state
 * so a screen reader does not have to infer it from the graphic.
 */
@Composable
fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    val palette = showcaseColors
    val trackColor by animateColorAsState(
        when {
            !enabled -> palette.hairline
            checked -> palette.primary
            else -> palette.surfaceSunken
        },
        label = "toggleTrack",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 2.dp,
        animationSpec = tween(160),
        label = "toggleThumb",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.touchTarget)
            .clip(ShowcaseShapes.control)
            .pressable(onClick = { onCheckedChange(!checked) }, enabled = enabled, scaleWhenPressed = 0.995f)
            .semantics {
                role = Role.Switch
                stateDescription = if (checked) "On" else "Off"
            }
            .padding(vertical = Tokens.Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s2),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) palette.ink else palette.inkFaint,
            )
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            }
        }

        Box(
            modifier = Modifier
                .width(44.dp)
                .height(26.dp)
                .clip(CircleShape)
                .background(trackColor)
                .border(Tokens.hairline, palette.hairlineStrong, CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .align(Alignment.CenterStart)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (checked) palette.onAccentInk else palette.surface)
                    .border(Tokens.hairline, palette.hairlineStrong.copy(alpha = 0.6f), CircleShape),
            )
        }
    }
}

/**
 * A single-choice row. The selection mark is a filled square with a rounded
 * corner, not a Material radio dot.
 */
@Composable
fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val palette = showcaseColors
    val markColor by animateColorAsState(
        if (selected) palette.primary else Color.Transparent,
        label = "choiceMark",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.touchTarget)
            .clip(ShowcaseShapes.control)
            .pressable(onClick = onClick, scaleWhenPressed = 0.995f)
            .semantics {
                role = Role.RadioButton
                stateDescription = if (selected) "Selected" else "Not selected"
            }
            .padding(vertical = Tokens.Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(ShowcaseShapes.chip)
                .background(markColor)
                .border(
                    Tokens.hairline,
                    if (selected) palette.primary else palette.hairlineStrong,
                    ShowcaseShapes.chip,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(ShowcaseShapes.chip)
                        .background(palette.onAccentInk),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s2),
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
            if (detail != null) {
                Text(text = detail, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            }
        }
    }
}
