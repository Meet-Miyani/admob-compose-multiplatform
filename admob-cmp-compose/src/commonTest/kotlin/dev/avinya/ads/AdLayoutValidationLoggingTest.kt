package dev.avinya.ads

import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdStaticText
import dev.avinya.ads.nativead.layout.logValidationWarningsOnce
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdLayoutValidationLoggingTest {

    private val originalSink = AdLogger.sink

    @AfterTest
    fun tearDown() {
        // AdLogger.sink is process-wide; a test that leaves one installed would leak into
        // unrelated tests run in the same process.
        AdLogger.sink = originalSink
    }

    @Test
    fun `logs each warning once per distinct layout identity even across repeated calls`() {
        val messages = mutableListOf<String>()
        AdLogger.sink = AdLogSink { _, _, message, _ -> messages += message }
        // AdStaticText's text participates in AdLayout.identity, so this marker keeps this
        // layout's identity from colliding with any other layout built in this test suite.
        val layout = missingBadgeAndAdChoicesLayout(
            marker = "logs each warning once per distinct layout identity even across repeated calls",
        )
        check(layout.validation.warnings.isNotEmpty()) { "test fixture must produce warnings" }

        layout.logValidationWarningsOnce()
        layout.logValidationWarningsOnce() // same identity: must not log again

        assertEquals(layout.validation.warnings.size, messages.size)
        layout.validation.warnings.forEach { warning ->
            assertTrue(messages.any { it.contains(warning.message) })
        }
    }

    @Test
    fun `warning messages do not expose static layout text or the full identity`() {
        val messages = mutableListOf<String>()
        AdLogger.sink = AdLogSink { _, _, message, _ -> messages += message }
        val sensitiveStaticText = "account-owner-email@example.test"
        val layout = missingBadgeAndAdChoicesLayout(marker = sensitiveStaticText)

        layout.logValidationWarningsOnce()

        assertTrue(messages.isNotEmpty())
        assertTrue(messages.none { sensitiveStaticText in it })
        assertTrue(messages.none { layout.identity in it })
    }

    @Test
    fun `does not log when the layout has no warnings`() {
        val messages = mutableListOf<String>()
        AdLogger.sink = AdLogSink { _, _, message, _ -> messages += message }
        val layout = AdLayout(
            // A Row root, not a Column: AdLayoutValidator's "ad_attribution_not_at_top" check
            // treats a non-Column root as its own top region, so the badge is found regardless
            // of child order -- see AdLayoutValidator.kt's topRegion computation.
            root = AdContainerNode.Row(
                modifier = AdModifier.empty.copy(backgroundArgb = 0xFF000000L),
                children = listOf(
                    AdAssetNode.Headline(),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                    AdStaticText(text = "does not log when the layout has no warnings marker"),
                ),
            ),
        )
        check(layout.validation.warnings.isEmpty()) { "test fixture must produce no warnings" }

        layout.logValidationWarningsOnce()

        assertTrue(messages.isEmpty())
    }

    @Test
    fun `evicts the oldest tracked identity once the bound is exceeded`() {
        val messages = mutableListOf<String>()
        AdLogger.sink = AdLogSink { _, _, message, _ -> messages += message }

        // One more than the internal MAX_TRACKED_LAYOUT_IDENTITIES bound (64), so the first
        // layout's identity is guaranteed to have been evicted by the time the loop finishes --
        // the dedup set must stay bounded, not grow for the life of the process.
        val layouts = (1..65).map { index ->
            missingBadgeAndAdChoicesLayout(marker = "evicts the oldest tracked identity marker $index")
        }
        layouts.forEach { it.logValidationWarningsOnce() }
        val warningsPerLayout = layouts.first().validation.warnings.size
        check(warningsPerLayout > 0) { "test fixture must produce warnings" }
        val messagesAfterFirstPass = messages.size
        assertEquals(65 * warningsPerLayout, messagesAfterFirstPass)

        // Most recently tracked identity: still within the bound, still deduped.
        layouts.last().logValidationWarningsOnce()
        assertEquals(messagesAfterFirstPass, messages.size)

        // Oldest identity: evicted to stay within the bound, so it logs again.
        layouts.first().logValidationWarningsOnce()
        assertEquals(messagesAfterFirstPass + warningsPerLayout, messages.size)
    }

    private fun missingBadgeAndAdChoicesLayout(marker: String): AdLayout = AdLayout(
        root = AdContainerNode.Column(
            modifier = AdModifier.empty,
            children = listOf(
                AdAssetNode.Headline(),
                AdStaticText(text = marker),
            ),
        ),
    )
}
