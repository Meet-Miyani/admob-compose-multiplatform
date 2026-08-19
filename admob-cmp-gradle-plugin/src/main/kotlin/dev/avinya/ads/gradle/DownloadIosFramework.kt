package dev.avinya.ads.gradle

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

/** Slices a Kotlin/Native test link needs; see `frameworkDir` in AdMobCmpPlugin. */
internal val REQUIRED_SLICES: List<String> = listOf("ios-arm64", "ios-arm64_x86_64-simulator")

/**
 * Resource bounds for a downloaded archive.
 *
 * The pinned SHA-256 is the integrity control; these are the *availability* control. A stalled
 * server used to hang the build forever, and a large archive was buffered whole in memory — twice,
 * since each entry was also read into a byte array before being written.
 */
internal object ArchiveLimits {
    const val CONNECT_TIMEOUT_MILLIS: Int = 30_000
    const val READ_TIMEOUT_MILLIS: Int = 30_000
    const val MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024
    const val MAX_EXPANDED_BYTES: Long = 2L * 1024 * 1024 * 1024
    const val MAX_ENTRY_BYTES: Long = 512L * 1024 * 1024
    const val MAX_ENTRIES: Int = 65_536
    const val BUFFER_BYTES: Int = 64 * 1024
}

@DisableCachingByDefault(because = "Downloads external binary archive")
public abstract class DownloadIosFramework : DefaultTask() {
    @get:Input
    public abstract val version: Property<String>

    @get:Input
    public abstract val baseUrl: Property<String>

    // Supply-chain integrity: the UMP endpoint is unversioned and neither archive was
    // checksummed, so the same commit could build against different headers. Fail closed.
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
     * framework makes this declaration complete, which is what lets Gradle's own up-to-date checking
     * be trusted — see [download].
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
        val zipUrl = URI("${baseUrl.get()}/${archiveName(baseName, version.get())}").toURL()
        logger.lifecycle("Downloading from $zipUrl...")

        // Everything happens inside the task's own temporary directory, so a failure at any point
        // leaves the previous framework byte-for-byte intact. The old code deleted it first, which
        // turned any transient network error, checksum mismatch, or malformed archive into a broken
        // local build that only a manual clean could recover.
        val workDir = File(temporaryDir, "staging")
        val archive = File(temporaryDir, "download/${zipUrl.path.substringAfterLast('/')}")
        try {
            workDir.deleteRecursively()
            archive.parentFile.mkdirs()
            archive.delete()

            val actualSha = downloadVerifying(zipUrl, archive)
            val expectedSha = expectedSha256.get()
            if (actualSha != expectedSha) {
                throw GradleException(
                    "$baseName iOS header archive checksum mismatch.\n" +
                        "  expected: $expectedSha\n  actual:   $actualSha\n" +
                        "Refusing to generate bindings from an unverified archive."
                )
            }

            workDir.mkdirs()
            extractArchive(archive, workDir, zipUrl.toString())

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

    /**
     * Streams [url] to [target], hashing as it goes, and returns the hex SHA-256.
     *
     * Streaming rather than `readBytes()`: the archive is hundreds of megabytes and was previously
     * held whole in the Gradle daemon's heap. Hashing the same stream that is written to disk means
     * the bytes verified are exactly the bytes kept.
     */
    private fun downloadVerifying(url: java.net.URL, target: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = url.openConnection().apply {
            connectTimeout = ArchiveLimits.CONNECT_TIMEOUT_MILLIS
            readTimeout = ArchiveLimits.READ_TIMEOUT_MILLIS
        }
        connection.getInputStream().use { raw ->
            DigestInputStream(raw, digest).use { hashing ->
                target.outputStream().buffered().use { out ->
                    copyBounded(
                        source = hashing,
                        sink = out,
                        limit = ArchiveLimits.MAX_ARCHIVE_BYTES,
                        what = "Downloaded archive from $url",
                    )
                }
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Expands [archive] into [into], enforcing entry, size and containment limits. */
    private fun extractArchive(archive: File, into: File, source: String) {
        val root = into.canonicalFile.toPath()
        var entries = 0
        var expanded = 0L
        val archiveRoots = mutableSetOf<String>()

        archive.inputStream().buffered().use { fileStream ->
            ZipInputStream(fileStream).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                    ?: throw GradleException("Empty zip from $source")
                // Google ships one top-level directory per archive; it carries the SDK version, so
                // it is stripped to keep the extracted path stable across version bumps.
                val prefix = entry!!.name.substringBefore('/') + "/"
                while (entry != null) {
                    if (++entries > ArchiveLimits.MAX_ENTRIES) {
                        throw GradleException(
                            "Archive from $source has more than ${ArchiveLimits.MAX_ENTRIES} entries."
                        )
                    }
                    archiveRoots += entry.name.substringBefore('/')
                    if (archiveRoots.size > 1) {
                        throw GradleException(
                            "Archive from $source has multiple top-level entries " +
                                "(${archiveRoots.sorted().joinToString()}); expected exactly one."
                        )
                    }
                    expanded += writeEntry(zis, entry, into, root, source, expanded)
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        if (expanded == 0L) throw GradleException("Archive from $source expanded to nothing.")
    }

    /** Writes one entry, returning the bytes it expanded to. */
    private fun writeEntry(
        zis: ZipInputStream,
        entry: ZipEntry,
        into: File,
        root: java.nio.file.Path,
        source: String,
        expandedSoFar: Long,
    ): Long {
        val relative = entry.name.removePrefix(entry.name.substringBefore('/') + "/")
        if (relative.isEmpty()) {
            if (entry.isDirectory) return 0L
            throw GradleException("Archive from $source has a file at its root: ${entry.name}")
        }
        val target = File(into, relative)
        // Confined to the task's OWN staging root. The previous check allowed anything under the
        // shared frameworks parent, so a checksum-approved archive with an unexpected layout could
        // write into the sibling download task's output directory.
        if (!target.canonicalFile.toPath().startsWith(root)) {
            throw GradleException("Zip entry escapes the extraction directory: ${entry.name}")
        }
        if (entry.isDirectory) {
            target.mkdirs()
            return 0L
        }
        target.parentFile.mkdirs()
        val remainingOverall = ArchiveLimits.MAX_EXPANDED_BYTES - expandedSoFar
        if (remainingOverall <= 0) {
            throw GradleException(
                "Archive from $source expands past ${ArchiveLimits.MAX_EXPANDED_BYTES} bytes."
            )
        }
        return target.outputStream().buffered().use { out ->
            // Streamed, not readAllBytes(): a single hostile-but-checksum-approved entry could
            // otherwise exhaust the daemon heap regardless of the archive's own size.
            copyBounded(
                source = zis,
                sink = out,
                limit = minOf(ArchiveLimits.MAX_ENTRY_BYTES, remainingOverall),
                what = "Zip entry ${entry.name} from $source",
            )
        }
    }

    private fun validateStaged(staged: File, baseName: String) {
        if (!staged.isDirectory) {
            throw GradleException(
                "Archive did not contain $baseName — zip layout changed? " +
                    "Found: ${staged.parentFile.list()?.sorted()?.joinToString().orEmpty()}"
            )
        }
        // Both slices, not just ios-arm64: a test link needs the simulator slice too, and checking
        // only the device slice is precisely why a partially damaged cache used to survive.
        val missing = REQUIRED_SLICES.filterNot { slice -> File(staged, slice).isDirectory }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "$baseName is missing required slice(s): ${missing.joinToString()}. " +
                    "Present: ${staged.list()?.sorted()?.joinToString().orEmpty()}"
            )
        }
    }

    /**
     * Replaces [destination] with [staged], keeping the old tree recoverable until the new one is
     * in place.
     */
    private fun swapIntoPlace(staged: File, destination: File) {
        destination.parentFile.mkdirs()
        val backup = File(destination.parentFile, "${destination.name}.previous")
        backup.deleteRecursively()
        val hadPrevious = destination.exists() && destination.renameTo(backup)
        try {
            if (!staged.renameTo(destination)) {
                // Different filesystem, or a rename the platform refused: fall back to a copy.
                destination.deleteRecursively()
                staged.copyRecursively(destination, overwrite = true)
            }
        } catch (t: Throwable) {
            if (hadPrevious) {
                destination.deleteRecursively()
                backup.renameTo(destination)
            }
            throw t
        }
        backup.deleteRecursively()
    }
}

/** The archive Google publishes for [baseName]. */
internal fun archiveName(baseName: String, version: String): String = when {
    baseName.startsWith("GoogleMobileAds") -> "googlemobileadssdkios-$version.zip"
    // Not version-pinned by Google; the marker records the catalog version that fetched it.
    baseName.startsWith("UserMessagingPlatform") -> "googleusermessagingplatform.zip"
    else -> error("Unknown framework: $baseName")
}

/**
 * Copies [source] into [sink] until it ends, failing once more than [limit] bytes have been read.
 *
 * Returns the number of bytes copied.
 */
internal fun copyBounded(source: InputStream, sink: OutputStream, limit: Long, what: String): Long {
    val buffer = ByteArray(ArchiveLimits.BUFFER_BYTES)
    var total = 0L
    while (true) {
        val read = source.read(buffer)
        if (read < 0) break
        total += read
        if (total > limit) throw GradleException("$what exceeds the $limit byte limit.")
        sink.write(buffer, 0, read)
    }
    return total
}
