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

## Scenarios

Run every row on both platforms.

| # | Scenario | Android | iOS |
|---|---|---|---|
| 1 | Fresh install, EEA debug geography, consent accepted → ads serve | | |
| 2 | Fresh install, consent denied → no ad requests are made | | |
| 3 | Privacy options revoke after ads were allowed → `load()`/`show()` blocked | | |
| 4 | Privacy options grant after prior denial → initialization resumes once | | |
| 5 | Background/foreground while a load is in flight → no crash, no stuck state | | |
| 6 | Background/foreground while a full-screen ad is showing → ad is not force-closed | | |
| 7 | Rotation / window resize around banner and native views → geometry survives | | |
| 8 | Offline or DNS-blocked → bounded failure with a typed error, retry works | | |
| 9 | All six formats render: banner, interstitial, rewarded, rewarded-interstitial, app-open, native | | |
| 10 | Reward is emitted exactly once per rewarded presentation | | |
| 11 | AdMob native ad validator reports no implementation issues | | |
| 12 | iOS only: ordering is UMP → ATT → first ad request | n/a | |
| 13 | Double-tap the privacy options entry point during the launch-time consent refresh → one form, and the second tap declines without a spurious failure | | |
| 14 | Slow mediation adapter (throttled network, real mediation configured) → `Ready` is reached, not a spurious initialization failure, on both platforms | | |
| 15 | Airplane mode during the launch-time consent refresh, then restore and retry `initialize()` immediately → the retry contacts UMP rather than declining, and `ConsentStatus` never contradicts `canRequestAds` | | |

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
