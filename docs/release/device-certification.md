# Device certification

Required for any release that changes native integration, consent,
presentation, the renderer, or Gradle/XCFramework behaviour. Not required for
docs-only or test-only changes.

Unit tests cannot prove any of the below: every item depends on real GMA/UMP
behaviour, real OS lifecycle, or real network conditions. An emulator-only pass
is not certification for the iOS column.

## How to run it

Use the `showcase` module — it renders all six ad formats and exposes the
privacy options entry point under Profile → SDK Lab. Run each scenario on one
Android device and one iOS device, then record the result below and paste the
completed table into the release PR or release notes.

Google's test ad units are configured in the showcase. Do not certify against
production ad units.

The EEA rows use the SDK Lab's debug geography, which UMP honours only on a test
device. iOS simulators qualify automatically. An Android emulator does not:
register it first with the lab's **Detect from SDK log**, or the EEA override
does nothing.

## Scenarios

Run every row on both platforms.

| # | Scenario | Android | iOS |
|---|---|---|---|
| 1 | Fresh install, EEA debug geography, consent accepted → ads serve | | |
| 2 | Fresh install, EEA debug geography → no ad request while the consent form is open. After **Do not consent** the SDK follows UMP's `canRequestAds`, which UMP sets to true after any answer, so limited ads may serve | | |
| 3 | Consent withdrawn after ads were allowed, with `resetConsentForDebug` (the Inspector's **Reset consent (debug)**); answering the privacy options form does not withdraw it → `load()` fails with `consent_required`, nothing cached can be shown, and a native feed on screen scrolls without a crash or a request | | |
| 4 | Launch with no consent yet (fresh install, or after row 3's reset) → nothing starts while the form is open; after **Consent**, initialization runs exactly once | | |
| 5 | Background/foreground while a load is in flight → no crash, no stuck state | | |
| 6 | Background/foreground while a full-screen ad is showing → ad is not force-closed | | |
| 7 | Rotation / window resize around banner and native views → geometry survives | | |
| 8 | Offline or DNS-blocked → bounded failure with a typed error, retry works | | |
| 9 | All six formats render: banner, interstitial, rewarded, rewarded-interstitial, app-open, native | | |
| 10 | Reward is emitted exactly once per rewarded presentation | | |
| 11 | AdMob native ad validator reports no implementation issues | | |
| 12 | iOS only: ordering is UMP → ATT → first ad request | n/a | |
| 13 | Privacy options during and after the launch-time consent check. While `gatherConsent` runs it holds the consent slot, so every tap is declined: `showPrivacyOptions()` returns `false`, no form opens, and there is no error or crash. After the check, a double-tap opens exactly one form, and the second call declines without a spurious failure | | |
| 14 | Slow mediation adapter (throttled network, real mediation configured) → `Ready` is reached, not a spurious initialization failure, on both platforms | | |
| 15 | Airplane mode during the launch-time consent refresh, then restore and retry `initialize()` immediately → the retry contacts UMP rather than declining, and `ConsentStatus` never contradicts `canRequestAds` | | |
| 16 | Android only: with a hardware keyboard or D-pad (`adb shell input keyevent KEYCODE_TAB`), traversal skips every banner and native ad; then switch dark mode (`adb shell cmd uimode night yes`) and rotate with a native ad on screen in a lazy list → no crash | | n/a |
| 17 | Android only: touch a banner and a native media view, then hide the slot and let the ad refresh → no crash; a touch click and a TalkBack double-tap each still report `AdEvent.Clicked`, impressions still record, and banner and native layout is unchanged from the previous release (same size, position and clipping) | | n/a |

## Devices

Minimum for a native-affecting release:

- **Android:** one current reference device or emulator at the project's
  `compileSdk`, plus one device at `minSdk` (API 26) where practical.
- **iOS:** one current iPhone simulator **and** one physical iPhone. The
  simulator alone does not exercise real presentation and lifecycle behaviour.

Add an iPad or split-view pass when the release changes adaptive banner or
native layout sizing.

## Sign-off

    Release version:
    Certified by:
    Date:
    Devices used:
    Deviations or known issues:

The release owner signs this. A release that changes native behaviour without a
signed matrix is not certified, regardless of what the local test suite reports.

## Run record — 2026-09-21, post-#47/#48 (Android only)

Android device: Samsung SM-S911B (Galaxy S23), Android 16, font scale 1.08,
private DNS off. Build: `:androidApp:installDebug` at `77f6a3c8`. Zero entries
in the crash buffer for `dev.avinya` across the whole session.

**This run does not certify a release.** It covers one Android device and no
iOS hardware, so rows 1, 2, 3, 4, 6, 13, 14 and the entire iOS column are
untested. It is evidence that the #47/#48 changes behave on device, not a
signed matrix.

| # | Scenario | Android | Evidence |
|---|---|---|---|
| 5 | Background/foreground, no stuck state | PASS | Home → relaunch; feed resumed, full-screen slots still loadable afterwards |
| 7 | Rotation → geometry survives | PASS | Landscape: native re-bound (`renderInto` → `registered after containment. hasMediaView=true`), validator `violations=[]` at the new width |
| 8 | Offline → typed error, retry works | PASS | Cold rewarded slot offline → `failed: java.net.UnknownHostException … googleads.g.doubleclick.net`, Show stayed disabled; after restore, retry → `ready · cached 1` |
| 9 | Formats render | PARTIAL | Banner (adaptive/fixed/collapsible), interstitial, rewarded, native all rendered. Rewarded-interstitial and app-open not exercised |
| 10 | Reward exactly once | PASS | "Reward granted" → dismiss → `Grants this session: 1`, balance 10 coins; survived rotation and backgrounding |
| 11 | Native ad validator clean | PASS | Green "No implementation issues found"; `violations=[]` on feed and in landscape; `AdLayoutValidator` "No findings" on all five lab layouts |

Findings-specific checks, on device:

- **F-06** — feed native slots filled on a normal cold start
  (`renderInto` → `registered after containment`). Pre-fix these stayed
  permanently `Failed`.
- **F-16** — Native lab → Deactivate leaves `slot: Retained`, `active: false`,
  `tracked slots: 1`, ad still rendered. The anchor survives renderer release.
- **F-24** — `Fixed(320,50)` renders ~900px wide at 450dpi (scale 2.8125) =
  **320dp exactly**, inset within a 1080px container rather than filling it.
- **F-18** — every layout shows real attribution (`Ad` chip, or "SPONSORED" on
  `App: inline`); validator counts it, so the badge is non-blank in practice.
- **F-01 / X-1** — banner paid event reached the app (`Last event: Paid`) with
  all view work on Main; no crash across repeated banner screen entry.

Deviations noted during the run:

- The device is in a **non-EEA** geography (`Consent: NotRequired`,
  `Ads can load: Yes`), so rows 1, 2, 13 and 15 need EEA debug geography.
- The showcase logs `AdConfig.testMode is true but no test device IDs are
  configured`. Harmless here (official Google test ad units), but worth wiring
  a test device id before any run against non-test units.

## Run record — 2026-09-21, iOS simulator (partial)

iPhone 16 Pro simulator (iOS 18.6), Xcode 27.0, built from `iosApp.xcodeproj`
at `e011e537`. No crash reports; the app stayed alive through the whole flow
including live content-size changes.

**Simulator, not a device.** Per the rule above, this does not certify the iOS
column. It is recorded because it does cover one thing the Android run could
not: F-17, which has no unit-test safety net.

| # | Scenario | iOS sim | Evidence |
|---|---|---|---|
| 9 | Formats render | PARTIAL | Native rendered via the SDK's `NativeAdView`. Banner, interstitial, rewarded not exercised |
| 11 | Native ad validator clean | PASS | "No implementation issues found" at default size and at `accessibility-extra-extra-extra-large`; `AdLayoutValidator` reports no findings |
| 12 | Ordering is UMP -> ATT -> first ad request | PASS | Consent gate, then the real `ATTrackingManager` system dialog, then the feed; the SDK Lab then reported "Ready - ads may load" |

**F-17 (iOS Dynamic Type) — verified working.** With the simulator at
`accessibility-extra-extra-extra-large`, the ad's SPONSORED attribution
rendered several times its default size, and returned to normal when the
category was set back to `large`. The scaling is live and reversible, which is
what `UIFontMetrics.defaultMetrics.scaledFontForFont` provides and what the
unfixed renderer could not do. This is the first end-to-end evidence for F-17;
until now it had neither a unit test nor a device run.

Observed and deliberately not filed as a defect: in the `App: feed` layout this
creative's media view renders at a tall aspect ratio and fills the card, so the
headline, body and call to action sit below the fold. It behaves identically at
`large` and at the maximum accessibility size, so it is not text scaling, and
Google's validator passes it in both states.

Still untested on iOS: rows 1-8, 10, 13, 14, 15, and every format except
native. A physical iPhone is still required before the iOS column can be
signed.

## Run record — 2026-09-27, ad-host input focus fix (Android only)

Android device: Samsung SM-S942B (Galaxy S26), Android 16 (API 36). Two
`:androidApp:assembleDebug` builds of the same app: the fix branch, and `master`
at `48a41376` (2.5.0) as the baseline. For the run only, both builds' manifests
added `android:configChanges="uiMode"` to `MainActivity`, matching the consumer
app where the crash was reported, so a dark-mode switch recomposes in place. That
line was reverted and is not part of the change.

**This run does not certify a release.** It covers rows 7, 11, 16 and part of
17 on one Android device. The TalkBack half of row 17 was not run, and neither
were rows 1-6, 8-10 and 12-15 or any iOS row.

| # | Scenario | Baseline (2.5.0) | Fix branch | Evidence |
|---|---|---|---|---|
| 16 | Keyboard traversal | TAB enters the feed native ad (`NativeAdView`, AdChoices, icon, headline, body) and the article banner's WebView | PASS — TAB skips both ads | Real View focus flags from `dumpsys activity top`; tabs 1-7 identical on both builds, tab 8 enters the ad only on the baseline |
| 16 | Dark-mode swap with the ad focused and last focusable on screen | **Crash**: `ComposeRuntimeError … pending composition has not been applied`, via `removeAndroidView` → `rootViewRequestFocus` → `focusSearch` → `searchBeyondBounds` → `measureLazyList` | PASS — no crash in 6 switches, 2 in the same position | Crash buffer empty for the whole fix-branch session |
| 17 | Touch click and impression | — | PASS (touch) | Tap on the native headline opened Play; Inspector recorded `feed_native CLICKED`, `IMPRESSION` and `PAID` |
| 17 | Layout unchanged | Banner holder `0,1960-1080,2298`, `AdView` 1080×337; inline native 878×386 | PASS — identical bounds; `AdHostFrame` matches the holder exactly | `dumpsys activity top` on both builds, same article, portrait |
| 7 | Rotation | — | PASS | Landscape: banner re-bound at 2340×216, feed native at 2228×406; no crash |
| 11 | Native ad validator | "No implementation issues found" | PASS — same result, portrait and landscape | Validator popup |

Findings from the run:

- The crash needs the ad to be the **last focusable element on screen** in the
  search direction. With a focusable card visible below the ad, even the
  baseline does not crash: Compose finds that card and never lays out
  off-screen items. A quick manual check can therefore report a false "no
  crash" against a vulnerable build.
- On GMA Next-Gen 1.4.0 a **touch does not give the banner WebView input
  focus** on either build; only keyboard traversal reaches it. uiautomator
  reported the WebView as `focused` after a touch on the fix branch, but that is
  Chromium's accessibility focus, not View input focus. Use
  `dumpsys activity top` flags, not uiautomator, to judge input focus.
- On the baseline, backing out of an article with the banner focused did not
  crash. The banner is anchored outside the scrolling column, so the refocus
  search has no off-screen list items to lay out.

## Run record — 2026-10-08, 2.6.0 candidate (Android device + iOS simulator)

Android device: Samsung SM-S942B (Galaxy S26), Android 16, test ad units. Build:
`:androidApp:installDebug` at `e912ce49` (PR #62 head, merged as `3c79403b`).
iOS: iPhone 16 Pro simulator, iOS 18.2, `iosApp` scheme at the same commit.
Evidence comes from logcat, screenshots, and the showcase's own `ad_events` table,
read from the device with `run-as`. No crash entries and no GMA
"Fullscreen ads that show when your app is in the background" errors in any session.

**This run does not certify the release on its own.** It has no physical iPhone,
and rows 1–8 and 12–16 were not re-run, because this release does not touch
consent, lifecycle, rotation or input focus. The release owner decides whether that
scope is enough and signs below.

| # | Scenario | Android | iOS sim | Evidence |
|---|---|---|---|---|
| 9 | All six formats render | PASS | App-open only | `ad_events`: `lab_banner`/`article_banner` Loaded+Impression+Paid; `lab_native`/`feed_native` Impression+Paid; interstitial, rewarded, rewarded-interstitial Opened+Impression+Paid+Closed; `app_open` Opened+Impression+Paid+Closed. iOS: app-open shown on return, `result=Shown` |
| 10 | Reward exactly once per presentation | PASS | — | Rewarded and rewarded-interstitial each `RewardEarned` once; "Grants this session" 1 → 2, wallet 10 → 20 coins |
| 11 | Native ad validator clean | PASS | — | Validator popup "No implementation issues found" in the Native lab |
| 17 | Touch click and impression recorded | PASS | — | `ad_events`: `feed_native` Clicked+Impression+Paid, `article_banner` Clicked+Impression+Paid; each tap opened Play |
| — | App-open on a warm return (the 2.6.0 fix) | PASS | PASS | Before the fix: 4/4 warm returns refused by GMA as background shows. After: shown about 90 ms after the return, 0 refusals |
| — | `skipAfterAdClick` (showcase has it on) | PASS | — | The return after a real ad click logged `skipped … first return after an ad click`; the next return showed |

Findings from the run:

- **Google-side, not this SDK:** GMA's app-open *test* creative opens Play's
  inline-install sheet about 2 s after display, with no touch (`label=alleyoop_triggered`,
  no `/pagead/aclk`). It happened on every Android app-open test ad seen today.
- **Showcase only, not the SDK:** the Banner lab puts "Anchored adaptive" and
  "Fixed 320×50" on one placement, so one slot stays empty. The App Open lab shows
  a 60 s cooldown while the host uses 15 s. After ads are turned on in onboarding,
  app-open loads only on the next return. All three are tracked as a follow-up.
- The nine `app_open` `LoadFailed` events were all `consent_required`, from before
  ads were turned on in onboarding: the consent gate working as designed.

## Run record — 2026-11-05, #66 + #67 combined (iOS simulator only)

iOS: iPhone 17 Pro simulator, iOS 26.5 (23F77), `iosApp` scheme built at `fcf8a69f`
(PR #66 head, with #67 merged in through `3d550e3e`). Fresh install, Google test ad
units, showcase defaults (`startFromStoredConsent` on, adapter wait 5 s). EEA rows use
the showcase's debug geography; the simulator counts as a UMP test device without an ID.
Evidence comes from the SDK's `[AdMobCMP]` log lines in the simulator's unified log, the
showcase's `ad_events`, `policy_decisions` and `reward_grants` tables read from the app
container, screenshots, and one crash report.

**This run does not certify the release on its own.** There is no Android column, because
the owner skipped the Android device for this run. There is no physical iPhone: the owner
accepted the simulator for the iOS column of this release, which is a deviation from the
device rule above. Rows 8, 14 and 15 could not run; see the table.

| # | Scenario | iOS sim | Evidence |
|---|---|---|---|
| 1 | EEA, consent accepted → ads serve | PASS | No ad events between relaunch and the tap. After **Consent**: `initialize` reused the launch's UMP update ("no second request"), gate `canRequestAds=true`, adapters 71 ms, `app_open` and `feed_native` `Loaded` 1.3–1.5 s later |
| 2 | Consent denied → no ad requests | NOTE | UMP sets `canRequestAds=true` after **Do not consent** (Google serves limited ads), and the SDK follows UMP. Verified instead: no request while `canRequestAds=false`, both before the form was answered and before ATT. The row's wording needs updating |
| 3 | Revoke after ads were allowed → `load()`/`show()` blocked | PASS | Revoked through the SDK's own path ("Reset consent state"): "consent revoked … purging cached inventory", purge `fullScreen=1`; the next interstitial `load()` failed with `consent_required`, and Show stayed disabled. **Do not consent** in the privacy options form does not revoke, as in row 2 |
| 4 | Grant after prior denial → initialization resumes once | PASS | Cold start with consent cleared: stored-consent start waited, UMP `Required canRequestAds=false`, nothing started while the form was open. After **Consent**, exactly one each of `initialize requested`, "accepts ad requests" and "initialization succeeded", and one UMP update, reused |
| 5 | Background/foreground during a load → no stuck state | PASS | Interstitial `Load`, then Home at once; `Loaded` arrived while backgrounded; after return the state was "ready · cached 1", and Show opened it |
| 6 | Background/foreground during a full-screen ad → not force-closed | PASS | App-open ad on screen, Home, return: same ad still shown, no `ClosedFullScreen` until dismissed by hand; the next app-open ad reloaded in between |
| 7 | Rotation → geometry survives | PASS, see F4 | Banners reloaded at landscape width, native card re-laid out and re-bound the same ad (`ad 1`) on return to portrait, validator "No findings"; no crash |
| 8 | Offline → typed error, retry works | NOT RUN | Taking a simulator offline means taking the Mac offline |
| 9 | All six formats render | PASS | Banner adaptive, fixed and collapsible; interstitial; rewarded; rewarded interstitial; app-open on a warm return; native. Each `Loaded` (or `OpenedFullScreen`) + `Impression` + `Paid` |
| 10 | Reward exactly once per presentation | PASS | Rewarded and rewarded interstitial each `RewardEarned` once; `reward_grants` has two keys, wallet 20 coins |
| 11 | Native ad validator clean | PASS | "No implementation issues found"; `AdLayoutValidator` "No findings" |
| 12 | Ordering UMP → ATT → first ad request | PASS | UMP update 15:55:07.5 → UMP explainer → ATT requested 15:55:31.2, authorized 15:55:51.8 → `initialize` 15:55:52.4 → first `Loaded` 15:55:54.9. Before ATT the only events were two `app_open` `consent_required` refusals |
| 13 | Double-tap privacy options → one form, no spurious failure | PARTIAL | Two rapid taps: one form, no error, no crash. The launch-time refresh (300–900 ms) cannot be hit by simulator automation, and the UI cannot show whether the second tap reached the SDK |
| 14 | Slow mediation adapter → `Ready`, no spurious failure | NOT RUN | The test app's mediation config lists ten adapters, all "No such adapter in the application"; nothing slow to wait for |
| 15 | Airplane mode during the launch consent refresh | NOT RUN | Same as row 8 |
| 16, 17 | Android only | n/a | — |

#66 behaviour seen on every launch:

- **Stored-consent start** (opt-in, on in the showcase): on all 8 returning launches,
  GMA accepted requests 5–42 ms after the UMP update was requested ("starting MobileAds
  from consent stored in an earlier session" 2–19 ms after it, where logged), before UMP
  answered. With no stored consent, or consent cleared, it waited for UMP.
- **One UMP update per launch:** every `initialize` after `gatherConsent` logged
  "Reusing the UMP consent info update … no second request".
- **Bounded adapter wait:** "Waiting up to 5s for mediation adapters"; the adapters
  reported in 56–1222 ms, so `Ready` came at their callback every time.
- **App-open:** no show at launch (`FirstSession`); shown on a warm return after the 4 s
  minimum background; the next ad reloaded after the show.

Device measurements owed by #66's research (M1–M3; M4 needs real mediation):

- **M1:** confirmed on iOS: right after `requestConsentInfoUpdate`, UMP reports the
  previous session's answer, and the early start uses it.
- **M2:** confirmed: the iOS UMP update is a network round trip on every launch,
  309–906 ms here.
- **M3:** cold launch to `Ready`, five launches each, same simulator and state (EEA,
  consent stored, ATT allowed). 2.6.0 median **1733 ms**, #66 median **1112 ms**, about
  0.6 s sooner. 2.6.0 also made a second UMP request inside `initialize`. First
  `app_open` loaded at a median 1756 ms with #66; the 2.6.0 showcase preloads app-open
  only on a return, so it has no comparable figure.

Native ad info (#67) on iOS: native `Loaded` events with response IDs; the lab's slot
note "served by: Reservation campaign · ad 1", and the same id on that ad's `Impression`.

Findings from the run:

- **F1 — crash, pre-existing since 2.0.0, high.** `NativeAdSession.updateWindow` throws
  `IllegalStateException("Native ad session '…' is no longer active.")` on a session that
  is closed, and its KDoc lists only `IllegalArgumentException`. The showcase's native
  lab **Close → Recreate** reproduces it: uncaught Kotlin exception, `SIGABRT`, two crash
  reports. The SDK also closes sessions itself on consent revocation and inactive reap.
  A unit probe (not committed) confirmed that after `onConsentRevoked()` the same handle's
  next `updateWindow` throws, so an app with a native feed or slot composed crashes on its
  next scroll after consent is revoked. #66's opt-in stored-consent start adds one more
  way to reach it: a launch-time UMP update that withdraws stored consent. Fix needs an
  owner decision.
- **F2 — matrix wording.** Rows 2 and 3 assume **Do not consent** blocks requests; UMP's
  `canRequestAds` is true after any choice.
- **F3 — showcase.** After a revocation the SDK Lab header still reads "Ready — ads may
  load": `status` is `Ready` by design, and the gate is what is closed.
- **F4 — unverified, pre-existing code.** In landscape the Fixed 320×50 banner's creative
  looked wider than 320 pt. Banner code is unchanged since 2.6.0; a view-frame
  measurement is needed before calling it a defect.

## Run record — 2026-11-07, #66 after #68 merged (iOS simulator only)

#68 fixed F1, and `d49b08c3` merged it into #66. This run repeats the consent and native
rows, the ones #68 changed. Same simulator as the run above, `iosApp` scheme built at
`d49b08c3`, which had `READINESS: PASS`. The app was uninstalled first, so it was a fresh
install, with the same test ad units and showcase defaults. The reinstall cleared the
debug geography, so the first launch ran outside the EEA. Rows 1 and 4 come from a
relaunch with EEA set and consent reset. Evidence comes from the same sources as above.
There was no crash report and no exception on the console in either launch.

The same deviations apply: no Android column and no physical iPhone. Rows 2, 5–8, 10 and
13–15 were not re-run, because #68 does not touch them.

| # | Scenario | iOS sim | Evidence |
|---|---|---|---|
| 1 | EEA, consent accepted → ads serve | PASS | GDPR form shown on relaunch. No ad event and no `initialize` while it was open. After **Consent**: `initialize` reused the launch's UMP update ("no second request"), adapters 112 ms, `app_open` and `feed_native` `Loaded` 1.2 s and 1.4 s later. The feed's native ad recorded `Impression` + `Paid` |
| 3 | Revoke after ads were allowed → `load()`/`show()` blocked | PASS | Inspector **Reset consent (debug)** with the Today feed's native ad on screen: "consent revoked … purging", purge `fullScreen=1 nativeSessions=1`. Inspector: session `active`, slot `Ready` → `Empty`, manager loaded 4 → 0, reserved 0. Scrolling the feed requested nothing and did not crash (same PID). An interstitial `load()` then failed with `consent_required` |
| 4 | Grant after prior denial → initialization resumes once | PASS | Same relaunch. The stored-consent start waited, and UMP reported `Required canRequestAds=false`. For the whole launch: one UMP update, one `initialize requested`, one "accepts ad requests", one "initialization succeeded" |
| 9 | All six formats render: native | PASS | Feed: sponsored card, `Loaded` + `Impression` + `Paid`. Native lab: the same three events |
| 11 | Native ad validator clean | PASS | "No implementation issues found"; `AdLayoutValidator` "No findings" |
| 12 | Ordering UMP → ATT → first ad request | PASS | First launch: the UMP update finished 14:46:01.6 (`Required canRequestAds=false`), then the UMP explainer, then ATT allowed. `initialize` at 14:46:25.4, first `Loaded` 14:46:27.9 |
| — | F1: native lab **Close** → **Recreate** | PASS | Close: "slot: not in window · active: false · tracked slots: 0". Recreate: a new ad loaded (instance 2 → 3, new response ID), no crash |

Not covered on a device:

- **Consent allowed again in the same process.** The showcase only asks for consent at
  startup, so it cannot reach this state. Unit tests cover the coordinator re-offering
  demand, and the admission hook firing on the transition. Across a relaunch, row 1
  above loaded the feed again.
- **A launch-time UMP update that withdraws stored consent.** This is the extra way to
  reach F1 that #66's stored-consent start added. Reproducing it needs UMP to withdraw
  consent stored in an earlier session during the update, which the simulator cannot
  arrange.

F2, F3 and F4 are still open. F3 was seen again: after the reset, the SDK Lab header
read "Ready — ads may load".

## Run record — 2026-11-07, master `11eab7f1`, 2.7.0 candidate (Android emulator + iOS simulator)

`11eab7f1` is master with #66–#70 merged, and `VERSION_NAME` is still 2.6.0. It had a full
`READINESS: PASS` (all nine sections). Both apps were built at that commit and freshly
installed, with Google test ad units and showcase defaults (stored-consent start on,
adapter wait 5 s).

- **Android:** `Pixel_Tablet` emulator, Android 17 / API 37 (= `compileSdk`), Play Store
  image. The emulator was registered as a UMP test device before the EEA rows.
- **iOS:** iPhone 17 Pro simulator, iOS 26.5.
- **Evidence:** the SDK's `AdMobCMP` log lines (logcat, unified log); the showcase's
  `ad_events`, `policy_decisions` and `reward_grants` tables, copied from the app; `dumpsys`
  for focus; screenshots.
- **Stability:** no crash report, ANR or fatal exception on either platform.

**Deviations:**
- An emulator instead of an Android device, and no `minSdk` (API 26) pass.
- No physical iPhone. The owner accepted the simulator for this release.
- TalkBack is not installed on the emulator image.

| # | Scenario | Android emu | iOS sim | Evidence |
|---|---|---|---|---|
| 1 | EEA, consent accepted → ads serve | PASS | PASS | Form open: no `initialize` and no ad request (Android 42 s, iOS). After **Consent**: `initialize` reused the launch's UMP update ("no second request"); `feed_native` and `app_open` `Loaded` 1.7–5 s later; feed native `Impression` + `Paid` |
| 2 | No request while the form is open; **Do not consent** follows UMP | PASS | PASS | While open, only gate refusals (`app_open` `consent_required`), no request. After **Do not consent**: `canRequestAds=true`, one `initialize`, limited ads `Loaded` |
| 3 | **Reset consent (debug)** after ads were allowed | PASS | PASS | "consent revoked … purging", purge `fullScreen=1 nativeSessions=1`; slot `Ready` → `Empty`; feed scrolled with no request and the same PID; interstitial `load()` → `consent_required`, Show opened nothing |
| 4 | No consent yet → nothing starts while the form is open; then one initialization | PASS | PASS | One UMP update, one `initialize requested`, one "accepts ad requests", one "initialization succeeded" per launch |
| 5 | Background/foreground during a load | PASS | PASS | Rewarded (Android) and interstitial (iOS) `Loaded` while backgrounded; "ready · cached 1" on return; Show opened it |
| 6 | Background/foreground during a full-screen ad | PASS | PASS | App-open ad still on screen after Home and return; `ClosedFullScreen` only when dismissed; the next app-open preloaded meanwhile |
| 7 | Rotation around banner and native | PASS | PASS, see F4 | Banners reloaded at each width (`Loaded` + `Impression` + `Paid`); native card re-bound the same ad with no new load; `AdLayoutValidator` "No findings" |
| 8 | Offline → typed error, retry works | PASS | NOT RUN | Android airplane mode: `LoadFailed NETWORK_ERROR` in about 2 s; retry loaded. The simulator shares the Mac's network |
| 9 | All six formats render | PASS | PASS | Banner adaptive, fixed and collapsible; interstitial; rewarded; rewarded interstitial; app-open on a warm return; native in the feed and the lab. Each `Loaded` or `OpenedFullScreen` + `Impression` + `Paid` |
| 10 | Reward exactly once per presentation | PASS | PASS | One `RewardEarned` per presentation (Android 3 of 3, iOS 2 of 2); one `reward_grants` key each; wallet credited 10 per reward |
| 11 | Native ad validator clean | PASS | PASS | "No implementation issues found" in the feed and the Native lab; `AdLayoutValidator` "No findings" in all five layouts |
| 12 | UMP → ATT → first ad request | n/a | PASS | Fresh install: UMP update finished 17:38:50.9 → ATT authorized 17:39:29.5 → `initialize` 17:39:30.1 → first `Loaded` 17:39:32.1; nothing before ATT |
| 13 | Double-tap privacy options during the launch refresh | PARTIAL | PARTIAL | Android hit the window: **both** taps declined, 0 forms, "Privacy options aren't available right now" (see F6). After the refresh, one form and the second call declined. iOS: automation can't reach the 0.6–1.4 s window; two rapid taps later gave one form, no error |
| 14 | Slow mediation adapter → `Ready` | NOT RUN | NOT RUN | No real adapters in the test app. The bounded wait itself worked: "Waiting up to 5s…"; one Android launch published `Ready` at the 5 s bound, then initialization succeeded |
| 15 | Airplane mode during the launch refresh, then retry | PASS | NOT RUN | Android: the retry sent a fresh UMP update request, then form → **Consent** → one `initialize`; `ConsentStatus` and `canRequestAds` agreed throughout. iOS: as row 8 |
| 16 | TAB skips ads; dark mode + rotation with native on screen | PASS | n/a | 25 × TAB with the feed native as the last card: focus stayed on `AndroidComposeView`, `AdHostFrame` not focusable. `uimode night yes` + rotation: no crash, same PID |
| 17 | Touch, hide, refresh; clicks; layout | PARTIAL | n/a | Touch `Clicked` on `feed_native` (media view), `article_banner`, `lab_banner`; hiding and re-showing the slots reloaded them with no crash. TalkBack double-tap not run; layout not compared with a 2.6.0 build |

Checks of earlier findings:
- **F1:** fixed. Native lab **Close** → **Recreate** loads a new ad on both platforms, with no crash.
- **F2:** fixed. Rows 2–4 passed as written.
- **F3:** fixed. After a reset the SDK Lab reads "Ready — consent blocks ads".
- **F5:** fixed. Today's Inspector dot is green after the form flow and accent after a reset.
- **#66 stored-consent start (iOS):** requests were accepted 16 ms after the UMP update request,
  before UMP answered (613 ms).
- **#67:** the Native lab shows "served by: Reservation campaign · ad N".

Findings from the run:

- **F4 — confirmed, iOS only, SDK, pre-existing.** The "Fixed 320×50" banner's view takes the
  container width: 360 pt in portrait and about 832 pt in landscape, against exactly 320 dp on
  Android. In landscape, GMA's responsive test creative stretched across that width.
  `IosBannerAdView` sizes the `UIKitView` with `.fillMaxWidth().height(adSize.height)`, so a
  `Fixed` size's width never reaches the frame. Banner code is unchanged since 2.6.0, so this
  is not a regression. It was measured from screenshots; the view frame was not read directly.
  **Fixed in #71** (`32881d88`). The banner now sits in a full-width row at the ad size's own
  width, centred, as on Android. On the same simulator the fixed frame measured 960 px at 3×
  (320 pt), centred, in portrait and landscape. The anchored adaptive banner still spans the
  container, and the collapsible banner expands and collapses as before. The test creative's
  text clips inside the 320×50 frame. The cause is the showcase's choice of test unit for that
  slot, Google's adaptive one (`…/2435281174`); the fixed-size unit is `…/2934735716`.
- **F6 — row 13 vs the SDK's design.** The showcase's launch refresh is `gatherConsent`. That
  call claims the consent slot, and `showPrivacyOptions()` documents declining while it is
  held. So every privacy-options tap in the first 1–3 s is declined, and none opens a form. The
  row expects "one form", which fits only a plain `requestConsentInfoUpdate`. Either reword
  the row, or let privacy options wait for a `gatherConsent` that ends without a form.
  **Resolved:** the owner chose to reword row 13 to the documented behaviour, and it now reads
  that way. This behaviour has shipped since 2.2.0/2.3.0, and #66 did not change it. Against the
  new wording, the Android result above passes: both taps during the check were declined with
  no error, then one form opened after it. The iOS result stays partial, because automation
  cannot reach the window. Making privacy options wait out the check and then open the form
  would be an SDK behaviour change, left for a later release.
- **Google-side, debug only:** the validator popup stayed on screen after leaving the Native lab
  (Android).
- **Showcase only:** rotating the tablet resets the feed's scroll position. The `RewardEarned`
  detail shows micros (`10000000`) while the wallet credits 10.

**Sign-off for 2.7.0:**

    Release version: 2.7.0
    Certified by: Meet Miyani
    Date: 2026-11-07
    Devices used: Android emulator Pixel_Tablet, Android 17 / API 37 (= compileSdk), Play Store image;
      iOS simulator iPhone 17 Pro, iOS 26.5. Certified build: master 11eab7f1. #71 (the iOS banner
      frame, the only code change since) was re-checked on the same simulator; #72 and #73 are docs only.
    Deviations or known issues:
      - No physical Android device (emulator only) and no minSdk (API 26) pass.
      - No physical iPhone. The simulator was accepted for this release.
      - No iPad or split-view pass, although #71 changes iOS banner framing.
      - Not run: rows 8 and 15 on iOS (the simulator can't go offline without the Mac), and row 14
        on both platforms (no real mediation adapters, so the M4 slow-adapter timing is still owed).
      - Partial: row 13 on iOS (automation can't reach the launch window); row 17 on Android
        (TalkBack isn't on the emulator image, and layout wasn't compared with a 2.6.0 build).
      - Known, showcase only: the "Fixed 320×50" slot uses Google's adaptive test unit, so the test
        creative clips its text. Also, the feed's scroll resets on tablet rotation, and the
        RewardEarned detail shows micros.
      - Known, Google-side, debug only: the native validator popup can stay on screen after
        leaving the Native lab.
