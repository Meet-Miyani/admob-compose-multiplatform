package dev.avinya.admob.showcase.feature.article

import dev.avinya.admob.showcase.data.db.entity.ArticleEntity
import dev.avinya.admob.showcase.domain.ad.SuppressionReason
import dev.avinya.admob.showcase.ui.ad.RewardOutcome

/**
 * Immutable UI state for the article reader.
 *
 * `article == null` with [loading] true means the load is in flight; with
 * [loading] false it means the row was missing, and the screen says so rather
 * than spinning forever.
 *
 * [adsEnabled] reflects the user-facing master switch; [sdkReady] confirms the
 * AdManager finished initialising. Every ad slot on this screen is gated on
 * both, so turning ads off leaves a complete, readable article behind.
 */
data class ArticleState(
    val article: ArticleEntity? = null,
    val bookmarked: Boolean = false,
    val unlocked: Boolean = false,
    val initialProgress: Float = 0f,
    val loading: Boolean = true,
    val adsEnabled: Boolean = true,
    val sdkReady: Boolean = false,
    val unlocking: Boolean = false,
    val leaving: Boolean = false,
) {
    /** Premium body stays sealed until the reader unlocks it. */
    val isLocked: Boolean get() = article?.isPremium == true && !unlocked

    val canShowAds: Boolean get() = adsEnabled && sdkReady
}

sealed interface ArticleIntent {
    data object ToggleBookmark : ArticleIntent
    data class ProgressUpdated(val fraction: Float) : ArticleIntent

    /** Watch a rewarded ad to unlock this premium article. */
    data object UnlockWithAd : ArticleIntent

    /** Spend earned coins instead of watching an ad. */
    data object UnlockWithCoins : ArticleIntent

    /**
     * The reader is leaving.
     *
     * The ViewModel evaluates the interstitial policy and answers with
     * [ArticleEffect.Leave] once the decision — and any presentation — is
     * settled, so navigation never races an ad that is about to appear.
     */
    data object Close : ArticleIntent
}

sealed interface ArticleEffect {
    /** Navigation may proceed. Always emitted exactly once per [ArticleIntent.Close]. */
    data object Leave : ArticleEffect

    data class Notice(val message: String) : ArticleEffect

    data class UnlockResult(val outcome: RewardOutcome) : ArticleEffect

    /**
     * The interstitial was declined, and why.
     *
     * Surfaced rather than swallowed: "no ad appeared and I don't know why" is
     * the most common AdMob integration confusion, so the reason is a
     * first-class value the Inspector and Diagnostics can render.
     */
    data class InterstitialSuppressed(val reason: SuppressionReason) : ArticleEffect
}
