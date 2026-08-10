package dev.avinya.admob.showcase.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * The app's text input.
 *
 * Built on `BasicTextField` rather than `OutlinedTextField` because the
 * Material field brings a floating label, its own container heights, and an
 * indicator line that all read as Android. Focus is shown by promoting the
 * border to the interactive colour — nothing moves.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onSubmit: (() -> Unit)? = null,
) {
    val palette = showcaseColors
    val focusManager: FocusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tokens.touchTarget)
            .clip(ShowcaseShapes.control)
            .background(palette.surfaceSunken)
            .border(
                width = Tokens.hairline,
                color = if (focused) palette.primary else palette.hairline,
                shape = ShowcaseShapes.control,
            )
            .padding(horizontal = Tokens.Spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = if (focused) palette.primary else palette.inkFaint,
            modifier = Modifier.size(18.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(text = placeholder, style = ShowcaseType.bodyMedium, color = palette.inkFaint)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                interactionSource = interactionSource,
                textStyle = ShowcaseType.bodyMedium.copy(color = palette.ink),
                cursorBrush = SolidColor(palette.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        onSubmit?.invoke()
                        focusManager.clearFocus()
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (query.isNotEmpty()) {
            IconAction(
                icon = Icons.Rounded.Close,
                contentDescription = "Clear search",
                onClick = { onQueryChange("") },
                tint = palette.inkMuted,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * A horizontally scrolling filter row.
 *
 * These are filters, not decoration: each carries `selected` semantics so
 * assistive technology reports the active one.
 */
@Composable
fun PillTabs(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = Tokens.Spacing.gutterCompact,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = contentPadding),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
    ) {
        options.forEach { option ->
            Pill(
                label = option,
                selected = option == selected,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
fun Pill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    Text(
        text = label,
        style = ShowcaseType.labelMedium,
        color = if (selected) palette.onAccentInk else palette.inkMuted,
        modifier = modifier
            .clip(ShowcaseShapes.control)
            .background(if (selected) palette.primary else palette.surfaceSunken)
            .border(
                Tokens.hairline,
                if (selected) palette.primary else palette.hairline,
                ShowcaseShapes.control,
            )
            .pressable(onClick = onClick, scaleWhenPressed = 0.94f)
            .semantics {
                role = Role.Tab
                this.selected = selected
            }
            .padding(horizontal = Tokens.Spacing.s12, vertical = 10.dp),
    )
}
