![AdMob CMP — Compose Multiplatform AdMob SDK for Android and iOS](.github/readme-header.png)

# AdMob CMP — Compose Multiplatform AdMob SDK for Android and iOS

[![Maven Central](https://img.shields.io/maven-central/v/dev.avinya.ads/admob-cmp?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.avinya.ads/admob-cmp)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Platforms](https://img.shields.io/badge/Platforms-Android%20%7C%20iOS-3DDC84)](#compatibility)
[![API reference](https://img.shields.io/badge/docs-API%20reference-1793D1)](https://ads.avinya.dev/reference/api/)

**[Quickstart](#30-second-quickstart) · [Ad formats](#ad-formats) · [Compatibility](#compatibility) · [Showcase](#showcase-app--fieldnotes) · [Contributing](#contributing)**

AdMob CMP is an open-source Kotlin Multiplatform SDK for Google AdMob in Compose Multiplatform apps. Use one `commonMain` API for banner, interstitial, rewarded, rewarded interstitial, app-open, and native ads on Android and iOS.

The SDK wraps Google Mobile Ads Next-Gen on Android and Google Mobile Ads on iOS while preserving familiar AdMob concepts such as `AdValue`, `ResponseInfo`, adaptive banner sizes, UMP consent states, and native asset names. Its shared API uses suspend functions, `StateFlow` state, and a sealed `AdEvent` stream, with consent, ATT ordering, paid events, and mediation integrated into initialization.

> [!NOTE]
> **Brand, repository, coordinate.** The library is branded **AdMob CMP**, the repository is **`admob-compose-multiplatform`**, and the Maven coordinate is **`dev.avinya.ads:admob-cmp`**. The coordinate has not changed across any release and will not change.

**Documentation: [ads.avinya.dev](https://ads.avinya.dev)** · [What is AdMob CMP?](https://ads.avinya.dev/start/what-is-admob-cmp/) · [Quickstart](https://ads.avinya.dev/start/quickstart/) · [Installation](https://ads.avinya.dev/start/installation/) · [iOS setup](https://ads.avinya.dev/start/ios-setup/) · [Troubleshooting](https://ads.avinya.dev/reference/troubleshooting/)

Coming from a hand-rolled `expect class AdManager`? See the [migration guide](https://ads.avinya.dev/start/migrate-from-expect-actual/).

## Install

```kotlin
// commonMain
implementation("dev.avinya.ads:admob-cmp:2.2.0")
```

> [!IMPORTANT]
> If your project runs Kotlin/Native tests (`:yourModule:iosSimulatorArm64Test`), also apply the Gradle plugin. Without it the test link fails with `Undefined symbols … _OBJC_CLASS_$_GAD*`, because a Kotlin/Native test executable has no Xcode to resolve the Swift packages for it:
>
> ```kotlin
> plugins {
>     id("dev.avinya.ads.admob-cmp") version "2.2.0"
> }
> ```

Platform setup — the Android manifest entry, and on iOS the two Swift packages plus `Info.plist` keys — is required. Follow the [Android setup](https://ads.avinya.dev/start/android-setup/) and [iOS setup](https://ads.avinya.dev/start/ios-setup/) guides, then verify with `./gradlew :admob-cmp-core:doctorIos`.

## Ad formats

All six formats, on both platforms, from one `commonMain` API.

| Format | `AdFormat` | Controller (from `AdManager`) | Composable | Test ad units |
|---|---|---|---|---|
| Banner (incl. collapsible) | `AdFormat.Banner` | `banner(placement)` | `BannerAdView(placement)` | `TestAdIds.ANDROID_BANNER` / `IOS_BANNER` |
| Interstitial | `AdFormat.Interstitial` | `interstitial(placement)` | — | `ANDROID_INTERSTITIAL` / `IOS_INTERSTITIAL` |
| Rewarded | `AdFormat.Rewarded` | `rewarded(placement)` | — | `ANDROID_REWARDED` / `IOS_REWARDED` |
| Rewarded interstitial | `AdFormat.RewardedInterstitial` | `rewardedInterstitial(placement)` | — | `ANDROID_REWARDED_INTERSTITIAL` / `IOS_REWARDED_INTERSTITIAL` |
| App-open | `AdFormat.AppOpen` | `appOpen(placement)` + `AppOpenAdCoordinator` | — | `ANDROID_APP_OPEN` / `IOS_APP_OPEN` |
| Native | `AdFormat.Native` | `nativeAds.session(key, policy)` | `NativeAdView(session, slotKey, placement, layout)` | `ANDROID_NATIVE` / `IOS_NATIVE` |

## 30-second quickstart

This runs against Google's official sample ad units, so it is safe to paste as-is.

```kotlin
@Composable
fun App() {
    val adManager = rememberAdManager()

    LaunchedEffect(Unit) {
        adManager.gatherConsentAndInitialize(
            AdConfig(
                androidAppId = TestAdIds.ANDROID_APP_ID,
                iosAppId = TestAdIds.IOS_APP_ID,
                testMode = true
            )
        )
    }

    val placement = remember {
        AdPlacement(
            id = "main_interstitial",
            format = AdFormat.Interstitial,
            androidAdUnitId = TestAdIds.ANDROID_INTERSTITIAL,
            iosAdUnitId = TestAdIds.IOS_INTERSTITIAL,
            strictTestMode = true
        )
    }
    val interstitial = remember(adManager) { adManager.interstitial(placement) }
    val scope = rememberCoroutineScope()

    Button(onClick = {
        scope.launch {
            interstitial.load()
            interstitial.show()
        }
    }) { Text("Show ad") }
}
```

`gatherConsentAndInitialize` runs the whole production sequence for you: UMP consent, then App Tracking Transparency on iOS, then the one-time SDK initialization. Gate ad-dependent UI on `adManager.status.collectAsState()` reaching `AdManagerStatus.Ready`.

A banner is one composable — it measures its own container and supplies the width, so adaptive sizing is correct even in iPad split view and Slide Over:

```kotlin
BannerAdView(
    placement = AdPlacement(
        id = "home_banner",
        format = AdFormat.Banner,
        androidAdUnitId = TestAdIds.ANDROID_BANNER,
        iosAdUnitId = TestAdIds.IOS_BANNER
    ),
    modifier = Modifier.fillMaxWidth()
)
```

Native ads are laid out with a declarative DSL and served by a bounded session. A feed reuses one placement id, keeps stable model-owned slot keys, and lets the session own native platform objects:

```kotlin
val nativePlacement = remember {
    AdPlacement(
        id = "feed_native",
        format = AdFormat.Native,
        androidAdUnitId = TestAdIds.ANDROID_NATIVE,
        iosAdUnitId = TestAdIds.IOS_NATIVE,
    )
}

val layout = remember {
    adLayout {
        column(modifier = AdModifier.fillMaxWidth()) {
            media(modifier = AdModifier.fillMaxWidth().aspectRatio(16f / 9f))
            headline(maxLines = 2)
            body(maxLines = 3)
            row(spacing = 8.dp) { icon(modifier = AdModifier.size(24.dp)); advertiser(); adBadge() }
            callToAction(modifier = AdModifier.fillMaxWidth())
        }
    }
}

val session = rememberNativeAdFeedSession(
    sessionKey = "feed",
    listState = listState,
    itemCount = feed.size,
    slotAt = { index -> (feed[index] as? FeedItem.NativeSlot)?.let { NativeAdSlot(it.key, nativePlacement) } },
)
NativeAdView(session = session, slotKey = "after-article-3", placement = nativePlacement, layout = layout)
```

> [!WARNING]
> Use a static, finite placement id and stable model-owned slot keys. Never generate either from a row index. The default active session retains three records; the process-wide governor bounds loaded plus reserved ads at soft 4 / hard 6. A temporary tab exit deactivates the session and retains one anchor; permanently discarded destinations close it. Per-placement native TTL remains one hour by default.

## Why AdMob CMP

- **Six formats, not four.** Native ads and app-open ads are supported on both platforms, with a layout DSL and bounded, viewport-aware sessions.
- **Consent is part of initialization.** UMP modes, the privacy options form, and `canRequestAds` are first-class, and the iOS consent → ATT → initialize ordering is enforced rather than documented and hoped for.
- **The iOS test link actually works.** The `dev.avinya.ads.admob-cmp` Gradle plugin links Google Mobile Ads and UMP into Kotlin/Native test executables, which is the difference between `:iosSimulatorArm64Test` passing and failing with `Undefined symbols … _OBJC_CLASS_$_GAD*`.
- **Revenue and mediation are exposed.** Paid events carry `AdValue` and `ResponseInfo`; mediation adapters get initialization hooks.
- **Test safety fails closed.** `AdPlacement.strictTestMode` throws at construction if a placement points at a production ad unit — turn it on in debug builds.
- **The public ABI is frozen** and checked locally by `./scripts/release-readiness.sh`, because CI does not run SDK or ABI verification.

## Compatibility

`admob-cmp` publishes Kotlin/Native klibs plus cinterop klibs. Klibs are not binary-compatible across arbitrary Kotlin versions, so consumers must build with a compatible compiler.

| admob-cmp | Kotlin | Compose Multiplatform | Android `minSdk` | iOS deployment target |
|---|---|---|---|---|
| 2.2.0 | 2.3.20 | 1.11.1 | 26 | 15.0 |
| 2.1.0 | 2.3.20 | 1.11.1 | 26 | 15.0 |
| 2.0.1 | 2.3.20 | 1.11.1 | 26 | 15.0 |
| 2.0.0 | 2.3.20 | 1.11.1 | 26 | 15.0 |
| 1.1.1 | 2.3.20 | 1.11.1 | 26 | 15.0 |
| 1.1.0 | 2.3.20 | 1.11.1 | 26 | 15.0 |
| 1.0.2 | 2.3.20 | 1.11.1 | 26 | 15.0 |
| 1.0.0 | 2.3.20 | 1.11.1 | 26 | 15.0 |

Underlying Google SDKs bound by 2.2.0:

| SDK | Version |
|---|---|
| Google Mobile Ads, Android (Next-Gen) | 1.3.0 |
| Google Mobile Ads, iOS | 13.7.0 |
| User Messaging Platform, Android | 4.0.0 |
| User Messaging Platform, iOS | 3.1.0 |

**Kotlin:** the module is compiled with 2.3.20. Consumers on a different Kotlin *minor* version may fail to resolve the klib. Patch versions are generally safe.

**Compose Multiplatform:** required only if you use the composable surface (`BannerAdView`, `NativeAdView`, `rememberAdManager`). The controller API in `dev.avinya.ads:admob-cmp-core` has no Compose dependency.

**Consumption model:** the SDK is consumable from Kotlin Multiplatform / Gradle projects only — it compiles into the consumer's umbrella framework. A pure-Swift iOS app cannot adopt it without a Kotlin Multiplatform shim.

**Published artifacts:** `dev.avinya.ads:admob-cmp` is the facade and is what you should depend on. It brings in `dev.avinya.ads:admob-cmp-core` (Compose-free) and `dev.avinya.ads:admob-cmp-compose` (the composables). `dev.avinya.ads:admob-cmp-gradle-plugin` is the Kotlin/Native test-linking plugin, applied by its `dev.avinya.ads.admob-cmp` plugin id.

## Documentation

Full guides, diagrams, and the generated API reference live at **[ads.avinya.dev](https://ads.avinya.dev)**.

- [What is AdMob CMP?](https://ads.avinya.dev/start/what-is-admob-cmp/) · [Quickstart](https://ads.avinya.dev/start/quickstart/) — a rendering test ad in five minutes
- [Installation](https://ads.avinya.dev/start/installation/) — Gradle, version catalog, and the Gradle plugin
- [Android setup](https://ads.avinya.dev/start/android-setup/) · [iOS setup](https://ads.avinya.dev/start/ios-setup/) · [Migrate from expect/actual](https://ads.avinya.dev/start/migrate-from-expect-actual/)
- [Banner](https://ads.avinya.dev/formats/banner/) · [Interstitial](https://ads.avinya.dev/formats/interstitial/) · [Rewarded](https://ads.avinya.dev/formats/rewarded/) · [App-open](https://ads.avinya.dev/formats/app-open/) · [Native](https://ads.avinya.dev/formats/native/)
- [UMP consent](https://ads.avinya.dev/privacy/consent/) · [App Tracking Transparency](https://ads.avinya.dev/privacy/app-tracking-transparency/) · [Play Data safety](https://ads.avinya.dev/privacy/play-data-safety/)
- [Mediation](https://ads.avinya.dev/advanced/mediation/) · [Revenue events](https://ads.avinya.dev/advanced/revenue-events/) · [Caching, retry and timeouts](https://ads.avinya.dev/advanced/caching-retry-timeouts/) · [Test safety](https://ads.avinya.dev/advanced/test-safety/)
- [Architecture](https://ads.avinya.dev/reference/architecture/) · [Compatibility](https://ads.avinya.dev/reference/compatibility/) · [Troubleshooting](https://ads.avinya.dev/reference/troubleshooting/) · [Changelog](https://ads.avinya.dev/reference/changelog/)
- [Roadmap](https://ads.avinya.dev/project/roadmap/) · [Contributing](https://ads.avinya.dev/project/contributing/) · [Using with AI agents](https://ads.avinya.dev/project/ai-agents/)
- [Publishing](admob-cmp/docs/PUBLISHING.md) — maintainer guide, repository only

Integrating with an AI coding agent? Point it at [`admob-cmp/AGENTS.md`](admob-cmp/AGENTS.md) and <https://ads.avinya.dev/llms.txt> — the latter is the canonical, machine-readable bundle of the full site.

<details>
<summary><strong>Repository layout</strong></summary>

This repository is the SDK plus a Kotlin Multiplatform demo that exercises it.

| Module | What it is |
|---|---|
| `admob-cmp/` | The published facade artifact — depends on core and compose |
| `admob-cmp-core/` | Compose-free Kotlin Multiplatform core: `AdManager`, consent, full-screen orchestration, banner and native-session coordination, iOS cinterop bindings |
| `admob-cmp-compose/` | Compose Multiplatform UI: `BannerAdView`, `NativeAdView`, the native-ad layout DSL, the debug console, `rememberAdManager` |
| `admob-cmp-gradle-plugin/` | Links Google Mobile Ads and UMP into Kotlin/Native test executables |
| `shared/`, `androidApp/`, `iosApp/`, `desktopApp/`, `webApp/` | The demo application. Ads render on the Android and iOS targets; desktop and web build without the ad surface. |

</details>

## Running the demo

Android and iOS open directly into the AdMob debug console, which exercises every format against Google's official sample ad units with `strictTestMode` validation on every placement.

```bash
./gradlew :androidApp:assembleDebug          # Android
./gradlew :desktopApp:run                    # Desktop (no ads)
./gradlew :webApp:wasmJsBrowserDevelopmentRun # Web (no ads)
```

> [!IMPORTANT]
> For iOS, open [`iosApp/`](iosApp) in Xcode and run. Compose Multiplatform requires **Xcode 26** (and the iOS 26 SDK) because of `UIViewLayoutRegion` linkage.

Tests:

```bash
./gradlew :admob-cmp-core:testAndroidHostTest        # JVM + Android-layer unit tests
./gradlew :admob-cmp-core:iosSimulatorArm64Test      # iOS unit tests
./gradlew :admob-cmp-core:checkKotlinAbi             # public API surface check
./gradlew :admob-cmp-core:doctorIos                  # diagnose iOS consumer integration
```

## Contributing

Issues are open for bugs and feature ideas. This repository runs **no SDK tests in CI**, by design — verification is local and is the contributor's responsibility. Before opening a PR, run `./scripts/release-readiness.sh` and get a clean `READINESS: PASS`; a pass is a prerequisite for asking the owner to open the PR, not authorization to open it unilaterally.

Full contributor guide, including the public-ABI rules and the release procedure: **[CONTRIBUTING.md](CONTRIBUTING.md)** and [ads.avinya.dev/project/contributing/](https://ads.avinya.dev/project/contributing/).

## Showcase app — Fieldnotes

`showcase/` is a product-shaped Compose Multiplatform reference module named **Fieldnotes**. It exercises every ad format in real product flows — a chronological feed with interleaved native slots, section browsing, a rewarded coin economy, and an interstitial gated on natural transitions — with an in-app SDK Lab and telemetry Inspector. It is a **consumer** of `admob-cmp`; reusable ad lifecycle behavior belongs in the SDK, not in sample-only workarounds.

> [!NOTE]
> `showcase/` is currently a library module with its own test suite, not a launchable app — `androidApp`/`iosApp` embed the separate `shared` debug console described in [Running the demo](#running-the-demo). Explore Fieldnotes through its source under `showcase/src/`:
>
> ```bash
> ./gradlew :showcase:testAndroidHostTest :showcase:iosSimulatorArm64Test :showcase:compileKotlinIosSimulatorArm64 --no-configuration-cache
> ```

Full destination tour, format-coverage table, and the Telemetry Inspector: **[ads.avinya.dev/project/showcase/](https://ads.avinya.dev/project/showcase/)**.

## License

[Apache License 2.0](LICENSE).

---

Not affiliated with or endorsed by Google. AdMob and Google Mobile Ads are trademarks of Google LLC.
