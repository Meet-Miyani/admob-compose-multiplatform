package dev.avinya.admob.showcase.ui.kit

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/** One destination in the app's primary navigation. */
data class TabItem(
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * The bottom tab bar.
 *
 * Grounded directly to the window edge — it consumes its own navigation-bar
 * inset and has no outer floating container, which is what the shell used to
 * get wrong by wrapping the bar and then padding the content a second time.
 *
 * The selected state is drawn as a short ink underline rather than Material's
 * pill indicator, so the bar does not read as an Android component on iOS.
 */
@Composable
fun AppTabBar(
    tabs: List<TabItem>,
    modifier: Modifier = Modifier,
) {
    val palette = showcaseColors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.surface),
    ) {
        Rule()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                TabCell(tab = tab, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun TabCell(tab: TabItem, modifier: Modifier = Modifier) {
    val palette = showcaseColors
    val content by animateColorAsState(
        if (tab.selected) palette.primary else palette.inkMuted,
        label = "tabContent",
    )
    val indicator by animateDpAsState(
        targetValue = if (tab.selected) 18.dp else 0.dp,
        animationSpec = tween(180),
        label = "tabIndicator",
    )

    Column(
        modifier = modifier
            .pressable(onClick = tab.onSelect, scaleWhenPressed = 0.94f)
            .semantics {
                role = Role.Tab
                selected = tab.selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(top = 2.dp),
        )
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .width(indicator)
                .height(2.dp)
                .clip(ShowcaseShapes.chip)
                .background(palette.primary),
        )
    }
}

/**
 * The expanded-width counterpart. Same destinations, same stacks — it only
 * changes where the chrome sits, never what it points at.
 */
@Composable
fun AppNavRail(
    tabs: List<TabItem>,
    modifier: Modifier = Modifier,
    wordmark: String = "FN",
) {
    val palette = showcaseColors
    Row(modifier = modifier.fillMaxHeight().background(palette.surface)) {
        Column(
            modifier = Modifier
                .width(88.dp)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(vertical = Tokens.Spacing.s24),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        ) {
            Text(
                text = wordmark,
                style = ShowcaseType.wordmark,
                color = palette.accent,
                modifier = Modifier.padding(bottom = Tokens.Spacing.s16),
            )
            tabs.forEach { tab ->
                RailCell(tab)
            }
        }
        Box(modifier = Modifier.width(Tokens.hairline).fillMaxHeight().background(palette.hairline))
    }
}

@Composable
private fun RailCell(tab: TabItem) {
    val palette = showcaseColors
    val content by animateColorAsState(
        if (tab.selected) palette.primary else palette.inkMuted,
        label = "railContent",
    )
    val container by animateColorAsState(
        if (tab.selected) palette.primarySoft else palette.surface,
        label = "railContainer",
    )

    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(ShowcaseShapes.control)
            .background(container)
            .pressable(onClick = tab.onSelect, scaleWhenPressed = 0.94f)
            .semantics {
                role = Role.Tab
                selected = tab.selected
            }
            .padding(vertical = Tokens.Spacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = content,
            modifier = Modifier.size(22.dp),
        )
        Text(text = tab.label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}
