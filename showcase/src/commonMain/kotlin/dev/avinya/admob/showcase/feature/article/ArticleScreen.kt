package dev.avinya.admob.showcase.feature.article

import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avinya.admob.showcase.data.db.entity.ArticleEntity
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.di.LocalAppOpenSuppressor
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.article.ArticleBlock
import dev.avinya.admob.showcase.domain.article.buildArticleBlocks
import dev.avinya.admob.showcase.feature.rewards.message
import dev.avinya.admob.showcase.ui.ad.rememberInlineAdLayout
import dev.avinya.admob.showcase.ui.inspector.InspectorEntryPoint
import dev.avinya.admob.showcase.ui.inspector.InspectorHost
import dev.avinya.admob.showcase.ui.kit.AppHeader
import dev.avinya.admob.showcase.ui.kit.ArticleCover
import dev.avinya.admob.showcase.ui.kit.Badge
import dev.avinya.admob.showcase.ui.kit.EmptyState
import dev.avinya.admob.showcase.ui.kit.GhostButton
import dev.avinya.admob.showcase.ui.kit.IconAction
import dev.avinya.admob.showcase.ui.kit.LoadingState
import dev.avinya.admob.showcase.ui.kit.MetaLine
import dev.avinya.admob.showcase.ui.kit.NativeAdCard
import dev.avinya.admob.showcase.ui.kit.Plane
import dev.avinya.admob.showcase.ui.kit.PrimaryButton
import dev.avinya.admob.showcase.ui.kit.ProgressRule
import dev.avinya.admob.showcase.ui.kit.Rule
import dev.avinya.admob.showcase.ui.kit.SectionEyebrow
import dev.avinya.admob.showcase.ui.theme.ShowcaseShapes
import dev.avinya.admob.showcase.ui.theme.ShowcaseType
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.ui.BannerAdView
import dev.avinya.ads.ui.rememberNativeAdSlotSession
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private const val PROGRESS_DEBOUNCE_MS: Long = 500

/**
 * The reader — a full-screen route with no tab chrome.
 *
 * Four SDK surfaces meet here, and each is gated:
 * an inline native after real content, a collapsible bottom banner, a rewarded
 * ad that unlocks premium stories, and a frequency-capped interstitial on the
 * way out. Turn ads off in Profile and every one of them disappears, leaving a
 * complete article behind.
 */
@Composable
fun ArticleScreen(articleId: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val adManager = LocalAdManager.current
    val suppressor = LocalAppOpenSuppressor.current
    val sessionId = remember { graph.clock.nowMillis().toString() }

    val viewModel: ArticleViewModel = viewModel {
        ArticleViewModel(
            articles = graph.articles,
            settings = graph.settings,
            adState = graph.adState,
            wallet = graph.wallet,
            adManager = adManager,
            suppressor = suppressor,
            clock = graph.clock,
            sessionId = sessionId,
            articleId = articleId,
        )
    }
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }

    val placements = remember {
        listOf(
            ShowcasePlacements.articleNative,
            ShowcasePlacements.articleBanner,
            ShowcasePlacements.articleInterstitial,
            ShowcasePlacements.articleUnlockRewarded,
        )
    }

    // Back always routes through the ViewModel so the interstitial decision is
    // settled before navigation happens — otherwise the pop races the ad.
    val requestLeave = { viewModel.onIntent(ArticleIntent.Close) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ArticleEffect.Leave -> onBack()
                is ArticleEffect.Notice -> snackbar.showSnackbar(effect.message)
                is ArticleEffect.UnlockResult -> snackbar.showSnackbar(effect.outcome.message())
                // Recorded for the Inspector; not worth a toast on every exit.
                is ArticleEffect.InterstitialSuppressed -> Unit
            }
        }
    }

    InspectorHost(placements = placements) { inspectorEnabled, onOpenInspector ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(showcaseColors.canvas),
        ) {
            val article = state.article
            when {
                article != null -> ArticleBody(
                    state = state,
                    article = article,
                    listState = listState,
                    onBack = requestLeave,
                    onToggleBookmark = { viewModel.onIntent(ArticleIntent.ToggleBookmark) },
                    onProgress = { viewModel.onIntent(ArticleIntent.ProgressUpdated(it)) },
                    onUnlockWithAd = { viewModel.onIntent(ArticleIntent.UnlockWithAd) },
                    onUnlockWithCoins = { viewModel.onIntent(ArticleIntent.UnlockWithCoins) },
                    inspectorEnabled = inspectorEnabled,
                    onOpenInspector = onOpenInspector,
                )

                state.loading -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(showcaseColors.canvas),
                ) {
                    AppHeader(title = "", onBack = onBack)
                    LoadingState(
                        label = "Opening story",
                        modifier = Modifier.padding(Tokens.Spacing.gutterCompact),
                    )
                }

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(showcaseColors.canvas),
                ) {
                    AppHeader(title = "Not found", onBack = onBack)
                    EmptyState(
                        title = "Article not found",
                        message = "This story is no longer in your library.",
                        actionLabel = "Go back",
                        onAction = onBack,
                    )
                }
            }

            SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ArticleBody(
    state: ArticleState,
    article: ArticleEntity,
    listState: LazyListState,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onProgress: (Float) -> Unit,
    onUnlockWithAd: () -> Unit,
    onUnlockWithCoins: () -> Unit,
    inspectorEnabled: Boolean,
    onOpenInspector: () -> Unit,
) {
    val palette = showcaseColors
    val locked = state.isLocked
    val blocks = remember(article.body, article.id, locked) {
        if (locked) {
            // A locked story shows its opening and stops. The teaser is real
            // content, not a blur: the reader can judge whether it is worth an
            // ad, which is the only thing that makes the exchange fair.
            buildArticleBlocks(article.body, article.id)
                .filterIsInstance<ArticleBlock.Paragraph>()
                .take(2)
        } else {
            buildArticleBlocks(article.body, article.id)
        }
    }
    val adBlockIndex = remember(blocks) { blocks.indexOfFirst { it is ArticleBlock.NativeAd } }
    val showInlineAd = state.canShowAds && !locked
    val inlineLayout = rememberInlineAdLayout()

    val inlineSlot = remember(article.id) {
        NativeAdSlot(
            key = "article:${article.id}:inline-1",
            placement = ShowcasePlacements.articleNative,
        )
    }
    val nativeSession = rememberNativeAdSlotSession(
        sessionKey = "article:${article.id}",
        slot = inlineSlot,
    )

    // paragraphCountUpTo[i] = paragraphs among blocks[0 until i], so a scroll
    // position looks up its paragraph count in O(1) instead of rescanning
    // `blocks` from the top on every scroll-driven recomposition.
    val paragraphCountUpTo = remember(blocks) {
        val counts = IntArray(blocks.size + 1)
        for (i in blocks.indices) {
            counts[i + 1] = counts[i] + if (blocks[i] is ArticleBlock.Paragraph) 1 else 0
        }
        counts
    }
    val paragraphCount = paragraphCountUpTo.last()
    val fraction by remember(listState, paragraphCount) {
        derivedStateOf {
            if (paragraphCount <= 1) {
                0f
            } else {
                val firstVisible = listState.firstVisibleItemIndex.coerceIn(0, blocks.size)
                val paragraphsAbove = paragraphCountUpTo[firstVisible]
                (paragraphsAbove.toFloat() / (paragraphCount - 1)).coerceIn(0f, 1f)
            }
        }
    }

    LaunchedEffect(article.id, locked) {
        if (!locked && state.initialProgress > 0f && blocks.isNotEmpty()) {
            val target = (state.initialProgress * (paragraphCount - 1))
                .toInt()
                .coerceIn(0, (paragraphCount - 1).coerceAtLeast(0))
            var itemIndex = 0
            var seen = 0
            for ((index, block) in blocks.withIndex()) {
                if (block is ArticleBlock.Paragraph) seen++
                if (seen > target) break
                itemIndex = index
            }
            // +1 for the header item that precedes the blocks.
            listState.scrollToItem(itemIndex + 1)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { fraction }.debounce(PROGRESS_DEBOUNCE_MS).collect(onProgress)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.canvas),
    ) {
        AppHeader(
            title = article.title,
            onBack = onBack,
            actions = {
                InspectorEntryPoint(enabled = inspectorEnabled, onOpen = onOpenInspector)
                IconAction(
                    icon = if (state.bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = if (state.bookmarked) "Remove bookmark" else "Bookmark article",
                    onClick = onToggleBookmark,
                    tint = if (state.bookmarked) palette.primary else palette.ink,
                )
            },
        )
        ProgressRule(fraction = fraction)

        LazyColumn(
            modifier = Modifier.fillMaxSize().weight(1f),
            state = listState,
            contentPadding = PaddingValues(
                start = Tokens.Spacing.gutterCompact,
                end = Tokens.Spacing.gutterCompact,
                top = Tokens.Spacing.s24,
                bottom = Tokens.Spacing.s32,
            ),
        ) {
            item(key = "article_header") { ArticleHeader(article) }

            itemsIndexed(
                items = blocks,
                key = { index, block ->
                    when (block) {
                        is ArticleBlock.Paragraph -> "p-$index"
                        is ArticleBlock.NativeAd -> block.slotKey
                    }
                },
            ) { index, block ->
                when (block) {
                    is ArticleBlock.Paragraph -> Paragraph(block.text)
                    is ArticleBlock.NativeAd -> if (showInlineAd && adBlockIndex == index) {
                        NativeAdCard(
                            session = nativeSession,
                            slotKey = block.slotKey,
                            placement = ShowcasePlacements.articleNative,
                            layout = inlineLayout,
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = Tokens.readingMeasure)
                                .padding(vertical = Tokens.Spacing.s16),
                        )
                    }
                }
            }

            if (locked) {
                item(key = "paywall") {
                    UnlockCard(
                        costCoins = article.unlockCostCoins,
                        canWatch = state.canShowAds,
                        busy = state.unlocking,
                        onWatch = onUnlockWithAd,
                        onSpendCoins = onUnlockWithCoins,
                    )
                }
            }
        }

        // The banner is anchored below the content, outside the scrolling
        // column, and consumes its own navigation-bar inset — so it never
        // overlaps a paragraph and never double-pads the page.
        if (state.canShowAds) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                Rule()
                BannerAdView(
                    placement = ShowcasePlacements.articleBanner,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * The premium gate.
 *
 * Two ways through, priced honestly and side by side: watch one rewarded ad,
 * or spend coins already earned. Neither is a dark pattern — the reader has
 * read the opening and can simply leave.
 */
@Composable
private fun UnlockCard(
    costCoins: Int,
    canWatch: Boolean,
    busy: Boolean,
    onWatch: () -> Unit,
    onSpendCoins: () -> Unit,
) {
    val palette = showcaseColors
    Plane(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = Tokens.readingMeasure)
            .padding(top = Tokens.Spacing.s24),
    ) {
        Column(
            modifier = Modifier.padding(Tokens.Spacing.s20),
            verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
        ) {
            Badge(text = "Premium")
            Text(
                text = "Keep reading",
                style = MaterialTheme.typography.headlineSmall,
                color = palette.ink,
            )
            Text(
                text = if (canWatch) {
                    "Watch one short ad to unlock the rest of this story, or spend " +
                        "coins you've already earned. It stays unlocked in your Library."
                } else {
                    "Ads are switched off, so unlocking with an ad isn't available. " +
                        "You can still spend coins you've earned."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.inkMuted,
            )
            PrimaryButton(
                label = "Watch an ad to unlock",
                onClick = onWatch,
                enabled = canWatch && !busy,
                loading = busy,
                icon = Icons.Rounded.PlayArrow,
                modifier = Modifier.fillMaxWidth(),
            )
            GhostButton(
                label = "Unlock for $costCoins coins",
                onClick = onSpendCoins,
                enabled = !busy,
                icon = Icons.Rounded.Lock,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = showcaseColors.ink,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = Tokens.readingMeasure)
            .padding(vertical = Tokens.Spacing.s12),
    )
}

@Composable
private fun ArticleHeader(article: ArticleEntity) {
    val palette = showcaseColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = Tokens.readingMeasure)
            .padding(bottom = Tokens.Spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.s12),
    ) {
        ArticleCover(
            articleId = article.id,
            section = article.section,
            aspectRatio = 2f,
            modifier = Modifier
                .fillMaxWidth()
                .clip(ShowcaseShapes.media),
        )

        ArticleLabels(article)

        Text(text = article.title, style = MaterialTheme.typography.displaySmall, color = palette.ink)

        MetaLine(
            parts = listOf(
                article.author,
                "${article.readTimeMin} min read",
                formatRelativeDate(article.feedOrdinal),
            ),
        )

        Rule(strong = true)
    }
}

@Composable
private fun ArticleLabels(article: ArticleEntity) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionEyebrow(article.section)
        if (article.isPremium) Badge(text = "Premium")
    }
}

private fun formatRelativeDate(feedOrdinal: Int): String = when {
    feedOrdinal == 0 -> "Just now"
    feedOrdinal < 24 -> "${feedOrdinal}h ago"
    feedOrdinal < 48 -> "1 day ago"
    else -> "${feedOrdinal / 24} days ago"
}
