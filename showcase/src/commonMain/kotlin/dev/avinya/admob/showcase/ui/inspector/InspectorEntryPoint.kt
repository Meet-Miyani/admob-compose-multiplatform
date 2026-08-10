package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.kit.IconAction
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdManagerStatus
import dev.avinya.ads.LocalAdManager

/**
 * The telemetry Inspector's trigger, as a header action.
 *
 * It is an icon, not a bar. The previous version was a full-width `Surface`
 * that drew its own copy of the screen title, and every caller passed it into
 * the header's action slot — so each screen rendered its title twice and the
 * header's layout was fighting a `fillMaxWidth()` child.
 *
 * The status dot is the one piece of chrome worth keeping: it pulses while the
 * SDK is working, sits steady green when ads may load, and turns to the danger
 * colour when initialisation has failed — so the Inspector is discoverable
 * exactly when there is something to inspect.
 */
@Composable
fun InspectorEntryPoint(
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    val palette = showcaseColors
    val adManager = LocalAdManager.current
    val status by adManager.status.collectAsState()

    val (dotColor, pulsing) = when (status) {
        is AdManagerStatus.Ready -> palette.success to false
        is AdManagerStatus.Initializing -> palette.accent to true
        is AdManagerStatus.ConsentRequired -> palette.accent to true
        is AdManagerStatus.Failed -> palette.danger to false
        is AdManagerStatus.Disabled -> palette.inkFaint to false
        is AdManagerStatus.Idle -> palette.inkFaint to false
    }

    val transition = rememberInfiniteTransition(label = "inspectorPulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inspectorPulseAlpha",
    )

    Box(modifier = modifier) {
        IconAction(
            icon = Icons.Rounded.Analytics,
            contentDescription = "Open telemetry inspector",
            onClick = onOpen,
            tint = palette.ink,
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-10).dp, y = 10.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
                .alpha(if (pulsing) pulse else 1f),
        )
    }
}
