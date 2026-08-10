package dev.avinya.admob.showcase.feature.article

import androidx.lifecycle.viewModelScope
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.admob.showcase.core.mvi.MviViewModel
import dev.avinya.admob.showcase.core.time.Clock
import dev.avinya.admob.showcase.data.db.entity.UnlockSource
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.data.repo.AdStateRepository
import dev.avinya.admob.showcase.data.repo.ArticleRepository
import dev.avinya.admob.showcase.data.repo.WalletRepository
import dev.avinya.admob.showcase.domain.ad.AdDecision
import dev.avinya.admob.showcase.domain.ad.AdPolicy
import dev.avinya.admob.showcase.domain.ad.AdPolicySnapshot
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.ad.SuppressionReason
import dev.avinya.admob.showcase.domain.ad.advancesCooldown
import dev.avinya.admob.showcase.domain.wallet.DebitResult
import dev.avinya.admob.showcase.domain.wallet.rewardGrantKey
import dev.avinya.admob.showcase.ui.ad.AppOpenSuppressor
import dev.avinya.admob.showcase.ui.ad.RewardOutcome
import dev.avinya.admob.showcase.ui.ad.runRewarded
import dev.avinya.admob.showcase.ui.ad.suppressing
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The reader's state, and the three ad decisions it owns.
 *
 * 1. **Inline native** — placed deterministically by the block model, never
 *    above the headline.
 * 2. **Collapsible bottom banner** — anchored, dismissible, gated on the ad
 *    switch and SDK readiness.
 * 3. **Interstitial on leave** — and only on leave. Opening an article is not
 *    a break in the reader's attention; finishing one is. Every attempt goes
 *    through [AdPolicy], which caps it to one per three articles, no sooner
 *    than 60s apart, never in the first 30s after a cold start, and never
 *    after a rewarded unlock — a reader who *just watched an ad* to open this
 *    story does not get a second one on the way out.
 *
 * The cooldown advances only when an ad actually appeared
 * ([advancesCooldown]); charging 60s of suppression for an ad that failed to
 * show is a bug this sample shipped once already.
 */
class ArticleViewModel(
    private val articles: ArticleRepository,
    private val settings: SettingsRepository,
    private val adState: AdStateRepository,
    private val wallet: WalletRepository,
    private val adManager: AdManager,
    private val suppressor: AppOpenSuppressor,
    private val clock: Clock,
    private val sessionId: String,
    private val articleId: String,
) : MviViewModel<ArticleState, ArticleIntent, ArticleEffect>(ArticleState()) {

    private val policy = AdPolicy()

    /** True once this reader unlocked the article by watching an ad. */
    private var unlockedByRewardThisSession = false
    private var rewardSequence = 0

    init {
        load()
        observeBookmark()
        observeUnlock()
        observeAdGates()
    }

    private fun load() {
        viewModelScope.launch {
            val entityDeferred = async { articles.article(articleId) }
            val progressDeferred = async { articles.progress(articleId) }
            val entity = entityDeferred.await()
            val progress = progressDeferred.await()
            updateState {
                copy(article = entity, initialProgress = progress, loading = false)
            }
        }
    }

    private fun observeBookmark() {
        viewModelScope.launch {
            articles.isBookmarked(articleId).collect { bookmarked ->
                updateState { copy(bookmarked = bookmarked) }
            }
        }
    }

    private fun observeUnlock() {
        viewModelScope.launch {
            articles.isUnlocked(articleId).collect { unlocked ->
                updateState { copy(unlocked = unlocked) }
            }
        }
    }

    private fun observeAdGates() {
        combine(settings.adsMasterSwitch, adManager.status) { adsEnabled, status ->
            adsEnabled to status
        }.onEach { (adsEnabled, status) ->
            updateState {
                copy(adsEnabled = adsEnabled, sdkReady = status == AdManagerStatus.Ready)
            }
        }.launchIn(viewModelScope)
    }

    override fun onIntent(intent: ArticleIntent) {
        when (intent) {
            ArticleIntent.ToggleBookmark -> viewModelScope.launch {
                // Read from state so the optimistic write cannot race the
                // bookmark flow's next emission.
                articles.setBookmarked(articleId, !state.value.bookmarked)
            }

            is ArticleIntent.ProgressUpdated -> viewModelScope.launch {
                articles.setProgress(articleId, intent.fraction)
            }

            ArticleIntent.UnlockWithAd -> unlockWithAd()
            ArticleIntent.UnlockWithCoins -> unlockWithCoins()
            ArticleIntent.Close -> leave()
        }
    }

    private fun unlockWithAd() {
        val current = state.value
        if (current.unlocking || !current.canShowAds) return
        updateState { copy(unlocking = true) }

        viewModelScope.launch {
            val controller = adManager.rewarded(ShowcasePlacements.articleUnlockRewarded)
            val grantKey = rewardGrantKey(
                ShowcasePlacements.articleUnlockRewarded.id,
                sessionId,
                ++rewardSequence,
            )

            // An app-open ad on top of a rewarded presentation would be both a
            // bad experience and a policy problem.
            val outcome = suppressor.suppressing {
                runRewarded(
                    load = { controller.load() },
                    show = { onReward -> controller.show(onRewardEarned = onReward) },
                    wallet = wallet,
                    grantKey = grantKey,
                )
            }

            // The unlock is driven by the reward callback, never by `show()`
            // returning: a reader who dismissed early gets no article.
            if (outcome is RewardOutcome.Earned || outcome is RewardOutcome.AlreadyGranted) {
                articles.unlock(articleId, UnlockSource.REWARDED)
                unlockedByRewardThisSession = true
            }

            updateState { copy(unlocking = false) }
            emitEffect(ArticleEffect.UnlockResult(outcome))
        }
    }

    private fun unlockWithCoins() {
        val article = state.value.article ?: return
        if (state.value.unlocking) return
        updateState { copy(unlocking = true) }

        viewModelScope.launch {
            suppressor.suppressing {
                when (val result = wallet.debit(article.unlockCostCoins)) {
                    is DebitResult.Debited -> {
                        articles.unlock(articleId, UnlockSource.COINS)
                        emitEffect(ArticleEffect.Notice("Unlocked with coins"))
                    }

                    is DebitResult.InsufficientFunds -> emitEffect(
                        ArticleEffect.Notice(
                            "You need ${result.required - result.balance} more coins — " +
                                "watch an ad to unlock instead",
                        ),
                    )
                }
            }
            updateState { copy(unlocking = false) }
        }
    }

    /**
     * Leaving the article: count the read, ask the policy, present if allowed,
     * and only then let navigation proceed.
     */
    private fun leave() {
        if (state.value.leaving) return
        updateState { copy(leaving = true) }

        viewModelScope.launch {
            adState.incrementArticlesRead()

            val decision = policy.decideInterstitial(
                AdPolicySnapshot(
                    articlesRead = adState.articlesRead.first(),
                    millisSinceLastInterstitial = adState.lastInterstitialAt.first()
                        ?.let { clock.nowMillis() - it }
                        ?: Long.MAX_VALUE,
                    millisSinceColdStart = clock.nowMillis() - adState.coldStartAt,
                    canRequestAds = adManager.consent.canRequestAds.value,
                    wasRewardedUnlock = unlockedByRewardThisSession,
                    adsEnabled = state.value.adsEnabled && state.value.sdkReady,
                ),
            )

            when (decision) {
                is AdDecision.Suppress -> emitEffect(
                    ArticleEffect.InterstitialSuppressed(decision.reason),
                )

                AdDecision.Show -> {
                    val controller = adManager.interstitial(ShowcasePlacements.articleInterstitial)
                    try {
                        val result = suppressor.suppressing {
                            controller.load()
                            controller.show()
                        }
                        // Only a presentation that actually happened may advance
                        // the cooldown.
                        if (advancesCooldown(result)) {
                            adState.recordInterstitialShown()
                        }
                    } catch (t: Throwable) {
                        // A platform that throws instead of returning Failed would
                        // otherwise crash this coroutine and leave ArticleEffect.Leave
                        // unemitted — i.e. the user is stuck on the article.
                        emitEffect(ArticleEffect.InterstitialSuppressed(SuppressionReason.NotReady))
                    }
                }
            }

            emitEffect(ArticleEffect.Leave)
        }
    }
}
