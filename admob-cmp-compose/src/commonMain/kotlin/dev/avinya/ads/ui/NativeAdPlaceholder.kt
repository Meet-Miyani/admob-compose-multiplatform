package dev.avinya.ads.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal fun NativeAdPlaceholder(
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier, propagateMinConstraints = true) {
        content()
    }
}
