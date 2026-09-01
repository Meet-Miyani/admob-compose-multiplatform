#!/usr/bin/env bash
# Build a throwaway KMP consumer against published admob-cmp artifacts.
#
# Two modes:
#   --local   resolve from Maven Local. Run BEFORE a release, after
#             `publishToMavenLocal`, as a clean-room check that the artifacts a
#             real consumer would download actually resolve and compile.
#   (default) resolve from Maven Central ONLY. Run AFTER a Central release, as
#             a canary.
#
# Why this exists alongside release-readiness.sh section 7:
#   Section 7 round-trips through Maven Local using the monorepo's own :shared
#   module — same settings.gradle.kts, same version catalog, same wrapper, same
#   pluginManagement. It proves the artifact installs. It cannot prove a real
#   consumer can RESOLVE it, because nothing about that consumer is independent.
#   This generates a project that has never seen the source tree.
#
# Maven Central artifacts are immutable. A failure here invokes the patch-release
# playbook in docs/release/hotfix-playbook.md — there is no rollback.
#
# Usage:
#   verify-published-release.sh <version> [--local] [--keep]
#
#   <version>  e.g. 2.1.0
#   --local    use mavenLocal() instead of mavenCentral()
#   --keep     leave the generated fixture in place and print its path
#
# Exit codes:
#   0  the fixture resolved and compiled
#   1  resolution or compilation failed
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION=""
USE_LOCAL=0
KEEP=0

if [ -z "${JAVA_HOME:-}" ]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1 && /usr/libexec/java_home >/dev/null 2>&1; then
    export JAVA_HOME="$(/usr/libexec/java_home)"
  elif [ -d "${HOME}/.gradle/jdks" ]; then
    JDK_CANDIDATE="$(find "${HOME}/.gradle/jdks" -type d -name Home 2>/dev/null | head -1)"
    if [ -n "${JDK_CANDIDATE}" ]; then
      export JAVA_HOME="${JDK_CANDIDATE}"
    fi
  fi
  [ -n "${JAVA_HOME:-}" ] && export PATH="${JAVA_HOME}/bin:${PATH}"
fi

while [ $# -gt 0 ]; do
  case "$1" in
    --local) USE_LOCAL=1; shift ;;
    --keep)  KEEP=1; shift ;;
    -h|--help) sed -n '2,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) VERSION="$1"; shift ;;
  esac
done

if [ -z "${VERSION}" ]; then
  echo "usage: verify-published-release.sh <version> [--local] [--keep]" >&2
  exit 1
fi

if [ "${USE_LOCAL}" -eq 1 ]; then
  PLUGIN_REPOS="mavenLocal()
        google()
        gradlePluginPortal()
        mavenCentral {
            content { excludeGroupByRegex(\"dev\\\\.avinya\\\\.ads.*\") }
        }"
  DEPENDENCY_REPOS="mavenLocal()
        google()
        mavenCentral {
            content { excludeGroupByRegex(\"dev\\\\.avinya\\\\.ads.*\") }
        }"
  MODE="Maven Local"
else
  PLUGIN_REPOS="google()
        gradlePluginPortal()
        mavenCentral()"
  DEPENDENCY_REPOS="google()
        mavenCentral()"
  MODE="Maven Central"
fi

# Toolchain versions come from the repo's catalog so the fixture tracks the
# blessed combination instead of drifting into a version nobody supports.
KOTLIN_VERSION="$(sed -n 's/^kotlin = "\(.*\)"/\1/p' "${ROOT}/gradle/libs.versions.toml" | head -1)"
AGP_VERSION="$(sed -n 's/^agp = "\(.*\)"/\1/p' "${ROOT}/gradle/libs.versions.toml" | head -1)"
COMPILE_SDK="$(sed -n 's/^android-compileSdk = "\(.*\)"/\1/p' "${ROOT}/gradle/libs.versions.toml" | head -1)"
MIN_SDK="$(sed -n 's/^android-minSdk = "\(.*\)"/\1/p' "${ROOT}/gradle/libs.versions.toml" | head -1)"

FIXTURE="$(mktemp -d)/admob-cmp-consumer"
mkdir -p "${FIXTURE}/src/commonMain/kotlin" "${FIXTURE}/src/androidMain/kotlin" \
         "${FIXTURE}/src/commonTest/kotlin"

echo "== Clean-room consumer =="
echo "  version:   ${VERSION}"
echo "  repository ${MODE}"
echo "  kotlin     ${KOTLIN_VERSION}, agp ${AGP_VERSION}, compileSdk ${COMPILE_SDK}"
echo "  fixture    ${FIXTURE}"

cat > "${FIXTURE}/settings.gradle.kts" <<EOF
pluginManagement {
    repositories {
        ${PLUGIN_REPOS}
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        ${DEPENDENCY_REPOS}
    }
}
rootProject.name = "admob-cmp-consumer"
EOF

cat > "${FIXTURE}/build.gradle.kts" <<EOF
plugins {
    kotlin("multiplatform") version "${KOTLIN_VERSION}"
    id("com.android.library") version "${AGP_VERSION}"
    id("dev.avinya.ads.admob-cmp") version "${VERSION}"
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("dev.avinya.ads:admob-cmp:${VERSION}")
        }
        // Required, not optional: without a test source set,
        // linkDebugTestIosSimulatorArm64 has nothing to link, reports NO-SOURCE,
        // and the iOS half of this check silently passes without linking
        // anything. See the .kexe assertion below.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "dev.avinya.ads.cleanroom"
    compileSdk = ${COMPILE_SDK}
    defaultConfig { minSdk = ${MIN_SDK} }
}
EOF

cat > "${FIXTURE}/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx3g
android.useAndroidX=true
android.builtInKotlin=false
android.newDsl=false
kotlin.code.style=official
EOF

# Touch the public surface a real consumer touches, so this is a link check and
# not merely a resolution check.
cat > "${FIXTURE}/src/commonMain/kotlin/CleanRoom.kt" <<'EOF'
import dev.avinya.ads.AdAppIds
import dev.avinya.ads.AdConfig
import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement

// Resolves the facade's public API. If a type moved, was renamed, or a
// transitive dependency is missing from the published POM, this does not
// compile.
//
// Google's public test ad units — never production ones.
internal val cleanRoomPlacement: AdPlacement = AdPlacement(
    id = "clean_room_banner",
    format = AdFormat.Banner,
    androidAdUnitId = "ca-app-pub-3940256099942544/6300978111",
    iosAdUnitId = "ca-app-pub-3940256099942544/2934735716",
)

internal val cleanRoomConfig: AdConfig = AdConfig(
    appIds = AdAppIds(
        android = "ca-app-pub-3940256099942544~3347511713",
        ios = "ca-app-pub-3940256099942544~1458002511",
    ),
)
EOF

# Exists so the iOS link step has something to link. A Kotlin/Native test
# executable links the whole klib graph, including the GMA and UMP cinterop
# klibs, so producing this binary is what proves the Gradle plugin's linker
# flags actually resolve the native frameworks — the undefined
# _OBJC_CLASS_$_GADMobileAds / _OBJC_CLASS_$_UMPConsentInformation failure a
# consumer hits when the plugin is missing or misconfigured.
cat > "${FIXTURE}/src/commonTest/kotlin/CleanRoomTest.kt" <<'EOF'
import kotlin.test.Test
import kotlin.test.assertEquals

class CleanRoomTest {
    @Test
    fun placementResolves() {
        assertEquals("clean_room_banner", cleanRoomPlacement.id)
        assertEquals("ca-app-pub-3940256099942544~3347511713", cleanRoomConfig.appIds.android)
    }
}
EOF

# The fixture uses the repo's wrapper so this tests ARTIFACT resolution, not
# Gradle bootstrapping. sdk.dir is propagated because a temp dir has no
# local.properties and the Android plugin needs one.
cp -R "${ROOT}/gradle/wrapper" "${FIXTURE}/gradle-wrapper-tmp"
mkdir -p "${FIXTURE}/gradle"
mv "${FIXTURE}/gradle-wrapper-tmp" "${FIXTURE}/gradle/wrapper"
cp "${ROOT}/gradlew" "${FIXTURE}/gradlew"
chmod +x "${FIXTURE}/gradlew"
if [ -f "${ROOT}/local.properties" ]; then
  grep '^sdk.dir=' "${ROOT}/local.properties" > "${FIXTURE}/local.properties" 2>/dev/null || true
fi

STATUS=0

echo
echo "== Resolving and compiling Android =="
( cd "${FIXTURE}" && ./gradlew compileDebugKotlinAndroid --no-configuration-cache --no-daemon ) || STATUS=1

if [ "${STATUS}" -eq 0 ] && command -v xcrun >/dev/null 2>&1; then
  echo
  echo "== Linking iOS simulator test executable (proves the plugin wires GMA/UMP) =="
  ( cd "${FIXTURE}" && ./gradlew linkDebugTestIosSimulatorArm64 --no-configuration-cache --no-daemon ) || STATUS=1

  # A green link task is NOT proof the link happened. With no test source set
  # the task reports NO-SOURCE, links nothing, and the build still succeeds —
  # which is exactly how this check passed for its first two releases without
  # ever exercising the native frameworks. Assert the binary exists so the step
  # cannot silently become a no-op again.
  KEXE="${FIXTURE}/build/bin/iosSimulatorArm64/debugTest/test.kexe"
  if [ "${STATUS}" -eq 0 ] && [ ! -f "${KEXE}" ]; then
    echo
    echo "  FAIL: the link task succeeded but produced no test executable." >&2
    echo "        expected: ${KEXE}" >&2
    echo "        The iOS half of this check linked nothing. Confirm the fixture" >&2
    echo "        still has a commonTest source set and a kotlin(\"test\")" >&2
    echo "        dependency; without them the task reports NO-SOURCE and passes" >&2
    echo "        without proving anything." >&2
    STATUS=1
  elif [ "${STATUS}" -eq 0 ]; then
    echo "  linked: ${KEXE#"${FIXTURE}/"}"
  fi
else
  echo
  echo "  skip: no xcrun on this host; iOS link not attempted."
fi

echo
echo "== Resolved coordinates =="
echo "  dev.avinya.ads:admob-cmp:${VERSION}"
echo "  dev.avinya.ads.admob-cmp (Gradle plugin) ${VERSION}"
echo "  resolved from: ${MODE}"

if [ "${KEEP}" -eq 1 ]; then
  echo "  fixture kept at: ${FIXTURE}"
else
  rm -rf "$(dirname "${FIXTURE}")"
fi

echo
if [ "${STATUS}" -eq 0 ]; then
  echo "PUBLISHED RELEASE CHECK: PASS (${VERSION} from ${MODE})"
  exit 0
fi
echo "PUBLISHED RELEASE CHECK: FAIL (${VERSION} from ${MODE})"
echo
echo "If this was the post-Central canary, the artifact is immutable — follow"
echo "docs/release/hotfix-playbook.md. Do not attempt a rollback."
exit 1
