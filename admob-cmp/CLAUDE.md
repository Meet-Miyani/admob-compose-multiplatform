# CLAUDE.md — admob-cmp

Compose Multiplatform AdMob SDK. Package `dev.avinya.ads`, artifact
`dev.avinya.ads:admob-cmp`. Android (GMA Next-Gen, API 26+) + iOS (GMA 13.x,
iOS 15+).

**Read [AGENTS.md](AGENTS.md) first** — it is the authoritative API/usage guide
(entry points, per-format API, consent, iOS setup, troubleshooting, module
internals). This file only adds the rules for *modifying* the module with Claude
Code. Don't duplicate AGENTS.md here; if the API surface changes, update
AGENTS.md, not this file.

## Build & test (run from repo root)

```bash
./gradlew :admob-cmp:compileAndroidMain                 # Android main
./gradlew :admob-cmp:compileKotlinIosSimulatorArm64     # iOS
./gradlew :admob-cmp:compileCommonMainKotlinMetadata    # shared commonMain
./gradlew :admob-cmp:testAndroidHostTest                # JVM + Android-layer unit tests
./gradlew :admob-cmp:iosSimulatorArm64Test              # iOS unit tests
```

- Tests use hand-written fakes (`Fakes.kt`) and injectable `clock` /
  `foregroundEvents` seams. There are no instrumented tests. `commonTest` covers
  the shared state machine; `androidHostTest` covers the Android platform layer
  (notably the GMA error-code mapping contract, which is easy to break silently
  on an SDK bump — `LoadAdError.code` is an **enum**, so `code.toString()` yields
  the enum NAME that `retryableLoadFailureCodes` matches on, not an integer).
- `explicitApi()` is on **and** KGP ABI validation is enforced: after ANY public
  API change run `./gradlew :admob-cmp:updateKotlinAbi` and commit
  `api/admob-cmp.klib.api`, or the build fails. The DSL is still experimental and
  `enabled` defaults to false — an empty `abiValidation {}` block leaves both
  `updateKotlinAbi` and `checkKotlinAbi` silently SKIPPED, so keep the explicit
  `enabled.set(true)` and the `@OptIn(ExperimentalAbiValidation::class)`.
- iOS bindings are cinterop against XCFrameworks downloaded by the
  `dev.avinya.ads.admob-cmp` Gradle plugin (included build) to
  `build/admob-cmp-ios-frameworks/` (version-stamped via a marker file). The
  download/checksum/linker logic lives in the plugin, **not** in
  `admob-cmp-core/build.gradle.kts` — don't reintroduce it there. Bindings-only
  distribution — **never** add `staticLibraries` to the `.def` files.

## Hard invariants when editing

1. **`FullScreenSlotCore` is the shared state machine.** Android/iOS slots
   implement only `loadAd` / `presentAd` / `destroyAd` / `canPresent` /
   `getResponseInfo`. Put load/show/cache/retry/consent logic in the core, not
   in platform slots — keep the fix at the shared altitude.
2. **`presentAd` suspends until the ad is dismissed.** Destroy a presented ad
   only on *normal* return, never on cancellation (cancelling mid-show means the
   ad is still on screen). See the `catch (CancellationException)` in
   `FullScreenSlotCore.show()`.
3. **Native coordinator owns native objects.** The governor is the sole capacity authority:
   loaded records plus reservations never exceed the hard limit. Reservation tokens map to a
   session generation; every cancellation, stale callback, clear, eviction, or TTL expiry
   retires exactly once. Use one lock direction (governor before coordinator/session); never
   call a platform SDK or destroy an ad while holding either lock. Android's batch callback
   handoff remains synchronized because callbacks and cancellation race.
4. **iOS ObjC delegates are weak.** Keep a strong Kotlin ref alongside the coordinator-owned ad
   record and renderer fields; capture ads in paid-event
   handlers via `WeakReference` to avoid ARC cycles.
5. **All GMA/UMP calls happen on `Dispatchers.Main`** (`.immediate`), **except
   `MobileAds.initialize()` which is `@WorkerThread` in GMA Next-Gen and runs on
   `Dispatchers.IO`** (its completion callback fires on Main regardless of calling thread).
   The consent gate (`adRequestBlockedError()`) is checked in **both** `load()` and `show()`,
   and `canPresent()` is re-checked at present time — don't remove either check.
6. **Banner controllers have no layout context.** Geometry is host-supplied:
   `load(geometry: BannerGeometry?, …)` takes the width, and `BannerAdView` measures
   its own container and passes it. `refresh()` replays the *whole* resolved request
   (geometry + size policy + request options), not just the size. A controller must
   never reach for an `Activity`, `UIScreen`, or any other layout source — the
   headless fallback is `BannerPlatform.fallbackWidthDp()`, which is nullable on both
   platforms so `BannerCore` owns the failure policy. On iOS that fallback reads the
   **key window**, never `UIScreen.mainScreen`: window bounds are what is correct in
   split view, Slide Over and popovers.

7. **`BannerCore`, `NativeAdCoordinatorCore`, and `NativeAdSessionCore` are shared state
   machines.** Platform classes implement only their platform interface. Native batching lock
   handoff, iOS delegate creation/retention/ordering, and iOS in-flight registries remain
   platform-side. A record may have one renderer lease; a stale release cannot unmount a newer
   record. The coordinator retains platform delegates until exact retirement.

8. **Native capacity is global and session-driven.** The default process policy is soft 4/hard
   6 and counts loaded plus reserved work. Visible demand may pass soft capacity; speculative
   demand may not. Mounted records are never eviction candidates. `AdCachePolicy.maxSize` and
   `reloadAfterShow` do not control native sessions; per-placement `nativeTtl` still does.

9. **Every suspending ad operation is bounded.** Loads go through
   `withTimeoutOrNull(placement.timeoutPolicy.loadTimeout)` inside the shared core, never
   per-platform. Presentation bounds only the pre-hand-off window: once
   `FullScreenPresentationHandle.tryHandOffToCallbacks()` succeeds the SDK owns the
   presentation and it must never be force-closed.

10. **Test safety fails closed.** `AdPlacement.strictTestMode` throws on a production ad
    unit id. `AdDebugOptions.testMode` is UMP-only and is NOT a test-ad guarantee — never
    describe it as one.

11. **ATT precedes the first iOS request.** UMP consent, then
    `tracking.requestAuthorization()`, then `initialize(config, ConsentMode.InitializeOnlyIfAlreadyAllowed)`. Requesting earlier permanently
    forfeits the IDFA for those requests.

12. **The public ABI is frozen.** A prior audit of breaking-change candidates against the
    public surface has been taken or explicitly rejected in full. Do not take further
    breaking changes without a written migration plan for every consuming app. Additive
    changes are fine.

## Demo app & on-device verification

- The composeApp demo only wires a **native** ad (`feed_native`, in the profile /
  "You" → TrendingSection). No `BannerAdView` / interstitial is placed in the
  demo, so banner-rotation and full-screen paths are not reachable there.
- To exercise ads on a device the SDK must initialize: the demo defaults to
  `ConsentMode.InitializeOnlyIfAlreadyAllowed`, which correctly **defers** init
  when no consent exists. For a quick local ad check, temporarily switch
  `composeApp/.../App.kt` to `ConsentMode.SkipConsent` (test ad units only) and
  revert after.
- Test ad units come from `TestAdIds`; the manifest uses Google's sample
  AdMob App ID. Real ad fetches are blocked by ad-filtering DNS
  (e.g. AdGuard `private_dns`) even when the network is otherwise up — symptom is
  `ERR_CONNECTION_REFUSED` to `googleads.g.doubleclick.net`. Disable private DNS
  to load real test ads.
- Logcat tag is `AdMobCMP`. The native pipeline logs
  `preload requested → load started → loading completed loaded=N →
  preload finished state=Loaded → acquired token=… nativeAdFound=true`.
  Google's "AdMob native ad validator — No implementation issues found" card
  rendering on screen confirms correct native-ad binding.
