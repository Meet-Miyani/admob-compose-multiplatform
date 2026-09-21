package dev.avinya.ads.gradle

import java.io.File
import java.net.URI
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Downloads external binary archive")
public abstract class DownloadIosFramework : DefaultTask() {
    @get:Input
    public abstract val version: Property<String>

    @get:Input
    public abstract val baseUrl: Property<String>

    // Supply-chain integrity: neither archive was checksummed, so the same commit could build
    // against different headers. Fail closed. For UMP this value is load-bearing twice over —
    // it also selects the content-addressed download directory, so a wrong digest cannot even
    // reach a mismatched artifact. See archiveName().
    @get:Input
    public abstract val expectedSha256: Property<String>

    /**
     * The extracted `<name>.xcframework` directory — the task's real product, and now its *only*
     * product.
     *
     * Extraction used to write straight into the shared frameworks parent, so the GMA archive also
     * deposited `Licenses/` and a placeholder Swift source beside the framework: undeclared outputs,
     * in a directory the sibling download task also writes to. Nothing in the build consumes them
     * (the linker options reference only the `.xcframework` directories, and `doctorIos` only probes
     * slices), and the tree lives under `build/`, so it is never distributed. Materialising only the
     * framework is what makes this declaration complete enough for Gradle's own up-to-date checking
     * to be trusted — see [download].
     */
    @get:OutputDirectory
    public abstract val frameworkDir: DirectoryProperty

    /**
     * Records the catalog version the tree was extracted from.
     *
     * Lives inside [frameworkDir] and is therefore already covered by that output; `@Internal` keeps
     * it from being declared twice. Diagnostics only — it is deliberately no longer consulted to
     * decide whether work is needed.
     */
    @get:Internal
    public abstract val markerFile: RegularFileProperty

    /**
     * Gradle's `--offline` flag, captured at configuration time.
     *
     * `@Internal`, not `@Input`: switching offline on or off must not invalidate an intact
     * framework and force a re-download. It is a property rather than a read of
     * `project.gradle.startParameter` in the action, because a task may not touch `Project`
     * at execution time under the configuration cache.
     */
    @get:Internal
    public abstract val offline: Property<Boolean>

    /** Overridden only by this plugin's own tests, to drive the bounds to small values. */
    @get:Internal
    internal open val limits: ArchiveLimits get() = ArchiveLimits.DEFAULT

    @TaskAction
    public fun download() {
        val fwDir = frameworkDir.get().asFile
        val baseName = fwDir.name

        // No manual short-circuit. Gradle runs this action only when an @Input changed
        // (version / baseUrl / expectedSha256) or the @OutputDirectory tree no longer matches its
        // fingerprint. Both mean "re-materialise", and the old guard actively defeated that: it
        // returned early whenever the marker named the current version and ios-arm64 existed, so a
        // deleted simulator slice, a truncated binary, or a changed baseUrl or checksum at an
        // unchanged version all left the damage in place while the task reported success. The
        // failure then surfaced much later, in cinterop or the native link.
        val expectedSha = expectedSha256.get()
        val zipUrl = URI("${baseUrl.get()}/${archiveName(baseName, version.get(), expectedSha)}").toURL()

        // Reaching this line means Gradle decided the output needs materialising, so the
        // framework is missing or damaged and only the network can supply it. Under --offline
        // the build has asked for no network access, so say what is missing and how to get it
        // rather than opening a connection and letting it time out — a `URLConnection` does
        // not implement Gradle's offline policy on its own. An intact framework never gets
        // here: up-to-date checking skips the action entirely, which is the common case.
        // A file: mirror is still honoured, since that is not network access.
        if (offline.getOrElse(false) && zipUrl.protocol.startsWith("http")) {
            throw GradleException(
                "Cannot download the $baseName ${version.get()} iOS framework in --offline mode, " +
                    "and no usable copy is cached at ${fwDir.absolutePath}.\n" +
                    "Run this task once with network access, or point " +
                    "-PadmobCmp.ios.baseUrl at a local mirror containing " +
                    "${archiveName(baseName, version.get(), expectedSha)}."
            )
        }
        logger.lifecycle("Downloading from $zipUrl...")

        // Everything happens inside the task's own temporary directory, so a failure at any point
        // leaves the previous framework byte-for-byte intact. The old code deleted it first, which
        // turned any transient network error, checksum mismatch, or malformed archive into a broken
        // local build that only a manual clean could recover.
        val workDir = File(temporaryDir, "staging")
        val archive = File(temporaryDir, "download/archive.zip")
        try {
            workDir.deleteRecursively()
            archive.parentFile.mkdirs()
            archive.delete()

            val actualSha = downloadVerifying(zipUrl, archive, limits)
            if (actualSha != expectedSha) {
                throw GradleException(
                    "$baseName iOS header archive checksum mismatch.\n" +
                        "  expected: $expectedSha\n  actual:   $actualSha\n" +
                        "  url:      $zipUrl\n" +
                        "Refusing to generate bindings from an unverified archive. If Google has " +
                        "republished this artifact, pin the new digest with " +
                        "-PadmobCmp.gma.ios.sha256 / -PadmobCmp.ump.ios.sha256, or point " +
                        "-PadmobCmp.ios.baseUrl at a mirror of the expected bytes."
                )
            }

            workDir.mkdirs()
            // baseName is the framework directory name, which is how extractArchive tells a
            // version-wrapper archive from one with the .xcframework at its root.
            extractArchive(archive, workDir, zipUrl.toString(), limits, expectedRoot = baseName)

            val staged = File(workDir, baseName)
            validateStaged(staged, baseName)
            swapIntoPlace(staged, fwDir)
        } finally {
            archive.delete()
            workDir.deleteRecursively()
        }

        // After the swap: the marker lives inside frameworkDir, which the swap replaces wholesale.
        markerFile.get().asFile.writeText(version.get())
        logger.lifecycle("Extracted to $fwDir")
    }
}
