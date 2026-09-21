package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Runtime updates to the global request configuration.
 *
 * `initialize()` treats its whole configuration as process identity, which is right for the
 * app ID and wrong for request configuration: Android exposes
 * `MobileAds.setRequestConfiguration()` and iOS a mutable `requestConfiguration`, both meant
 * to be set at runtime. Before this API existed, changing a content rating for a new profile —
 * or merely turning the ad volume down — returned INITIALIZATION_CONFLICT and told the
 * publisher to restart the process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestConfigurationUpdateTest {

    private fun config(
        rating: MaxAdContentRating = MaxAdContentRating.Unspecified,
        volume: Float? = null,
    ) = AdConfig(
        appIds = AdAppIds("ca-app-pub-A", "ca-app-pub-A"),
        globalRequestConfiguration = GlobalRequestConfiguration(
            maxAdContentRating = rating,
            appVolume = volume,
        ),
    )

    @Test
    fun `an update before initialization reports NotInitialized`() = runSlotTest {
        val manager = FakeGoogleAdManager()

        val result = manager.updateGlobalRequestConfiguration(GlobalRequestConfiguration())

        assertEquals(RequestConfigurationUpdateResult.NotInitialized, result)
    }

    @Test
    fun `a manager without the capability reports Unsupported`() = runSlotTest {
        // A third-party AdManager, or the no-op one a host uses when ads are disabled, must
        // get a clear answer rather than a silent no-op.
        val result = NoOpAdManager.updateGlobalRequestConfiguration(GlobalRequestConfiguration())

        assertEquals(RequestConfigurationUpdateResult.Unsupported, result)
    }

    @Test
    fun `changing a serving-relevant field applies and invalidates cached ads`() = runSlotTest {
        val manager = FakeGoogleAdManager()
        manager.initialize(config(), ConsentMode.SkipConsent)

        val result = manager.updateGlobalRequestConfiguration(
            GlobalRequestConfiguration(maxAdContentRating = MaxAdContentRating.General),
        )

        val applied = assertIs<RequestConfigurationUpdateResult.Applied>(result)
        assertTrue(
            applied.invalidatedCachedAds,
            "ads fetched under the previous content rating must not be served under the new one",
        )
        assertEquals(
            listOf(GlobalRequestConfiguration(maxAdContentRating = MaxAdContentRating.General)),
            manager.appliedRequestConfigurations,
        )
    }

    @Test
    fun `changing only audio applies without dropping inventory`() = runSlotTest {
        val manager = FakeGoogleAdManager()
        manager.initialize(config(), ConsentMode.SkipConsent)

        val result = manager.updateGlobalRequestConfiguration(
            GlobalRequestConfiguration(appVolume = 0.2f),
        )

        val applied = assertIs<RequestConfigurationUpdateResult.Applied>(result)
        assertTrue(
            !applied.invalidatedCachedAds,
            "volume is a playback setting; discarding a warm cache for it is pure fill cost",
        )
    }

    @Test
    fun `an unchanged configuration is a no-op that still reports Applied`() = runSlotTest {
        val manager = FakeGoogleAdManager()
        manager.initialize(config(rating = MaxAdContentRating.General), ConsentMode.SkipConsent)

        val result = manager.updateGlobalRequestConfiguration(
            GlobalRequestConfiguration(maxAdContentRating = MaxAdContentRating.General),
        )

        // The requested configuration IS in force, which is what the caller asked about.
        assertEquals(RequestConfigurationUpdateResult.Applied(invalidatedCachedAds = false), result)
        assertEquals(emptyList(), manager.appliedRequestConfigurations, "nothing to re-apply")
    }

    /**
     * After an update, `initialize()` with the SAME new configuration must be equivalent.
     *
     * The applied identity is what a repeat `initialize()` is compared against, so an update
     * that did not record itself there would leave the manager reporting a conflict for a
     * configuration it is actually running.
     */
    @Test
    fun `initialize with the updated configuration is no longer a conflict`() = runSlotTest {
        val manager = FakeGoogleAdManager()
        manager.initialize(config(), ConsentMode.SkipConsent)
        val updated = GlobalRequestConfiguration(maxAdContentRating = MaxAdContentRating.Teen)
        manager.updateGlobalRequestConfiguration(updated)

        val status = manager.initialize(
            AdConfig(appIds = AdAppIds("ca-app-pub-A", "ca-app-pub-A"), globalRequestConfiguration = updated),
            ConsentMode.SkipConsent,
        )

        assertEquals(AdManagerStatus.Ready, status)
    }

    @Test
    fun `a platform failure leaves the previous configuration in force`() = runSlotTest {
        val manager = FakeGoogleAdManager(
            applyRequestConfigurationFailure = { IllegalStateException("SDK rejected it") },
        )
        manager.initialize(config(), ConsentMode.SkipConsent)

        val result = manager.updateGlobalRequestConfiguration(
            GlobalRequestConfiguration(maxAdContentRating = MaxAdContentRating.General),
        )

        assertIs<RequestConfigurationUpdateResult.Failed>(result)
        // The recorded identity must still describe what the SDK actually holds, so a repeat
        // initialize() with the ORIGINAL configuration is still equivalent.
        assertEquals(AdManagerStatus.Ready, manager.initialize(config(), ConsentMode.SkipConsent))
    }
}
