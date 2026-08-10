package dev.avinya.admob.showcase.feature.rewards

import dev.avinya.admob.showcase.data.repo.PremiumArticle
import dev.avinya.admob.showcase.ui.ad.RewardOutcome

/** Which rewarded format the reader chose to watch. */
enum class RewardKind { Rewarded, RewardedInterstitial }

/** Presentation state for the single in-flight rewarded ad. */
enum class RewardPhase { Idle, Presenting }

data class RewardsState(
    val balance: Int = 0,
    val premium: List<PremiumArticle> = emptyList(),
    val phase: RewardPhase = RewardPhase.Idle,
    val adsEnabled: Boolean = true,
    val sdkReady: Boolean = false,
) {
    /**
     * Rewarded ads are only offered when they can actually be shown.
     *
     * Offering a reward the SDK cannot deliver — because ads are switched off,
     * or consent has not resolved — is worse than not offering it: the reader
     * taps, nothing happens, and they learn the button lies.
     */
    val canWatch: Boolean get() = adsEnabled && sdkReady && phase == RewardPhase.Idle
}

sealed interface RewardsIntent {
    data class Watch(val kind: RewardKind) : RewardsIntent
    data class Unlock(val article: PremiumArticle) : RewardsIntent
}

sealed interface RewardsEffect {
    data class RewardResult(val outcome: RewardOutcome) : RewardsEffect
    data class Unlocked(val title: String) : RewardsEffect
    data class NeedMoreCoins(val shortfall: Int) : RewardsEffect
}
