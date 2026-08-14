import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // REQUIRED: supplies GoogleMobileAds/UMP XCFrameworks to Kotlin/Native TEST
    // executables. Without it, :showcase:iosSimulatorArm64Test fails at link with
    // "Undefined symbols ... _OBJC_CLASS_$_GADBannerView". An iOS app resolves
    // these from Xcode's SPM packages; a test executable has no Xcode.
    id("dev.avinya.ads.admob-cmp")
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val consumePublishedAdmobCmp =
    providers.gradleProperty("admobCmpConsumePublished")
        .map(String::toBoolean)
        .getOrElse(false)

kotlin {
    applyDefaultHierarchyTemplate()

    iosArm64()
    iosSimulatorArm64()

    android {
        namespace = "dev.avinya.admob.showcase"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.compose.ui)
                implementation(libs.compose.uiToolingPreview)
                implementation(libs.compose.components.resources)
                implementation(libs.androidx.lifecycle.viewmodelCompose)
                implementation(libs.androidx.lifecycle.runtimeCompose)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.androidx.room.runtime)
                implementation(libs.androidx.sqlite.bundled)
                implementation(libs.androidx.datastore.preferences)
                implementation(libs.androidx.navigation3.runtime)
                implementation(libs.androidx.navigation3.ui)
                implementation(libs.androidx.lifecycle.viewmodelNavigation3)
                implementation(libs.androidx.paging.common)
                implementation(libs.androidx.paging.compose)
                implementation(libs.androidx.room.paging)

                if (consumePublishedAdmobCmp) {
                    implementation("dev.avinya.ads:admob-cmp:${providers.gradleProperty("VERSION_NAME").get()}")
                } else {
                    implementation(project(":admob-cmp-compose"))
                }
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        // No androidHostTest dependencies: every test in this module is a pure
        // JVM/Native test over values. Verifying Room would need Robolectric and
        // androidx.test here, which is an unreasonable cost for a module whose
        // job is to demonstrate the ad SDK — see RoomCodegenCanaryTest (iosTest).
        val androidMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Room's KMP compiler runs per target, so the processor is registered per
// KSP configuration rather than once globally.
dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

