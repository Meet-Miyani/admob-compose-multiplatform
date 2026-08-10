package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.ads.AdPlacement

/**
 * Every screen with ad placements to inspect wires the same three things:
 * whether the Inspector is enabled, a `showInspector` toggle, and the
 * [LocalInspectorPlacements] scope [InspectorSheet] reads from. This
 * collects them once so a screen only supplies its own [placements] and
 * consumes `inspectorEnabled`/`onOpenInspector` — normally passed straight
 * into [dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint] — from
 * [content].
 */
@Composable
fun InspectorHost(
    placements: List<AdPlacement>,
    content: @Composable (inspectorEnabled: Boolean, onOpenInspector: () -> Unit) -> Unit,
) {
    val graph = LocalAppGraph.current
    val inspectorEnabled by graph.settings.inspectorEnabled.collectAsState(initial = true)
    var showInspector by remember { mutableStateOf(false) }

    CompositionLocalProvider(LocalInspectorPlacements provides placements) {
        content(inspectorEnabled) { showInspector = true }
    }

    if (showInspector) {
        InspectorSheet(placements = placements, onDismiss = { showInspector = false })
    }
}
