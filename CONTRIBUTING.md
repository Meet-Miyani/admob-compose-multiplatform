# Contributing to AdMob CMP

Issues and pull requests are welcome. The narrative version of this guide —
with the reasoning behind each rule — lives at
[ads.avinya.dev/project/contributing/](https://ads.avinya.dev/project/contributing/).
This file is the quick reference.

## Building and testing

```bash
./gradlew :admob-cmp-core:iosSimulatorArm64Test       # common tests, iOS runner
./gradlew :admob-cmp-core:testAndroidHostTest         # common tests, JVM runner
./gradlew :admob-cmp-core:checkKotlinAbi :admob-cmp-compose:checkKotlinAbi # public API surface check
./gradlew :admob-cmp-core:updateKotlinAbi :admob-cmp-compose:updateKotlinAbi # regenerate api/ after an intentional API change
./gradlew :admob-cmp-core:doctorIos                   # diagnose iOS consumer integration
```

Tests live in `commonTest` only, using hand-written fakes rather than a
mocking framework, with injectable `clock` and `foregroundEvents` seams. The
suite is a contract test for the library's common code — native behavior
that calls into Google Mobile Ads is not exercised by it.

## The public ABI is frozen

`admob-cmp-core/api/admob-cmp-core.klib.api` and
`admob-cmp-compose/api/admob-cmp-compose.klib.api` are committed records of
the public surface. Any intentional API change must regenerate and commit
them in the same PR:

```bash
./gradlew :admob-cmp-core:updateKotlinAbi :admob-cmp-compose:updateKotlinAbi
git add admob-cmp-core/api/ admob-cmp-compose/api/
```

If `checkKotlinAbi` fails and the change was **not** intentional, fix the
code, not the dump — the dump records the agreed public surface. See the
[architecture reference](https://ads.avinya.dev/reference/architecture/) for
the full rationale, including why `AdManager` is never constructed directly
by consumers.

## Before you open a pull request

This repository runs **no SDK tests in CI**, by design. The single
[`.github/workflows/release.yml`](.github/workflows/release.yml) workflow
runs only on `master` and on `workflow_dispatch`, and it only publishes,
tags, and deploys — there is no pull-request CI and no verification job in
the pipeline. Verification is local and is the contributor's responsibility:

1. Run `./scripts/release-readiness.sh` on macOS with **Xcode 26** installed.
   It runs the Android host tests, publication-metadata and Central
   task-graph checks, the iOS tests and klib ABI check, the Maven Local
   round trip, the Xcode consumer build, and the docs build. It exits with
   `READINESS: PASS` on success. Use `--skip-docs` for changes that do not
   touch the published modules, `gradle/libs.versions.toml`,
   `gradle.properties`, or `docs-site/`.
2. There is no remote fallback. If you cannot run the script (no macOS, no
   Xcode 26), say so in the PR rather than describing it as verified.
3. A clean `READINESS: PASS` is a prerequisite for *asking* the maintainer
   to open or merge the PR — it is not authorization to do so unilaterally.
   The maintainer decides.

**Do not propose adding verification jobs to `release.yml`.** Keeping CI
free of SDK tests is a standing, reaffirmed decision. If coverage needs to
move, it moves into `scripts/release-readiness.sh`.

## Pull request checklist

- Has a test that fails before the change and passes after it.
- Runs both test tasks and `checkKotlinAbi` locally, and passes
  `./scripts/release-readiness.sh`.
- Ships its documentation in the same pull request — docs live at
  `docs-site/src/content/docs/`, so an API change and its guide update
  version together.
- Updates [the changelog](https://ads.avinya.dev/reference/changelog/) when
  it changes behavior a consumer can observe.
- Keeps the trademark and neutrality rules intact: nominative use of
  "AdMob" only, and no comparative claims about other projects beyond
  verifiable capability facts.

## Core repository invariants

Stated so a contribution does not propose changing them in good faith:

- The published Maven coordinate stays `dev.avinya.ads:admob-cmp`.
- The public ABI stays frozen absent a deliberate major version.
- The iOS distribution stays bindings-only — never add `staticLibraries` to
  the cinterop `.def` files.
- `AdManager` implementations are never constructed directly by consumers.

## Releasing

Releases are cut by bumping `VERSION_NAME` in lockstep across both
`gradle.properties` files — this is a maintainer decision, not something a
contributing PR should do. The full procedure is in
[`admob-cmp/docs/PUBLISHING.md`](admob-cmp/docs/PUBLISHING.md).
