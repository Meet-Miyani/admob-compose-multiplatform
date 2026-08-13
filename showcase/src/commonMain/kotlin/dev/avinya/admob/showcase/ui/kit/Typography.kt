package dev.avinya.admob.showcase.ui.kit

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.sectionColor
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * A tracked-out uppercase taxonomy label.
 *
 * Used for section names, "SPONSORED", "PREMIUM" — anything that classifies
 * rather than describes.
 */
@Composable
fun Eyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = showcaseColors.accent,
) {
    Text(
        text = text.uppercase(),
        style = ShowcaseType.eyebrow,
        color = color,
        modifier = modifier,
    )
}

/** An eyebrow tinted with the section's own hue. */
@Composable
fun SectionEyebrow(section: String, modifier: Modifier = Modifier) {
    Eyebrow(text = section, color = sectionColor(section), modifier = modifier)
}

/**
 * A section heading with an optional trailing text action.
 *
 * The rule underneath is part of the heading, not a separate element — that is
 * what makes a list of sections scan as a page rather than as a stack.
 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    ruled: Boolean = true,
) {
    val palette = showcaseColors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s2),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.ink,
                )
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                TextAction(label = actionLabel, onClick = onAction)
            }
        }
        if (ruled) {
            Rule(modifier = Modifier.padding(top = Tokens.Spacing.s12), strong = true)
        }
    }
}

/**
 * A titled group of rows.
 *
 * Exists because every settings-style screen was hand-assembling
 * `Column { SectionTitle(...); rows }` and each one chose its own gap, so the
 * spacing between a heading and its rows drifted from screen to screen. One
 * container fixes the rhythm in a single place.
 */
@Composable
fun Section(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionTitle(title = title, caption = caption)
        Column(
            modifier = Modifier.padding(top = Tokens.Spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
            content = content,
        )
    }
}

/** A small filled label. */
@Composable
fun Badge(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = showcaseColors.accent,
    container: Color = showcaseColors.accentSoft,
) {
    Text(
        text = text.uppercase(),
        style = ShowcaseType.eyebrow,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(ShowcaseShapes.chip)
            .background(container)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Metadata that separates its parts with a middot. */
@Composable
fun MetaLine(
    parts: List<String>,
    modifier: Modifier = Modifier,
    color: Color = showcaseColors.inkMuted,
) {
    val visible = parts.filter { it.isNotBlank() }
    if (visible.isEmpty()) return
    Text(
        text = visible.joinToString("  ·  "),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
