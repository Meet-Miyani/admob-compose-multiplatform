package dev.avinya.admob.showcase.domain.wallet

import dev.avinya.ads.AdReward
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class RewardGrantTest {

    @Test
    fun theSameRewardYieldsTheSameKeySoAReplayCannotDoubleCredit() {
        assertEquals(
            rewardGrantKey("lab_rewarded", "session-1", 4),
            rewardGrantKey("lab_rewarded", "session-1", 4),
        )
    }

    @Test
    fun successiveRewardsInASessionGetDistinctKeys() {
        assertNotEquals(
            rewardGrantKey("lab_rewarded", "session-1", 4),
            rewardGrantKey("lab_rewarded", "session-1", 5),
        )
    }

    @Test
    fun differentSessionsAndPlacementsDoNotCollide() {
        assertNotEquals(
            rewardGrantKey("lab_rewarded", "session-1", 4),
            rewardGrantKey("lab_rewarded", "session-2", 4),
        )
        assertNotEquals(
            rewardGrantKey("lab_rewarded", "session-1", 4),
            rewardGrantKey("lab_rewarded_interstitial", "session-1", 4),
        )
    }

    @Test
    fun aWholeRewardConvertsToCoins() {
        assertEquals(50, coinsFor(AdReward(amountMicros = 50_000_000L, type = "coins")))
    }

    @Test
    fun aFractionalRewardIsRejectedRatherThanRounded() {
        // AdReward.wholeAmountOrNull() returns null for fractional amounts.
        // Rounding would silently over- or under-pay; refusing is honest.
        assertNull(coinsFor(AdReward(amountMicros = 1_500_000L, type = "coins")))
    }

    @Test
    fun noRewardMeansNoCoins() {
        // The user dismissed before earning. There is no consolation grant.
        assertNull(coinsFor(null))
    }
}
