package dev.avinya.ads

import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutValidator
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdNode
import dev.avinya.ads.nativead.layout.AdSpacer
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdStaticText
import dev.avinya.ads.nativead.layout.AdTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp

class AdLayoutValidatorTest {

    @Test
    fun `valid layout with headline badge and adchoices passes`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.AdChoices(),
                )
            )
        )
        assertTrue(layout.validation.isValid)
    }

    @Test
    fun `layout missing AdBadge produces warning`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Headline(),
                )
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(warnings.any { it.code == "missing_ad_badge" })
    }

    @Test
    fun `empty container produces warning`() {
        val layout = AdLayout(
            root = AdContainerNode.Row(
                modifier = AdModifier.empty,
                children = emptyList()
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(warnings.any { it.code == "empty_container" })
    }

    @Test
    fun `layout with no renderable assets produces error`() {
        val layout = AdLayout(
            root = AdContainerNode.Box(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.AdBadge(),
                )
            )
        )
        assertFalse(layout.validation.isValid)
        assertTrue(layout.validation.errors.any { it.code == "missing_renderable_asset" })
    }

    @Test
    fun `identity is stable for equal trees`() {
        val root1 = AdContainerNode.Column(
            modifier = AdModifier.empty,
            children = listOf(
                AdAssetNode.Headline(),
                AdAssetNode.AdBadge(),
            )
        )
        val root2 = AdContainerNode.Column(
            modifier = AdModifier.empty,
            children = listOf(
                AdAssetNode.Headline(),
                AdAssetNode.AdBadge(),
            )
        )
        val layout1 = AdLayout(root = root1)
        val layout2 = AdLayout(root = root2)
        assertEquals(layout1.identity, layout2.identity)
    }

    @Test
    fun `identity differs when a node changes`() {
        val root1 = AdContainerNode.Column(
            modifier = AdModifier.empty,
            children = listOf(AdAssetNode.Headline())
        )
        val root2 = AdContainerNode.Column(
            modifier = AdModifier.empty,
            children = listOf(AdAssetNode.Headline(maxLines = 2))
        )
        val layout1 = AdLayout(root = root1)
        val layout2 = AdLayout(root = root2)
        assertTrue(layout1.identity != layout2.identity)
    }

    @Test
    fun `copy with a changed root changes the identity`() {
        // Regression test: identity used to default to root.identity() but be stored as
        // ordinary constructor state, so copy(root = changedRoot) silently kept the OLD identity.
        val original = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(AdAssetNode.Headline())
            )
        )
        val changed = original.copy(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(AdAssetNode.Headline(), AdAssetNode.Body())
            )
        )
        assertTrue(
            original.identity != changed.identity,
            "a changed tree must not reuse the old identity, or the native view is never rebuilt"
        )
    }

    @Test
    fun `blank static text triggers warning`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdStaticText(text = "   "),
                    AdAssetNode.Headline(),
                    AdAssetNode.AdBadge(),
                )
            )
        )
        val warnings = layout.validation.warnings
        assertTrue(warnings.any { it.code == "blank_static_text" })
    }

    @Test
    fun `ad attribution below the top region triggers a policy warning`() {
        val layout = AdLayout(
            root = AdContainerNode.Column(
                modifier = AdModifier.empty,
                children = listOf(
                    AdAssetNode.Media(),
                    AdAssetNode.AdBadge(),
                    AdAssetNode.Headline(),
                ),
            ),
        )

        assertTrue(
            layout.validation.warnings.any { it.code == "ad_attribution_not_at_top" },
        )
    }

    @Test
    fun `default ad attribution meets Google's minimum badge dimensions`() {
        val badge = AdAssetNode.AdBadge()

        assertEquals(15.dp.value, badge.modifier.minWidthDp)
        assertEquals(15.dp.value, badge.modifier.minHeightDp)
    }

    @Test
    fun `built in template attribution meets Google's minimum badge dimensions`() {
        fun findBadge(node: AdNode): AdAssetNode.AdBadge? = when (node) {
            is AdAssetNode.AdBadge -> node
            is AdContainerNode -> node.children.firstNotNullOfOrNull(::findBadge)
            else -> null
        }

        val badge = requireNotNull(findBadge(AdTemplates.medium.root))
        assertEquals(15.dp.value, badge.modifier.minWidthDp)
        assertEquals(15.dp.value, badge.modifier.minHeightDp)
    }

    /**
     * Mutating a list handed to a container cannot change an already-built layout.
     *
     * Kotlin's `List` is read-only, not immutable, so passing a `MutableList` where one is
     * expected compiles silently and is idiomatic. The container kept that exact instance,
     * while `identity` and `validation` were computed once at construction — so editing the
     * list afterwards produced a layout whose cached report described a tree that no longer
     * existed, under an identity that never changed, on a class annotated `@Immutable`.
     */
    @Test
    fun `mutating the caller's child list cannot change a built layout`() {
        val children = mutableListOf<AdNode>(
            AdAssetNode.AdBadge(),
            AdAssetNode.Headline(),
            AdAssetNode.AdChoices(),
        )
        val layout = AdLayout(root = AdContainerNode.Column(AdModifier.empty, children))
        val identityBefore = layout.identity
        val warningsBefore = layout.validation.warnings.map { it.code }.toSet()

        children.clear()

        assertEquals(identityBefore, layout.identity, "identity must describe the validated tree")
        assertEquals(
            warningsBefore,
            layout.validation.warnings.map { it.code }.toSet(),
            "the validation report must keep describing the tree it validated",
        )
        assertEquals(
            3,
            (layout.frozenRoot as AdContainerNode).children.size,
            "the rendered tree must not lose its children to a caller-side edit",
        )
    }
}
