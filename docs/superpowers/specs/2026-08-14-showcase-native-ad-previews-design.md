# Showcase Native Ad Previews Design

**Date:** 2026-08-14  
**Status:** Approved  
**Topic:** Split showcase native ad layouts and add Compose previews for IDE side-by-side view

---

## 1. Background & Goals

The showcase app currently defines three native ad layouts in a single file (`showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayouts.kt`):
- `feedAdLayout` (cover-led hero native ad card)
- `feedRowAdLayout` (editorial story-style ad row matching `StoryCard`)
- `inlineAdLayout` (in-article text-led band)

Having all layouts crammed into one file without Compose `@Preview` composables makes it difficult to preview them in IDE design tools (Android Studio / Fleet / IntelliJ) and impossible to capture clean side-by-side IDE screenshots showing the DSL code on the left and the rendered native ad preview on the right.

### Goals
1. Split `AdLayouts.kt` into dedicated, modular layout files under `dev.avinya.admob.showcase.ui.ad`:
   - `AdLayoutStyles.kt`: Shared styling utilities and color converters.
   - `FeedAdLayout.kt`: Hero cover-led layout DSL + Light and Dark `@Preview`s.
   - `FeedRowAdLayout.kt`: Editorial story row layout DSL + Light and Dark `@Preview`s.
   - `InlineAdLayout.kt`: Inline article band layout DSL + Light and Dark `@Preview`s.
2. Remove `AdLayouts.kt` while preserving all existing function names and package paths (`dev.avinya.admob.showcase.ui.ad`) so zero screen callers or tests break.
3. Add `libs.compose.uiToolingPreview` to `showcase/build.gradle.kts` to support `@Preview` in common code.
4. Verify all tests and compilation.

---

## 2. File Specifications

### 2.1 `AdLayoutStyles.kt`
**Location:** `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayoutStyles.kt`  
**Package:** `dev.avinya.admob.showcase.ui.ad`

Shared utilities for styling native ad elements:
- `internal fun Color.argb(): Long`: Converts Compose `Color` to unsigned 32-bit `Long` ARGB value.
- `internal fun badgeStyle(palette: ShowcasePalette): AdTextStyle`: 10sp bold accent-colored badge style.
- `internal fun title(palette: ShowcasePalette, headlineFamily: FontFamily): AdTextStyle`: 17sp bold ink title using the supplied headline font family.
- `internal fun body(palette: ShowcasePalette): AdTextStyle`: 14sp muted ink body text style.
- `internal fun caption(palette: ShowcasePalette): AdTextStyle`: 12sp faint ink caption style.
- `internal fun ctaStyle(palette: ShowcasePalette): AdButtonStyle`: Centered bold CTA button with 10dp corner radius and primary background.

### 2.2 `FeedAdLayout.kt`
**Location:** `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/FeedAdLayout.kt`  
**Package:** `dev.avinya.admob.showcase.ui.ad`

- `fun rememberFeedAdLayout(): AdLayout`: Remembers the hero feed layout keyed to the active palette and typography.
- `internal fun feedAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout`: Returns the hero card `AdLayout`.
- `@Preview private fun FeedAdLayoutLightPreview()`: Light mode preview wrapped in `ShowcaseTheme(themeMode = ThemeMode.Light)` and `Surface`.
- `@Preview private fun FeedAdLayoutDarkPreview()`: Dark mode preview wrapped in `ShowcaseTheme(themeMode = ThemeMode.Dark)` and `Surface`.

### 2.3 `FeedRowAdLayout.kt`
**Location:** `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/FeedRowAdLayout.kt`  
**Package:** `dev.avinya.admob.showcase.ui.ad`

- `fun rememberFeedRowAdLayout(): AdLayout`: Remembers the feed row layout keyed to the active palette and typography.
- `internal fun feedRowAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout`: Returns the feed row `AdLayout` mirroring `StoryCard`.
- `@Preview private fun FeedRowAdLayoutLightPreview()`: Light mode preview wrapped in `ShowcaseTheme(themeMode = ThemeMode.Light)` and `Surface`.
- `@Preview private fun FeedRowAdLayoutDarkPreview()`: Dark mode preview wrapped in `ShowcaseTheme(themeMode = ThemeMode.Dark)` and `Surface`.

### 2.4 `InlineAdLayout.kt`
**Location:** `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/InlineAdLayout.kt`  
**Package:** `dev.avinya.admob.showcase.ui.ad`

- `fun rememberInlineAdLayout(): AdLayout`: Remembers the inline ad layout keyed to the active palette and typography.
- `internal fun inlineAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout`: Returns the inline article `AdLayout`.
- `@Preview private fun InlineAdLayoutLightPreview()`: Light mode preview wrapped in `ShowcaseTheme(themeMode = ThemeMode.Light)` and `Surface`.
- `@Preview private fun InlineAdLayoutDarkPreview()`: Dark mode preview wrapped in `ShowcaseTheme(themeMode = ThemeMode.Dark)` and `Surface`.

---

## 3. Build Configuration

In `showcase/build.gradle.kts`:
- Add `implementation(libs.compose.uiToolingPreview)` to `commonMain.dependencies`.

---

## 4. Verification Plan

1. **Unit Tests:**
   - Execute `./gradlew :showcase:allTests` to ensure `AdLayoutsTypographyTest` and all showcase tests pass.
2. **Android Compilation:**
   - Execute `./gradlew :showcase:compileDebugKotlinAndroid` to ensure `@Preview` and all Compose code compiles with zero errors.
3. **Full verification:**
   - Run `./scripts/release-readiness.sh --skip-docs` to ensure full project integrity.
