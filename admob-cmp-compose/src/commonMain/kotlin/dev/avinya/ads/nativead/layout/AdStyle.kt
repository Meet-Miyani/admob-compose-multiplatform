package dev.avinya.ads.nativead.layout

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontFamily

/** Alignment types used in native ad layout containers. */
public object AdAlignment {
    /** Horizontal alignment values. */
    public enum class Horizontal { Start, CenterHorizontally, End }
    /** Vertical alignment values. */
    public enum class Vertical { Top, CenterVertically, Bottom }
    /** Box content alignment values. */
    public enum class Box { TopStart, TopCenter, TopEnd, CenterStart, Center, CenterEnd, BottomStart, BottomCenter, BottomEnd }
}

/**
 * Font family for text nodes in native ad layouts.
 *
 * System families are guaranteed available on every platform. [FromCompose] is the
 * recommended path for fonts packaged with Compose Resources. [Named] is an advanced
 * escape hatch for fonts already installed or registered with the platform.
 *
 * [FromCompose] bridges a Compose [FontFamily] into the layout DSL.
 */
@Immutable
public sealed interface AdFontFamily {
    /** Platform default sans-serif font. */
    public data object Default : AdFontFamily
    /** Explicit sans-serif font family (e.g. Roboto on Android, SF Pro on iOS). */
    public data object SansSerif : AdFontFamily
    /** Serif font family (e.g. Noto Serif on Android, New York on iOS). */
    public data object Serif : AdFontFamily
    /** Monospace font family (e.g. Roboto Mono on Android, SF Mono on iOS). */
    public data object Monospace : AdFontFamily

    /**
     * A named font family resolved against the platform font registry.
     *
     * On Android, maps to `Typeface.create(name, style)`.
     * On iOS, maps to `UIFont(name:size:)` with PostScript name lookup.
     * Falls back to [Default] if the name is not found.
     */
    public data class Named(val name: String) : AdFontFamily

    /**
     * Bridges a Compose [FontFamily] into the ad layout DSL.
     *
     * Preview uses the family directly. Android resolves it through Compose and applies
     * the resolved `Typeface`. iOS registers bytes from a resource-backed Compose family
     * with CoreText and creates the matching `UIFont`. No Android font XML, iOS
     * `UIAppFonts`, or platform font name is required. Unsupported, unavailable, or
     * corrupt families fall back to the platform system font without throwing.
     */
    public data class FromCompose(
        val fontFamily: FontFamily
    ) : AdFontFamily
}

/**
 * Style configuration for text nodes in native ad layouts. Includes font
 * size, colour, weight, alignment, and font family.
 */
@Immutable
public data class AdTextStyle(
    /** Font size in scaled pixels. */
    val fontSizeSp: Float = 14f,
    /** Text colour as ARGB long. */
    val colorArgb: Long = 0xFF202124,
    /** Font weight. */
    val fontWeight: AdFontWeight = AdFontWeight.Normal,
    /** Text alignment. */
    val textAlign: AdTextAlign = AdTextAlign.Start,
    /** Font family. */
    val fontFamily: AdFontFamily = AdFontFamily.Default
) {
    public companion object {
        /** Title preset (16sp, bold). */
        public val title: AdTextStyle = AdTextStyle(16f, 0xFF111111, AdFontWeight.Bold)
        /** Body text preset (14sp, normal). */
        public val body: AdTextStyle = AdTextStyle(14f, 0xFF3C4043)
        /** Caption preset (12sp, normal). */
        public val caption: AdTextStyle = AdTextStyle(12f, 0xFF5F6368)
        /** Badge preset (11sp, bold). */
        public val badge: AdTextStyle = AdTextStyle(11f, 0xFF333333, AdFontWeight.Bold)
    }
}

/**
 * Style configuration for call-to-action buttons in native ad layouts.
 */
@Immutable
public data class AdButtonStyle(
    /** Text style for the button label. */
    val textStyle: AdTextStyle = AdTextStyle(14f, 0xFFFFFFFF, AdFontWeight.Bold, AdTextAlign.Center),
    /** Background colour as ARGB long. */
    val backgroundArgb: Long = 0xFF1A73E8,
    /** Corner radius in dp. */
    val cornerRadiusDp: Float = 8f,
    /** Horizontal padding inside the button in dp. */
    val horizontalPaddingDp: Float = 12f
) {
    public companion object {
        /** Filled button preset (blue background, white text). */
        public val filled: AdButtonStyle = AdButtonStyle()
        /** Text-button preset (transparent background, blue text). */
        public val text: AdButtonStyle = AdButtonStyle(
            textStyle = AdTextStyle(14f, 0xFF1A73E8, AdFontWeight.Bold, AdTextAlign.Center),
            backgroundArgb = 0x00000000,
            cornerRadiusDp = 0f
        )
    }
}

/**
 * Style configuration for image assets (icon, media) in native ad layouts.
 */
@Immutable
public data class AdImageStyle(
    /** Content scale mode (Fit, Crop, FillBounds). */
    val contentScale: AdContentScale = AdContentScale.Fit,
    /** Background colour as ARGB long, or null for transparent. */
    val backgroundArgb: Long? = null
)

/**
 * Style configuration for container nodes.
 */
@Immutable
public data class AdContainerStyle(
    /** Background colour as ARGB long, or null for transparent. */
    val backgroundArgb: Long? = null,
    /** Corner radius in dp. */
    val cornerRadiusDp: Float = 0f
)

/** Colour roles used in native ad theming. */
public enum class AdColorRole { Background, OnBackground, Primary, OnPrimary, Outline, Muted }
/** Font weight options. */
public enum class AdFontWeight { Normal, Medium, Bold }
/** Text alignment options. */
public enum class AdTextAlign { Start, Center, End }
/** Content scale mode for images. */
public enum class AdContentScale { Fit, Crop, FillBounds }

/**
 * Controls layout behavior when a native ad asset is missing from the
 * creative. [HideWhenMissing] removes the node entirely, losing its space.
 * [InvisibleWhenMissing] keeps the space but renders nothing.
 * [KeepSpace] always reserves the space, even when the asset is absent.
 */
public enum class AdVisibilityPolicy { HideWhenMissing, InvisibleWhenMissing, KeepSpace }
