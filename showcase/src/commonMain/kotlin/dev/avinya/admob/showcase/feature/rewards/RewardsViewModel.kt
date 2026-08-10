package dev.avinya.admob.showcase.feature.rewards

import androidx.lifecycle.viewModelScope
import dev.avinya.ads.AdManager
import dev.avinya.ads.AdManagerStatus
import dev.avinya.admob.showcase.core.mvi.MviViewModel
import dev.avinya.admob.showcase.data.db.entity.UnlockSource
import dev.avinya.admob.showcase.data.prefs.SettingsRepository
import dev.avinya.admob.showcase.data.repo.ArticleRepository
import dev.avinya.admob.showcase.data.repo.PremiumArticle
import dev.avinya.admob.showcase.data.repo.WalletRepository
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.wallet.DebitResult
import dev.avinya.admob.showcase.domain.wallet.rewardGrantKey
import dev.avinya.admob.showcase.ui.ad.AppOpenSuppressor
import dev.avinya.admob.showcase.ui.ad.RewardOutcome
import dev.avinya.admob.showcase.ui.ad.runRewarded
import dev.avinya.admob.showcase.ui.ad.suppressing
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * The reward economy, as a product feature.
 *
 * This is the honest case for rewarded ads: the reader wants something —
 * a premium story — and chooses to watch an ad to get it. Nothing is
 * interrupted, nothing is forced, and the exchange is stated before it starts.
 *
 * The accounting rule this screen exists to demonstrate: **one earned reward
 * produces exactly one wallet mutation.** The grant key comes from a monotonic
 * per-session sequence, the wallet refuses a replayed key, and the credit is
 * driven by the SDK's `onReward` callback rather than by `show()` returning —
 * because a reader who dismisses early still yields `Shown`, and paying on
 * that is the classic rewarded-ads bug.
 */
class RewardsViewModel(
    private val wallet: WalletRepository,
    private val articles: ArticleRepository,
    private val adManager: AdManager,
    private val suppressor: AppOpenSuppressor,
    private val sessionId: String,
    settings: SettingsRepository,
) : MviViewModel<RewardsState, RewardsIntent, RewardsEffect>(RewardsState()) {

    /** Monotonic within a session; combined with [sessionId] into the grant key. */
    private var rewardSequence = 0

    init {
        combine(
            wallet.balance(),
            articles.premiumCatalog(),
            settings.adsMasterSwitch,
            adManager.status,
        ) { balance, premium, adsEnabled, status ->
            Snapshot(balance, premium, adsEnabled, status == AdManagerStatus.Ready)
        }.onEach { snapshot ->
            updateState {
                copy(
                    balance = snapshot.balance,
                    premium = snapshot.premium,
                    adsEnabled = snapshot.adsEnabled,
                    sdkReady = snapshot.sdkReady,
                )
            }
        }.launchIn(viewModelScope)
    }

    override fun onIntent(intent: RewardsIntent) {
        when (intent) {
            is RewardsIntent.Watch -> watch(intent.kind)
            is RewardsIntent.Unlock -> unlock(intent.article)
        }
    }

    private fun watch(kind: RewardKind) {
        if (!state.value.canWatch) return
        updateState { copy(phase = RewardPhase.Presenting) }

        viewModelScope.launch {
            val placement = when (kind) {
                RewardKind.Rewarded -> ShowcasePlacements.rewardsRewarded
                RewardKind.RewardedInterstitial -> ShowcasePlacements.rewardsRewardedInterstitial
            }
            val grantKey = rewardGrantKey(placement.id, sessionId, ++rewardSequence)

            // A full-screen ad is already showing, so an app-open ad on top of
            // it would be both a bad experience and a policy problem.
            val outcome = suppressor.suppressing {
                when (kind) {
                    RewardKind.Rewarded -> {
                        val controller = adManager.rewarded(placement)
                        runRewarded(
                            load = { controller.load() },
                            show = { onReward -> controller.show(onRewardEarned = onReward) },
                            wallet = wallet,
                            grantKey = grantKey,
                        )
                    }

                    RewardKind.RewardedInterstitial -> {
                        val controller = adManager.rewardedInterstitial(placement)
                        runRewarded(
                            load = { controller.load() },
                            show = { onReward -> controller.show(onRewardEarned = onReward) },
                            wallet = wallet,
                            grantKey = grantKey,
                        )
                    }
                }
            }

            updateState { copy(phase = RewardPhase.Idle) }
            emitEffect(RewardsEffect.RewardResult(outcome))
        }
    }

    private fun unlock(article: PremiumArticle) {
        viewModelScope.launch {
            if (articles.isUnlocked(article.id).first()) return@launch
            suppressor.suppressing {
                when (val result = wallet.debit(article.costCoins)) {
                    is DebitResult.Debited -> {
                        articles.unlock(article.id, UnlockSource.COINS)
                        emitEffect(RewardsEffect.Unlocked(article.title))
                    }

                    is DebitResult.InsufficientFunds ->
                        emitEffect(RewardsEffect.NeedMoreCoins(result.required - result.balance))
                }
            }
        }
    }

    private data class Snapshot(
        val balance: Int,
        val premium: List<PremiumArticle>,
        val adsEnabled: Boolean,
        val sdkReady: Boolean,
    )
}

/** Human-readable summary of a presentation outcome. */
fun RewardOutcome.message(): String = when (this) {
    is RewardOutcome.Earned -> "You earned $coins coins — balance $newBalance"
    RewardOutcome.AlreadyGranted -> "Reward already claimed — wallet unchanged"
    RewardOutcome.DismissedWithoutReward -> "Dismissed before earning — no coins awarded"
    RewardOutcome.NotReady -> "Ad wasn't ready. Try again in a moment."
    is RewardOutcome.Failed -> "Ad failed: $message"
}
