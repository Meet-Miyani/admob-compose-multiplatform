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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * exactly when there is something to inspect. "Ads may load" needs consent too:
 * the status stays `Ready` after consent is withdrawn, and the dot then holds
 * the accent colour without pulsing, because nothing is in progress.
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
    val canRequestAds by adManager.consent.canRequestAds.collectAsState()

    val (dotColor, pulsing) = when (status) {
        is AdManagerStatus.Ready -> (if (canRequestAds) palette.success else palette.accent) to false
        is AdManagerStatus.Initializing -> palette.accent to true
        is AdManagerStatus.ConsentRequired -> palette.accent to true
        is AdManagerStatus.Failed -> palette.danger to false
        is AdManagerStatus.Disabled -> palette.inkFaint to false
        is AdManagerStatus.Idle -> palette.inkFaint to false
    }

    // Only a pulsing dot runs an animation; a steady one requests no frames.
    val pulse = if (pulsing) rememberPulseAlpha() else null

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
                .inspectorStatusDot(color = dotColor, pulse = pulse),
        )
    }
}

/**
 * The status dot: an 8 dp circle in [color], faded by [pulse] when there is one.
 *
 * The chain has the same elements in every status — only their values change.
 * `Modifier.alpha(1f)` adds no layer, so the previous trailing
 * `.alpha(if (pulsing) pulse else 1f)` removed a layer from the end of the
 * chain when the SDK became ready. Compose then drew the background into a
 * reused layer that it never re-recorded, and on iOS and Android the dot
 * vanished for the life of that screen on every launch that composed the
 * header before initialisation ended. Here one layer is always present,
 * carries both the clip and the pulse, and sits *before* the background so the
 * pulse actually fades the dot. [pulse] is read inside the layer block, so a
 * pulsing dot updates a layer property each frame instead of recomposing.
 */
internal fun Modifier.inspectorStatusDot(color: Color, pulse: State<Float>?): Modifier =
    this
        .offset(x = (-10).dp, y = 10.dp)
        .size(8.dp)
        .graphicsLayer {
            alpha = pulse?.value ?: 1f
            shape = CircleShape
            clip = true
        }
        .background(color)

@Composable
private fun rememberPulseAlpha(): State<Float> =
    rememberInfiniteTransition(label = "inspectorPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "inspectorPulseAlpha",
    )
