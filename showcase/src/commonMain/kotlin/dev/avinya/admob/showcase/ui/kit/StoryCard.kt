package dev.avinya.admob.showcase.ui.kit

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

data class StoryCardModel(
    val id: String,
    val title: String,
    val author: String,
    val section: String,
    val readTimeMinutes: Int,
    val snippet: String,
    val isPremium: Boolean,
)

/**
 * How prominent a story is in its list.
 *
 * - [Hero] leads a feed: full-bleed cover, display headline, standfirst.
 * - [Standard] is the workhorse row: cover thumbnail beside the headline.
 * - [Compact] is a dense list entry with no artwork — Library, search results.
 */
enum class StoryTreatment { Hero, Standard, Compact }

/**
 * One editorial story.
 *
 * The card is a single click target with the bookmark broken out as its own
 * labelled ≥48.dp action, so a screen reader announces "open article" and
 * "bookmark" separately rather than one ambiguous blob.
 */
@Composable
fun StoryCard(
    story: StoryCardModel,
    treatment: StoryTreatment,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    isBookmarked: Boolean = false,
) {
    when (treatment) {
        StoryTreatment.Hero -> HeroStory(story, onClick, onBookmark, isBookmarked, modifier)
        StoryTreatment.Standard -> StandardStory(story, onClick, onBookmark, isBookmarked, modifier)
        StoryTreatment.Compact -> CompactStory(story, onClick, onBookmark, isBookmarked, modifier)
    }
}

@Composable
private fun HeroStory(
    story: StoryCardModel,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
    isBookmarked: Boolean,
    modifier: Modifier,
) {
    val palette = showcaseColors
    Plane(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick = onClick)
            .semantics { role = Role.Button },
        elevation = Tokens.Elevation.floating,
    ) {
        Column {
            ArticleCover(
                articleId = story.id,
                section = story.section,
                aspectRatio = 16f / 9f,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier.padding(Tokens.Spacing.s20),
                verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
            ) {
                StoryLabels(story)
                Text(
                    text = story.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = palette.ink,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (story.snippet.isNotBlank()) {
                    Text(
                        text = story.snippet,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.inkMuted,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaLine(
                        parts = listOf(story.author, "${story.readTimeMinutes} min read"),
                        modifier = Modifier.weight(1f),
                    )
                    BookmarkAction(story, isBookmarked, onBookmark)
                }
            }
        }
    }
}

@Composable
private fun StandardStory(
    story: StoryCardModel,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
    isBookmarked: Boolean,
    modifier: Modifier,
) {
    val palette = showcaseColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, scaleWhenPressed = 0.99f)
            .semantics { role = Role.Button }
            .padding(vertical = Tokens.Spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s16),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        ) {
            StoryLabels(story)
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleLarge,
                color = palette.ink,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (story.snippet.isNotBlank()) {
                Text(
                    text = story.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaLine(
                    parts = listOf(story.author, "${story.readTimeMinutes} min"),
                    modifier = Modifier.weight(1f),
                )
                BookmarkAction(story, isBookmarked, onBookmark)
            }
        }

        ArticleCover(
            articleId = story.id,
            section = story.section,
            aspectRatio = 1f,
            monogram = false,
            modifier = Modifier
                .width(Tokens.feedThumbnail)
                .clip(ShowcaseShapes.media),
        )
    }
}

@Composable
private fun CompactStory(
    story: StoryCardModel,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
    isBookmarked: Boolean,
    modifier: Modifier,
) {
    val palette = showcaseColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, scaleWhenPressed = 0.99f)
            .semantics { role = Role.Button }
            .padding(vertical = Tokens.Spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A colour-coded spine instead of artwork: it keeps the section
        // legible at this density without a second image to decode.
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(40.dp)
                .clip(ShowcaseShapes.chip)
                .background(dev.avinya.admob.showcase.ui.theme.sectionColor(story.section)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s4),
        ) {
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleMedium,
                color = palette.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MetaLine(
                parts = listOf(story.section, "${story.readTimeMinutes} min"),
            )
        }
        BookmarkAction(story, isBookmarked, onBookmark)
    }
}

@Composable
private fun StoryLabels(story: StoryCardModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionEyebrow(story.section)
        if (story.isPremium) {
            Badge(text = "Premium")
        }
    }
}

@Composable
private fun BookmarkAction(
    story: StoryCardModel,
    isBookmarked: Boolean,
    onBookmark: () -> Unit,
) {
    IconAction(
        icon = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
        contentDescription = if (isBookmarked) {
            "Remove bookmark for ${story.title}"
        } else {
            "Bookmark ${story.title}"
        },
        onClick = onBookmark,
        tint = if (isBookmarked) showcaseColors.primary else showcaseColors.inkFaint,
        modifier = Modifier.size(Tokens.touchTarget),
    )
}
