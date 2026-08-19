package dev.avinya.ads

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the FS-04 derivation only — the pure mapping from the manager's applied global request
 * configuration to the values Android restores after a full-screen presentation.
 *
 * Deliberately does not touch `MobileAds`: its audio API is static and write-only, so asserting the
 * actual setter calls would need a static mock (a new Gradle dependency). The mapping is where the
 * defect was — the old code ignored the configuration entirely and reverted to hardcoded
 * unmuted/1.0f, permanently discarding a host's configured global audio after the first ad.
 */
class AndroidFullScreenAudioStateTest {

    @Test
    fun `no applied configuration falls back to the platform defaults`() {
        // null means initialize() has not completed, or the host configured no audio at all. GMA's
        // own defaults are unmuted at full volume, so that is what a restore must reassert.
        assertEquals(false, null.effectiveAudioMuted())
        assertEquals(1.0f, null.effectiveAudioVolume())
    }

    @Test
    fun `a configured global audio state round-trips`() {
        val configured = GlobalRequestConfiguration(appMuted = true, appVolume = 0.25f)

        assertEquals(true, configured.effectiveAudioMuted())
        assertEquals(0.25f, configured.effectiveAudioVolume())
    }

    @Test
    fun `an unset property falls back independently of the one that is set`() {
        val mutedOnly = GlobalRequestConfiguration(appMuted = true)
        assertEquals(true, mutedOnly.effectiveAudioMuted())
        assertEquals(1.0f, mutedOnly.effectiveAudioVolume(), "volume was never configured")

        val volumeOnly = GlobalRequestConfiguration(appVolume = 0.5f)
        assertEquals(false, volumeOnly.effectiveAudioMuted(), "mute was never configured")
        assertEquals(0.5f, volumeOnly.effectiveAudioVolume())
    }

    @Test
    fun `an out of range configured volume is clamped before it reaches the SDK`() {
        assertEquals(1.0f, GlobalRequestConfiguration(appVolume = 1.5f).effectiveAudioVolume())
        assertEquals(0.0f, GlobalRequestConfiguration(appVolume = -0.5f).effectiveAudioVolume())
    }
}
