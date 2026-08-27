package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals

class AdEventModelsTest {

    @Test
    fun `AdError message factory sets correct fields`() {
        val error = AdError.message("test message")
        assertEquals("test message", error.message)
        assertEquals(null, error.code)
    }

    @Test
    fun `AdError consentRequired factory sets correct code and message`() {
        val error = AdError.consentRequired()
        assertEquals(AdErrorCode.CONSENT_REQUIRED, error.code)
        assertEquals("Ads cannot be requested until consent allows ad requests.", error.message)
    }

    @Test
    fun `AdError sdkNotReady factory sets correct code and message`() {
        val error = AdError.sdkNotReady()
        assertEquals(AdErrorCode.SDK_NOT_READY, error.code)
        assertEquals("Google Mobile Ads SDK is not initialized yet.", error.message)
    }

    @Test
    fun `ConsentStatus exhaustive when coverage`() {
        val all = listOf(
            ConsentStatus.Unknown,
            ConsentStatus.Required,
            ConsentStatus.NotRequired,
            ConsentStatus.Obtained,
            ConsentStatus.Failed(AdError.message("")),
        )
        val covered = all.map { status ->
            when (status) {
                is ConsentStatus.Unknown -> "unknown"
                is ConsentStatus.Required -> "required"
                is ConsentStatus.NotRequired -> "not_required"
                is ConsentStatus.Obtained -> "obtained"
                is ConsentStatus.Failed -> "failed"
            }
        }
        assertEquals(5, covered.size)
    }

    @Test
    fun `AdValuePrecision exhaustive when coverage`() {
        val covered = AdValuePrecision.entries.map { precision ->
            when (precision) {
                AdValuePrecision.Unknown -> "unknown"
                AdValuePrecision.Estimated -> "estimated"
                AdValuePrecision.PublisherProvided -> "publisher_provided"
                AdValuePrecision.Precise -> "precise"
            }
        }
        assertEquals(4, covered.size)
    }

    @Test
    fun `AdEvent subclasses carry correct placementId`() {
        assertEquals("p1", AdEvent.Loaded("p1").placementId)
        assertEquals("p2", AdEvent.LoadFailed("p2", AdError.message("e")).placementId)
        assertEquals("p3", AdEvent.Impression("p3").placementId)
        assertEquals("p4", AdEvent.Clicked("p4").placementId)
        assertEquals("p5", AdEvent.ShowFailed("p5", AdError.message("e")).placementId)
        assertEquals("p6", AdEvent.RewardEarned("p6", AdReward(1_000_000L, "coin")).placementId)
        assertEquals("p7", AdEvent.VideoStarted("p7").placementId)
        assertEquals("p8", AdEvent.VideoEnded("p8").placementId)
    }

    // Impression/Clicked/Paid gained an optional adInstanceId so a NativeAdView can
    // filter a shared pool's events flow down to its own leased ad.
    @Test
    fun `AdEvent Impression carries the optional adInstanceId`() {
        assertEquals(null, AdEvent.Impression("p").adInstanceId)
        assertEquals("tok-1", AdEvent.Impression("p", adInstanceId = "tok-1").adInstanceId)
    }

    @Test
    fun `AdEvent Clicked carries the optional adInstanceId`() {
        assertEquals(null, AdEvent.Clicked("p").adInstanceId)
        assertEquals("tok-1", AdEvent.Clicked("p", adInstanceId = "tok-1").adInstanceId)
    }

    @Test
    fun `AdEvent Paid carries the optional adInstanceId`() {
        val paidEvent = PaidEvent("p", AdValue(1_000_000L, "USD", AdValuePrecision.Estimated))
        assertEquals(null, AdEvent.Paid("p", paidEvent).adInstanceId)
        assertEquals("tok-1", AdEvent.Paid("p", paidEvent, adInstanceId = "tok-1").adInstanceId)
    }

    @Test
    fun `AdEvent video lifecycle events carry their native instance identity`() {
        assertEquals("first", AdEvent.VideoStarted("native", adInstanceId = "first").adInstanceId)
        assertEquals("second", AdEvent.VideoPlayed("native", adInstanceId = "second").adInstanceId)
        assertEquals("third", AdEvent.VideoPaused("native", adInstanceId = "third").adInstanceId)
        assertEquals("fourth", AdEvent.VideoEnded("native", adInstanceId = "fourth").adInstanceId)
        assertEquals("fifth", AdEvent.VideoMuted("native", muted = true, adInstanceId = "fifth").adInstanceId)
    }

    @Test
    fun `AdShowResult subclasses work correctly`() {
        assertEquals(AdShowResult.Shown::class, AdShowResult.Shown::class)
        assertEquals(AdShowResult.NotReady::class, AdShowResult.NotReady::class)
        assertEquals(AdShowResult.Failed(AdError.message("e"))::class, AdShowResult.Failed::class)
    }

    private suspend fun showRewardedWithCallback(
        controller: RewardedAdController,
        rewards: MutableList<AdReward>
    ): AdShowResult = controller.show(onRewardEarned = rewards::add)
}
