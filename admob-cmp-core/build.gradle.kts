import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("dev.avinya.ads.admob-cmp")
    id("org.jetbrains.dokka")
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.mavenPublish)
}

kotlin {
    explicitApi()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation { enabled.set(true) }

    // Silences the recurring "'expect'/'actual' classes ... are in Beta" warning
    // (KT-61573) across every target — this project's expect/actual usage (AdPlatformLogger,
    // FullScreenStateLock) is the ordinary kind the flag is meant to unblock.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "dev.avinya.ads.core"
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

        iosTarget.binaries.all {
            if (this !is org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable) {
                freeCompilerArgs += listOf(
                    "-Xoverride-konan-properties=osVersionMin.ios_simulator_arm64=15.0;osVersionMin.ios_arm64=15.0;osVersionMin=15.0"
                )
            }
        }

        iosTarget.binaries.framework {
            baseName = "AdMobCmp"
            isStatic = true
            freeCompilerArgs += listOf("-Xbinary=bundleId=dev.avinya.ads")
        }

        iosTarget.compilations.getByName("main").cinterops {
            val slice = when (targetName) {
                "iosArm64" -> "ios-arm64"
                "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
                else -> error("Unknown iOS target: $targetName")
            }
            val gma by creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/GoogleMobileAds.def"))
                compilerOpts("-F", layout.buildDirectory.dir("admob-cmp-ios-frameworks/GoogleMobileAds.xcframework/$slice").get().asFile.absolutePath)
            }
            val ump by creating {
                definitionFile.set(project.file("src/nativeInterop/cinterop/UserMessagingPlatform.def"))
                compilerOpts("-F", layout.buildDirectory.dir("admob-cmp-ios-frameworks/UserMessagingPlatform.xcframework/$slice").get().asFile.absolutePath)
            }
        }

        tasks.matching { it.name.startsWith("cinterop") && it.name.contains(targetName.replaceFirstChar { c -> c.uppercase() }) }
            .configureEach { dependsOn("downloadGmaIos", "downloadUmpIos") }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.lifecycle.process)
            implementation(libs.google.ads.mobile.sdk)
            implementation(libs.google.user.messaging.platform)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.google.ads.mobile.sdk)
            implementation(libs.mockito.core)
        }
    }
}

dokka {
    moduleName.set("admob-cmp-core")
    dokkaSourceSets.configureEach {
        // `explicitApi()` is on and the ABI is frozen: document exactly the
        // surface `checkKotlinAbi` guards, nothing wider.
        documentedVisibilities.set(
            setOf(org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier.Public)
        )
        sourceLink {
            localDirectory.set(layout.projectDirectory)
            remoteUrl("https://github.com/Meet-Miyani/admob-compose-multiplatform/blob/master/admob-cmp-core")
            remoteLineSuffix.set("#L")
        }
    }
}

// Pin the publication exactly as it resolves today. Without this, the vanniktech
// plugin's auto-detection sees `org.jetbrains.dokka` on the classpath and swaps
// JavadocJar.Empty() for JavadocJar.Dokka(...), changing every published
// -javadoc.jar and coupling `publishToMavenCentral` to a Dokka run.
// `androidVariantsToPublish = emptyList()` matches what
// `configureBasedOnAppliedPlugins` derives when
// `com.android.kotlin.multiplatform.library` is applied.
mavenPublishing {
    configure(
        com.vanniktech.maven.publish.KotlinMultiplatform(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Empty(),
            sourcesJar = com.vanniktech.maven.publish.SourcesJar.Sources(),
            androidVariantsToPublish = emptyList(),
        )
    )
}

