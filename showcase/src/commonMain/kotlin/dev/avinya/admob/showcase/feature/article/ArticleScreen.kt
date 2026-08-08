package dev.avinya.admob.showcase.feature.article

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.avinya.admob.showcase.data.db.entity.ArticleEntity
import dev.avinya.admob.showcase.di.LocalAppGraph
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.ad.SuppressionReason
import dev.avinya.admob.showcase.domain.article.inlineAdSlotIndex
import dev.avinya.admob.showcase.domain.article.splitParagraphs
import dev.avinya.admob.showcase.ui.ad.AdEffectHandler
import dev.avinya.admob.showcase.ui.ad.inlineNativeAdLayout
import dev.avinya.admob.showcase.ui.inspector.InspectorSheet
import dev.avinya.admob.showcase.ui.inspector.LocalInspectorPlacements
import dev.avinya.ads.LocalAdManager
import dev.avinya.ads.nativead.NativeAdSlot
import dev.avinya.ads.ui.BannerAdView
import dev.avinya.ads.ui.NativeAdView
import dev.avinya.ads.ui.rememberNativeAdFeedSession
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private const val PROGRESS_DEBOUNCE_MS: Long = 500

@Composable
fun ArticleScreen(articleId: String, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val adManager = LocalAdManager.current
    val viewModel: ArticleViewModel = viewModel {
        ArticleViewModel(
            articles = graph.articles,
            settings = graph.settings,
            adState = graph.adState,
            telemetry = graph.telemetry,
            adManager = adManager,
            clock = graph.clock,
            articleId = articleId,
        )
    }
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    val inspectorEnabled by graph.settings.inspectorEnabled.collectAsState(initial = true)
    var showInspector by remember { mutableStateOf(false) }
    val placements = remember {
        listOf(
            ShowcasePlacements.articleNative,
            ShowcasePlacements.articleBanner,
            ShowcasePlacements.articleInterstitial,
        )
    }

    AdEffectHandler(
        effects = viewModel.effects,
        onSuppressed = { reason: SuppressionReason ->
            println("Article ad suppressed: $reason")
        },
        onNavigateBack = onBack,
        onShown = { viewModel.onInterstitialShown() },
    )

    CompositionLocalProvider(LocalInspectorPlacements provides placements) {
        when {
            state.article != null -> ArticleBody(
                article = state.article!!,
                bookmarked = state.bookmarked,
                initialProgress = state.initialProgress,
                adsEnabled = state.adsEnabled,
                sdkReady = state.sdkReady,
                listState = listState,
                onBack = { viewModel.onIntent(ArticleIntent.Close) },
                onToggleBookmark = { viewModel.onIntent(ArticleIntent.ToggleBookmark) },
                onProgress = { viewModel.onIntent(ArticleIntent.ProgressUpdated(it)) },
                inspectorEnabled = inspectorEnabled,
                onOpenInspector = { showInspector = true },
            )
            state.loading -> CenteredMessage("Loading…")
            else -> CenteredMessage("Article not found")
        }
    }

    if (showInspector) {
        InspectorSheet(placements = placements, onDismiss = { showInspector = false })
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ArticleBody(
    article: ArticleEntity,
    bookmarked: Boolean,
    initialProgress: Float,
    adsEnabled: Boolean,
    sdkReady: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    onProgress: (Float) -> Unit,
    inspectorEnabled: Boolean,
    onOpenInspector: () -> Unit,
) {
    val paragraphs = remember(article.body) { splitParagraphs(article.body) }
    val adIndex = remember(paragraphs.size) { inlineAdSlotIndex(paragraphs.size) }
    val showInlineAd = adsEnabled && sdkReady
    val showAdRow = adIndex != null && showInlineAd
    val effectiveAdIndex = adIndex?.takeIf { showInlineAd } ?: Int.MAX_VALUE
    val inlineSlot = remember(article.id) {
        NativeAdSlot("inline-after-paragraph-3:${article.id}", ShowcasePlacements.articleNative)
    }
    val nativeSession = rememberNativeAdFeedSession(
        sessionKey = "showcase-article:${article.id}",
        listState = listState,
        // LazyColumn has a header before its paragraph/ad rows.
        itemCount = 1 + paragraphs.size + if (showAdRow) 1 else 0,
        slotAt = { index -> inlineSlot.takeIf { showAdRow && index == effectiveAdIndex + 1 } },
    )

    val fraction by remember(listState, paragraphs.size) {
        derivedStateOf {
            val total = paragraphs.size
            if (total <= 1) 0f
            else {
                val firstVisible = listState.firstVisibleItemIndex
                val adjustedIndex = when {
                    firstVisible <= 0 -> 0
                    showAdRow && firstVisible > (effectiveAdIndex + 1) -> firstVisible - 2
                    showAdRow && firstVisible == (effectiveAdIndex + 1) -> effectiveAdIndex
                    else -> firstVisible - 1
                }
                (adjustedIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
            }
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 150),
        label = "ReadingProgressBar",
    )

    LaunchedEffect(article.id) {
        if (initialProgress > 0f && paragraphs.isNotEmpty()) {
            val target = (initialProgress * (paragraphs.size - 1))
                .toInt()
                .coerceIn(0, paragraphs.lastIndex)
            val listTarget = target + 1 + (if (showAdRow && target >= effectiveAdIndex) 1 else 0)
            listState.scrollToItem(listTarget)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { fraction }.debounce(PROGRESS_DEBOUNCE_MS).collect(onProgress)
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 1. Reading Progress Bar pinned at the top edge of the screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        // 2. Frosted Top Bar
        ArticleTopBar(
            title = article.title,
            section = article.section,
            isPremium = article.isPremium,
            bookmarked = bookmarked,
            onBack = onBack,
            onToggleBookmark = onToggleBookmark,
            inspectorEnabled = inspectorEnabled,
            onOpenInspector = onOpenInspector,
        )

        // 3. Article Content Stream
        LazyColumn(
            modifier = Modifier.fillMaxSize().weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header Item (Headline + Metadata Row)
            item(key = "article_header") {
                ArticleContentHeader(
                    article = article,
                )
            }

            // Paragraphs & Inline Native Ad
            val itemCount = paragraphs.size + if (showAdRow) 1 else 0
            items(
                count = itemCount,
                key = { index ->
                    if (!showAdRow) "p-$index"
                    else when {
                        index < effectiveAdIndex -> "p-$index"
                        index == effectiveAdIndex -> "ad-${article.id}"
                        else -> "p-${index - 1}"
                    }
                },
            ) { index ->
                when {
                    showAdRow && index == effectiveAdIndex -> {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        ) {
                            NativeAdView(
                                session = nativeSession,
                                slotKey = inlineSlot.key,
                                placement = ShowcasePlacements.articleNative,
                                layout = inlineNativeAdLayout,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 164.dp)
                                    .padding(16.dp),
                                loading = { Box(Modifier.fillMaxWidth().heightIn(min = 164.dp)) },
                            )
                        }
                    }
                    showAdRow && index > effectiveAdIndex -> {
                        ParagraphText(text = paragraphs[index - 1])
                    }
                    else -> {
                        ParagraphText(text = paragraphs[index])
                    }
                }
            }
        }

        // 4. Bottom Banner Ad
        if (adsEnabled && sdkReady) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                BannerAdView(
                    placement = ShowcasePlacements.articleBanner,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ParagraphText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ArticleContentHeader(
    article: ArticleEntity,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Section & Premium badge row
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Text(
                    text = article.section.uppercase(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (article.isPremium) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                    contentColor = MaterialTheme.colorScheme.tertiary,
                ) {
                    Text(
                        text = "PREMIUM",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Large Headline
        Text(
            text = article.title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // Author Metadata Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = "Author avatar",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = article.author,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = "Read time",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${article.readTimeMin} min read",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Text(
                        text = formatRelativeDate(article.feedOrdinal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Divider
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        ) {}
    }
}

@Composable
private fun ArticleTopBar(
    title: String,
    section: String,
    isPremium: Boolean,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    inspectorEnabled: Boolean,
    onOpenInspector: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Text(
                            text = section.uppercase(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (isPremium) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.tertiary,
                        ) {
                            Text(
                                text = "PREMIUM",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (inspectorEnabled) {
                IconButton(onClick = onOpenInspector) {
                    Icon(
                        imageVector = Icons.Rounded.Analytics,
                        contentDescription = "Inspect Telemetry",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            IconButton(onClick = onToggleBookmark) {
                Icon(
                    imageVector = if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                    contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark article",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatRelativeDate(feedOrdinal: Int): String {
    if (feedOrdinal == 0) return "Just now"
    if (feedOrdinal < 24) return "${feedOrdinal}h ago"
    val days = feedOrdinal / 24
    return if (days == 1) "1 day ago" else "$days days ago"
}
