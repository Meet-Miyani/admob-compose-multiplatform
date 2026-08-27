package dev.avinya.ads

import dev.avinya.ads.appopen.AppOpenConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Construction-time validation for public configuration types.
 *
 * Each must reject nonsense AT CONSTRUCTION. Accepting it defers the failure into a platform
 * SDK call, or silently changes policy, and the resulting message does not name the
 * configuration responsible. `AdPlacement` sets the precedent: it hard-`require`s on
 * `strictTestMode` rather than warning.
 */
class ConfigurationValidationTest {

    // --- API-01: audio volume ----------------------------------------------------------

    @Test
    fun `audio volume rejects NaN`() {
        // The specific trap: coerceIn does NOT sanitise NaN, it returns NaN, so clamping downstream
        // was never enough to keep this out of the platform audio setter.
        assertFailsWith<IllegalArgumentException> { FullScreenAdOptions(audioVolume = Float.NaN) }
    }

    @Test
    fun `audio volume rejects infinities`() {
        assertFailsWith<IllegalArgumentException> { FullScreenAdOptions(audioVolume = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { FullScreenAdOptions(audioVolume = Float.NEGATIVE_INFINITY) }
    }

    @Test
    fun `audio volume rejects out of range values`() {
        assertFailsWith<IllegalArgumentException> { FullScreenAdOptions(audioVolume = -0.01f) }
        assertFailsWith<IllegalArgumentException> { FullScreenAdOptions(audioVolume = 1.01f) }
    }

    @Test
    fun `audio volume accepts the full valid range and null`() {
        assertEquals(0.0f, FullScreenAdOptions(audioVolume = 0.0f).audioVolume)
        assertEquals(1.0f, FullScreenAdOptions(audioVolume = 1.0f).audioVolume)
        assertEquals(0.5f, FullScreenAdOptions(audioVolume = 0.5f).audioVolume)
        assertEquals(null, FullScreenAdOptions().audioVolume)
    }

    // --- API-02: identifiers ------------------------------------------------------------

    @Test
    fun `app ids must not be blank`() {
        assertFailsWith<IllegalArgumentException> { AdAppIds(android = "", ios = "ca-app-pub-1~1") }
        assertFailsWith<IllegalArgumentException> { AdAppIds(android = "ca-app-pub-1~1", ios = "   ") }
    }

    @Test
    fun `ad unit ids must not be blank`() {
        assertFailsWith<IllegalArgumentException> { AdUnitIds(android = "", ios = "ok") }
        assertFailsWith<IllegalArgumentException> { AdUnitIds(android = "ok", ios = "\t") }
    }

    @Test
    fun `Ad Manager identifiers are still accepted`() {
        // Guards against tightening this into a ca-app-pub- regex: Ad Manager ids look nothing like
        // AdMob's, and rejecting them would break valid configurations.
        val adManager = AdUnitIds(android = "/6499/example/banner", ios = "/6499/example/banner")
        assertEquals("/6499/example/banner", adManager.android)

        val appIds = AdAppIds(android = "ca-app-pub-3940256099942544~3347511713", ios = "ca-app-pub-3940256099942544~1458002511")
        assertEquals("ca-app-pub-3940256099942544~3347511713", appIds.android)
    }

    // --- APP-02: app-open durations ------------------------------------------------------

    @Test
    fun `app open thresholds reject negative and non-finite durations`() {
        assertFailsWith<IllegalArgumentException> { AppOpenConfig(minBackgroundDuration = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { AppOpenConfig(minBackgroundDuration = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { AppOpenConfig(cooldownBetweenShows = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { AppOpenConfig(cooldownBetweenShows = Duration.INFINITE) }
    }

    @Test
    fun `app open cold start timeout must be finite and positive`() {
        // Zero would abandon the load before it starts; infinite would never give up.
        assertFailsWith<IllegalArgumentException> { AppOpenConfig(coldStartTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { AppOpenConfig(coldStartTimeout = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { AppOpenConfig(coldStartTimeout = Duration.INFINITE) }
    }

    @Test
    fun `app open accepts zero thresholds and the defaults`() {
        // ZERO is meaningful for both: show on every foreground, and no cooldown.
        val eager = AppOpenConfig(minBackgroundDuration = Duration.ZERO, cooldownBetweenShows = Duration.ZERO)
        assertEquals(Duration.ZERO, eager.minBackgroundDuration)
        assertEquals(4.seconds, AppOpenConfig().minBackgroundDuration)
        assertEquals(5.seconds, AppOpenConfig().coldStartTimeout)
    }
}
