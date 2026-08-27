# AGENTS.md — admob-compose-multiplatform

Compose Multiplatform AdMob SDK + Astro/Starlight docs site. Published to
Maven Central as `dev.avinya.ads:admob-cmp`; docs live at
<https://ads.avinya.dev>.

## Repo map

**Published Gradle projects** (all share a single `VERSION_NAME`):
- `admob-cmp/` — the user-facing facade. Re-exports `admob-cmp-core` and
  `admob-cmp-compose` so consumers depend on one coordinate.
- `admob-cmp-core/` — shared state machines (consent, slots, banner, native
  pool) plus Android (`GMA Next-Gen`) and iOS (`GMA 13.x`) platform
  implementations.
- `admob-cmp-compose/` — the Compose composables (`rememberAdManager`,
  `BannerAdView`, `NativeAdView`, `AppOpenAdCoordinator`).
- `admob-cmp-gradle-plugin/` — **included build** that downloads/links the
  Google Mobile Ads and UMP iOS XCFrameworks so the consumer's iOS test
  executables can resolve their cinterops. Consumers apply it as
  `dev.avinya.ads.admob-cmp` in `pluginManagement`.

**Sample consumers** (do not publish; exist to exercise the SDK end-to-end):
`androidApp/`, `iosApp/`, `shared/`, `desktopApp/`, `webApp/`.

**Docs site:** `docs-site/` (Astro + Starlight, deployed to Cloudflare Pages).

**Authoritative module guides — read these before editing the library:**
- [admob-cmp/AGENTS.md](admob-cmp/AGENTS.md) — entry points, per-format API,
  consent flow, iOS setup, troubleshooting, module internals.
- [admob-cmp/CLAUDE.md](admob-cmp/CLAUDE.md) — hard invariants when editing
  the library (frozen ABI, `FullScreenSlotCore` ownership, `Dispatchers.Main`
  rules, etc.).

Do not restate their content here. If you need to know "how does the SDK
work", go read those files.

---

## Pre-PR protocol (mandatory)

**CI runs no SDK tests.** The single GitHub workflow,
`.github/workflows/release.yml`, runs only on pushes to `master` and on
`workflow_dispatch`, and it only publishes, tags, and deploys. There is no
pull-request CI, and no Gradle verification job anywhere in the pipeline —
not even the Ubuntu-runnable ones. The sole test step in CI is `docs-site`'s
own Vitest suite. **If you do not verify the SDK locally, nobody and nothing
will.**

Before opening a PR, **all four of the following must happen, in order**:

1. Run `./scripts/release-readiness.sh` and get a clean `READINESS: PASS`.
   The script is the only verification that exists in this project (Android
   host tests + publication metadata; the Central task graph; iOS tests +
   klib ABI; Maven Local publish + shared consumer round trip; the Xcode
   consumer build; and the docs section — Dokka + Astro + verify).
   `--skip-docs` is acceptable when the change does not touch
   `admob-cmp/`, `admob-cmp-core/`, `admob-cmp-compose/`,
   `gradle/libs.versions.toml`, `gradle.properties`, or `docs-site/`.
2. **Report the result to the owner and ask for explicit confirmation
   before opening the PR.** Do not open it unilaterally. Include which
   sections ran, which were skipped, and anything that failed and was
   fixed.
3. If you cannot run the script locally (no macOS / no Xcode 26), **say so
   and stop.** There is no remote fallback — no workflow will verify the
   branch for you. Do not open the PR describing it as verified.
4. A `READINESS: PASS` is **not** authorisation to open the PR — it is a
   prerequisite for asking. The owner decides whether to open it.

**Do not add verification jobs to `release.yml`**, including as a gate in
front of `publish`, and including cheap Ubuntu-only ones. Keeping CI free of
SDK tests is a standing decision, not an oversight. If coverage needs to
move, it moves into `scripts/release-readiness.sh`.

---

## Public API changes

The library's public ABI is frozen (see invariant 12 in
[admob-cmp/CLAUDE.md](admob-cmp/CLAUDE.md)). Additive changes are fine;
breaking changes need a written migration plan for every consuming app.

After **any** change to the public surface of `admob-cmp-core` or
`admob-cmp-compose`:

```bash
./gradlew :admob-cmp-core:updateKotlinAbi
./gradlew :admob-cmp-compose:updateKotlinAbi
```

Commit the regenerated `api/*.klib.api` dump in the same commit as the
change. **Nothing in CI will catch a stale dump** — `checkKotlinAbi` runs
only in `scripts/release-readiness.sh`, so a missed `updateKotlinAbi` merges
and publishes silently.

---

## Releasing

Bumping `VERSION_NAME` in **both** `gradle.properties` and
`admob-cmp-gradle-plugin/gradle.properties` (in lockstep) is the *only*
thing that triggers a release. Do it deliberately, in its own commit, as
the last commit of a release-worthy change.

On merge to `master`, `release.yml`:

0. **Waits for approval.** The `publish` job is bound to the protected
   `maven-central` environment, so it does not start until a required
   reviewer approves it in the Actions run. A version bump therefore does
   *not* publish on its own — if nobody approves, the release sits pending
   and nothing is uploaded or tagged. The environment name is the half that
   lives in code; the required reviewers are configured in repository
   settings.
1. Generates the provenance assets (checksum manifest + SPDX SBOM) and
   attests both, **before** anything is uploaded. Nothing there reads the
   deployment, and the upload is the only irreversible step, so a provenance
   failure must not be able to leave a published-but-untagged version behind.
2. Publishes two staging deployments to Maven Central via
   `./admob-cmp/scripts/publish-maven-central.sh` (library + Gradle
   plugin — they are two separate Gradle builds, so two deployments).
   **It runs no tests first.**
3. Creates an annotated tag named exactly `<version>` (no `v` prefix).
4. Cuts a GitHub release with auto-generated notes and a header line
   pointing at the manual Central Portal step.

In parallel, and independently of the release path, it regenerates the Dokka
API reference (`api-reference`, macOS) and rebuilds and deploys the docs site
(`docs-site`, Ubuntu).

**Manual step the pipeline does not do:** both staging deployments must be
released manually in [Central Portal](https://central.sonatype.com/publishing/deployments)
before the artifacts are publicly available. The pipeline's release notes
and the `publish` job's summary both say this. `mavenCentralAutomaticPublishing`
stays `false` — Maven Central coordinates are immutable, so this last human
step is deliberate.

**Never create tags or GitHub releases by hand.** `release.yml` owns them.
A hand-made tag will make the next run early-exit and skip the release
entirely.

A push that changes neither the version nor any docs input
(`docs-site/`, `admob-cmp/`, `admob-cmp-core/`, `admob-cmp-compose/`,
`gradle/libs.versions.toml`, `gradle.properties`, or the workflow file
itself) exits in seconds on the `gate` job. **That is expected, not a
failure** — it is the steady state.

---

## Docs site

`docs-site/public/api/` is generated by `./gradlew syncApiDocsToDocsSite`
and gitignored. **Never commit it.** The same applies to `docs-site/dist/`.

The Astro build itself runs on `ubuntu-latest` in CI, but the Dokka
generation it depends on runs on `macos-26` because Apple's iOS cinterop
needs the Xcode-provided iOS SDK headers, which a Linux runner does not
have. This is why the pipeline splits `api-reference` (macOS) and
`docs-site` (Ubuntu) into two jobs with an artifact handoff.

Local docs work only needs Node — `cd docs-site && npm ci && npm run build &&
npm test && npm run verify` is sufficient to validate Astro changes once
`./gradlew syncApiDocsToDocsSite` has populated `docs-site/public/api/`.
**Build before test, not the other way round** — some Vitest cases assert
against rendered `dist/` output and hard-fail rather than skip when it is
absent. Test-before-build shipped for a while and looked fine locally
because a stale `dist/` from an earlier manual build was still on disk; a
truly clean tree, including CI, fails.

Read `docs-site/DESIGN.md` before changing `tokens.css`, `landing.css` or
`diagrams.css`. It records what the design system is for, which colour
pairings are contrast-enforced, and the traps that have already been hit
once. The gates catch broken mechanics; they cannot tell you the intent.
It is an internal note and is not part of the built site.
