package dev.avinya.admob.showcase.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.FieldnotesTokens

/**
 * Minimal flat editorial loading indicator.
 * Uses flat hierarchy, zero rounded card enclosures, and no glassmorphism.
 */
@Composable
fun EditorialLoadingState(
    message: String = "Loading editorial content…",
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FieldnotesTokens.Spacing.s24, horizontal = FieldnotesTokens.Spacing.s16),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.size(FieldnotesTokens.Spacing.s12))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Flat editorial empty state component.
 */
@Composable
fun EditorialEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FieldnotesTokens.Spacing.s32, horizontal = FieldnotesTokens.Spacing.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.height(FieldnotesTokens.Spacing.s12))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(FieldnotesTokens.Spacing.s8))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(FieldnotesTokens.Spacing.s16))
            OutlinedButton(
                onClick = onActionClick,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Flat editorial error state component.
 */
@Composable
fun EditorialErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Unable to load content",
    retryLabel: String = "Try Again",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FieldnotesTokens.Spacing.s32, horizontal = FieldnotesTokens.Spacing.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(32.dp),
        )
        Spacer(modifier = Modifier.height(FieldnotesTokens.Spacing.s12))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(FieldnotesTokens.Spacing.s8))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(FieldnotesTokens.Spacing.s16))
        OutlinedButton(
            onClick = onRetry,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        ) {
            Text(
                text = retryLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
