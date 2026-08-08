package dev.avinya.admob.showcase.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.FieldnotesTokens

data class ArticleCardModel(
    val id: String,
    val title: String,
    val author: String,
    val section: String,
    val readTimeMinutes: Int,
    val snippet: String,
    val isPremium: Boolean,
)

enum class ArticleCardTreatment { Hero, Standard, Compact }

/**
 * Editorial article card with accessible content hierarchy.
 * Merges primary content into a single click target while keeping
 * the bookmark action as a separate >=48.dp touch target.
 */
@Composable
fun ArticleCard(
    article: ArticleCardModel,
    treatment: ArticleCardTreatment,
    onClick: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier,
    isBookmarked: Boolean = false,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(FieldnotesTokens.cardRadius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            modifier = Modifier.padding(
                when (treatment) {
                    ArticleCardTreatment.Hero -> FieldnotesTokens.Spacing.s24
                    ArticleCardTreatment.Standard -> FieldnotesTokens.Spacing.s16
                    ArticleCardTreatment.Compact -> FieldnotesTokens.Spacing.s12
                }
            ),
            verticalArrangement = Arrangement.spacedBy(
                when (treatment) {
                    ArticleCardTreatment.Hero -> FieldnotesTokens.Spacing.s12
                    ArticleCardTreatment.Standard -> FieldnotesTokens.Spacing.s8
                    ArticleCardTreatment.Compact -> FieldnotesTokens.Spacing.s4
                }
            ),
        ) {
            // Header Row: Section badge, read time, premium indicator, and bookmark button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FieldnotesTokens.Spacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = article.section.uppercase(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }

                    if (article.isPremium) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) {
                            Text(
                                text = "PREMIUM",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Text(
                        text = "${article.readTimeMinutes} MIN READ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Minimum 48.dp touch target bookmark button
                IconButton(
                    onClick = onBookmark,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            role = Role.Button
                            contentDescription = if (isBookmarked) "Remove bookmark for ${article.title}" else "Bookmark ${article.title}"
                        },
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            // Title
            Text(
                text = article.title,
                style = when (treatment) {
                    ArticleCardTreatment.Hero -> MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Serif)
                    ArticleCardTreatment.Standard -> MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif)
                    ArticleCardTreatment.Compact -> MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif)
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (treatment == ArticleCardTreatment.Compact) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )

            // Snippet (omitted or 1-line for Compact, 2-line for Standard, full for Hero)
            if (treatment != ArticleCardTreatment.Compact && article.snippet.isNotBlank()) {
                Text(
                    text = article.snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (treatment == ArticleCardTreatment.Hero) 4 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Footer: Author
            if (treatment != ArticleCardTreatment.Compact && article.author.isNotBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FieldnotesTokens.Spacing.s4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Person,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = article.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
