package dev.avinya.ads

import androidx.compose.ui.unit.dp
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdDisplay
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdModifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdLayoutValidatorVisibilityTest {

    @Test
    fun `hidden AdDisplay node is flagged as hidden_asset`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(modifier = AdModifier.empty.copy(display = AdDisplay.Gone)),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                    AdAssetNode.Body(),
                )
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(warnings.any { it.code == "hidden_asset" && it.nodePath == "root/column[0]" })
        assertTrue(warnings.any { it.code == "missing_headline" })
    }

    @Test
    fun `zero-alpha node is flagged as hidden_asset`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(modifier = AdModifier.empty.copy(alpha = 0f)),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                    AdAssetNode.Body(),
                )
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(warnings.any { it.code == "hidden_asset" && it.nodePath == "root/column[0]" })
        assertTrue(warnings.any { it.code == "missing_headline" })
    }

    @Test
    fun `zero-dimension node is flagged as hidden_asset`() {
        val layoutWidth0 = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(modifier = AdModifier.empty.width(0.dp)),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                    AdAssetNode.Body(),
                )
            )
        )
        assertTrue(layoutWidth0.validation.warnings.any { it.code == "hidden_asset" })

        val layoutHeight0 = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(modifier = AdModifier.empty.height(0.dp)),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                    AdAssetNode.Body(),
                )
            )
        )
        assertTrue(layoutHeight0.validation.warnings.any { it.code == "hidden_asset" })
    }

    @Test
    fun `hidden required content asset fails validation when no other content asset is visible`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(modifier = AdModifier.empty.copy(display = AdDisplay.Gone)),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        val validation = layout.validation
        assertFalse(validation.isValid, "Layout with only hidden content assets must not be valid")
        assertTrue(validation.errors.any { it.code == "missing_renderable_asset" })
    }

    // The policy cases below are the reason the visibility rules exist. A native ad that
    // renders with no visible "Ad" attribution is an AdMob policy violation, and the validator
    // previously certified these as compliant because it recorded assets by node type alone.

    @Test
    fun `gone ad badge is reported as missing attribution`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.AdBadge(modifier = AdModifier.empty.copy(display = AdDisplay.Gone)),
                    AdAssetNode.Headline(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(
            warnings.any { it.code == "missing_ad_badge" },
            "an ad badge hidden with gone() must not satisfy the attribution requirement; got $warnings"
        )
    }

    @Test
    fun `zero-alpha ad badge is reported as missing attribution`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.AdBadge(modifier = AdModifier.empty.copy(alpha = 0f)),
                    AdAssetNode.Headline(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        assertTrue(
            layout.validation.warnings.any { it.code == "missing_ad_badge" },
            "an ad badge with alpha=0 must not satisfy the attribution requirement"
        )
    }

    @Test
    fun `ad badge inside a gone container is reported as missing attribution`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdContainerNode.Row(
                        modifier = AdModifier.empty.copy(display = AdDisplay.Gone),
                        children = listOf(AdAssetNode.AdBadge())
                    ),
                    AdAssetNode.Headline(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        assertTrue(
            layout.validation.warnings.any { it.code == "missing_ad_badge" },
            "a badge inside a gone container is not visible and must not satisfy attribution"
        )
    }

    @Test
    fun `visible ad badge at the top satisfies attribution`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.AdBadge(),
                    AdAssetNode.Headline(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        val warnings = layout.validation.warnings
        // Guards against over-correction: the visibility rules must not start reporting
        // correctly-built layouts as non-compliant.
        assertFalse(
            warnings.any { it.code == "missing_ad_badge" },
            "a plain visible adBadge() must satisfy attribution; got $warnings"
        )
        assertFalse(
            warnings.any { it.code == "ad_attribution_not_at_top" },
            "a badge as the first child of the root column is at the top; got $warnings"
        )
        assertFalse(
            warnings.any { it.code == "hidden_asset" },
            "nothing in this layout is hidden; got $warnings"
        )
    }

    @Test
    fun `blank ad badge is reported as missing attribution`() {
        // A badge is "visible" by every geometric rule yet renders nothing. Both renderers pass
        // node.text through unchanged, so this ships an ad with no attribution. The realistic
        // route is a localized string that resolves to empty for one locale.
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.AdBadge(text = ""),
                    AdAssetNode.Headline(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(
            warnings.any { it.code == "blank_ad_badge" },
            "a blank badge must be named as blank, not silently accepted; got $warnings"
        )
        assertTrue(
            warnings.any { it.code == "missing_ad_badge" },
            "a blank badge must not satisfy the attribution requirement; got $warnings"
        )
    }

    @Test
    fun `whitespace-only ad badge is reported as missing attribution`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.AdBadge(text = "   "),
                    AdAssetNode.Headline(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        assertTrue(
            layout.validation.warnings.any { it.code == "missing_ad_badge" },
            "whitespace renders as nothing, so it is not attribution either"
        )
    }

    @Test
    fun `a blank badge at the top does not satisfy attribution-at-top either`() {
        // Guards the second, independent badge check: containsAdBadge() keyed on node type
        // alone, so a blank badge suppressed this warning as well.
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(),
                    AdAssetNode.AdBadge(text = ""),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(
            warnings.any { it.code == "missing_ad_badge" },
            "got $warnings"
        )
    }

    @Test
    fun `hidden parent container marks all children as hidden_asset`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty.copy(display = AdDisplay.Gone),
                children = listOf(
                    AdAssetNode.Headline(),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        val warnings = layout.validation.warnings
        val hiddenAssets = warnings.filter { it.code == "hidden_asset" }
        assertTrue(hiddenAssets.size >= 3, "All children of hidden container should be reported as hidden_asset")
    }
}
