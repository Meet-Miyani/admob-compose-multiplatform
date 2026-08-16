package dev.avinya.ads.nativead.layout

import androidx.compose.runtime.Immutable
import dev.avinya.ads.nativead.rendering.resolveNativeAdSurfaceArgb

/**
 * Result of validating an [AdLayout] tree. [errors] are structural problems
 * that prevent rendering; [warnings] indicate missing policy-relevant assets
 * (headline, ad badge, AdChoices).
 */
@Immutable
public data class AdLayoutValidationReport(
    /** Errors that prevent the layout from rendering. */
    val errors: List<AdLayoutValidationIssue> = emptyList(),
    /** Warnings about missing policy-relevant assets. */
    val warnings: List<AdLayoutValidationIssue> = emptyList()
) {
    /** True when there are no errors (warnings are acceptable). */
    public val isValid: Boolean get() = errors.isEmpty()
    /** True when there are warnings. */
    public val hasWarnings: Boolean get() = warnings.isNotEmpty()
}

/**
 * A single validation issue found during [AdLayoutValidator.validate].
 */
@Immutable
public data class AdLayoutValidationIssue(
    /** Machine-readable issue code (e.g., "missing_headline", "empty_container"). */
    val code: String,
    /** Human-readable description of the issue. */
    val message: String,
    /** Path to the offending node in the layout tree (e.g., "root/column[0]"). */
    val nodePath: String
)

/**
 * Validates a native [AdLayout] tree.
 *
 * Missing headline / ad badge / AdChoices space are reported as **warnings, by design** —
 * this mirrors the official AdMob SDKs, which log and still render (the SDK draws its own
 * AdChoices overlay regardless of the layout). Only a layout with no renderable asset at
 * all is an error. The library does not throw or refuse to render on warnings; consumers
 * decide how to react to [AdLayoutValidationReport.warnings].
 */
public object AdLayoutValidator {
    /**
     * Validates a native ad [AdNode] tree. Checks for missing required assets
     * (headline, ad badge, AdChoices) and structural problems.
     *
     * @param root The root node of the layout tree to validate.
     * @return A validation report with errors and warnings.
     */
    public fun validate(root: AdNode): AdLayoutValidationReport {
        val warnings = mutableListOf<AdLayoutValidationIssue>()
        val errors = mutableListOf<AdLayoutValidationIssue>()
        val assets = mutableSetOf<String>()

        fun isVisible(node: AdNode, parentVisible: Boolean): Boolean {
            if (!parentVisible) return false
            val m = node.modifier
            if (m.display != AdDisplay.Visible) return false
            if (m.alpha <= 0f) return false
            if (m.width is AdLayoutSize.Fixed && m.width.dp <= 0f) return false
            if (m.height is AdLayoutSize.Fixed && m.height.dp <= 0f) return false
            return true
        }

        fun visit(node: AdNode, path: String, parentVisible: Boolean = true) {
            val visible = isVisible(node, parentVisible)
            when (node) {
                is AdContainerNode.Row -> {
                    if (node.children.isEmpty()) warnings += warning("empty_container", "Row has no children.", path)
                    node.children.forEachIndexed { index, child -> visit(child, "$path/row[$index]", visible) }
                }
                is AdContainerNode.Column -> {
                    if (node.children.isEmpty()) warnings += warning("empty_container", "Column has no children.", path)
                    node.children.forEachIndexed { index, child -> visit(child, "$path/column[$index]", visible) }
                }
                is AdContainerNode.Box -> {
                    if (node.children.isEmpty()) warnings += warning("empty_container", "Box has no children.", path)
                    node.children.forEachIndexed { index, child -> visit(child, "$path/box[$index]", visible) }
                }
                is AdSpacer -> Unit
                is AdStaticText -> {
                    if (node.text.isBlank()) warnings += warning("blank_static_text", "Static text is blank.", path)
                }
                is AdAssetNode -> {
                    if (visible) {
                        val key = when (node) {
                            is AdAssetNode.Headline -> "headline"
                            is AdAssetNode.Body -> "body"
                            is AdAssetNode.CallToAction -> "call_to_action"
                            is AdAssetNode.Icon -> "icon"
                            is AdAssetNode.Media -> "media"
                            is AdAssetNode.Advertiser -> "advertiser"
                            is AdAssetNode.Price -> "price"
                            is AdAssetNode.Store -> "store"
                            is AdAssetNode.StarRating -> "star_rating"
                            is AdAssetNode.AdChoices -> "ad_choices"
                            is AdAssetNode.AdBadge -> "ad_badge"
                        }
                        assets += key
                    } else {
                        warnings += warning("hidden_asset", "Asset ${node::class.simpleName} is hidden (gone, alpha=0, or 0 size).", path)
                    }
                }
            }
        }

        visit(root, "root")

        fun containsAdBadge(node: AdNode, parentVisible: Boolean = true): Boolean {
            val visible = isVisible(node, parentVisible)
            if (!visible) return false
            return when (node) {
                is AdAssetNode.AdBadge -> true
                is AdContainerNode -> node.children.any { containsAdBadge(it, visible) }
                else -> false
            }
        }
        val topRegion = when (root) {
            is AdContainerNode.Column -> root.children.firstOrNull()
            else -> root
        }

        if ("headline" !in assets) {
            warnings += warning("missing_headline", "Native layout does not include a headline asset.", "root")
        }
        if ("ad_badge" !in assets) {
            warnings += warning("missing_ad_badge", "Native layout should include a visible ad attribution badge.", "root")
        } else if (topRegion?.let(::containsAdBadge) != true) {
            warnings += warning(
                "ad_attribution_not_at_top",
                "Ad attribution must be displayed at the top of the native ad.",
                "root",
            )
        }
        if ("ad_choices" !in assets) {
            warnings += warning("missing_ad_choices_space", "Native layout should reserve space for the SDK-owned AdChoices overlay.", "root")
        }
        if (resolveNativeAdSurfaceArgb(root) == null) {
            warnings += warning(
                "transparent_root_background",
                "Native layout root has no opaque background. On iOS the ad is embedded below " +
                    "Compose's canvas, which clears every Compose pixel under it, so the ad will " +
                    "render over the platform backdrop (white in light mode, black in dark) " +
                    "instead of the app's surface. Set AdModifier.background(...) on the root.",
                "root",
            )
        }
        if (assets.none { it in contentAssets }) {
            errors += error("missing_renderable_asset", "Native layout has no renderable ad asset.", "root")
        }

        return AdLayoutValidationReport(errors = errors, warnings = warnings)
    }

    private val contentAssets = setOf(
        "headline",
        "body",
        "call_to_action",
        "icon",
        "media",
        "advertiser",
        "price",
        "store",
        "star_rating"
    )

    private fun warning(code: String, message: String, path: String): AdLayoutValidationIssue =
        AdLayoutValidationIssue(code, message, path)

    private fun error(code: String, message: String, path: String): AdLayoutValidationIssue =
        AdLayoutValidationIssue(code, message, path)
}
