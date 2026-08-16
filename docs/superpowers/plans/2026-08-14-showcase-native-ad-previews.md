# Showcase Native Ad Previews Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the showcase app's native ad layouts into dedicated modular files (`FeedAdLayout.kt`, `FeedRowAdLayout.kt`, `InlineAdLayout.kt`, `AdLayoutStyles.kt`) with Light & Dark Compose `@Preview` composables for clean side-by-side IDE code & preview screenshots.

**Architecture:** Refactor `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayouts.kt` by extracting common styling utilities to `AdLayoutStyles.kt` and each layout into its own file with dedicated `@Preview` functions wrapped in `ShowcaseTheme`.

**Tech Stack:** Kotlin Multiplatform (2.3.20), Compose Multiplatform (1.11.1), Material 3, admob-cmp SDK.

## Global Constraints
- Target package for all layout files: `dev.avinya.admob.showcase.ui.ad`.
- Preserve existing public/internal signatures (`rememberFeedAdLayout`, `feedAdLayout`, `rememberFeedRowAdLayout`, `feedRowAdLayout`, `rememberInlineAdLayout`, `inlineAdLayout`) so no screens or tests break.
- Each layout file must contain two `@Preview` composables: one for `ThemeMode.Light` and one for `ThemeMode.Dark`.

---

### Task 1: Add Tooling Preview Dependency to Showcase

**Files:**
- Modify: `showcase/build.gradle.kts:45-72`

**Interfaces:**
- Consumes: `libs.compose.uiToolingPreview` from `gradle/libs.versions.toml`
- Produces: `@Preview` annotation available across commonMain in `showcase`

- [ ] **Step 1: Update `showcase/build.gradle.kts`**

Add `implementation(libs.compose.uiToolingPreview)` to `commonMain.dependencies`.

```kotlin
        val commonMain by getting {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.ui)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.compose.components.resources)
                // ...
```

- [ ] **Step 2: Commit**

```bash
git add showcase/build.gradle.kts
git commit -m "build(showcase): add compose.uiToolingPreview dependency"
```

---

### Task 2: Create Shared `AdLayoutStyles.kt`

**Files:**
- Create: `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayoutStyles.kt`

**Interfaces:**
- Consumes: `ShowcasePalette`, `Color`, `FontFamily`, `AdTextStyle`, `AdButtonStyle`
- Produces: `Color.argb()`, `badgeStyle()`, `title()`, `body()`, `caption()`, `ctaStyle()`

- [ ] **Step 1: Create `AdLayoutStyles.kt`**

```kotlin
package dev.avinya.admob.showcase.ui.ad

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.ads.nativead.layout.AdButtonStyle
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdTextAlign
import dev.avinya.ads.nativead.layout.AdTextStyle

internal fun Color.argb(): Long = toArgb().toLong() and 0xFFFFFFFFL

internal fun badgeStyle(palette: ShowcasePalette): AdTextStyle = AdTextStyle(
    fontSizeSp = 10f,
    colorArgb = palette.accent.argb(),
    fontWeight = AdFontWeight.Bold,
)

internal fun title(palette: ShowcasePalette, headlineFamily: FontFamily): AdTextStyle = AdTextStyle(
    fontSizeSp = 17f,
    colorArgb = palette.ink.argb(),
    fontWeight = AdFontWeight.Bold,
    fontFamily = AdFontFamily.FromCompose(headlineFamily),
)

internal fun body(palette: ShowcasePalette): AdTextStyle = AdTextStyle(
    fontSizeSp = 14f,
    colorArgb = palette.inkMuted.argb(),
)

internal fun caption(palette: ShowcasePalette): AdTextStyle = AdTextStyle(
    fontSizeSp = 12f,
    colorArgb = palette.inkFaint.argb(),
)

internal fun ctaStyle(palette: ShowcasePalette): AdButtonStyle = AdButtonStyle(
    textStyle = AdTextStyle(
        fontSizeSp = 14f,
        colorArgb = palette.onAccentInk.argb(),
        fontWeight = AdFontWeight.Bold,
        textAlign = AdTextAlign.Center,
    ),
    backgroundArgb = palette.primary.argb(),
    cornerRadiusDp = 10f,
)
```

- [ ] **Step 2: Commit**

```bash
git add showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayoutStyles.kt
git commit -m "refactor(showcase): extract shared ad layout styles"
```

---

### Task 3: Create `FeedAdLayout.kt` with Compose Previews

**Files:**
- Create: `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/FeedAdLayout.kt`

**Interfaces:**
- Consumes: `ShowcasePalette`, `badgeStyle`, `title`, `body`, `caption`, `ctaStyle`, `AdLayoutPreview`
- Produces: `rememberFeedAdLayout()`, `feedAdLayout()`, `FeedAdLayoutLightPreview()`, `FeedAdLayoutDarkPreview()`

- [ ] **Step 1: Create `FeedAdLayout.kt`**

```kotlin
package dev.avinya.admob.showcase.ui.ad

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.admob.showcase.ui.theme.ShowcaseTheme
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutPreview
import dev.avinya.ads.nativead.layout.AdLayoutPreviewData
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.adLayout

/**
 * Feed treatment: cover-led, for a hero-sized slot.
 */
@Composable
fun rememberFeedAdLayout(): AdLayout {
    val palette = showcaseColors
    val headlineFamily = MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Serif
    return remember(palette, headlineFamily) { feedAdLayout(palette, headlineFamily) }
}

internal fun feedAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout {
    val badge = badgeStyle(palette)
    return adLayout {
        column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.CenterVertically,
                spacing = 8.dp,
            ) {
                adBadge(
                    modifier = AdModifier
                        .background(palette.accentSoft)
                        .cornerRadius(4.dp)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    text = "SPONSORED",
                    style = badge,
                )
                advertiser(
                    modifier = AdModifier.weight(1f),
                    style = caption(palette),
                    maxLines = 1,
                )
                adChoices(
                    modifier = AdModifier.size(20.dp),
                    visibilityPolicy = AdVisibilityPolicy.KeepSpace,
                )
            }

            media(
                modifier = AdModifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .cornerRadius(20.dp),
            )

            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.Top,
                spacing = 12.dp,
            ) {
                icon(modifier = AdModifier.size(44.dp).cornerRadius(10.dp))
                column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                    headline(style = title(palette, headlineFamily), maxLines = 2)
                    body(style = body(palette), maxLines = 2)
                    row(spacing = 8.dp) {
                        starRating(style = caption(palette))
                        price(style = caption(palette))
                        store(style = caption(palette))
                    }
                }
            }

            callToAction(
                modifier = AdModifier.fillMaxWidth(),
                style = ctaStyle(palette),
            )
        }
    }
}

@Preview
@Composable
private fun FeedAdLayoutLightPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Light) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberFeedAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}

@Preview
@Composable
private fun FeedAdLayoutDarkPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Dark) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberFeedAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/FeedAdLayout.kt
git commit -m "feat(showcase): extract FeedAdLayout with Light/Dark previews"
```

---

### Task 4: Create `FeedRowAdLayout.kt` with Compose Previews

**Files:**
- Create: `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/FeedRowAdLayout.kt`

**Interfaces:**
- Consumes: `ShowcasePalette`, `Tokens.feedThumbnail`, `AdLayoutPreview`
- Produces: `rememberFeedRowAdLayout()`, `feedRowAdLayout()`, `FeedRowAdLayoutLightPreview()`, `FeedRowAdLayoutDarkPreview()`

- [ ] **Step 1: Create `FeedRowAdLayout.kt`**

```kotlin
package dev.avinya.admob.showcase.ui.ad

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.admob.showcase.ui.theme.ShowcaseTheme
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import dev.avinya.admob.showcase.ui.theme.Tokens
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdContentScale
import dev.avinya.ads.nativead.layout.AdFontFamily
import dev.avinya.ads.nativead.layout.AdFontWeight
import dev.avinya.ads.nativead.layout.AdImageStyle
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutPreview
import dev.avinya.ads.nativead.layout.AdLayoutPreviewData
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdTextStyle
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.adLayout

/**
 * Feed-row treatment: the same geometry as `StoryCard`'s Standard row —
 * eyebrow, serif headline, two-line standfirst, meta line, and a square
 * thumbnail on the trailing edge.
 */
@Composable
fun rememberFeedRowAdLayout(): AdLayout {
    val palette = showcaseColors
    val headlineFamily = MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Serif
    return remember(palette, headlineFamily) { feedRowAdLayout(palette, headlineFamily) }
}

internal fun feedRowAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout = adLayout {
    row(
        modifier = AdModifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = AdAlignment.Vertical.Top,
        spacing = 16.dp,
    ) {
        column(modifier = AdModifier.weight(1f), spacing = 8.dp) {
            row(
                modifier = AdModifier.fillMaxWidth(),
                verticalAlignment = AdAlignment.Vertical.CenterVertically,
                spacing = 6.dp,
            ) {
                adBadge(
                    modifier = AdModifier.wrapContentSize(),
                    text = "SPONSORED",
                    style = AdTextStyle(
                        fontSizeSp = 11f,
                        colorArgb = palette.accent.argb(),
                        fontWeight = AdFontWeight.Bold,
                    ),
                )
                spacer(AdModifier.weight(1f))
                adChoices(
                    modifier = AdModifier.size(16.dp),
                    visibilityPolicy = AdVisibilityPolicy.KeepSpace,
                )
            }

            headline(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(
                    fontSizeSp = 21f,
                    colorArgb = palette.ink.argb(),
                    fontWeight = AdFontWeight.Medium,
                    fontFamily = AdFontFamily.FromCompose(headlineFamily),
                ),
                maxLines = 3,
            )

            body(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(fontSizeSp = 13f, colorArgb = palette.inkMuted.argb()),
                maxLines = 2,
            )

            advertiser(
                modifier = AdModifier.fillMaxWidth(),
                style = AdTextStyle(fontSizeSp = 12f, colorArgb = palette.inkMuted.argb()),
                maxLines = 1,
                visibilityPolicy = AdVisibilityPolicy.HideWhenMissing,
            )
        }

        box(modifier = AdModifier.size(Tokens.feedThumbnail)) {
            icon(
                modifier = AdModifier.size(Tokens.feedThumbnail).cornerRadius(20.dp),
                style = AdImageStyle(
                    contentScale = AdContentScale.Crop,
                    backgroundArgb = palette.surfaceSunken.argb(),
                ),
            )
            media(
                modifier = AdModifier.size(Tokens.feedThumbnail).cornerRadius(20.dp),
                style = AdImageStyle(contentScale = AdContentScale.Crop),
                visibilityPolicy = AdVisibilityPolicy.HideWhenMissing,
            )
        }
    }
}

@Preview
@Composable
private fun FeedRowAdLayoutLightPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Light) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberFeedRowAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}

@Preview
@Composable
private fun FeedRowAdLayoutDarkPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Dark) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberFeedRowAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/FeedRowAdLayout.kt
git commit -m "feat(showcase): extract FeedRowAdLayout with Light/Dark previews"
```

---

### Task 5: Create `InlineAdLayout.kt` with Compose Previews

**Files:**
- Create: `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/InlineAdLayout.kt`

**Interfaces:**
- Consumes: `ShowcasePalette`, `badgeStyle`, `title`, `body`, `caption`, `ctaStyle`, `AdLayoutPreview`
- Produces: `rememberInlineAdLayout()`, `inlineAdLayout()`, `InlineAdLayoutLightPreview()`, `InlineAdLayoutDarkPreview()`

- [ ] **Step 1: Create `InlineAdLayout.kt`**

```kotlin
package dev.avinya.admob.showcase.ui.ad

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.avinya.admob.showcase.ui.theme.ShowcasePalette
import dev.avinya.admob.showcase.ui.theme.ShowcaseTheme
import dev.avinya.admob.showcase.ui.theme.ThemeMode
import dev.avinya.admob.showcase.ui.theme.showcaseColors
import dev.avinya.ads.nativead.layout.AdAlignment
import dev.avinya.ads.nativead.layout.AdLayout
import dev.avinya.ads.nativead.layout.AdLayoutPreview
import dev.avinya.ads.nativead.layout.AdLayoutPreviewData
import dev.avinya.ads.nativead.layout.AdModifier
import dev.avinya.ads.nativead.layout.AdVisibilityPolicy
import dev.avinya.ads.nativead.layout.adLayout

/**
 * Article treatment: text-led band with no large media, so it reads as an
 * interruption in the column rather than a second hero.
 */
@Composable
fun rememberInlineAdLayout(): AdLayout {
    val palette = showcaseColors
    val headlineFamily = MaterialTheme.typography.titleLarge.fontFamily ?: FontFamily.Serif
    return remember(palette, headlineFamily) { inlineAdLayout(palette, headlineFamily) }
}

internal fun inlineAdLayout(palette: ShowcasePalette, headlineFamily: FontFamily): AdLayout = adLayout {
    column(modifier = AdModifier.fillMaxWidth(), spacing = 12.dp) {
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 8.dp,
        ) {
            adBadge(
                modifier = AdModifier
                    .background(palette.accentSoft)
                    .cornerRadius(4.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                text = "SPONSORED",
                style = badgeStyle(palette),
            )
            advertiser(
                modifier = AdModifier.weight(1f),
                style = caption(palette),
                maxLines = 1,
            )
            adChoices(
                modifier = AdModifier.size(20.dp),
                visibilityPolicy = AdVisibilityPolicy.KeepSpace,
            )
        }
        row(
            modifier = AdModifier.fillMaxWidth(),
            verticalAlignment = AdAlignment.Vertical.CenterVertically,
            spacing = 12.dp,
        ) {
            icon(modifier = AdModifier.size(48.dp).cornerRadius(10.dp))
            column(modifier = AdModifier.weight(1f), spacing = 4.dp) {
                headline(style = title(palette, headlineFamily), maxLines = 2)
                body(style = body(palette), maxLines = 2)
            }
        }
        callToAction(
            modifier = AdModifier.fillMaxWidth(),
            style = ctaStyle(palette),
        )
    }
}

@Preview
@Composable
private fun InlineAdLayoutLightPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Light) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberInlineAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}

@Preview
@Composable
private fun InlineAdLayoutDarkPreview() {
    ShowcaseTheme(themeMode = ThemeMode.Dark) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            AdLayoutPreview(
                layout = rememberInlineAdLayout(),
                data = AdLayoutPreviewData.default,
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/InlineAdLayout.kt
git commit -m "feat(showcase): extract InlineAdLayout with Light/Dark previews"
```

---

### Task 6: Remove `AdLayouts.kt` and Run Test & Compilation Verification

**Files:**
- Delete: `showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayouts.kt`
- Test: `showcase/src/commonTest/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayoutsTypographyTest.kt`

- [ ] **Step 1: Remove `AdLayouts.kt`**

```bash
git rm showcase/src/commonMain/kotlin/dev/avinya/admob/showcase/ui/ad/AdLayouts.kt
```

- [ ] **Step 2: Run Showcase Unit Tests**

Run: `./gradlew :showcase:allTests`
Expected: BUILD SUCCESSFUL (including `AdLayoutsTypographyTest`)

- [ ] **Step 3: Run Android Kotlin Compilation**

Run: `./gradlew :showcase:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A showcase/src/
git commit -m "refactor(showcase): remove legacy AdLayouts.kt now superseded by modular layout files"
```
