# SDK Enhancements — Design Specification

Four enhancements identified during the deep SDK audit. Ordered by implementation
risk: the internal-only changes first, the public-API addition last.

---

## Enhancement 1: iOS Targeting Parity (PPID / categoryExclusions)

### Problem

`AdRequestOptions.publisherProvidedId` and `AdRequestOptions.categoryExclusions`
are documented as "Android only" and silently ignored on iOS. However, iOS GMA
13.x does support both — on `GAMRequest` (a subclass of `GADRequest`), not on
standard `GADRequest`.

### Design

**Internal mapper change only. Zero public API or ABI impact.**

In `IosAdMappers.kt`, `toGADRequest()` currently always instantiates
`GADRequest()`. Change it to:

```kotlin
internal fun AdRequestOptions.toGADRequest(): GADRequest {
    val needsAdManager = publisherProvidedId != null ||
                         categoryExclusions.isNotEmpty()
    val request = if (needsAdManager) GAMRequest() else GADRequest()

    // ... existing field mappings ...

    if (request is GAMRequest) {
        publisherProvidedId?.let { request.publisherProvidedID = it }
        if (categoryExclusions.isNotEmpty()) {
            request.categoryExclusions = categoryExclusions.toList()
        }
    }
    return request
}
```

`GAMRequest` is a `GADRequest` subclass and is accepted everywhere `GADRequest`
is — `GADInterstitialAd.loadWithAdUnitID`, `GADBannerView`, `GADRewardedAd`,
`GADAdLoader`, etc. No call-site changes are needed.

`skipUninitializedAdapters` stays Android-only: iOS GMA has no per-request
equivalent, and that is correct — the iOS SDK initializes all configured
adapters during `start()`.

### Files Changed

| File | Change |
|---|---|
| [IosAdMappers.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosAdMappers.kt) | Conditional `GAMRequest` instantiation |
| [AdPlacement.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdPlacement.kt) | Update KDoc: remove "Android only" from PPID and categoryExclusions |

### KDoc Update

```kotlin
/**
 * Publisher-provided identifier for frequency capping, audience
 * segmentation, and attribution. Mapped on Android via
 * `setPublisherProvidedId()` and on iOS via `GAMRequest.publisherProvidedID`.
 *
 * Requires an Ad Manager–enabled ad unit on iOS; standard AdMob units
 * may ignore this field.
 */
val publisherProvidedId: String? = null,

/**
 * Ad Manager category exclusions. Mapped on Android via
 * `addCategoryExclusion()` and on iOS via `GAMRequest.categoryExclusions`.
 *
 * Requires an Ad Manager–enabled ad unit on iOS; standard AdMob units
 * may ignore this field.
 */
val categoryExclusions: Set<String> = emptySet(),
```

### Verification

- `./gradlew :admob-cmp-core:compileKotlinIosArm64` — confirms `GAMRequest`
  resolves against the cinterop.
- `./gradlew :admob-cmp-core:checkKotlinAbi` — must pass unchanged (no public
  API change).

### Risk

**Minimal.** `GAMRequest` is a documented, stable public class in Google Mobile
Ads iOS. The change is purely internal.

---

## Enhancement 2: Native Video Callback Documentation

### Problem

Five video lifecycle events (`VideoStarted`, `VideoPlayed`, `VideoPaused`,
`VideoEnded`, `VideoMuted`) are defined in the common `AdEvent` model and
emitted on iOS via `GADVideoControllerDelegateProtocol`. On Android GMA
Next-Gen, no equivalent API exists — `NativeAdEventCallback` exposes only
impression, click, and paid events.

### Design

**Documentation and monitoring only. No code changes.**

This is an upstream Google Mobile Ads SDK limitation. The Next-Gen SDK
(`com.google.android.libraries.ads.mobile.sdk`) does not expose `VideoController`
or `VideoLifecycleCallbacks`. Mixing with the legacy SDK
(`com.google.android.gms.ads`) is incompatible and unsupported.

### Actions

1. Add `@since` annotations and platform-availability notes to the five
   `AdEvent` subclasses in [AdTelemetry.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdTelemetry.kt):

   ```kotlin
   /**
    * Emitted when native ad video playback starts for the first time.
    *
    * **Platform availability:**
    * - iOS: emitted via `GADVideoControllerDelegate.videoControllerDidPlayVideo`.
    * - Android: not yet emitted — the GMA Next-Gen SDK does not expose video
    *   lifecycle callbacks. Support will be added when that API ships.
    *
    * @since 1.0.0
    */
   data class VideoStarted(...)
   ```

2. Add a tracking note to the docs site explaining the gap.

3. Monitor the [GMA Next-Gen release notes](https://developers.google.com/admob/android/rel-notes)
   for video controller additions.

### Verification

- `./gradlew :admob-cmp-core:checkKotlinAbi` — no ABI change.
- Review KDoc renders correctly via `./gradlew syncApiDocsToDocsSite`.

### Risk

**None.** Documentation only.

---

## Enhancement 3: Per-Request Audio Control for Full-Screen Ads

### Problem

`appMuted` and `appVolume` are global configuration applied once during
`initialize()` via `MobileAds.setUserMutedApp()` (Android) /
`GADMobileAds.sharedInstance.applicationMuted` (iOS). Neither platform SDK
supports per-ad audio control for full-screen formats. A publisher who wants
to mute rewarded ads but not interstitials must manually save/restore the
global state around each `show()` call.

### Design

**Add optional audio overrides to `FullScreenAdOptions` with automatic
save-restore around presentation.**

#### Public API Addition (additive, non-breaking)

```kotlin
// In FullScreenAdModels.kt
public data class FullScreenAdOptions(
    val immersiveMode: Boolean = false,
    val serverSideVerification: ServerSideVerificationOptions? = null,
    // NEW — per-presentation audio overrides
    val audioMuted: Boolean? = null,
    val audioVolume: Float? = null,
)
```

Both default to `null` (= "don't override, keep whatever the global config
says"). This is additive: existing call sites that don't pass these parameters
see zero behaviour change.

#### Internal Mechanism

In `FullScreenSlotCore.presentAd()`, wrap the platform `presentAd` call:

```kotlin
// Before presentation
val savedMuted = globalConfig.appMuted
val savedVolume = globalConfig.appVolume
options.audioMuted?.let { platformSetMuted(it) }
options.audioVolume?.let { platformSetVolume(it.coerceIn(0f, 1f)) }

try {
    val result = platformPresentAd(loaded, options, presentation, rewardDelivery)
    return result
} finally {
    // Restore on dismiss (or failure)
    savedMuted?.let { platformSetMuted(it) } ?: platformSetMuted(false)
    savedVolume?.let { platformSetVolume(it) } ?: platformSetVolume(1f)
}
```

This is concurrency-safe because `FullScreenPresentationArbiter` guarantees
exactly one full-screen ad presents at a time.

#### Platform Helpers

**Android** (`AndroidGoogleAdManager.kt`):
```kotlin
internal fun platformSetMuted(muted: Boolean) {
    MobileAds.setUserMutedApp(muted)
}
internal fun platformSetVolume(volume: Float) {
    MobileAds.setUserControlledAppVolume(volume)
}
```

**iOS** (`IosGoogleAdManager.kt`):
```kotlin
internal fun platformSetMuted(muted: Boolean) {
    GADMobileAds.sharedInstance.applicationMuted = muted
}
internal fun platformSetVolume(volume: Float) {
    GADMobileAds.sharedInstance.applicationVolume = volume
}
```

### Files Changed

| File | Change |
|---|---|
| [FullScreenAdModels.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/FullScreenAdModels.kt) | Add `audioMuted`, `audioVolume` to `FullScreenAdOptions` |
| [FullScreenSlotCore.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/FullScreenSlotCore.kt) | Save-restore wrapper around presentation |
| [AndroidGoogleAdManager.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/androidMain/kotlin/dev/avinya/ads/AndroidGoogleAdManager.kt) | `platformSetMuted` / `platformSetVolume` helpers |
| [IosGoogleAdManager.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosGoogleAdManager.kt) | Same helpers |
| `admob-cmp-core/api/*.klib.api` | Regenerated (additive) |

### KDoc

```kotlin
/**
 * Override the global app audio mute state for the duration of this
 * full-screen presentation.
 *
 * When non-null, the global [GlobalRequestConfiguration.appMuted] is
 * temporarily set to this value before presentation and restored to
 * the previous value when the ad is dismissed or fails to show.
 *
 * **Caveat:** because Google Mobile Ads only supports process-global
 * audio control, this momentarily affects any other ad surface
 * (banners, native) that is concurrently playing video. The effect
 * lasts only for the duration of the full-screen presentation.
 *
 * `null` means "use the current global setting" (the default).
 */
val audioMuted: Boolean? = null,
```

### Verification

- `./gradlew :admob-cmp-core:updateKotlinAbi` — regenerate the ABI dump.
- `./gradlew :admob-cmp-core:checkKotlinAbi` — passes with updated dump.
- Manual test: play a rewarded ad with `audioMuted = true` and confirm silence.

### Risk

**Low.** The arbiter's single-presentation guarantee makes the save-restore
safe.

---

## Enhancement 4: Font Family in `AdTextStyle`

### Problem

`AdTextStyle` carries `fontSizeSp`, `colorArgb`, `fontWeight`, and `textAlign`
but no font family. The showcase's `feedRowAdLayout` explicitly comments:
"The one thing that cannot match is the family: `AdTextStyle` has no
`fontFamily`, so an ad headline renders sans-serif where an editorial headline
is serif."

### Design

**Full cross-platform font resolution via a sealed `AdFontFamily` type.**

Since `AdTextStyle` lives in `admob-cmp-compose` (not `admob-cmp-core`), it
already has Compose as a dependency. The renderers each resolve to their native
font type.

#### Public API

```kotlin
// In admob-cmp-compose/.../layout/AdStyle.kt

/**
 * Font family for text nodes in native ad layouts.
 *
 * System families are guaranteed available on every platform. [Named]
 * resolves against the platform's font registry — a PostScript name on
 * iOS, a family name on Android — and falls back to [Default] if the
 * name is not found.
 *
 * [FromCompose] bridges a Compose [FontFamily] into the layout DSL,
 * allowing SDK consumers who already define a Compose type system to
 * reuse it directly.
 */
@Immutable
public sealed interface AdFontFamily {
    /** Platform default sans-serif. */
    public data object Default : AdFontFamily
    /** Explicit sans-serif (e.g. Roboto on Android, SF Pro on iOS). */
    public data object SansSerif : AdFontFamily
    /** Serif (e.g. Noto Serif on Android, New York on iOS). */
    public data object Serif : AdFontFamily
    /** Monospace (e.g. Roboto Mono on Android, SF Mono on iOS). */
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
     * On the Compose preview renderer, used directly. On platform
     * renderers (Android Views / iOS UIKit), resolved to a [Typeface]
     * or [UIFont] via platform font resolution.
     */
    public data class FromCompose(
        val fontFamily: androidx.compose.ui.text.font.FontFamily,
    ) : AdFontFamily
}
```

Then add it to `AdTextStyle`:

```kotlin
@Immutable
public data class AdTextStyle(
    val fontSizeSp: Float = 14f,
    val colorArgb: Long = 0xFF202124,
    val fontWeight: AdFontWeight = AdFontWeight.Normal,
    val textAlign: AdTextAlign = AdTextAlign.Start,
    val fontFamily: AdFontFamily = AdFontFamily.Default, // NEW
)
```

#### Platform Renderers

**Android** (`AndroidNativeAdStyleMapper.kt`):
```kotlin
internal fun AdFontFamily.toTypeface(weight: AdFontWeight): Typeface = when (this) {
    AdFontFamily.Default, AdFontFamily.SansSerif ->
        Typeface.create(Typeface.SANS_SERIF, weight.toStyle())
    AdFontFamily.Serif ->
        Typeface.create(Typeface.SERIF, weight.toStyle())
    AdFontFamily.Monospace ->
        Typeface.create(Typeface.MONOSPACE, weight.toStyle())
    is AdFontFamily.Named ->
        Typeface.create(name, weight.toStyle())
    is AdFontFamily.FromCompose ->
        resolveFontFamilyToTypeface(fontFamily, weight)
}
```

**iOS** (`IosNativeAdRenderer.kt`):
```kotlin
private fun font(style: AdTextStyle): UIFont {
    val size = style.fontSizeSp.toDouble()
    return when (val family = style.fontFamily) {
        AdFontFamily.Default, AdFontFamily.SansSerif ->
            UIFont.systemFontOfSize(size, fontWeightValue(style.fontWeight))
        AdFontFamily.Serif ->
            systemFontWithDesign(size, UIFontDescriptorSystemDesignSerif)
        AdFontFamily.Monospace ->
            UIFont.monospacedSystemFontOfSize(size, fontWeightValue(style.fontWeight))
        is AdFontFamily.Named ->
            UIFont.fontWithName(family.name, size) ?: UIFont.systemFontOfSize(size)
        is AdFontFamily.FromCompose ->
            UIFont.systemFontOfSize(size) // Compose FontFamily not resolvable in UIKit; fallback
    }
}
```

**Compose Preview** (`AdLayoutPreview.kt`):
```kotlin
fun AdFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    AdFontFamily.Default -> FontFamily.Default
    AdFontFamily.SansSerif -> FontFamily.SansSerif
    AdFontFamily.Serif -> FontFamily.Serif
    AdFontFamily.Monospace -> FontFamily.Monospace
    is AdFontFamily.Named -> FontFamily.Default // Cannot resolve arbitrary names in preview
    is AdFontFamily.FromCompose -> fontFamily   // Direct passthrough
}
```

#### Showcase Update

In `AdLayouts.kt`, `feedRowAdLayout` can now use:
```kotlin
headline(
    style = AdTextStyle(
        fontSizeSp = 21f,
        colorArgb = palette.ink.argb(),
        fontWeight = AdFontWeight.Medium,
        fontFamily = AdFontFamily.Serif, // Matches editorial headlines
    ),
    maxLines = 3,
)
```

The comment "The one thing that cannot match is the family" can be removed.

### Files Changed

| File | Change |
|---|---|
| [AdStyle.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/commonMain/kotlin/dev/avinya/ads/nativead/layout/AdStyle.kt) | Add `AdFontFamily` sealed interface; add `fontFamily` param to `AdTextStyle` |
| [AndroidNativeAdStyleMapper.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/androidMain/kotlin/dev/avinya/ads/nativead/rendering/AndroidNativeAdStyleMapper.kt) | `AdFontFamily.toTypeface()` resolution |
| [IosNativeAdRenderer.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/iosMain/kotlin/dev/avinya/ads/nativead/rendering/IosNativeAdRenderer.kt) | `font()` updated for `AdFontFamily` |
| [AdLayoutPreview.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/commonMain/kotlin/dev/avinya/ads/nativead/layout/AdLayoutPreview.kt) | `toComposeFontFamily()` mapping |
| `admob-cmp-compose/api/*.klib.api` | Regenerated (additive) |
| Showcase `AdLayouts.kt` | Use `AdFontFamily.Serif` in `feedRowAdLayout` headline |

### ABI Impact

Adding `fontFamily` as a 5th constructor parameter with a default value is
**additive**: existing callers that pass 4 positional args continue to work.
The ABI dump gains `component5()` and the new parameter in `<init>` and
`copy()`. This is a non-breaking addition per the frozen-ABI rule.

```bash
./gradlew :admob-cmp-compose:updateKotlinAbi
```

### Verification

- `./gradlew :admob-cmp-compose:updateKotlinAbi` — regenerate.
- `./gradlew :admob-cmp-compose:checkKotlinAbi` — passes with updated dump.
- Showcase `feedRowAdLayout` renders ad headlines in serif font.
- Manual test: verify all system families render correctly on both platforms.

### Risk

**Medium.** The `FromCompose` variant is the most complex — resolving a Compose
`FontFamily` to an Android `Typeface` for use in a `TextView` requires the
Compose font resolver infrastructure. If resolution proves too complex,
`FromCompose` can fall back to `Default` on platform renderers (it will still
work perfectly in the Compose preview renderer). The system families and
`Named` are straightforward on all platforms.

---

## Implementation Order

| Priority | Enhancement | Scope | ABI Impact |
|---|---|---|---|
| 1 | iOS Targeting Parity | Internal mapper only | None |
| 2 | Video Callback Documentation | KDoc only | None |
| 3 | Per-Request Audio Control | Additive public API + internal | `FullScreenAdOptions` gains 2 params |
| 4 | Font Family in AdTextStyle | Additive public API + renderers | `AdTextStyle` gains 1 param, new `AdFontFamily` type |

> [!IMPORTANT]
> Enhancements 3 and 4 modify the public ABI. After each:
> ```bash
> ./gradlew :admob-cmp-core:updateKotlinAbi    # Enhancement 3
> ./gradlew :admob-cmp-compose:updateKotlinAbi  # Enhancement 4
> ```
> Commit the regenerated `api/*.klib.api` dumps in the same commit as the
> change.

## Final Verification

After all four enhancements:

```bash
./scripts/release-readiness.sh
```

Must produce `READINESS: PASS`. Do not open a PR until this passes and the
owner confirms.
