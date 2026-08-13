@file:OptIn(
    androidx.compose.ui.text.ExperimentalTextApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package dev.avinya.ads.nativead.rendering

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.unit.Density
import dev.avinya.ads.AdLogger
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGDataProviderCreateWithCFData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGFontCopyPostScriptName
import platform.CoreGraphics.CGFontCreateWithDataProvider
import platform.CoreGraphics.CGFontRelease
import platform.CoreText.CTFontManagerRegisterGraphicsFont
import platform.UIKit.UIFont
import platform.UIKit.UIFontDescriptor
import platform.UIKit.UIFontDescriptorNameAttribute
import platform.UIKit.UIFontDescriptorTraitsAttribute
import platform.UIKit.UIFontWeightBold
import platform.UIKit.UIFontWeightLight
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UIFontWeightRegular
import platform.UIKit.UIFontWeightSemibold
import platform.UIKit.UIFontWeightTrait

internal fun selectClosestLoadedFont(
    fonts: List<Font>,
    requestedWeight: FontWeight,
): LoadedFont? {
    val upright = fonts.filterIsInstance<LoadedFont>().filter { it.style == FontStyle.Normal }
    if (upright.isEmpty()) return null
    upright.firstOrNull { it.weight == requestedWeight }?.let { return it }

    val target = requestedWeight.weight
    return when {
        target < 400 -> upright.filter { it.weight.weight <= target }.maxByOrNull { it.weight.weight }
            ?: upright.minByOrNull { it.weight.weight }
        target <= 500 -> upright.filter { it.weight.weight in target..500 }.minByOrNull { it.weight.weight }
            ?: upright.filter { it.weight.weight < target }.maxByOrNull { it.weight.weight }
            ?: upright.filter { it.weight.weight > 500 }.minByOrNull { it.weight.weight }
        else -> upright.filter { it.weight.weight >= target }.minByOrNull { it.weight.weight }
            ?: upright.maxByOrNull { it.weight.weight }
    }
}

internal fun LoadedFont.resolvedVariableWeight(
    requestedWeight: FontWeight,
    density: Density,
): Float = variationSettings.settings
    .firstOrNull { it.axisName == "wght" }
    ?.toVariationValue(density)
    ?: requestedWeight.weight.toFloat()

internal class IosComposeFontResolver(
    private val register: (LoadedFont, ByteArray) -> String? = ::registerComposeFont,
    private val construct: (String, Double, Float) -> UIFont? = ::constructComposeFont,
    private val logFailure: (String) -> Unit = AdLogger::w,
) {
    private data class CacheKey(
        val identity: String,
        val byteCount: Int,
        val contentHash: Int,
    )

    private val registrations = mutableMapOf<CacheKey, String?>()
    private val loggedFailures = mutableSetOf<CacheKey>()
    private val loggedUnsupportedFamilies = mutableSetOf<Int>()

    fun resolve(
        family: FontFamily,
        requestedWeight: FontWeight,
        size: Double,
        density: Density,
    ): UIFont? {
        val familyKey = family.hashCode()
        val fonts = (family as? FontListFontFamily)?.fonts ?: return unsupportedFamily(familyKey)
        val loaded = selectClosestLoadedFont(fonts, requestedWeight) ?: return unsupportedFamily(familyKey)
        val bytes = runCatching { loaded.data }.getOrElse {
            logOnce(CacheKey(loaded.identity, 0, 0), "could not read Compose font bytes", it)
            return null
        }
        val key = CacheKey(loaded.identity, bytes.size, bytes.contentHashCode())
        val postScriptName = if (registrations.containsKey(key)) {
            registrations[key]
        } else {
            register(loaded, bytes).also { registrations[key] = it }
        }
        if (postScriptName == null) {
            logOnce(key, "could not register Compose font with CoreText")
            return null
        }
        val variableWeight = loaded.resolvedVariableWeight(requestedWeight, density)
        return construct(postScriptName, size, variableWeight).also { font ->
            if (font == null) logOnce(key, "registered Compose font could not be created by UIKit")
        }
    }

    private fun logOnce(key: CacheKey, reason: String, cause: Throwable? = null) {
        if (loggedFailures.add(key)) {
            logFailure(
                "iOS native ad font fallback for '${key.identity}': $reason. " +
                    "The ad will use the system font.${cause?.message?.let { " Cause: $it" }.orEmpty()}"
            )
        }
    }

    private fun unsupportedFamily(familyKey: Int): UIFont? {
        if (loggedUnsupportedFamilies.add(familyKey)) {
            logFailure(
                "iOS native ad font fallback: AdFontFamily.FromCompose requires a loaded, " +
                    "resource-backed upright FontFamily. The ad will use the system font."
            )
        }
        return null
    }
}

internal val DefaultIosComposeFontResolver = IosComposeFontResolver()

internal inline fun registrationSucceeded(
    registered: Boolean,
    isAvailableToUIKit: () -> Boolean,
): Boolean = registered || isAvailableToUIKit()

private fun constructComposeFont(postScriptName: String, size: Double, variableWeight: Float): UIFont? {
    val traitWeight = when {
        variableWeight >= 700f -> UIFontWeightBold
        variableWeight >= 600f -> UIFontWeightSemibold
        variableWeight >= 500f -> UIFontWeightMedium
        variableWeight < 400f -> UIFontWeightLight
        else -> UIFontWeightRegular
    }
    val descriptor = UIFontDescriptor(
        fontAttributes = mapOf(
            UIFontDescriptorNameAttribute to postScriptName,
            UIFontDescriptorTraitsAttribute to mapOf(UIFontWeightTrait to traitWeight),
        )
    )
    return UIFont.fontWithDescriptor(descriptor, size)
}

private fun registerComposeFont(@Suppress("UNUSED_PARAMETER") loaded: LoadedFont, bytes: ByteArray): String? {
    if (bytes.isEmpty()) return null
    return bytes.usePinned { pinned ->
        val data = CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()) ?: return@usePinned null
        try {
            val provider = CGDataProviderCreateWithCFData(data) ?: return@usePinned null
            try {
                val cgFont = CGFontCreateWithDataProvider(provider) ?: return@usePinned null
                try {
                    val copiedName = CGFontCopyPostScriptName(cgFont) ?: return@usePinned null
                    try {
                        val postScriptName = copiedName.utf8String() ?: return@usePinned null
                        if (UIFont.fontWithName(postScriptName, 1.0) != null) return@usePinned postScriptName
                        val registered = CTFontManagerRegisterGraphicsFont(cgFont, null)
                        postScriptName.takeIf {
                            registrationSucceeded(registered) { UIFont.fontWithName(it, 1.0) != null }
                        }
                    } finally {
                        CFRelease(copiedName)
                    }
                } finally {
                    CGFontRelease(cgFont)
                }
            } finally {
                CGDataProviderRelease(provider)
            }
        } finally {
            CFRelease(data)
        }
    }
}

private fun CFStringRef?.utf8String(): String? = this?.let { value ->
    memScoped {
        val maxLength = CFStringGetMaximumSizeForEncoding(
            CFStringGetLength(value),
            kCFStringEncodingUTF8,
        ) + 1
        val buffer = allocArray<ByteVar>(maxLength)
        if (CFStringGetCString(value, buffer, maxLength, kCFStringEncodingUTF8)) {
            buffer.toKString()
        } else {
            null
        }
    }
}
