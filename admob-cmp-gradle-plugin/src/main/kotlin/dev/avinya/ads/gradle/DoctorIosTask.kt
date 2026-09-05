package dev.avinya.ads.gradle

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Diagnostic task that inspects workspace state on each run")
public abstract class DoctorIosTask : DefaultTask() {
    @get:Input
    public abstract val xcodeprojPath: Property<String>

    @get:Input
    public abstract val frameworksDir: Property<String>

    @get:Input
    public abstract val rootDirPath: Property<String>

    @get:Input
    public abstract val gmaVersion: Property<String>

    @TaskAction
    public fun doctor() {
        // Report-only: the #1 integration failure of the bindings-only model is a
        // forgotten SPM link, surfacing as "Undefined symbol: _OBJC_CLASS_$_GADMobileAds"
        // at app link time. This task diagnoses, it never fails the build.
        val ok = "\u2705"
        val bad = "\u274C"
        val skip = "\u26A0\uFE0F"

        // 1. Binding inputs: the downloaded GMA/UMP XCFrameworks.
        val fwDir = File(frameworksDir.get())
        for (name in listOf("GoogleMobileAds", "UserMessagingPlatform")) {
            // Every required slice, not just ios-arm64. A cache holding the device slice but
            // missing the simulator one links fine for a device build and fails only when a
            // Kotlin/Native simulator test link runs — exactly the confusing failure this task
            // exists to pre-empt.
            val missing = REQUIRED_SLICES.filterNot { slice ->
                File(fwDir, "$name.xcframework/$slice").isDirectory
            }
            when {
                !File(fwDir, "$name.xcframework").isDirectory ->
                    logger.lifecycle("$bad $name.xcframework cache missing — run ./gradlew downloadGmaIos downloadUmpIos")
                missing.isEmpty() ->
                    logger.lifecycle("$ok $name.xcframework download cache present")
                else ->
                    logger.lifecycle(
                        "$bad $name.xcframework cache incomplete, missing ${missing.joinToString()} " +
                            "— run ./gradlew downloadGmaIos downloadUmpIos"
                    )
            }
        }

        // 2. Consumer Xcode project links the SPM products.
        val projDir = File(rootDirPath.get(), xcodeprojPath.get())
        val pbxproj = projDir.walkTopDown().maxDepth(2)
            .firstOrNull { it.name == "project.pbxproj" }
        if (pbxproj == null) {
            logger.lifecycle("$skip skipped SPM check: no project.pbxproj under $projDir (override with -PadmobCmp.xcodeproj=<dir>)")
        } else {
            val content = pbxproj.readText()
            val packages = mapOf(
                "GoogleMobileAds" to "https://github.com/googleads/swift-package-manager-google-mobile-ads.git (from: ${gmaVersion.get()})",
                "GoogleUserMessagingPlatform" to "https://github.com/googleads/swift-package-manager-google-user-messaging-platform.git"
            )
            packages.forEach { (product, url) ->
                if (content.contains(product)) {
                    logger.lifecycle("$ok Xcode project links SPM product '$product'")
                } else {
                    logger.lifecycle("$bad SPM product '$product' not referenced in ${pbxproj.parentFile.name} — add the package: $url")
                    logger.lifecycle("   Without it the app fails to link with: Undefined symbol: _OBJC_CLASS_\$_GADMobileAds")
                }
            }
        }

        // 3. Info.plist requirements.
        val plist = projDir.parentFile?.let { base ->
            base.walkTopDown().maxDepth(3)
                .firstOrNull { it.name == "Info.plist" && !it.path.contains("Tests") }
        }
        if (plist == null) {
            logger.lifecycle("$skip skipped Info.plist check: none found near $projDir")
        } else {
            val content = plist.readText()
            // The declared VALUE is read, not just the key: a key present with an empty string is
            // the same configuration gap as an absent one.
            val declaredAppId = declaredAppIdInPlist(content)
            when {
                declaredAppId == null ->
                    logger.lifecycle("$bad Info.plist is missing GADApplicationIdentifier — GMA crashes at startup without it")
                declaredAppId.isEmpty() -> {
                    logger.lifecycle("$bad Info.plist declares GADApplicationIdentifier with an empty value")
                    logger.lifecycle("   GMA crashes at startup on it, and AdMob CMP's preflight fails initialize() with APP_ID_INVALID under the default FailWhenUnusable policy")
                }
                else -> {
                    logger.lifecycle("$ok Info.plist declares GADApplicationIdentifier")
                    if (declaredAppId.startsWith("ca-app-pub-3940256099942544~")) {
                        logger.lifecycle("$skip   ...but it is still the Google sample app id — replace before release")
                    }
                }
            }
            // Stripped for the same reason the app-ID read is: a commented-out block is not
            // configuration, and reporting it as present sends an integrator looking elsewhere.
            if (stripXmlComments(content).contains("SKAdNetworkItems")) {
                logger.lifecycle("$ok Info.plist declares SKAdNetworkItems")
            } else {
                logger.lifecycle("$skip Info.plist has no SKAdNetworkItems — attribution will suffer; copy the list from the AdMob iOS docs")
            }
        }

        logger.lifecycle("doctorIos is diagnostic only; it never fails the build.")
    }
}

private val GAD_APPLICATION_IDENTIFIER = Regex(
    "<key>\\s*GADApplicationIdentifier\\s*</key>\\s*<string>(.*?)</string>",
    RegexOption.DOT_MATCHES_ALL,
)

private val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)

/**
 * Drops XML comments so a commented-out declaration is never read as configuration.
 *
 * Matching the key AND its value is not sufficient on its own: a fully commented-out
 * `<key>`/`<string>` pair still matches that shape, which is how `doctorIos` reported an
 * `Info.plist` with no active `GADApplicationIdentifier` as correctly configured.
 */
private fun stripXmlComments(content: String): String = XML_COMMENT.replace(content, "")

/**
 * The active `GADApplicationIdentifier` value, or null when none is declared outside a comment.
 *
 * Returns the empty string when the key is declared with an empty value — a distinct finding
 * from an absent key, since GMA crashes on both but the fix differs.
 */
internal fun declaredAppIdInPlist(content: String): String? = GAD_APPLICATION_IDENTIFIER
    .find(stripXmlComments(content))
    ?.groupValues
    ?.get(1)
    ?.trim()
