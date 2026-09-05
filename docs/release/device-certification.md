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
