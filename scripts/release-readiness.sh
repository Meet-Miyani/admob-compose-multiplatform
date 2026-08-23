#!/usr/bin/env bash
# scripts/release-readiness.sh
#
# Local pre-PR gate for the admob-cmp repository.
#
# `.github/workflows/release.yml` runs NO SDK tests, ABI checks, or Gradle
# verification — it only publishes, tags, and deploys. This script is therefore
# the ONLY verification that exists anywhere in the project. Every command below
# was previously a CI step; if a section is skipped, that coverage is simply
# gone.
#
# Requires macOS — the iOS half needs Xcode. There is no remote fallback: on any
# other host this exits 1 and the run must be done on a Mac.
#
# Flags:
#   --skip-docs  Skip the Node/Astro section (Dokka generation, Astro build).
#   --help       Print this message and exit 0.
#
# Exit codes:
#   0  every section passed (`READINESS: PASS`).
#   1  the first failing section (named in the summary).
#
# Side effects:
#   - Writes to `~/.m2` (Section 6 publishes to Maven Local).
#   - Writes to `docs-site/public/api` and `docs-site/dist/` (Section 8).
#   Both are gitignored; the script never `git add`s anything.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

print_help() {
  cat <<'EOF'
scripts/release-readiness.sh — local pre-PR gate for the admob-cmp repository.

release.yml runs no SDK tests, ABI checks, or Gradle verification — it only
publishes, tags, and deploys. This script is the ONLY verification in the
project, and it is a hard prerequisite for opening a PR.

Requires macOS (the iOS half needs Xcode). There is no remote fallback; on
Linux/Windows this exits 1 and the run must be done on a Mac.

Usage:
  scripts/release-readiness.sh [--skip-docs] [--help]

Flags:
  --skip-docs  Skip the Node/Astro section (Dokka generation, Astro build).
  --help       Print this message and exit 0.

Exit codes:
  0  every section passed (READINESS: PASS).
  1  the first failing section is named in the summary.
EOF
}

SKIP_DOCS=false
for arg in "$@"; do
  case "$arg" in
    --skip-docs) SKIP_DOCS=true ;;
    --help|-h)
      print_help
      exit 0
      ;;
    *)
      echo "Unknown flag: $arg" >&2
      echo "Run with --help for usage." >&2
      exit 1
      ;;
  esac
done

if [ "$(uname -s)" != "Darwin" ]; then
  echo "release-readiness.sh requires macOS (the iOS half needs Xcode)." >&2
  echo "There is no remote fallback: release.yml runs no verification jobs." >&2
  echo "This check must be run on a Mac with Xcode 26 before opening a PR." >&2
  exit 1
fi

CURRENT_SECTION=""
FAILING_SECTION=""

section() {
  CURRENT_SECTION="$1"
  echo
  echo "=================================================================="
  echo "  $1"
  echo "=================================================================="
}

finish() {
  exit_code=$?
  echo
  echo "=================================================================="
  if [ "$exit_code" -eq 0 ]; then
    echo "  READINESS: PASS"
  else
    if [ -n "$FAILING_SECTION" ]; then
      echo "  READINESS: FAIL (first failing section: $FAILING_SECTION)"
    elif [ -n "$CURRENT_SECTION" ]; then
      echo "  READINESS: FAIL (first failing section: $CURRENT_SECTION)"
    else
      echo "  READINESS: FAIL"
    fi
  fi
  echo "=================================================================="
  exit "$exit_code"
}

trap finish EXIT

# --- Sections ---

section "1. Version lockstep"
lib="$(sed -n 's/^VERSION_NAME=//p' gradle.properties)"
plugin="$(sed -n 's/^VERSION_NAME=//p' admob-cmp-gradle-plugin/gradle.properties)"
[ -n "$lib" ] || { echo "VERSION_NAME missing from gradle.properties" >&2; FAILING_SECTION="1. Version lockstep"; exit 1; }
if [ "$lib" != "$plugin" ]; then
  echo "Version mismatch: library is $lib but plugin is $plugin." >&2
  echo "Bump both gradle.properties files in lockstep." >&2
  FAILING_SECTION="1. Version lockstep"
  exit 1
fi
echo "Library and plugin both at $lib."

section "2. Gradle plugin build + supply-chain policy"
# `build` runs the plugin's own test suite, which covers the iOS framework downloader's
# integrity, recovery and resource bounds.
./gradlew -p admob-cmp-gradle-plugin build --no-configuration-cache
./scripts/distribution/verify-workflow-policy.sh
# Proves a distributable bundle can be produced without credentials, browser state or build
# detritus. Built into a temporary path and discarded; this gate only asserts it is possible.
./scripts/distribution/make-source-bundle.sh HEAD "$(mktemp -d)/source-bundle.zip" >/dev/null

section "3. Android + ABI + publication metadata"
./scripts/distribution/verify-pom-metadata.sh
./gradlew \
  :admob-cmp-core:testAndroidHostTest \
  :admob-cmp-compose:testAndroidHostTest \
  :admob-cmp:verifyKotlinMultiplatformPomDependencyScopes \
  :admob-cmp-compose:verifyKotlinMultiplatformPomDependencyScopes \
  :showcase:testAndroidHostTest \
  :androidApp:assembleDebug \
  --no-configuration-cache

section "4. Central task graph"
./gradlew publishToMavenCentral --dry-run --no-configuration-cache

section "5. iOS + klib ABI"
./gradlew \
  :admob-cmp-core:iosSimulatorArm64Test \
  :admob-cmp-compose:iosSimulatorArm64Test \
  :showcase:iosSimulatorArm64Test \
  :admob-cmp-core:checkKotlinAbi \
  :admob-cmp-compose:checkKotlinAbi \
  --no-configuration-cache

# Section 6 mutates ~/.m2 — it runs publishToMavenLocal for both builds, then
# the shared module round-trips against the published facade. The two halves
# are kept in the same section (matching the plan's numbering) so that a
# consumer-round-trip failure points at "the published artifact is wrong",
# not at a separate step.
section "6. Published-consumer round trip"
./gradlew publishToMavenLocal -PsignAllPublications=false --no-configuration-cache
./gradlew -p admob-cmp-gradle-plugin publishToMavenLocal -PsignAllPublications=false --no-configuration-cache
./gradlew \
  :shared:compileAndroidMain \
  :shared:iosSimulatorArm64Test \
  -PadmobCmpConsumePublished=true \
  --refresh-dependencies \
  --no-configuration-cache

section "7. Xcode consumer"
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -destination "generic/platform=iOS Simulator" \
  CODE_SIGNING_ALLOWED=NO \
  build

if [ "$SKIP_DOCS" = "false" ]; then
  # Section 8 regenerates docs-site/public/api (gitignored) and
  # docs-site/dist/ (gitignored). Neither should ever be committed.
  section "8. Docs (Dokka + Astro + visual checks + verify)"
  ./gradlew syncApiDocsToDocsSite --no-configuration-cache
  if [ ! -f docs-site/public/api/index.html ]; then
    echo "docs-site/public/api/index.html was not produced by syncApiDocsToDocsSite." >&2
    FAILING_SECTION="8. Docs (Dokka + Astro + visual checks + verify)"
    exit 1
  fi
  (
    cd docs-site
    npm ci
    # `--with-deps` is Linux-only and fails on macOS — it is only used by the
    # ubuntu-latest `docs-site` job in release.yml. On macOS, plain
    # `playwright install chromium` is correct.
    npx playwright install chromium
    # Build before test: some Vitest cases assert against dist/ output (the
    # rendered <h1>, format/roadmap order, the diagram gallery) and hard-fail
    # rather than skip when dist/ is absent. Test-before-build looked clean
    # locally only because a stale dist/ from an earlier manual build was
    # still on disk — a truly clean tree fails. See AGENTS.md.
    npm run build
    npm test
    npm run check:theme
    npm run check:overflow
    npm run verify
  )
else
  section "8. Docs (skipped via --skip-docs)"
fi
