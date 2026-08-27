# Consent Host Availability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the SDK from permanently failing consent (and therefore initialization) when the host Activity / root view controller is momentarily unavailable, and stop the iOS ATT request from hanging forever.

**Architecture:** Three defects share one shape — a suspending step reads a transient platform host, gets `null` or never gets a callback, and returns a terminal failure. This plan adds one small `internal` polling primitive in `commonMain` (`awaitHost`, plus its Boolean form `awaitCondition`), unit-tests it, then rewires the three call sites (Android consent, iOS consent, iOS ATT) to use it. Timeout constants go into the existing `InitializationTimeouts` object rather than new top-level vals. Everything added is `internal`, so the frozen public ABI is untouched.

**Tech Stack:** Kotlin Multiplatform (Android + iOS), kotlinx-coroutines, Google UMP, Google Mobile Ads (Next-Gen), AppTrackingTransparency.

## Global Constraints

- **Invariant 5:** all GMA/UMP calls happen on `Dispatchers.Main` (`.immediate`).
- **Invariant 9:** every suspending ad operation is bounded. This plan extends that rule to consent-host acquisition and the ATT prompt, which are currently unbounded.
- **Invariant 11:** the order is UMP consent → `tracking.requestAuthorization()` → `initialize(config, ConsentMode.InitializeOnlyIfAlreadyAllowed)`. **Do not reorder anything in this plan.**
- **Invariant 12:** the public ABI is frozen. Every symbol added here MUST be `internal`. If you find yourself making something `public`, stop and re-read the task.
- **Do not** edit `gradle.properties` or `admob-cmp-gradle-plugin/gradle.properties` `VERSION_NAME`. Releases are the owner's call (Task 5 covers this).
- **Do not** add Gradle dependencies, files under `gradle/`, or CI jobs to `.github/workflows/release.yml`.
- **CI runs no SDK tests.** If you do not verify locally, nothing will.
- Kotlin `Duration` values come from `kotlin.time`; use named `internal val` constants, never magic numbers (detekt will flag them).

---

## Base and Prerequisites

**Implement on `fix/full-screen-presentation-transaction`, not `master`.**

- `master` has no `detekt` or `koverVerify` task, so Task 5 Step 2 fails outright there.
- That branch is at a verified `READINESS: PASS`, and its `release-readiness.sh` has 9 sections
  (static analysis + coverage was added as section 3).
- Implementing on `master` and merging later guarantees a conflict, because the branch already
  modified the exact functions Task 2 edits.

**What the branch already changed in this plan's blast radius** — read this before Task 2:

- `AndroidConsentController.requestConsentInfoUpdate` now takes an owned `AdConfig` snapshot and
  wraps the UMP callback in `awaitNativeCallback(... InitializationTimeouts.consentInfoUpdate)`.
  It starts at **line 646**, not 585, and its text differs from what an older plan draft quoted.
- `IosConsentController.requestConsentInfoUpdate` has the same two changes.
- `InitializationTimeouts` (`internal object`, `commonMain/internal/NativeCallbackTimeouts.kt`)
  already exists and already owns native-callback bounds. **New timeout constants in this plan go
  inside it** — do not add competing top-level vals.
- `awaitNativeCallback` already exists and boxes its result so a legitimate `null` is never
  mistaken for a timeout. Any new bounded primitive here must preserve that property.

Nothing in this plan conflicts semantically with that work: it bounds the **callback**, this plan
bounds **host acquisition**. They compose.

---

## Design Decisions

### Why each public consent entry acquires the host exactly once (and why two waits is the floor)

Startup reads the host three times: `gatherConsent` reads it, the `requestConsentInfoUpdate` it
calls internally reads it again, and `initialize(InitializeOnlyIfAlreadyAllowed)` reads it a third
time through `consent.requestConsentInfoUpdate(config)` (`AndroidGoogleAdManager.kt:283`). A naive
per-read timeout means three independent waits.

Three options were considered:

1. **Shared deadline threaded through a `CoroutineContext` element** — rejected. It makes the
   effective timeout invisible at the call site; `awaitHost(InitializationTimeouts.consentHost)` would silently
   mean something different depending on ambient context. That is a maintenance trap in a
   consent path, which is the last place to put implicit state.

2. **Hoist the acquisition so the host is passed down** — **adopted, within each public entry
   point.** `gatherConsent` acquires once and shares it with the internal update call. It cannot
   reach the third read: `initialize` calls `requestConsentInfoUpdate` through the **public**
   `ConsentController` interface, and adding a host parameter there would break the frozen ABI
   (invariant 12). So this takes three waits to two, not to one.

3. **Abandon immediately when the app is not foreground** — rejected, and this is the important
   one. `isAppInForeground()` on iOS tests `applicationState == Active`, which is **false while
   the app is merely Inactive** — including during launch and while a modal is dismissing. Early
   abandon would break the exact iOS launch case this plan exists to fix.

**Two bounded waits is therefore the floor without an ABI change, and that is accepted.** Note
what the cost actually is: on the transient path (the bug being fixed) the first wait resolves in
a few hundred milliseconds and the second is instant, so the added latency is sub-second. Only a
genuinely backgrounded app pays both timeouts, and that time is spent in the background on a path
that today fails permanently — it is not user-visible latency. The reason to hoist is redundant
work and unclear ownership, not milliseconds.

### Why the foreground gate polls instead of awaiting a transition

`appForegroundState()` is a transition-only flow: the iOS actual observes
`UIApplicationWillEnterForegroundNotification`, which **does not fire on a cold launch** — only on
a background-to-foreground return. Gating the ATT prompt on `appForegroundState().first { it }`
would therefore hang the full foreground timeout and then silently skip ATT whenever the app is
Inactive at that moment — which is entirely reachable, because dismissing the UMP consent form
leaves the app briefly Inactive right before ATT is requested in the documented order.

Silently skipping ATT is a revenue regression introduced by a reliability fix. So the foreground
gate **polls `isAppInForeground()`** with the same primitive Task 1 builds, and Task 4 does not use
`appForegroundState()` at all.

---

## File Structure

**Created:**
- `admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/HostAvailability.kt` — the `awaitHost` / `awaitCondition` polling primitives. One responsibility: "wait a bounded time for a condition to hold". The timeout constants themselves live in the existing `InitializationTimeouts` object, not here.
- `admob-cmp-core/src/commonTest/kotlin/dev/avinya/ads/HostAvailabilityTest.kt` — tests for the above.

**Modified:**
- `admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/NativeCallbackTimeouts.kt` — four constants added to the existing `InitializationTimeouts` object (`consentHost`, `hostPoll`, `attForeground`, `attPrompt`).
- `admob-cmp-core/src/androidMain/kotlin/dev/avinya/ads/AndroidGoogleAdManager.kt` — `AndroidConsentController.requestConsentInfoUpdate` and `.gatherConsent` (from line 646 on the target branch), plus a new `private suspend fun updateWithActivity(...)` extracted from the former.
- `admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosConsentController.kt` — the host acquisition in `gatherConsent` (the null-check around line 95 on the target branch).
- `admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosTrackingAuthorization.kt` — `requestAuthorization` (whole function).

**Deliberately NOT modified:**
- `RootViewController.kt` (`topViewController()`) — its `isBeingPresented` / `isBeingDismissed` guards are correct; the bug is that callers treat one `null` as final. Leave the probe alone.
- `AdMob.kt` (`ForegroundStack` / `CurrentActivityTracker`) — do **not** start retaining stopped Activities as a fallback. Presenting a UMP dialog on a stopped Activity throws `BadTokenException`; waiting for a live one is the correct fix.
- `showPrivacyOptions()` on either platform — it is user-initiated from a screen that is by definition on top, so a host is always present.

---

### Task 1: The `awaitHost` primitive

A bounded poll for a platform host that may be momentarily absent. Polling (rather than a callback) is deliberate: iOS has no notification for "presentation transition finished", so a probe loop is the only mechanism that works on both platforms, and one shared primitive means one set of tests.

**Files:**
- Create: `admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/HostAvailability.kt`
- Test: `admob-cmp-core/src/commonTest/kotlin/dev/avinya/ads/HostAvailabilityTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - Two constants added **inside the existing `InitializationTimeouts` object** (see Base and
    Prerequisites — do not create competing top-level vals):
    `val consentHost: Duration = 2.seconds` and `val hostPoll: Duration = 50.milliseconds`
  - `internal suspend fun <T : Any> awaitHost(timeout: Duration, pollInterval: Duration = InitializationTimeouts.hostPoll, probe: suspend () -> T?): T?`
  - `internal suspend fun awaitCondition(timeout: Duration, pollInterval: Duration = InitializationTimeouts.hostPoll, check: suspend () -> Boolean): Boolean`
  - Tasks 2 and 3 call `awaitHost(InitializationTimeouts.consentHost) { <platform probe>() }`;
    Task 4 calls `awaitCondition(InitializationTimeouts.attForeground) { isAppInForeground() }`.

**Why `probe` is `suspend`:** the foreground check (`isAppInForeground()`) is a suspend function
that hops to Main, and Task 4 needs to poll it. One suspend-capable loop serves both callers, so
there is a single implementation and a single set of tests rather than two near-duplicates.

- [ ] **Step 1: Write the failing test**

Create `admob-cmp-core/src/commonTest/kotlin/dev/avinya/ads/HostAvailabilityTest.kt`:

```kotlin
package dev.avinya.ads

import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.awaitCondition
import dev.avinya.ads.internal.awaitHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * Pins the behaviour that closes the "no Activity / no root view controller" class of
 * consent failure.
 *
 * The host is transiently absent in normal operation — between an ad Activity stopping and
 * the app Activity restarting, across a configuration change, and while an iOS view
 * controller is mid-presentation. Treating the first `null` as final is what left the SDK
 * uninitialized for a whole session; [awaitHost] gives the host a bounded moment to appear.
 */
class HostAvailabilityTest {

    @Test
    fun returnsImmediatelyWhenTheHostIsAlreadyPresent() = runTest {
        var probes = 0
        val host = awaitHost(2.seconds) { probes++; "activity" }

        assertEquals("activity", host)
        assertEquals(1, probes, "an available host must not cost a single poll delay")
    }

    @Test
    fun waitsForAHostThatArrivesShortlyAfterTheFirstProbe() = runTest {
        var probes = 0
        // Absent for the first three probes, exactly like an Activity handoff in flight.
        val host = awaitHost(2.seconds) {
            probes++
            if (probes > 3) "activity" else null
        }

        assertEquals("activity", host)
        assertEquals(4, probes)
    }

    @Test
    fun givesUpAfterTheTimeoutWhenTheHostNeverArrives() = runTest {
        val host = awaitHost(200.milliseconds, pollInterval = InitializationTimeouts.hostPoll) { null }

        assertNull(host, "a genuinely absent host must still fail, just not instantly")
    }

    @Test
    fun stopsProbingOnceTheHostIsFound() = runTest {
        var probes = 0
        awaitHost(2.seconds) {
            probes++
            if (probes >= 2) "activity" else null
        }

        assertEquals(2, probes, "probing must stop at the first non-null result")
    }

    @Test
    fun awaitConditionPollsUntilTheConditionHolds() = runTest {
        var checks = 0
        val became = awaitCondition(2.seconds) { checks++; checks > 2 }

        assertTrue(became)
        assertEquals(3, checks)
    }

    @Test
    fun awaitConditionGivesUpWhenTheConditionNeverHolds() = runTest {
        // This is the ATT foreground gate's failure mode: an app launched into the background
        // never becomes active, and the prompt must be skipped rather than waited on forever.
        assertFalse(awaitCondition(200.milliseconds) { false })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:iosSimulatorArm64Test --tests "dev.avinya.ads.HostAvailabilityTest" --no-configuration-cache
```

Expected: FAIL — compilation error, `Unresolved reference: awaitHost` (and `InitializationTimeouts.hostPoll`).

- [ ] **Step 3: Write the implementation**

Create `admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/HostAvailability.kt`:

```kotlin
package dev.avinya.ads.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long consent gathering waits for a usable platform host (Android `Activity`, iOS root
 * `UIViewController`) before giving up on this attempt.
 *
 * Two seconds because every window this exists to cover is sub-second: the gap between an ad
 * `Activity` stopping and the app `Activity` restarting, a configuration change, and an iOS
 * view controller finishing its presentation transition. Long enough to cover all three,
 * short enough that a genuinely backgrounded app does not sit here.
 */
// NOTE: these two go inside the EXISTING `InitializationTimeouts` object in
// commonMain/kotlin/dev/avinya/ads/internal/NativeCallbackTimeouts.kt, alongside
// `nativeInitialize` and `consentInfoUpdate`. Do not declare them as top-level vals here — the
// codebase already has one home for native-callback bounds and it should keep exactly one.
//
//     /** How long consent gathering waits for a usable platform host before giving up. */
//     val consentHost: Duration = 2.seconds
//     /** Gap between host probes. Invisible at consentHost's scale. */
//     val hostPoll: Duration = 50.milliseconds

/**
 * Waits up to [timeout] for [probe] to return a non-null host, polling every [pollInterval].
 * Returns null if it never does.
 *
 * ## Why polling rather than a callback
 * Android could observe `Application.ActivityLifecycleCallbacks`, but iOS has no notification
 * for "this view controller finished presenting" — `topViewController()` deliberately reports
 * null mid-transition. A probe loop is the only mechanism that works on both platforms, and
 * one shared primitive means one set of tests instead of two per-platform implementations.
 *
 * ## Why this is not a retry of the operation
 * This waits for the *host*, not for the UMP call. Once a host is in hand the caller makes
 * exactly one attempt; retrying the consent call itself is the consuming app's decision, not
 * the SDK's.
 */
internal suspend fun <T : Any> awaitHost(
    timeout: Duration,
    pollInterval: Duration = InitializationTimeouts.hostPoll,
    probe: suspend () -> T?,
): T? = probe() ?: withTimeoutOrNull(timeout) {
    var found: T? = null
    while (found == null) {
        delay(pollInterval)
        found = probe()
    }
    found
}

/**
 * Boolean form of [awaitHost]: waits up to [timeout] for [check] to become true.
 *
 * Used to gate the ATT prompt on the app actually being foreground. Deliberately a poll rather
 * than a wait on `appForegroundState()`: that flow only emits on transitions, and the iOS
 * foreground notification does not fire on a cold launch — so awaiting an edge would hang and
 * then silently skip the prompt. See "Why the foreground gate polls" in Design Decisions.
 */
internal suspend fun awaitCondition(
    timeout: Duration,
    pollInterval: Duration = InitializationTimeouts.hostPoll,
    check: suspend () -> Boolean,
): Boolean = awaitHost(timeout, pollInterval) { if (check()) true else null } != null
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:iosSimulatorArm64Test --tests "dev.avinya.ads.HostAvailabilityTest" --no-configuration-cache
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Run the same tests on the JVM backend**

The Native and JVM Kotlin backends differ; a common test can pass on one and fail on the other.

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:testAndroidHostTest --tests "dev.avinya.ads.HostAvailabilityTest" --no-configuration-cache
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && git add admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/HostAvailability.kt admob-cmp-core/src/commonTest/kotlin/dev/avinya/ads/HostAvailabilityTest.kt && git commit -m "feat(consent): add bounded awaitHost primitive for transient platform hosts"
```

---

### Task 2: Android consent waits for the Activity

`AndroidConsentController` currently reads `activityProvider()` once and returns a terminal `ConsentStatus.Failed` on the first null. Because `ForegroundStack` drops an Activity on `onActivityStopped`, that null is routine — the stack is briefly empty during any Activity-to-Activity handoff. One startup reads the provider three times (`gatherConsent`, the `requestConsentInfoUpdate` it calls internally, and the one `initialize` calls), so there are three chances to land in the gap.

**Files:**
- Modify: `admob-cmp-core/src/androidMain/kotlin/dev/avinya/ads/AndroidGoogleAdManager.kt:585-632`

**Interfaces:**
- Consumes: `awaitHost`, `InitializationTimeouts.consentHost` from Task 1.
- Produces: no new symbols. Behaviour change only: `requestConsentInfoUpdate` and `gatherConsent` now wait up to `InitializationTimeouts.consentHost` for a usable Activity before failing.

**No new unit test.** `AndroidConsentController` is a private class that calls `UserMessagingPlatform` statics, and `androidHostTest` has no Robolectric, so a real `Activity` cannot be constructed. The waiting logic is fully covered by Task 1; this task is wiring, verified by compilation, the existing suite, and the device smoke test in Task 5. Do not invent a mock-heavy test to fill the gap.

- [ ] **Step 1: Add the imports**

In `admob-cmp-core/src/androidMain/kotlin/dev/avinya/ads/AndroidGoogleAdManager.kt`, add these two imports alongside the existing `dev.avinya.ads.internal.*` imports at the top of the file (they sit next to `import dev.avinya.ads.internal.AdRequestAdmission`):

```kotlin
import dev.avinya.ads.internal.awaitHost
```

- [ ] **Step 2: Add the shared failure helper**

In the `AndroidConsentController` class, immediately after the existing `private fun fail(message: String): ConsentStatus` declaration, add:

```kotlin
    /**
     * The host was absent for the whole `InitializationTimeouts.consentHost` window.
     *
     * Kept as one helper so both consent entry points log identically and the public
     * [ConsentStatus.Failed] message stays byte-identical to what it has always been —
     * only the warning above it is new.
     */
    private fun failNoActivity(): ConsentStatus {
        AdLogger.w(
            "No usable Android Activity for UMP consent after waiting " +
                "${InitializationTimeouts.consentHost}. Consent was not gathered on this attempt; " +
                "the host app should retry."
        )
        return fail("No current Android Activity for UMP consent.")
    }
```

- [ ] **Step 3: Rewire `requestConsentInfoUpdate` (hoist the acquisition)**

The host is acquired once, inside the main hop, and the rest of the body is unchanged. Note this
is the **current branch text** — it already carries the owned snapshot and the
`awaitNativeCallback` wrapper. Replace only the head of the function, from its signature down to
and including the `getConsentInformation` line:

```kotlin
    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus {
        // Owned snapshot, matching AdManager.initialize(): lastConfig outlives this call and is
        // reused if showPrivacyOptions() later resumes initialization, so retaining the caller's
        // object let post-call mutation of its lists/hooks change UMP debug IDs and hook execution.
        val config = config.ownedSnapshot()
        lastConfig = config
        config.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
        val activity = activityProvider()
            ?: return fail("No current Android Activity for UMP consent.")
        return withContext(Dispatchers.Main.immediate) {
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
```

with:

```kotlin
    override suspend fun requestConsentInfoUpdate(config: AdConfig): ConsentStatus {
        // Owned snapshot, matching AdManager.initialize(): lastConfig outlives this call and is
        // reused if showPrivacyOptions() later resumes initialization, so retaining the caller's
        // object let post-call mutation of its lists/hooks change UMP debug IDs and hook execution.
        val config = config.ownedSnapshot()
        lastConfig = config
        config.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
        return withContext(Dispatchers.Main.immediate) {
            // Acquired inside the main hop, not before it: this reads Activity lifecycle state,
            // which is main-thread-owned (invariant 5). It waits rather than failing on the first
            // null because ForegroundStack legitimately empties for a few hundred milliseconds
            // during any Activity handoff.
            val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                ?: return@withContext failNoActivity()
            updateWithActivity(activity, config, UserMessagingPlatform.getConsentInformation(appContext))
        }
    }

    /**
     * The UMP info-update sequence, given a host that has already been acquired.
     *
     * Split out so each PUBLIC consent entry point acquires the host exactly once.
     * [gatherConsent] used to call [requestConsentInfoUpdate], which re-acquired it — two waits
     * for one logical operation. Callers are responsible for being on Main.
     */
    private suspend fun updateWithActivity(
        activity: Activity,
        config: AdConfig,
        consentInformation: ConsentInformation,
    ): ConsentStatus {
```

Then **move the remainder of the original body** — everything from `val params = buildConsentParams(...)`
down to and including the final `status`, unchanged — into `updateWithActivity`, and close it with a
single `}`. Every existing `return@withContext` inside that moved code becomes a plain `return`,
because it is no longer inside a `withContext` lambda. There is exactly one, in the
`NativeCallbackTimeoutException` catch:

```kotlin
        } catch (timeout: NativeCallbackTimeoutException) {
            AdLogger.e("Android UMP consent info update timed out.", timeout)
            return fail(timeout.message ?: "UMP consent info update timed out.")
        }
```

and the trailing `status` becomes `return status`.

- [ ] **Step 4: Rewire `gatherConsent` to reuse the acquired host**

Replace this exact block:

```kotlin
    override suspend fun gatherConsent(config: AdConfig): ConsentStatus {
        val activity = activityProvider()
            ?: return fail("No current Android Activity for UMP consent.")
        return withContext(Dispatchers.Main.immediate) {
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
            val update = requestConsentInfoUpdate(config)
```

with:

```kotlin
    override suspend fun gatherConsent(config: AdConfig): ConsentStatus =
        withContext(Dispatchers.Main.immediate) {
            val activity = awaitHost(InitializationTimeouts.consentHost) { activityProvider() }
                ?: return@withContext failNoActivity()
            val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)
            // The acquired Activity is reused rather than calling the public
            // requestConsentInfoUpdate, which would wait for a host a second time. The snapshot
            // and hook dispatch that entry point performs are replicated here so behaviour is
            // identical.
            val ownedConfig = config.ownedSnapshot()
            lastConfig = ownedConfig
            ownedConfig.dispatchInitializationHooks(AdInitializationPhase.BeforeConsentRequest)
            val update = updateWithActivity(activity, ownedConfig, consentInformation)
```

The function changes from a block body with `return withContext(...)` to an expression body. Every
other existing `return@withContext` inside it stays valid. **Delete the now-unmatched closing
brace**: the old block body ended with `}` for the lambda and `}` for the function; the expression
body needs only the lambda's. After editing, the function must end with:

```kotlin
            val status = consentInformationStatus(consentInformation).also { _status.value = it }
            status
        }
```

- [ ] **Step 4b: Add the imports this task needs**

`Activity` and `ConsentInformation` are already imported in this file. Confirm the two new ones
from Step 1 are present, plus:

```kotlin
import dev.avinya.ads.internal.InitializationTimeouts
```

(`awaitNativeCallback`, `NativeCallbackTimeoutException` and `InitializationTimeouts` may already
be imported by the branch's existing consent timeout work — check before adding duplicates.)

- [ ] **Step 5: Compile Android**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:compileAndroidMain --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`. If you see "expecting '}'" or "unresolved reference: activity", you mismatched braces in Step 4 — recount them.

- [ ] **Step 6: Run the full Android host suite for regressions**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:testAndroidHostTest --no-configuration-cache
```

Expected: PASS, no failures. `ForegroundStackTest` and `AndroidGoogleAdManagerNativePolicyTest` must both still pass — this task changed neither's behaviour.

- [ ] **Step 7: Commit**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && git add admob-cmp-core/src/androidMain/kotlin/dev/avinya/ads/AndroidGoogleAdManager.kt && git commit -m "fix(consent): wait for a usable Activity instead of failing UMP on the first null"
```

---

### Task 3: iOS consent waits for the root view controller

`topViewController()` returns null in three distinct situations — no foreground-active scene, no key window, or the top controller is `isBeingPresented()` / `isBeingDismissed()`. That last guard fires during any presentation transition, including the Compose `UIViewController` being installed by `UIViewControllerRepresentable` at launch, which is exactly when a host app's startup effect runs. The window here is wider than Android's, not narrower.

**Files:**
- Modify: `admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosConsentController.kt:66-88`

**Interfaces:**
- Consumes: `awaitHost`, `InitializationTimeouts.consentHost` from Task 1.
- Produces: no new symbols. Behaviour change only.

**No new unit test**, for the same reason as Task 2: `iosTest` cannot construct a `UIWindowScene`, and the waiting logic is already covered by Task 1.

- [ ] **Step 1: Add the imports**

In `admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosConsentController.kt`, add to the import block:

```kotlin
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.awaitHost
```

- [ ] **Step 2: Rewire the host acquisition in `gatherConsent`**

Replace this exact block:

```kotlin
        val rootVC = topViewController()
        if (rootVC == null) {
            _status.value = ConsentStatus.Failed(AdError.message("No root view controller for consent form."))
            return@withContext _status.value
        }
```

with:

```kotlin
        // Waits rather than failing on the first null. topViewController() deliberately
        // reports null while the top controller is mid-presentation or mid-dismissal, which
        // is routine at launch — the host's Compose UIViewController is often still being
        // presented when a startup effect first runs.
        val rootVC = awaitHost(InitializationTimeouts.consentHost) { topViewController() }
        if (rootVC == null) {
            AdLogger.w(
                "No usable iOS root view controller for the UMP consent form after waiting " +
                    "${InitializationTimeouts.consentHost}. Consent was not gathered on this " +
                    "attempt; the host " +
                    "app should retry."
            )
            _status.value = ConsentStatus.Failed(AdError.message("No root view controller for consent form."))
            return@withContext _status.value
        }
```

- [ ] **Step 3: Compile iOS**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:compileKotlinIosSimulatorArm64 --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Run the full iOS suite for regressions**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:iosSimulatorArm64Test --no-configuration-cache
```

Expected: PASS, no failures.

- [ ] **Step 5: Commit**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && git add admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosConsentController.kt && git commit -m "fix(consent): wait for a usable iOS root view controller before failing the UMP form"
```

---

### Task 4: Bound the iOS ATT request

`IosTrackingController.requestAuthorization()` is an unbounded `suspendCancellableCoroutine`
(`IosTrackingAuthorization.kt:28`). iOS does not present the ATT prompt while the app is not
foreground-active, and the completion handler can simply never fire. Because ATT sits between
consent and `initialize()` (invariant 11), a hang there means `initialize()` is never reached at
all — no error, no status change, no ads, silently, for the whole session.

The fix is two bounds: wait briefly for foreground before asking, and put a backstop timeout on
the prompt itself.

**The foreground wait must POLL, not await a transition.** An earlier draft of this plan used
`appForegroundState().first { it }`. That is a bug: the iOS actual observes
`UIApplicationWillEnterForegroundNotification`, which does **not** fire on a cold launch — only on
a background-to-foreground return. And `isAppInForeground()` tests `applicationState == Active`,
which is false while the app is merely *Inactive* — the state it is in immediately after a UMP
consent form dismisses, which is exactly when ATT is requested in the documented order. Awaiting
an edge there would hang the full foreground timeout and then **silently skip the ATT prompt** on
every such launch. That trades a reliability bug for a revenue bug. Poll instead, reusing Task 1's
`awaitCondition`.

**Files:**
- Modify: `admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/NativeCallbackTimeouts.kt`
- Modify: `admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosTrackingAuthorization.kt`

**Interfaces:**
- Consumes: `awaitCondition` from Task 1; `isAppInForeground()` (existing `internal expect suspend fun`).
  **Does not use `appForegroundState()`** — see above.
- Produces: two constants inside the existing `InitializationTimeouts` object:
  `val attForeground: Duration = 5.seconds`, `val attPrompt: Duration = 60.seconds`.

**No new unit test beyond Task 1's.** The bounding and polling logic is `awaitCondition` and
`awaitNativeCallback`, both already tested. What remains here is wiring plus two constants, and
`iosTest` cannot present an ATT prompt. Do not invent a mock-heavy test to fill the gap.

- [ ] **Step 1: Add the two constants**

In `admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/NativeCallbackTimeouts.kt`, add to
the existing `InitializationTimeouts` object, alongside `nativeInitialize` and `consentInfoUpdate`:

```kotlin
    /**
     * How long to wait for the app to become foreground-active before abandoning the ATT prompt
     * for this launch. Five seconds covers a cold start that is still becoming active and the
     * Inactive window right after a consent form dismisses; longer means the app was genuinely
     * launched into the background, where the prompt cannot be presented at all.
     */
    val attForeground: Duration = 5.seconds

    /**
     * Backstop for the ATT completion handler itself. Generous, because a real user reading the
     * system dialog is inside this window — but bounded, because the handler is documented to
     * simply never fire in some states, and an unbounded wait here blocks initialize().
     */
    val attPrompt: Duration = 60.seconds
```

- [ ] **Step 2: Rewire the iOS ATT controller**

Replace the `requestAuthorization` function in
`admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosTrackingAuthorization.kt`:

```kotlin
    override suspend fun requestAuthorization(): AdTrackingAuthorization =
        // UIKit/ATT prompt presentation is main-thread only (CLAUDE.md invariant #5).
        withContext(Dispatchers.Main.immediate) {
            if (status() != AdTrackingAuthorization.NotDetermined) return@withContext status()
            suspendCancellableCoroutine { continuation ->
                ATTrackingManager.requestTrackingAuthorizationWithCompletionHandler { _ ->
                    if (continuation.isActive) continuation.resume(status())
                }
            }
        }
```

with:

```kotlin
    /**
     * Requests ATT authorization, bounded on both sides (invariant 9).
     *
     * iOS does not present this prompt while the app is not foreground-active, and the completion
     * handler can never fire at all. Because ATT sits between UMP consent and `initialize()`
     * (invariant 11), an unbounded wait here means the SDK is never initialized — silently, for
     * the whole session.
     *
     * Timing out is the correct outcome rather than failing: a skipped prompt leaves the OS status
     * `NotDetermined`, so it is offered again on a later launch, and requests in the meantime
     * simply go out without the IDFA. That is a revenue cost; hanging is a total ad outage.
     */
    override suspend fun requestAuthorization(): AdTrackingAuthorization =
        // UIKit/ATT prompt presentation is main-thread only (CLAUDE.md invariant #5).
        withContext(Dispatchers.Main.immediate) {
            if (status() != AdTrackingAuthorization.NotDetermined) return@withContext status()
            // Polled, not awaited on appForegroundState(): that flow emits only on transitions and
            // its iOS notification does not fire on a cold launch, so awaiting an edge would hang
            // and then silently skip the prompt. See Design Decisions.
            if (!awaitCondition(InitializationTimeouts.attForeground) { isAppInForeground() }) {
                AdLogger.w(
                    "App did not become foreground-active within " +
                        "${InitializationTimeouts.attForeground}; skipping the ATT prompt this " +
                        "launch. Status stays ${status()} and the prompt will be offered again."
                )
                return@withContext status()
            }
            try {
                awaitNativeCallback(
                    operation = "ATTrackingManager.requestTrackingAuthorization",
                    timeout = InitializationTimeouts.attPrompt,
                ) {
                    suspendCancellableCoroutine { continuation ->
                        ATTrackingManager.requestTrackingAuthorizationWithCompletionHandler { _ ->
                            if (continuation.isActive) continuation.resume(status())
                        }
                    }
                }
            } catch (timeout: NativeCallbackTimeoutException) {
                AdLogger.w(
                    "ATT completion handler never fired within " +
                        "${InitializationTimeouts.attPrompt}; continuing with ${status()}. " +
                        "The prompt will be offered again on a later launch."
                )
                status()
            }
        }
```

Reusing `awaitNativeCallback` rather than a bare `withTimeoutOrNull` is deliberate: it already
distinguishes a timeout from a legitimate result (it boxes the value), and its exception is
**not** a `CancellationException`, so a timeout cannot be swallowed by a caller's cancellation
handling. A bare `withTimeoutOrNull(...) { request() } ?: fallback()` reintroduces exactly the
null-versus-timeout ambiguity that boxing exists to prevent.

- [ ] **Step 3: Add the imports**

```kotlin
import dev.avinya.ads.appopen.isAppInForeground
import dev.avinya.ads.internal.InitializationTimeouts
import dev.avinya.ads.internal.NativeCallbackTimeoutException
import dev.avinya.ads.internal.awaitCondition
import dev.avinya.ads.internal.awaitNativeCallback
```

`AdLogger` is in the same package (`dev.avinya.ads`) and needs no import.

- [ ] **Step 4: Compile iOS and run the full iOS suite**

```bash
./gradlew :admob-cmp-core:iosSimulatorArm64Test --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && git add admob-cmp-core/src/commonMain/kotlin/dev/avinya/ads/internal/NativeCallbackTimeouts.kt admob-cmp-core/src/iosMain/kotlin/dev/avinya/ads/IosTrackingAuthorization.kt && git commit -m "fix(att): bound the iOS ATT request on a polled foreground gate and a prompt timeout"
```

---

### Task 5: Full verification and release hard stop

**Files:** none modified. This task runs the project's only real verification and then stops.

- [ ] **Step 1: Confirm the ABI dump is unchanged**

Every symbol added by this plan is `internal`, so the frozen public ABI must be untouched. Verify rather than assume — a stale dump merges and publishes silently.

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :admob-cmp-core:checkKotlinAbi :admob-cmp-compose:checkKotlinAbi --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`. If this FAILS, you accidentally made something public — find it and make it `internal`. Do **not** run `updateKotlinAbi` to paper over it.

- [ ] **Step 2: Run detekt and coverage**

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew detekt :admob-cmp-core:koverVerify :admob-cmp-compose:koverVerify --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL`. Do not add detekt baseline entries to silence findings from this change — fix the code.

- [ ] **Step 3: Run the full release-readiness script**

This is the only verification that exists in this project. It takes a long time (Xcode + iOS + Astro).

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./scripts/release-readiness.sh
```

Expected: a clean `READINESS: PASS`.

`--skip-docs` is **not** acceptable here: this change touches `admob-cmp-core/`.

If you are not on macOS with Xcode 26, **stop and say so.** There is no remote fallback and no workflow will verify the branch for you.

- [ ] **Step 4: Device smoke test — the actual bug**

The unit tests cover the primitives; this covers the wiring. Run the showcase and confirm the failure mode is gone.

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && ./gradlew :androidApp:assembleDebug --no-configuration-cache
```

Install, then:
1. Cold start the app and immediately press Home (backgrounding it mid-startup). Return to the app.
2. Check logcat for `No current Android Activity for UMP consent`. **It must not appear.** Before this change it did.
3. Confirm the showcase's startup state reaches its ready state and ads load.

- [ ] **Step 5: Commit any fixes, then STOP**

If Steps 1–4 required fixes, commit them:

```bash
cd /Users/meetmiyani/Documents/MeetMiyani/MEET/AdmobCMP && git add -A && git commit -m "fix(consent): address release-readiness findings"
```

**Then stop.** Per the repo's CLAUDE.md this is a hard stop:
- A clean `READINESS: PASS` is a **prerequisite for asking** the owner whether to open the PR. It is not authorisation.
- Report to the owner: which sections ran, which were skipped, anything that failed and was fixed.
- **Do not** open the PR unilaterally.
- **Do not** bump `VERSION_NAME` in `gradle.properties` or `admob-cmp-gradle-plugin/gradle.properties`. The release decision, and the lockstep bump, belong to the owner. ViewTube's companion plan depends on a published version, so flag that the owner will need to cut one — but do not cut it yourself.
