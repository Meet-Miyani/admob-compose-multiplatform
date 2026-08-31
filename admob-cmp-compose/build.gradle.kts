import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.extraProperties

plugins {
    id("dev.avinya.ads.admob-cmp")
    id("org.jetbrains.dokka")
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }

    // Silences the recurring "'expect'/'actual' classes ... are in Beta" warning
    // (KT-61573) across every target — mirrors admob-cmp-core/build.gradle.kts.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "dev.avinya.ads.compose"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTest { isReturnDefaultValues = true }
        compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        val targetName = iosTarget.name

        // Set minimum iOS deployment target to 15.0 for non-test iOS binaries
        iosTarget.binaries.all {
            if (this !is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
                freeCompilerArgs += listOf(
                    "-Xoverride-konan-properties=osVersionMin.ios_simulator_arm64=15.0;osVersionMin.ios_arm64=15.0;osVersionMin=15.0"
                )
            }
        }

        // Test executables need:
        //  1. osVersionMin ≥ 18.0 to match Xcode 16's iOS 18+ SDK symbols in Compose/Skiko (UIViewLayoutRegion).
        //  2. disableNativeCache = true to prevent Xcode 16 Skiko/Compose UI cache symbol mismatches.
        iosTarget.binaries.withType(org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable::class.java).configureEach {
            freeCompilerArgs += listOf(
                "-Xoverride-konan-properties=osVersionMin.ios_simulator_arm64=18.0;osVersionMin.ios_arm64=18.0;osVersionMin=18.0"
            )
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":admob-cmp-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.google.ads.mobile.sdk)
            implementation(libs.google.user.messaging.platform)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.mockito.core)
        }
    }
}

composeCompiler {
    // See compose_compiler_config.conf for why each entry is a truthful promise, not a shortcut.
    stabilityConfigurationFiles.add(
        layout.projectDirectory.file("compose_compiler_config.conf")
    )

    // Opt-in metrics: -PcomposeReports=true. Off by default so ordinary builds stay fast; this
    // is the objective proof that a change to stability actually improved skippability — read
    // build/compose-reports/*-composables.txt after building with the flag.
    if (providers.gradleProperty("composeReports").isPresent) {
        metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
    }
}

publishing {
    publications.named<MavenPublication>("kotlinMultiplatform") {
        pom.withXml(PromotePomDependenciesToCompileScope("dev.avinya.ads", setOf("admob-cmp-core")))
    }
}

val verifyKotlinMultiplatformPomDependencyScopes = tasks.register<VerifyPomDependencyScopes>("verifyKotlinMultiplatformPomDependencyScopes") {
    group = "verification"
    description = "Verifies API dependencies retain compile scope in the root multiplatform POM."
    dependsOn("generatePomFileForKotlinMultiplatformPublication")

    pomFile.set(layout.buildDirectory.file("publications/kotlinMultiplatform/pom-default.xml"))
    groupId.set("dev.avinya.ads")
    expectedArtifactIds.set(setOf("admob-cmp-core"))
}

tasks.named("check") {
    dependsOn(verifyKotlinMultiplatformPomDependencyScopes)
}

dokka {
    moduleName.set("admob-cmp-compose")
    dokkaSourceSets.configureEach {
        documentedVisibilities.set(
            setOf(org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Public)
        )
        sourceLink {
            localDirectory.set(layout.projectDirectory)
            remoteUrl("https://github.com/Meet-Miyani/admob-compose-multiplatform/blob/master/admob-cmp-compose")
            remoteLineSuffix.set("#L")
        }
    }
}

// See admob-cmp-core/build.gradle.kts for why this is pinned explicitly.
mavenPublishing {
    configure(
        com.vanniktech.maven.publish.KotlinMultiplatform(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
            sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
            androidVariantsToPublish = emptyList(),
        )
    )
}
