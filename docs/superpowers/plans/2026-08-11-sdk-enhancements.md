# SDK Enhancements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the four monetization enhancements identified during the SDK deep audit: iOS Targeting Parity via GAMRequest, Video Callback KDoc annotations, Per-Request Audio Control for full-screen ads, and Cross-Platform Font Family support in the Native Ad Layout DSL.

**Architecture:** Ordered strictly by implementation risk and dependency layers: internal mapper changes first, documentation annotations second, core full-screen audio API additions third, and native layout DSL font family support fourth. Followed by ABI dump regeneration and end-to-end verification.

**Tech Stack:** Kotlin Multiplatform 2.3.20, Compose Multiplatform 1.10.x, Google Mobile Ads Android Next-Gen SDK, Google Mobile Ads iOS SDK 13.x (cinterop), Gradle ABI Validation (`checkKotlinAbi` / `updateKotlinAbi`).

## Global Constraints

- **Frozen ABI:** Any public API change to `admob-cmp-core` or `admob-cmp-compose` must be additive (default parameter values, new declarations) and must be accompanied by updated `.klib.api` dumps via `./gradlew :<module>:updateKotlinAbi`.
- **Main Thread Confinement:** All GMA/UMP SDK interactions and UI renderers must execute on the main thread / `Dispatchers.Main.immediate`.
- **Arbitration Invariant:** `FullScreenPresentationArbiter` retains exclusive ownership of full-screen presentation concurrency; save-restore audio lifecycle must never bypass or leak the arbiter token.
- **Verification Gate:** Verification requires running `./scripts/release-readiness.sh` producing `READINESS: PASS`.

---

### Task 1: iOS Targeting Parity via `GAMRequest`

**Files:**
- Modify: [IosAdMappers.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosAdMappers.kt#L1-L58)
- Modify: [AdPlacement.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdPlacement.kt#L138-L159)

**Interfaces:**
- Consumes: `AdRequestOptions.publisherProvidedId`, `AdRequestOptions.categoryExclusions`
- Produces: `AdRequestOptions.toGADRequest(): GADRequest` returning `GAMRequest` whenever PPID or category exclusions are populated.

- [ ] **Step 1: Update `AdPlacement.kt` KDoc for targeting fields**

Update KDoc on `publisherProvidedId` and `categoryExclusions` in `AdPlacement.kt`:

```kotlin
    /**
     * Ad Manager category exclusions. Mapped on Android via `addCategoryExclusion()`
     * and on iOS via `GAMRequest.categoryExclusions`.
     *
     * Requires an Ad Manager–enabled ad unit on iOS; standard AdMob units may ignore this field.
     */
    val categoryExclusions: Set<String> = emptySet(),
```

```kotlin
    /**
     * Publisher-provided identifier for frequency capping, audience segmentation, and attribution.
     * Mapped on Android via `setPublisherProvidedId()` and on iOS via `GAMRequest.publisherProvidedID`.
     *
     * Requires an Ad Manager–enabled ad unit on iOS; standard AdMob units may ignore this field.
     */
    val publisherProvidedId: String? = null,
```

- [ ] **Step 2: Update `IosAdMappers.kt` to instantiate `GAMRequest` conditionally**

In `IosAdMappers.kt`, import `GoogleMobileAds.GAMRequest` and update `toGADRequest()`:

```kotlin
import GoogleMobileAds.GAMRequest
```

```kotlin
internal fun AdRequestOptions.toGADRequest(): GADRequest {
    val needsAdManager = publisherProvidedId != null || categoryExclusions.isNotEmpty()
    val request = if (needsAdManager) GAMRequest() else GADRequest()
    if (keywords.isNotEmpty()) request.keywords = keywords.toList()
    request.contentURL = contentUrl
    if (neighboringContentUrls.isNotEmpty()) request.neighboringContentURLStrings = neighboringContentUrls.toList()
    requestAgent?.let { request.requestAgent = it }
    placementId?.let { request.placementID = it }
    if (customTargeting.isNotEmpty()) {
        request.customTargeting = customTargeting.mapValues { (_, values) ->
            if (values.size == 1) values.first() else values.joinToString(",")
        } as Map<Any?, *>
    }
    if (googleExtras.isNotEmpty()) {
        val extras = GADExtras()
        extras.additionalParameters = googleExtras as Map<Any?, *>
        request.registerAdNetworkExtras(extras)
    }
    if (request is GAMRequest) {
        publisherProvidedId?.let { request.publisherProvidedID = it }
        if (categoryExclusions.isNotEmpty()) {
            request.categoryExclusions = categoryExclusions.toList()
        }
    }
    // skipUninitializedAdapters remains Android-only; iOS GMA initializes adapters globally at SDK start.
    return request
}
```

- [ ] **Step 3: Verify iOS compilation & ABI validation**

Run:
```bash
./gradlew :admob-cmp-core:compileKotlinIosArm64
./gradlew :admob-cmp-core:checkKotlinAbi
```
Expected: `BUILD SUCCESSFUL` with ABI checks passing (no public API changed).

- [ ] **Step 4: Commit Task 1 changes**

```bash
git add admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosAdMappers.kt admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdPlacement.kt
git commit -m "feat(core): support iOS PPID and category exclusions via GAMRequest"
```

---

### Task 2: Video Callback Platform Availability Documentation

**Files:**
- Modify: [AdTelemetry.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdTelemetry.kt#L150-L175)

**Interfaces:**
- Consumes: Existing `AdEvent.VideoStarted`, `AdEvent.VideoPlayed`, `AdEvent.VideoPaused`, `AdEvent.VideoEnded`, `AdEvent.VideoMuted`
- Produces: Precise KDoc detailing iOS availability (`GADVideoControllerDelegate`) vs Android Next-Gen limitation.

- [ ] **Step 1: Update KDoc on Video Event classes in `AdTelemetry.kt`**

Add explicit platform notes to the 5 video event classes in `AdTelemetry.kt`:

```kotlin
    /**
     * Emitted when native ad video playback starts for the first time.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidPlayVideo`.
     * - **Android:** Not yet emitted — the GMA Next-Gen SDK does not expose video lifecycle
     *   callbacks. Support will be added when that API ships upstream.
     */
    public data class VideoStarted(override val placementId: String, val adInstanceId: String? = null) : AdEvent

    /**
     * Emitted when native ad video playback resumes after being paused.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidPlayVideo`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoPlayed(override val placementId: String, val adInstanceId: String? = null) : AdEvent

    /**
     * Emitted when native ad video playback pauses.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidPauseVideo`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoPaused(override val placementId: String, val adInstanceId: String? = null) : AdEvent

    /**
     * Emitted when native ad video playback completes.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidEndVideoPlayback`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoEnded(override val placementId: String, val adInstanceId: String? = null) : AdEvent

    /**
     * Emitted when native ad video is muted or unmuted.
     *
     * **Platform Availability:**
     * - **iOS:** Emitted via `GADVideoControllerDelegate.videoControllerDidMuteVideo` / `DidUnmuteVideo`.
     * - **Android:** Upstream limitation (GMA Next-Gen SDK omits video callbacks).
     */
    public data class VideoMuted(
        override val placementId: String,
        val muted: Boolean,
        val adInstanceId: String? = null
    ) : AdEvent
```

- [ ] **Step 2: Verify ABI validation**

Run:
```bash
./gradlew :admob-cmp-core:checkKotlinAbi
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit Task 2 changes**

```bash
git add admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/AdTelemetry.kt
git commit -m "docs(core): document platform availability on native video AdEvents"
```

---

### Task 3: Per-Request Audio Control for Full-Screen Ads

**Files:**
- Modify: [FullScreenAdModels.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/FullScreenAdModels.kt#L12-L16)
- Modify: [FullScreenSlotCore.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/FullScreenSlotCore.kt#L330-L405)
- Modify: [AndroidFullScreenSlots.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/androidMain/kotlin/dev/avinya/ads/AndroidFullScreenSlots.kt)
- Modify: [IosFullScreenSlots.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosFullScreenSlots.kt)
- Modify: [Fakes.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-core/src/commonTest/kotlin/dev/avinya/ads/Fakes.kt)
- Update ABI: `admob-cmp-core/api/*.klib.api`

**Interfaces:**
- Produces: `FullScreenAdOptions(val immersiveMode: Boolean = false, val serverSideVerification: ServerSideVerificationOptions? = null, val audioMuted: Boolean? = null, val audioVolume: Float? = null)`
- Platform Audio Hooks: `PlatformAudioOverrides.applyAudioOverrides(options: FullScreenAdOptions): AudioRestoreAction`

- [ ] **Step 1: Add `audioMuted` and `audioVolume` to `FullScreenAdOptions` in `FullScreenAdModels.kt`**

```kotlin
/**
 * Display options for full-screen ads (interstitial, rewarded, app-open).
 *
 * @param immersiveMode **Android only.** Enable immersive mode for the ad;
 *   ignored on iOS.
 * @param serverSideVerification Server-side verification options for
 *   rewarded / rewarded-interstitial formats.
 * @param audioMuted Optional per-presentation override for ad audio mute state.
 *   When non-null, temporarily applies to the global SDK audio configuration for the duration
 *   of the presentation and restores on dismissal.
 * @param audioVolume Optional per-presentation override for ad audio volume (0.0f..1.0f).
 *   When non-null, temporarily applies to the global SDK audio configuration for the duration
 *   of the presentation and restores on dismissal.
 */
public data class FullScreenAdOptions(
    val immersiveMode: Boolean = false,
    val serverSideVerification: ServerSideVerificationOptions? = null,
    val audioMuted: Boolean? = null,
    val audioVolume: Float? = null
)
```

- [ ] **Step 2: Create common and platform audio override interfaces**

In `admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/FullScreenAudioController.kt`:
```kotlin
package dev.avinya.ads.internal

import dev.avinya.ads.FullScreenAdOptions

internal interface FullScreenAudioController {
    fun applyOverrides(options: FullScreenAdOptions): AudioRestoreHandle?
}

internal fun interface AudioRestoreHandle {
    fun restore()
}
```

In `AndroidFullScreenSlots.kt` (or internal helper in `androidMain`):
```kotlin
import com.google.android.libraries.ads.mobile.sdk.MobileAds

internal object AndroidFullScreenAudioController : FullScreenAudioController {
    override fun applyOverrides(options: FullScreenAdOptions): AudioRestoreHandle? {
        if (options.audioMuted == null && options.audioVolume == null) return null
        // Capture current values (if custom getter unavailable, save default / state)
        options.audioMuted?.let { MobileAds.setUserMutedApp(it) }
        options.audioVolume?.let { MobileAds.setUserControlledAppVolume(it.coerceIn(0f, 1f)) }
        return AudioRestoreHandle {
            // Restore default audio behavior
            options.audioMuted?.let { MobileAds.setUserMutedApp(false) }
            options.audioVolume?.let { MobileAds.setUserControlledAppVolume(1.0f) }
        }
    }
}
```

In `IosFullScreenSlots.kt` (or internal helper in `iosMain`):
```kotlin
import GoogleMobileAds.GADMobileAds

internal object IosFullScreenAudioController : FullScreenAudioController {
    override fun applyOverrides(options: FullScreenAdOptions): AudioRestoreHandle? {
        if (options.audioMuted == null && options.audioVolume == null) return null
        val previousMuted = GADMobileAds.sharedInstance.applicationMuted
        val previousVolume = GADMobileAds.sharedInstance.applicationVolume
        options.audioMuted?.let { GADMobileAds.sharedInstance.applicationMuted = it }
        options.audioVolume?.let { GADMobileAds.sharedInstance.applicationVolume = it.coerceIn(0f, 1f) }
        return AudioRestoreHandle {
            GADMobileAds.sharedInstance.applicationMuted = previousMuted
            GADMobileAds.sharedInstance.applicationVolume = previousVolume
        }
    }
}
```

- [ ] **Step 3: Integrate save-restore into `FullScreenSlotCore.showInternal`**

In `FullScreenSlotCore.kt`:
Add constructor parameter `audioController: FullScreenAudioController? = null`.
Wrap the presentation in `showInternal`:
```kotlin
        val loaded = checkNotNull(preparation.selectedAd)
        val handle = checkNotNull(preparation.presentation)
        val audioRestore = audioController?.applyOverrides(options)
        return try {
            val timedOutBeforeHandOff = AtomicBoolean(false)
            val result = coroutineScope {
                val presentJob = async { presentAd(loaded, options, handle, rewardDelivery) }
                ...
            }
            ...
            result
        } catch (e: CancellationException) {
            handle.closeIfCoreOwned()
            throw e
        } catch (t: Throwable) {
            handle.close(wasShown = false)
            val error = AdError.message(t.message ?: "Full-screen ad presentation failed.")
            emit(AdEvent.ShowFailed(placement.id, error))
            AdShowResult.Failed(error)
        } finally {
            audioRestore?.restore()
        }
```

- [ ] **Step 4: Pass platform audio controllers in Android & iOS slots and test fakes**

Update `AndroidInterstitialSlot`, `AndroidRewardedSlot`, `AndroidRewardedInterstitialSlot`, `AndroidAppOpenSlot` to pass `audioController = AndroidFullScreenAudioController`.
Update `IosInterstitialSlot`, `IosRewardedSlot`, `IosRewardedInterstitialSlot`, `IosAppOpenSlot` to pass `audioController = IosFullScreenAudioController`.
Update `FakeFullScreenSlot` in `commonTest/.../Fakes.kt`.

- [ ] **Step 5: Write unit tests for audio options in `FullScreenSlotCoreTest.kt`**

Verify that:
1. When `audioMuted` or `audioVolume` is provided in `FullScreenAdOptions`, `applyOverrides` is called before `presentAd`.
2. When presentation concludes (or throws/fails), `restore()` is invoked in the `finally` block.

- [ ] **Step 6: Update Kotlin ABI dump for `admob-cmp-core`**

Run:
```bash
./gradlew :admob-cmp-core:updateKotlinAbi
./gradlew :admob-cmp-core:checkKotlinAbi
./gradlew :admob-cmp-core:testAndroidHostTest
```
Expected: All tests pass and ABI check passes.

- [ ] **Step 7: Commit Task 3 changes**

```bash
git add admob-cmp-core/
git commit -m "feat(core): add per-request audio control to FullScreenAdOptions with save-restore"
```

---

### Task 4: Font Family Support (`AdFontFamily`) in `AdTextStyle` & Native Renderers

**Files:**
- Modify: [AdStyle.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/commonMain/kotlin/dev/avinya/ads/nativead/layout/AdStyle.kt#L15-L40)
- Modify: [AndroidNativeAdStyleMapper.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/androidMain/kotlin/dev/avinya/ads/nativead/rendering/AndroidNativeAdStyleMapper.kt#L38-L43)
- Modify: [AndroidNativeAdLayoutRenderer.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/androidMain/kotlin/dev/avinya/ads/nativead/rendering/AndroidNativeAdLayoutRenderer.kt#L203-L206)
- Modify: [IosNativeAdRenderer.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/iosMain/kotlin/dev/avinya/ads/nativead/rendering/IosNativeAdRenderer.kt#L282-L287)
- Modify: [AdLayoutPreview.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/admob-cmp-compose/src/commonMain/kotlin/dev/avinya/ads/nativead/layout/AdLayoutPreview.kt#L160-L170)
- Update ABI: `admob-cmp-compose/api/*.klib.api`

**Interfaces:**
- Produces: `sealed interface AdFontFamily` (`Default`, `SansSerif`, `Serif`, `Monospace`, `Named(val name: String)`, `FromCompose(val fontFamily: androidx.compose.ui.text.font.FontFamily)`)
- Produces: `AdTextStyle(..., val fontFamily: AdFontFamily = AdFontFamily.Default)`
- Platform mappers: `AdFontFamily.toTypeface(weight: AdFontWeight): Typeface`, `font(style: AdTextStyle): UIFont`, `AdFontFamily.toComposeFontFamily(): FontFamily`

- [ ] **Step 1: Define `AdFontFamily` and update `AdTextStyle` in `AdStyle.kt`**

In `AdStyle.kt`:
```kotlin
import androidx.compose.ui.text.font.FontFamily

/**
 * Font family for text nodes in native ad layouts.
 *
 * System families are guaranteed available on every platform. [Named] resolves against
 * the platform's font registry — a PostScript name on iOS, a family name on Android — and
 * falls back to [Default] if the name is not found.
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
     * On the Compose preview renderer, used directly. On platform renderers
     * (Android Views / iOS UIKit), falls back safely to default or resolved typeface.
     */
    public data class FromCompose(
        val fontFamily: FontFamily
    ) : AdFontFamily
}

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
```

- [ ] **Step 2: Update Android Typeface resolution in `AndroidNativeAdStyleMapper.kt` and `AndroidNativeAdLayoutRenderer.kt`**

In `AndroidNativeAdStyleMapper.kt`:
```kotlin
import dev.avinya.ads.nativead.layout.AdFontFamily

internal fun AdFontWeight.toStyle(): Int = when (this) {
    AdFontWeight.Normal -> Typeface.NORMAL
    AdFontWeight.Medium, AdFontWeight.Bold -> Typeface.BOLD
}

internal fun AdFontFamily.toTypeface(weight: AdFontWeight): Typeface {
    val style = weight.toStyle()
    return when (this) {
        AdFontFamily.Default, AdFontFamily.SansSerif -> Typeface.create(Typeface.SANS_SERIF, style)
        AdFontFamily.Serif -> Typeface.create(Typeface.SERIF, style)
        AdFontFamily.Monospace -> Typeface.create(Typeface.MONOSPACE, style)
        is AdFontFamily.Named -> Typeface.create(name, style) ?: Typeface.create(Typeface.DEFAULT, style)
        is AdFontFamily.FromCompose -> Typeface.create(Typeface.DEFAULT, style)
    }
}
```

In `AndroidNativeAdLayoutRenderer.kt`:
Update `applyTextStyle`:
```kotlin
    private fun TextView.applyTextStyle(style: AdTextStyle, maxLines: Int?) {
        setTextColor(style.colorArgb.toAndroidColor())
        textSize = style.fontSizeSp
        typeface = style.fontFamily.toTypeface(style.fontWeight)
        gravity = style.textAlign.toAndroidGravity()
        maxLines?.let { this.maxLines = it }
        includeFontPadding = false
    }
```
Update `AdAssetNode.CallToAction`:
```kotlin
    typeface = node.style.textStyle.fontFamily.toTypeface(node.style.textStyle.fontWeight)
```

- [ ] **Step 3: Update iOS UIFont resolution in `IosNativeAdRenderer.kt`**

In `IosNativeAdRenderer.kt`:
```kotlin
import dev.avinya.ads.nativead.layout.AdFontFamily
import platform.UIKit.UIFontDescriptorSystemDesignDefault
import platform.UIKit.UIFontDescriptorSystemDesignMonospaced
import platform.UIKit.UIFontDescriptorSystemDesignSerif
import platform.UIKit.UIFontDescriptorTraitBold
import platform.UIKit.UIFontDescriptor
import platform.UIKit.UIFontWeightBold
import platform.UIKit.UIFontWeightMedium
import platform.UIKit.UIFontWeightRegular

    private fun font(style: AdTextStyle): UIFont {
        val size = style.fontSizeSp.toDouble()
        val weight = when (style.fontWeight) {
            AdFontWeight.Bold -> UIFontWeightBold
            AdFontWeight.Medium -> UIFontWeightMedium
            AdFontWeight.Normal -> UIFontWeightRegular
        }
        return when (val family = style.fontFamily) {
            AdFontFamily.Default, AdFontFamily.SansSerif -> {
                UIFont.systemFontOfSize(size, weight)
            }
            AdFontFamily.Serif -> {
                val descriptor = UIFont.systemFontOfSize(size, weight).fontDescriptor.fontDescriptorWithDesign(UIFontDescriptorSystemDesignSerif)
                if (descriptor != null) UIFont.fontWithDescriptor(descriptor, size) else UIFont.systemFontOfSize(size, weight)
            }
            AdFontFamily.Monospace -> {
                val descriptor = UIFont.systemFontOfSize(size, weight).fontDescriptor.fontDescriptorWithDesign(UIFontDescriptorSystemDesignMonospaced)
                if (descriptor != null) UIFont.fontWithDescriptor(descriptor, size) else UIFont.monospacedSystemFontOfSize(size, weight)
            }
            is AdFontFamily.Named -> {
                UIFont.fontWithName(family.name, size) ?: UIFont.systemFontOfSize(size, weight)
            }
            is AdFontFamily.FromCompose -> {
                UIFont.systemFontOfSize(size, weight)
            }
        }
    }
```

- [ ] **Step 4: Update Compose Layout Preview in `AdLayoutPreview.kt`**

In `AdLayoutPreview.kt`:
```kotlin
private fun AdFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    AdFontFamily.Default -> FontFamily.Default
    AdFontFamily.SansSerif -> FontFamily.SansSerif
    AdFontFamily.Serif -> FontFamily.Serif
    AdFontFamily.Monospace -> FontFamily.Monospace
    is AdFontFamily.Named -> FontFamily.Default
    is AdFontFamily.FromCompose -> fontFamily
}
```

Update `PreviewText`:
```kotlin
        style = TextStyle(
            color = Color(style.colorArgb),
            fontSize = style.fontSizeSp.sp,
            fontWeight = style.fontWeight.toComposeFontWeight(),
            fontFamily = style.fontFamily.toComposeFontFamily(),
            textAlign = style.textAlign.toComposeTextAlign()
        )
```

- [ ] **Step 5: Regenerate ABI dump for `admob-cmp-compose`**

Run:
```bash
./gradlew :admob-cmp-compose:updateKotlinAbi
./gradlew :admob-cmp-compose:checkKotlinAbi
```
Expected: `BUILD SUCCESSFUL` with updated `.klib.api` dump.

- [ ] **Step 6: Commit Task 4 changes**

```bash
git add admob-cmp-compose/
git commit -m "feat(compose): add AdFontFamily to AdTextStyle with cross-platform renderer support"
```

---

### Task 5: Showcase Integration & Full Release Readiness Verification

**Files:**
- Modify: [AdLayouts.kt](file:///Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP/showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayouts.kt)
- Verification: `./scripts/release-readiness.sh`

- [ ] **Step 1: Update `feedRowAdLayout` in `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayouts.kt`**

Update the headline style to use `fontFamily = AdFontFamily.Serif` to match the editorial styling, and remove the outdated limitation comment.

- [ ] **Step 2: Run release-readiness verification**

Run:
```bash
./scripts/release-readiness.sh
```
Expected: Clean `READINESS: PASS` output.

- [ ] **Step 3: Commit Task 5 changes**

```bash
git add showcase/
git commit -m "feat(showcase): apply AdFontFamily.Serif to feed row native ad headlines"
```

---

## Verification Plan

### Automated Tests
1. `./gradlew :admob-cmp-core:testAndroidHostTest`
2. `./gradlew :admob-cmp-core:checkKotlinAbi`
3. `./gradlew :admob-cmp-compose:checkKotlinAbi`
4. `./gradlew :showcase:testAndroidHostTest`
5. `./scripts/release-readiness.sh`

### Manual Verification
1. Inspect Showcase Feed Tab: Verify native ad headlines now render in Serif font matching surrounding editorial headlines.
2. Inspect FullScreen Lab: Verify ad show with `audioMuted = true` is silent and global audio state properly restores after ad dismissal.
