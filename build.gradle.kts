plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKmpLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.mavenPublish) apply false
    // Applied (not `apply false`) so the root project owns the aggregating
    // Dokka publication. Subprojects then apply `id("org.jetbrains.dokka")`
    // without a version, resolving it from this build's script classpath.
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kover) apply false
}

// Static analysis, scoped to the PUBLISHED modules. The sample apps and showcase are consumers,
// not shipped code, and folding them in would bury real findings in demo-quality noise.
//
// Deliberately wired into scripts/release-readiness.sh only, never into release.yml: running no SDK
// verification in CI is a standing decision for this repository.
val detektedProjects = setOf("admob-cmp", "admob-cmp-core", "admob-cmp-compose")

subprojects {
    if (name !in detektedProjects) return@subprojects
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        parallel = true
        buildUponDefaultConfig = true
        config.setFrom(rootProject.layout.projectDirectory.file("gradle/detekt.yml"))
        // Per module: the baseline task writes one file per project, so a shared path would have
        // each module overwrite the previous one and silently baseline only the last.
        baseline = layout.projectDirectory.file("detekt-baseline.xml").asFile
        basePath = rootProject.projectDir.absolutePath
        // Detekt defaults to src/main/kotlin, which does not exist in a Kotlin Multiplatform
        // layout -- without this every module reports NO-SOURCE and the gate passes vacuously.
        source.setFrom(layout.projectDirectory.dir("src"))
    }

    // Coverage regression control, per module. Root-level Kover aggregation cannot resolve a
    // Kotlin Multiplatform project that publishes iOS framework variants -- it sees
    // debugFrameworkIosArm64 and friends and cannot choose -- so each module verifies itself and
    // the gate runs both.
    //
    // The threshold is a RATCHET, not a target: it records where coverage stands so a change that
    // meaningfully erodes the suite fails the gate. Raise it when coverage improves; treat any
    // proposal to LOWER it as a discussion, not a mechanical edit.
    if (name != "admob-cmp") {
        apply(plugin = "org.jetbrains.kotlinx.kover")
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            reports {
                filters {
                    excludes {
                        // Debug UI and Compose-generated singletons are exercised visually, not by
                        // unit tests; counting them would depress the number without saying
                        // anything about SDK correctness.
                        classes("dev.avinya.ads.debug.*", "*ComposableSingletons*")
                    }
                }
                verify {
                    rule("Line coverage must not regress") {
                        bound { minValue = coverageFloorFor(name) }
                    }
                }
            }
        }
    }
}

/**
 * Per-module line-coverage floors. See the ratchet note above.
 *
 * Measured at the time of writing: core 68.7%, compose 46.1%. Set a few points below so
 * ordinary churn does not trip the gate while a real erosion of the suite does.
 *
 * Aspiration, not a gate: `admob-cmp-compose` is the layer consumers see directly
 * (`BannerAdView`, `NativeAdView`, the layout DSL renderers), so its coverage matters more
 * than the number alone suggests — target 60% as the platform-specific renderers
 * (`AndroidNativeAdLayoutRenderer`, `IosNativeAdRenderer`) gain tests. Raise the floor itself
 * only after coverage has actually moved; do not raise it ahead of the suite.
 */
fun coverageFloorFor(module: String): Int = when (module) {
    "admob-cmp-core" -> 65
    "admob-cmp-compose" -> 43
    else -> 0
}

// Dokka Gradle plugin v2 aggregates through the `dokka` configuration, not
// through task wiring. These three are the documented public modules.
dependencies {
    dokka(project(":admob-cmp"))
    dokka(project(":admob-cmp-core"))
    dokka(project(":admob-cmp-compose"))
}

dokka {
    moduleName.set("AdMob CMP")
    moduleVersion.set(providers.gradleProperty("VERSION_NAME"))
}

// Astro copies `docs-site/public/` to the site root verbatim, so the aggregated
// Dokka HTML lands at https://ads.avinya.dev/api/. `Sync` (not `Copy`) so a
// removed declaration cannot leave a stale page behind.
tasks.register<Sync>("syncApiDocsToDocsSite") {
    group = "documentation"
    description = "Generates the aggregated Dokka HTML and copies it into docs-site/public/api."
    from(tasks.named("dokkaGeneratePublicationHtml"))
    into(layout.projectDirectory.dir("docs-site/public/api"))
}
