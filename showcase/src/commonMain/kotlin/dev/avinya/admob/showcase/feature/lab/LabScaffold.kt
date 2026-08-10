package dev.avinya.admob.showcase.feature.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.avinya.admob.showcase.ui.kit.AppHeader
import dev.avinya.admob.showcase.ui.kit.SectionTitle
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.AdLoadState
import dev.avinya.ads.FullScreenAdController
import kotlin.time.Duration

/**
 * Shared frame for every SDK Lab screen.
 *
 * The header always takes a back action. Lab routes are pushed above a tab's
 * retained stack, and iOS offers no system gesture out of a Compose
 * `NavDisplay`, so a Lab screen without one is a dead end — which is what all
 * five of them were before.
 */
@Composable
fun LabScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(showcaseColors.canvas),
    ) {
        AppHeader(title = title, subtitle = subtitle, onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Tokens.Spacing.gutterCompact,
                end = Tokens.Spacing.gutterCompact,
                top = Tokens.Spacing.s16,
                bottom = Tokens.Spacing.s48,
            ),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s24),
            content = content,
        )
    }
}

/** A titled, explained block inside a Lab screen. */
@Composable
fun LabSection(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
    ) {
        SectionTitle(title = title, caption = description)
        content()
    }
}

/** Monospaced status text — readiness labels, last events, slot states. */
@Composable
fun LabStatus(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = ShowcaseType.numeric,
        color = showcaseColors.inkMuted,
        modifier = modifier.padding(vertical = Tokens.Spacing.s4),
    )
}

/**
 * A full-screen controller's readiness, as observable state.
 *
 * `FullScreenAdController.isReady()` is a plain function over
 * `availability()`, not snapshot state, so reading it directly in a composable
 * captures a value that never updates — the Show button stays disabled after
 * the ad has actually loaded, which is exactly the bug this Lab existed to
 * disprove. Deriving from `loadState`, which *is* a `StateFlow`, gives Compose
 * something to observe.
 *
 * The one case this does not catch is a cached ad expiring with no load
 * activity; `availability().expiresIn` is surfaced next to it so the gap is
 * visible rather than hidden.
 */
@Composable
fun rememberAdReadiness(controller: FullScreenAdController): AdReadiness {
    val loadState by controller.loadState.collectAsState()
    return remember(controller, loadState) {
        val availability = controller.availability()
        AdReadiness(
            loadState = loadState,
            isReady = availability.isReady,
            cachedCount = availability.cachedCount,
            expiresIn = availability.expiresIn,
        )
    }
}

/** A snapshot of one full-screen controller, safe to read in composition. */
@Immutable
data class AdReadiness(
    val loadState: AdLoadState,
    val isReady: Boolean,
    val cachedCount: Int,
    val expiresIn: Duration?,
) {
    val isLoading: Boolean get() = loadState is AdLoadState.Loading

    /** A single honest line: what the controller is doing right now. */
    fun label(): String = when {
        loadState is AdLoadState.Loading -> "loading…"
        loadState is AdLoadState.Failed -> "failed: ${loadState.error.message}"
        isReady -> buildString {
            append("ready · cached $cachedCount")
            expiresIn?.let { append(" · expires in ${it.inWholeSeconds}s") }
        }
        loadState is AdLoadState.Loaded -> "loaded but no longer available"
        else -> "idle — load first"
    }
}
