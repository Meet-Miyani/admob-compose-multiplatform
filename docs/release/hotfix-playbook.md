# Hotfix playbook

**Maven Central artifacts are immutable.** A published version cannot be
withdrawn, patched in place, or rolled back. Every response below is a
forward fix. This is why the procedure is written down before it is needed.

## Severity

| Severity | Definition | Response |
|---|---|---|
| **Blocker** | Consent/privacy is wrong, ads serve when they must not, the SDK crashes hosts, or a documented integration cannot build | Stop recommending the version immediately; patch release |
| **High** | A format is broken, an ad or native object leaks, or a documented API behaves incorrectly with no workaround | Patch release |
| **Medium** | Incorrect behaviour with a documented workaround | Next scheduled release |
| **Low** | Cosmetic, docs, or diagnostics | Next scheduled release |

Privacy and consent defects are always at least High and take triage priority
over everything else, including build breaks.

## A bad release is already on Central

1. **Stop recommending the version.** Update the version in
   `docs-site/src/content/docs/start/installation.mdx` and the README to the
   last known-good release, and add a note to
   `docs-site/src/content/docs/reference/changelog.mdx` naming the affected
   version and the symptom.
2. **Establish the affected range.** Identify the first version carrying the
   defect. State the range explicitly in the changelog — consumers need to know
   whether they are exposed, not just that a fix exists.
3. **Write a failing test first.** The fix does not start until a test in
   `admob-cmp-core` or `admob-cmp-compose` reproduces the defect and fails.
4. **Fix, then verify.** `./scripts/release-readiness.sh` must reach
   `READINESS: PASS`, including the public-API additivity guard. A hotfix is
   still additive-only within the major.
5. **Certify if native behaviour changed.** Run
   [device-certification.md](device-certification.md).
6. **Bump `VERSION_NAME` in both `gradle.properties` files, in lockstep**, in
   its own commit, as the last commit. That is the only release trigger.
7. **Canary the patch after Central publishes:**
   `./scripts/distribution/verify-published-release.sh <new-version>`
8. **Close the loop.** Note in the changelog which versions are affected and
   which version fixes it.

Never attempt to delete or re-upload a published version.

## Required reproduction information

Ask for all of it up front; a report missing these cannot be triaged:

- `admob-cmp` version, and whether the Gradle plugin is at the same version
- Platform and OS version; physical device or simulator/emulator
- Resolved GMA and UMP versions (Android: `./gradlew :app:dependencies`;
  iOS: the SPM resolved versions)
- Kotlin, Gradle, AGP, Compose Multiplatform, and Xcode versions
- Ad format and whether it reproduces with Google's test ad units
- Consent state when it occurred, and the geography (real or debug)
- SDK logs via `AdLogger`, and the typed `AdError` if one was surfaced
- A minimal `AdPlacement` / `AdConfig` that reproduces it

For iOS build and link failures, also require the output of
`./gradlew :admob-cmp-core:doctorIos`.

## Compatibility regressions

For a report that the SDK no longer builds under a new Kotlin, Gradle, AGP, or
Xcode version, first confirm whether the combination is one the project claims
to support in
`docs-site/src/content/docs/reference/compatibility.mdx`. The project pins a
single blessed toolchain deliberately. If the combination is outside it, the
answer is a documentation clarification, not a patch release — say so plainly
rather than widening the supported range under pressure.

Reproduce with `./scripts/distribution/verify-published-release.sh <version>`,
which builds a clean-room consumer and isolates whether the failure is in
resolution, the Gradle plugin, or the consumer's own configuration.
