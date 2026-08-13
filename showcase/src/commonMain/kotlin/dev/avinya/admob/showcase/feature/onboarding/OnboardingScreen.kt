package dev.avinya.admob.showcase.feature.onboarding

import androidx.compose.material3.MaterialTheme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avinya.admob.showcase.StartupState
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.ui.kit.Eyebrow
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.PrimaryButton
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors

/**
 * First launch, in three short panels.
 *
 * The order is the contract: nothing touches the ads SDK until the reader
 * presses a button on the second panel. Then consent is gathered, ATT is
 * requested where it applies, the SDK initialises, and only after that can a
 * placement request an ad. The navigation shell holds the app-open suppressor
 * for this whole route, so nothing can interrupt it.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val graph = LocalAppGraph.current
    val viewModel: OnboardingViewModel = viewModel {
        OnboardingViewModel(graph.startup, graph.settings)
    }
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                OnboardingEffect.Finished -> onFinished()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(showcaseColors.canvas)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedContent(
            targetState = state.panel,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
            label = "onboardingPanel",
        ) { panel ->
            when (panel) {
                OnboardingPanel.Welcome -> WelcomePanel(
                    onContinue = { viewModel.onIntent(OnboardingIntent.Continue) },
                )

                OnboardingPanel.AdsChoice -> AdsChoicePanel(
                    busy = state.busy,
                    onEnableAds = { viewModel.onIntent(OnboardingIntent.EnableAds) },
                    onDecline = { viewModel.onIntent(OnboardingIntent.ContinueWithoutAds) },
                )

                OnboardingPanel.Preparing -> PreparingPanel(
                    state = state,
                    onRetry = { viewModel.onIntent(OnboardingIntent.Retry) },
                    onSkip = { viewModel.onIntent(OnboardingIntent.Finish) },
                )
            }
        }
    }
}

@Composable
private fun WelcomePanel(onContinue: () -> Unit) {
    val palette = showcaseColors
    PanelScaffold {
        Spacer(Modifier.height(Tokens.Spacing.s48))
        Eyebrow(text = "Fieldnotes")
        Text(
            text = "Culture and technology, read daily.",
            style = MaterialTheme.typography.displayMedium,
            color = palette.ink,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Text(
            text = "A working demonstration of the admob-cmp SDK, built as a real " +
                "reading app rather than a gallery of buttons.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.inkMuted,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        Rule(modifier = Modifier.padding(vertical = Tokens.Spacing.s16), strong = true)

        Highlight(
            icon = Icons.Rounded.AutoStories,
            title = "Read first",
            detail = "126 stories across six sections, stored locally and available offline.",
        )
        Highlight(
            icon = Icons.Rounded.Insights,
            title = "Ads in context",
            detail = "Every format appears where a real product would put it — and the " +
                "Inspector shows you exactly what the SDK did.",
        )
        Highlight(
            icon = Icons.Rounded.Shield,
            title = "Test ads only",
            detail = "Every placement uses an official Google test unit, with strict " +
                "test mode on.",
        )

        Spacer(Modifier.height(Tokens.Spacing.s24))
        PrimaryButton(
            label = "Get started",
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AdsChoicePanel(
    busy: Boolean,
    onEnableAds: () -> Unit,
    onDecline: () -> Unit,
) {
    val palette = showcaseColors
    PanelScaffold {
        Spacer(Modifier.height(Tokens.Spacing.s48))
        Eyebrow(text = "Before we start")
        Text(
            text = "Ads pay for Fieldnotes.",
            style = MaterialTheme.typography.displayMedium,
            color = palette.ink,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Text(
            text = "If you turn ads on, we'll ask for the consent your region requires " +
                "using Google's own privacy form — this app never stores that choice " +
                "itself. You can change it at any time in Profile.",
            style = MaterialTheme.typography.bodyLarge,
            color = palette.inkMuted,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        Rule(modifier = Modifier.padding(vertical = Tokens.Spacing.s16), strong = true)

        Highlight(
            icon = Icons.Rounded.Insights,
            title = "With ads",
            detail = "Sponsored stories in the feed, one ad inside long articles, and " +
                "rewarded ads you can watch to unlock premium stories.",
        )
        Highlight(
            icon = Icons.Rounded.Shield,
            title = "Without ads",
            detail = "Everything still works. Every screen renders exactly the same, " +
                "minus the ads.",
        )

        Spacer(Modifier.height(Tokens.Spacing.s24))
        PrimaryButton(
            label = "Turn on ads",
            onClick = onEnableAds,
            enabled = !busy,
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(
            label = "Continue without ads",
            onClick = onDecline,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PreparingPanel(
    state: OnboardingState,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    val palette = showcaseColors
    PanelScaffold {
        Spacer(Modifier.height(Tokens.Spacing.s48))
        Eyebrow(text = "Setting up")
        Text(
            text = "Getting things ready.",
            style = MaterialTheme.typography.displayMedium,
            color = palette.ink,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Text(
            text = "The order matters: consent resolves, then tracking, then the SDK " +
                "initialises. Requesting an ad before that would forfeit personalisation " +
                "for the whole session.",
            style = MaterialTheme.typography.bodyMedium,
            color = palette.inkMuted,
            modifier = Modifier.widthIn(max = 480.dp),
        )

        Rule(modifier = Modifier.padding(vertical = Tokens.Spacing.s16), strong = true)

        OnboardingStep.orderedSteps().forEach { step ->
            StepRow(
                label = step.label(),
                detail = step.detail(state),
                status = step.statusFor(state),
            )
        }

        val startup = state.startup
        if (state.step == OnboardingStep.Failed && startup is StartupState.Failed) {
            Text(
                text = startup.message,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.danger,
                modifier = Modifier.padding(top = Tokens.Spacing.s8),
            )
            PrimaryButton(
                label = "Try again",
                onClick = onRetry,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            GhostButton(
                label = "Skip and read anyway",
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PanelScaffold(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Tokens.Spacing.s24),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
    ) {
        content()
    }
}

@Composable
private fun Highlight(icon: ImageVector, title: String, detail: String) {
    val palette = showcaseColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = palette.primary,
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = palette.ink)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
        }
    }
}

@Composable
private fun StepRow(label: String, detail: String, status: StepStatus) {
    val palette = showcaseColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Tokens.Spacing.s8),
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
    ) {
        Box(
            modifier = Modifier.size(20.dp).padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (status) {
                StepStatus.Active -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = palette.primary,
                )

                StepStatus.Complete -> Dot(palette.success)
                StepStatus.Skipped -> Dot(palette.inkFaint)
                StepStatus.Pending -> Dot(palette.hairlineStrong)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = label, style = MaterialTheme.typography.titleSmall, color = palette.ink)
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
        }
    }
}

@Composable
private fun Dot(color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
}

private enum class StepStatus { Pending, Active, Complete, Skipped }

private fun OnboardingStep.label(): String = when (this) {
    OnboardingStep.Consent -> "Consent"
    OnboardingStep.Tracking -> "Tracking permission"
    OnboardingStep.Initializing -> "Starting the ads SDK"
    OnboardingStep.Done -> "Ready"
    OnboardingStep.Failed -> "Failed"
}

private fun OnboardingStep.detail(state: OnboardingState): String = when (this) {
    OnboardingStep.Consent -> "Google's UMP form, where your region requires one"
    OnboardingStep.Tracking -> when (state.tracking) {
        // Shown, not hidden: a reader needs to learn ATT is iOS-only.
        TrackingStepDisplay.NotApplicable -> "Not applicable on this platform"
        TrackingStepDisplay.Pending -> "Waiting for the system prompt"
        TrackingStepDisplay.Granted -> "Granted — personalised ads available"
        TrackingStepDisplay.Refused -> "Refused — non-personalised ads only"
    }
    OnboardingStep.Initializing -> "Google Mobile Ads, after consent resolves"
    OnboardingStep.Done -> "Done"
    OnboardingStep.Failed -> "Failed"
}

private fun OnboardingStep.statusFor(state: OnboardingState): StepStatus {
    if (this == OnboardingStep.Tracking &&
        state.tracking == TrackingStepDisplay.NotApplicable
    ) {
        return StepStatus.Skipped
    }
    val order = OnboardingStep.orderedSteps()
    val currentIndex = order.indexOf(state.step)
    val thisIndex = order.indexOf(this)
    return when {
        state.step == OnboardingStep.Done -> StepStatus.Complete
        currentIndex < 0 -> StepStatus.Pending
        thisIndex < currentIndex -> StepStatus.Complete
        thisIndex == currentIndex -> StepStatus.Active
        else -> StepStatus.Pending
    }
}
