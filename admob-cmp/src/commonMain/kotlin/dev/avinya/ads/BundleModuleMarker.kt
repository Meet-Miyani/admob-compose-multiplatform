package dev.avinya.ads

/**
 * Exists only so `commonMain` is not empty. `admob-cmp` is a pure bundling facade — it re-exports
 * `admob-cmp-core` and `admob-cmp-compose` via `api(project(...))` in `build.gradle.kts`, with no
 * Kotlin declarations of its own, so without this class every source set here is `NO-SOURCE`.
 *
 * That is not harmless for a Kotlin/Native target: with nothing to compile, `compileKotlinIosArm64`
 * / `compileKotlinIosSimulatorArm64` produce no `.klib` file at all — not even an empty one — and
 * `generateMetadataFileForIosArm64Publication` then fails with a `FileNotFoundException` looking
 * for a klib that was never written. Verified by temporarily deleting this file and running
 * `./gradlew :admob-cmp:publishToMavenLocal`.
 *
 * Never referenced; never construct it (the private constructor forbids it, hence
 * `UnusedPrivateClass` in `detekt-baseline.xml`). Do not delete without first confirming the
 * underlying "empty commonMain breaks Kotlin/Native publishing" behavior no longer applies.
 */
private class BundleModuleMarker private constructor()
