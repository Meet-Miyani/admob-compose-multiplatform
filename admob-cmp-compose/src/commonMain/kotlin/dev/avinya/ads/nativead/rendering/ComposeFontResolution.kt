package dev.avinya.ads.nativead.rendering

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.avinya.ads.nativead.layout.AdAssetNode
import dev.avinya.ads.nativead.layout.AdContainerNode
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdNode
import dev.avinya.ads.nativead.layout.AdStaticText
import dev.avinya.ads.nativead.layout.AdTextStyle

@Immutable
internal data class ComposeFontRequest(
    val fontFamily: FontFamily,
    val fontWeight: FontWeight,
)

@Immutable
internal data class ResolvedComposeFonts(
    private val values: Map<ComposeFontRequest, Any> = emptyMap(),
) {
    operator fun get(request: ComposeFontRequest): Any? = values[request]

    companion object {
        val Empty = ResolvedComposeFonts()
    }
}

internal fun AdFontWeight.toComposeFontWeight(): FontWeight = when (this) {
    AdFontWeight.Normal -> FontWeight.Normal
    AdFontWeight.Medium -> FontWeight.Medium
    AdFontWeight.Bold -> FontWeight.Bold
}

internal fun AdTextStyle.composeFontRequestOrNull(): ComposeFontRequest? {
    val composeFamily = fontFamily as? AdFontFamily.FromCompose ?: return null
    return ComposeFontRequest(composeFamily.fontFamily, fontWeight.toComposeFontWeight())
}

internal fun AdFontFamily.resolvedComposeValueOrNull(
    weight: AdFontWeight,
    resolvedFonts: ResolvedComposeFonts,
): Any? {
    val composeFamily = this as? AdFontFamily.FromCompose ?: return null
    return resolvedFonts[ComposeFontRequest(composeFamily.fontFamily, weight.toComposeFontWeight())]
}

internal fun AdLayout.composeFontRequests(): Set<ComposeFontRequest> = buildSet {
    fun collectStyle(style: AdTextStyle) {
        style.composeFontRequestOrNull()?.let(::add)
    }

    fun collectNode(node: AdNode) {
        when (node) {
            is AdContainerNode -> node.children.forEach(::collectNode)
            is AdStaticText -> collectStyle(node.style)
            is AdAssetNode.Headline -> collectStyle(node.style)
            is AdAssetNode.Body -> collectStyle(node.style)
            is AdAssetNode.CallToAction -> collectStyle(node.style.textStyle)
            is AdAssetNode.Advertiser -> collectStyle(node.style)
            is AdAssetNode.Price -> collectStyle(node.style)
            is AdAssetNode.Store -> collectStyle(node.style)
            is AdAssetNode.StarRating -> collectStyle(node.style)
            is AdAssetNode.AdBadge -> collectStyle(node.style)
            is AdAssetNode.Icon,
            is AdAssetNode.Media,
            is AdAssetNode.AdChoices,
            is dev.avinya.ads.nativead.layout.AdSpacer,
            -> Unit
        }
    }

    collectNode(root)
}

@Composable
internal fun rememberResolvedComposeFonts(layout: AdLayout): ResolvedComposeFonts {
    val resolver = LocalFontFamilyResolver.current
    val requests = remember(layout) { layout.composeFontRequests() }
    val values = requests.associateWith { request ->
        resolver.resolve(request.fontFamily, request.fontWeight).value
    }
    return ResolvedComposeFonts(values)
}
