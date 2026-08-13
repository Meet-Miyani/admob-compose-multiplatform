# Showcase — Fieldnotes

A product-shaped demonstration of the admob-cmp SDK, named **Fieldnotes**. It
exists to show how the SDK behaves in real product flows, not as a component
gallery. The consumer tabs stay free of demo controls; the SDK Lab is where
every format is exercised deliberately.

## Architecture

Four consumer-facing top-level destinations, each with an independent retained
navigation stack (`nav/ShowcaseNavigationState.kt`):

| Tab | Purpose | Ads |
|---|---|---|
| **Today** | Curated chronological feed | Native slots: first after 4 stories, then every 8 |
| **Discover** | Section browsing and local search | Native slots in result feeds, scoped per query/section |
| **Library** | Saved, in-progress, and unlocked stories | None, by design |
| **Profile** | Appearance, privacy, rewards, SDK Lab entry | None |

Two secondary destinations hang off Profile: **Rewards**, where rewarded ads
are exchanged for premium stories, and **SDK Lab**, which exposes every
supported format plus privacy and diagnostics tooling.

Only tab roots keep the tab bar. A pushed detail — an article, Rewards, a Lab
scenario — takes the whole window and slides in over its parent with a
parallax, and always renders a back action; iOS offers no system gesture out of
a Compose `NavDisplay`, so a pushed screen without one is a dead end. Switching
*tabs* is not animated: each tab owns its own `NavDisplay` under a `key`, so a
lateral move between peers swaps instantly instead of sliding.

## Design system

A custom, platform-neutral language — not stock Material, which would read as
an Android app on iOS, and not a UIKit imitation, which would read as a foreign
app on Android. It lives in `ui/theme/` (palette, tokens, type, shapes) and
`ui/kit/` (components).

- **Two accents.** `primary` is interactive; `accent` is editorial. Keeping
  them apart is what lets `danger` be a real red instead of colliding with the
  brand.
- **Six section hues** (`ui/theme/SectionAccent.kt`) give each editorial
  section an identity across the feed, its cover artwork, and its badges.
- **Procedural covers** (`ui/kit/ArticleCover.kt`). The seed corpus ships no
  images, so each cover is drawn on a Compose `Canvas` from the article's own
  id: the section picks the hue, the id picks the motif. No assets, no network,
  no new dependency, and the same article always looks the same. It also keeps
  the contrast honest — a native ad's `media` view renders a real photo or
  video right beside them.
- **Flat planes, one shadow.** Material's tonal elevation is switched off
  (`surfaceTint` is transparent) so depth reads identically in light and dark.
- **Controls are hand-built.** A Material `Switch` or `RadioButton` carries an
  unmistakably Android silhouette; `ui/kit/Controls.kt` draws its own.
- **Newsreader editorial typography.** Display, headline, `titleLarge`, and
  `titleMedium` roles use the Newsreader variable font from Google Fonts,
  declared at weights 400, 500, 600, and 700 through Compose Resources. Body,
  label, control, metadata, disclosure, and CTA roles remain system sans-serif.
  The bundled font is licensed under the SIL Open Font License 1.1; its text is
  packaged at `composeResources/files/licenses/NEWSREADER-OFL.txt`.

Contrast floors for every rendered pairing are asserted in
`ShowcaseContrastTest` — 7:1 for primary ink, 4.5:1 for everything else that
carries text.

## Ad behaviour

### The placement catalog

`domain/ad/ShowcasePlacements.kt` is a static, finite catalog. Controllers are
cached per `AdPlacement.id` for the manager's lifetime and never evicted, so
generated per-item ids would leak permanently; the feed serves per-item ads
from the native pool keyed by `itemKey` instead.

Every placement uses an official Google test ad unit with
`strictTestMode = true`, so the sample fails closed if a production id is ever
supplied. `ShowcasePlacementsTest` additionally asserts that:

- every catalog entry is reachable from a real surface — the Inspector builds a
  controller per listed placement, so an orphan requests inventory for a screen
  nobody can see;
- **no banner sits in a scrolling feed.** Today, Discover, and Library carry
  none: a banner welded to an infinite list is the integration this sample
  argues against. Exactly one consumer banner is allowed — the reader's
  anchored, collapsible, dismissible slot.

### Stable slot keys

Slot identity derives from product context, never from list position:

- Today: `today:seed-v1:{articleId}` — the revision changes only when editorial
  ordering intentionally invalidates prior identity
- Discover: `discover:search:{query}:{articleId}`,
  `discover:category:{section}:{articleId}`, or `discover:all:{articleId}`
- Article inline: `article:{articleId}:inline-1`

### Native sessions

`rememberNativeAdFeedSession` in the feed screens keeps one session keyed to the
feed context; `rememberNativeAdSlotSession` covers the single inline article
slot. The SDK owns loading, retention, eviction, expiry, and destruction.
Scrolling away detaches the platform view but does not destroy the
session-owned ad; switching Today → Profile → Today returns to the same loaded
ad identity.

Discover debounces input by 250 ms, and only committed, normalised queries
receive session identities, so keystrokes do not accumulate sessions. A retired
query's session is closed explicitly.

`ui/kit/NativeAdCard.kt` renders **nothing at all** until the SDK reports a slot
renderable. A permanent skeleton at full ad height would leave a hole in the
feed whenever fill is slow, consent is missing, or the device is offline — and
an ads showcase that looks broken without ads teaches the wrong lesson.

### Where the ads are

| Surface | Formats |
|---|---|
| Today, Discover | Native, rendered as an editorial row |
| Article | Native (inline), collapsible banner (anchored), rewarded (unlock), interstitial (on leave) |
| Rewards | Native (hero), rewarded, rewarded interstitial |
| Library | None, by design |
| App-wide | App-open, governed by `AppOpenEligibilityPolicy` |

### Native layouts

`ui/ad/AdLayouts.kt` builds two layouts with the SDK's `adLayout {}` DSL, per
theme rather than as top-level constants — a layout baked with light-theme
colours renders a white card in a dark feed. Both carry the two policy-relevant
nodes: `adBadge()` at the top, and reserved `adChoices()` space.

`feedRowAdLayout` is the argument for the DSL in one screen: it composes the
same asset bag into the *exact* geometry of an editorial row — eyebrow,
21sp headline, two-line standfirst, meta line, 92.dp square on the trailing
edge — so a sponsored item sits in the feed's rhythm instead of interrupting
it, while the SPONSORED badge and AdChoices carry the disclosure. It renders no
call-to-action button: the CTA is not required by policy, the validator does not
check for it, the whole native view stays clickable without it, and it was the
one element an article row had no counterpart for.

SDK Lab → Native renders one creative through five layouts — `AdTemplates`
compact, medium, and feedCard, plus the app's own two — and shows the live
`AdLayoutValidator` report for the selected one.

The ad headline family comes from `MaterialTheme.typography.titleLarge` and is
passed to `AdTextStyle` through `AdFontFamily.FromCompose`. The SDK resolves the
same Compose resource font for Android Views and registers its bytes internally
for UIKit, so the consumer needs no Android font XML, iOS `UIAppFonts`, or
platform-specific font name.

### Rewarded ads

Rewarded formats live on the **Rewards** screen, not in the Lab, because that
is the only context in which they are defensible: the reader wants a specific
premium story, the price is stated up front, and declining costs nothing.

One earned reward produces exactly one wallet mutation. The grant key comes
from a monotonic per-session sequence, the wallet refuses a replayed key, and
the credit is driven by the SDK's `onReward` callback rather than by `show()`
returning — a reader who dismisses early still yields `Shown`, and paying on
that is the classic rewarded-ads bug. `RewardGrantTest` and `CoinEconomyTest`
pin this.

### The interstitial

Shown when the reader *leaves* an article, never when they open one — leaving
is a natural break, opening is not. `AdPolicy` caps it further: one per three
articles, no sooner than 60s after the last, never within 30s of a cold start,
and never after a rewarded unlock, because a reader who just watched an ad to
open a story does not get a second one on the way out. The cooldown advances
only when an ad actually appeared (`advancesCooldown`) — charging 60s of
suppression for an ad that failed to show is a bug this sample shipped once.

Every suppression carries a `SuppressionReason` rather than a bare false. "No
ad appeared and I don't know why" is the most common AdMob integration
confusion, and making the reason a first-class value is the most useful thing
this showcase teaches.

### App-open policy

`AppOpenEligibilityPolicy` (pure, unit-tested) refuses app-open ads during
onboarding and the first session, over sensitive routes, while another
full-screen ad is showing, before SDK readiness, without consent, and after a
backgrounding shorter than the configured minimum. The coordinator's
`isBlocked` is bound to the policy decision, and every decision is recorded
sanitised into Diagnostics.

## SDK Lab

One scenario per supported format, asserted complete by `SdkLabCoverageTest` —
a new SDK format cannot ship without one.

| Scenario | What it demonstrates |
|---|---|
| Banner | Anchored adaptive, fixed 320×50, and the collapsible placement the reader uses |
| Native | One creative through five layouts, live validator findings, session controls |
| Full screen | Interstitial / rewarded / rewarded-interstitial, each with Load, Show, and observable readiness |
| App open | Every eligibility gate live, the controller's cache, and the last recorded decision |
| Privacy | Consent state, debug geography, consent reset, ATT, initialisation order |
| Diagnostics | SDK status, placement mapping, native governor, recent events, replay onboarding |

Readiness in the Lab is derived from `loadState`, never from `isReady()`.
`isReady()` is a plain function over `availability()`, so a composable that
calls it captures a value Compose cannot observe — which left every Show button
disabled after a successful load. `rememberAdReadiness` in `LabScaffold.kt`
observes the `StateFlow` instead and reports cache count and expiry alongside.

App open has no button to press, which makes it the format most likely to look
broken during an integration. Its screen lists each gate — onboarding complete,
not on a sensitive route, SDK ready, ads enabled, consent allows requests — with
pass/fail, so "nothing happened" is always attributable.

## Onboarding

Three panels, and **nothing touches the ads SDK until the reader presses a
button on the second one**. `AdStartupController.attach(autoStart = false)` on a
first run makes that guarantee real: consent gathering is what the button
authorises, so it cannot begin before the ask. Then the order runs consent →
ATT (iOS) → initialise → load, which is load-bearing rather than cosmetic —
requesting an ad before ATT resolves permanently forfeits the IDFA for those
requests.

Declining is a first-class path: it flips the ads master switch off, still
initialises the SDK, and every screen renders exactly as it would with ads,
minus the ads.

## Manual QA

1. Onboarding and consent complete without app-open interruption.
2. Today native slots reveal smoothly and keep identity across reverse scroll.
3. Today → Profile → Today preserves scroll position and retained ad identity.
4. Profile → SDK Lab → Today → Profile restores the Lab child route.
5. Discover: the section row starts on "All" and always shows results; query
   and section survive a tab switch without disturbing Today's retention; a
   no-match query shows the empty state.
6. Article inline native appears only after meaningful content, and the page
   still reads correctly when it never loads.
7. No banner appears in a feed. The reader's collapsible banner anchors below
   the article and disappears when ads are switched off.
8. Rewards: a completed rewarded ad credits once; a dismissed one credits
   nothing; a replayed grant key changes nothing.
9. Every SDK Lab format loads/shows or reports an honest unavailable state, and
   every Lab child screen can be backed out of.
10. The tab bar meets the window edge with no outer container and no duplicate
    bottom padding; at ≥ 840.dp the rail replaces it without resetting routes.
11. Opening an article slides it in full-screen with no tab bar; leaving may
    show an interstitial, subject to `AdPolicy`, before navigation completes.
12. Switching tabs does not animate; scrolling a feed collapses its header
    continuously rather than snapping.
13. Backgrounding for 5s and returning shows an app-open ad, once per minute at
    most, and never over onboarding.

## Verification

```bash
./gradlew :showcase:testAndroidHostTest :showcase:iosSimulatorArm64Test :showcase:compileKotlinIosArm64 --no-configuration-cache
```
