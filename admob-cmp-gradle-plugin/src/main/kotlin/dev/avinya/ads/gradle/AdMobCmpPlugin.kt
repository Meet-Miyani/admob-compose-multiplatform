package dev.avinya.ads.gradle

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget

private const val GMA_DOWNLOAD_BASE = "https://dl.google.com/googleadmobadssdk"

/**
 * Supplies the GoogleMobileAds/UMP frameworks that a consumer's Kotlin/Native **test**
 * executables must link against.
 *
 * admob-cmp ships cinterop bindings only — never Google's binaries. An iOS app resolves
 * `GAD*`/`UMP*` at final link from the Swift packages Xcode links. A Kotlin/Native test
 * executable has no Xcode, so it must resolve them itself; without this plugin the link
 * fails with `Undefined symbols ... _OBJC_CLASS_$_GADBannerView`.
 *
 * The shipped app framework is deliberately left alone.
 */
public abstract class AdMobCmpPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            val frameworksDir = target.layout.buildDirectory.dir("admob-cmp-ios-frameworks")
            // Read at CONFIGURATION time. A task may not touch Project (and therefore
            // `project.gradle.startParameter`) at execution time under the configuration cache.
            val offlineMode = target.gradle.startParameter.isOffline

            val downloadGma = target.tasks.register("downloadGmaIos", DownloadIosFramework::class.java) {
                description = "Download the Google Mobile Ads iOS XCFramework (test linking only)"
                group = "ios-setup"
                baseUrl.set(
                    target.providers.gradleProperty("admobCmp.ios.baseUrl").orElse(GMA_DOWNLOAD_BASE)
                )
                version.set(
                    target.providers.gradleProperty("admobCmp.gma.ios.version").orElse(AdMobCmpNativeDeps.gmaIosVersion)
                )
                expectedSha256.set(
                    target.providers.gradleProperty("admobCmp.gma.ios.sha256").orElse(AdMobCmpNativeDeps.gmaIosSha256)
                )
                // frameworkDir is the tracked output; the marker is derived from it so the two
                // can never drift apart.
                offline.set(offlineMode)
                frameworkDir.set(frameworksDir.map { d -> d.dir("GoogleMobileAds.xcframework") })
                markerFile.set(frameworkDir.file(".gma_downloaded"))
            }
            val downloadUmp = target.tasks.register("downloadUmpIos", DownloadIosFramework::class.java) {
                description = "Download the User Messaging Platform iOS XCFramework (test linking only)"
                group = "ios-setup"
                baseUrl.set(
                    target.providers.gradleProperty("admobCmp.ios.baseUrl").orElse(GMA_DOWNLOAD_BASE)
                )
                version.set(
                    target.providers.gradleProperty("admobCmp.ump.ios.version").orElse(AdMobCmpNativeDeps.gmaUmpIosVersion)
                )
                expectedSha256.set(
                    target.providers.gradleProperty("admobCmp.ump.ios.sha256").orElse(AdMobCmpNativeDeps.umpIosSha256)
                )
                offline.set(offlineMode)
                frameworkDir.set(frameworksDir.map { d -> d.dir("UserMessagingPlatform.xcframework") })
                markerFile.set(frameworkDir.file(".ump_downloaded"))
            }

            val kotlin = target.extensions.getByType(KotlinMultiplatformExtension::class.java)
            kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
                // Classify by the target's ARCHITECTURE, not by its name. A target's name is
                // the consumer's to choose — `iosSimulatorArm64("simulator")` is legal — and
                // matching on the conventional name silently skipped such a target, so its
                // test link reached the linker with no framework paths and failed with
                // undefined GAD*/UMP* symbols and nothing pointing back here.
                val slice = frameworkSliceFor(konanTarget)
                if (slice == null) {
                    if (konanTarget.family == Family.IOS) {
                        target.logger.warn(
                            "admob-cmp: iOS target '$name' (${konanTarget.name}) is not one of the " +
                                "architectures this plugin supplies frameworks for " +
                                "(iOS arm64 device and arm64 simulator), so its Kotlin/Native test " +
                                "executables will not link against GoogleMobileAds/UserMessagingPlatform."
                        )
                    }
                    return@configureEach
                }

                // Test executables perform a real link with no Xcode involved, so they must
                // resolve GAD*/UMP* themselves. Frameworks are NOT touched: Kotlin/Native
                // leaves those symbols undefined there on purpose, for Xcode to bind via SPM.
                binaries.withType(TestExecutable::class.java).configureEach {
                    linkerOpts(testLinkerOpts(target, slice, frameworksDir.get().asFile))
                    // Attach the downloads to THIS binary's own link task rather than
                    // rediscovering link tasks by substring, which had the same
                    // custom-name blind spot as the target matching above.
                    linkTaskProvider.configure { dependsOn(downloadGma, downloadUmp) }
                }
            }

            target.tasks.register("doctorIos", DoctorIosTask::class.java) {
                description = "Diagnose iOS integration (SPM products, Info.plist, framework cache). Report-only."
                group = "ios-setup"
                this.frameworksDir.set(frameworksDir.get().asFile.absolutePath)
                gmaVersion.set(AdMobCmpNativeDeps.gmaIosVersion)
                xcodeprojPath.set(
                    target.providers.gradleProperty("admobCmp.xcodeproj").orElse("iosApp")
                )
                rootDirPath.set(target.rootDir.absolutePath)
            }
        }
    }
}

/**
 * Linker options a Kotlin/Native test link needs to resolve the GoogleMobileAds/UMP symbols
 * admob-cmp's cinterop klibs reference.
 *
 * Returns empty off macOS so Linux CI can still configure the build.
 */
private fun testLinkerOpts(project: Project, slice: IosFrameworkSlice, frameworksDir: File): List<String> {
    // Host check via a tracked provider; never use org.gradle.internal.os.OperatingSystem here —
    // internal APIs break across the consumer's Gradle version, and this plugin runs on arbitrary
    // wrappers. isMacOsHost covers the same aliases that class does, because dropping the linker
    // options on a Mac surfaces only as an undefined-symbol link failure much later.
    val osName = project.providers.systemProperty("os.name").getOrElse("")
    if (!isMacOsHost(osName)) return emptyList()
    val swiftPlatform = slice.swiftPlatform
    val developerDir = project.providers.exec {
        commandLine("xcode-select", "-p")
    }.standardOutput.asText.get().trim()
    val swiftCompatLibDir =
        "$developerDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/$swiftPlatform"
    return listOf(
        "-F" + frameworkDir(frameworksDir, "GoogleMobileAds", slice).parentFile.absolutePath,
        "-F" + frameworkDir(frameworksDir, "UserMessagingPlatform", slice).parentFile.absolutePath,
        "-framework", "GoogleMobileAds",
        "-framework", "UserMessagingPlatform",
        // GoogleMobileAds force-loads JavaScriptCore (GADOMIDJSContextPool) and the Swift
        // runtime-compat shims; without these the link reports undefined
        // _OBJC_CLASS_$_JSContext / __swift_FORCE_LOAD_$_swiftCompatibility56.
        "-framework", "JavaScriptCore",
        "-L$swiftCompatLibDir",
    )
}

/**
 * Whether [osName] (a raw `os.name`) identifies a macOS host.
 *
 * Covers the same aliases as Gradle's own `OperatingSystem.forName` — `darwin` and `osx` as well
 * as the `Mac OS X` string the JVM still reports — plus a modernised `macOS`. Matching that set
 * matters: this is a fail-silent check, and a name it fails to recognise drops the GoogleMobileAds
 * linker options on a machine that needed them.
 */
internal fun isMacOsHost(osName: String): Boolean {
    val name = osName.lowercase()
    return name.startsWith("mac") || name.contains("darwin") || name.contains("osx")
}

private fun frameworkDir(baseDir: File, baseName: String, slice: IosFrameworkSlice): File =
    File(File(baseDir, "$baseName.xcframework"), "${slice.directory}/$baseName.framework")

/**
 * The XCFramework slice and Swift runtime directory for one supported iOS architecture.
 *
 * Keyed off [KonanTarget] rather than a Gradle target name, which the consumer chooses.
 */
internal enum class IosFrameworkSlice(val directory: String, val swiftPlatform: String) {
    Device("ios-arm64", "iphoneos"),
    SimulatorArm64("ios-arm64_x86_64-simulator", "iphonesimulator"),
}

/** Null for any architecture this plugin does not supply a framework slice for. */
internal fun frameworkSliceFor(konanTarget: KonanTarget): IosFrameworkSlice? = when (konanTarget) {
    KonanTarget.IOS_ARM64 -> IosFrameworkSlice.Device
    KonanTarget.IOS_SIMULATOR_ARM64 -> IosFrameworkSlice.SimulatorArm64
    // IOS_X64 (Intel simulator) is deliberately absent: the arm64 simulator slice is a fat
    // ios-arm64_x86_64 binary, but this plugin has never claimed Intel-simulator support and
    // adding it silently here would ship an untested path.
    else -> null
}
