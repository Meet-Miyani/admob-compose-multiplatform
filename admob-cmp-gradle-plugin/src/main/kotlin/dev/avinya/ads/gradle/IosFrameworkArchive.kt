package dev.avinya.ads.gradle

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.gradle.api.GradleException

/** Slices a Kotlin/Native test link needs; see `frameworkDir` in AdMobCmpPlugin. */
internal val REQUIRED_SLICES: List<String> = listOf("ios-arm64", "ios-arm64_x86_64-simulator")

/**
 * Bounds for fetching and expanding a vendor archive.
 *
 * The pinned SHA-256 is the *integrity* control; these are the *availability* control. Before them,
 * a stalled server hung the build indefinitely and the archive was buffered whole in the Gradle
 * daemon's heap — twice, since each entry was also read into a byte array before being written.
 *
 * A parameter rather than constants so the limits can be driven to small values in tests; production
 * always uses [DEFAULT].
 */
internal data class ArchiveLimits(
    val connectTimeoutMillis: Int = 30_000,
    val readTimeoutMillis: Int = 30_000,
    val maxArchiveBytes: Long = 512L * 1024 * 1024,
    val maxExpandedBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxEntryBytes: Long = 512L * 1024 * 1024,
    val maxEntries: Int = 65_536,
    val bufferBytes: Int = 64 * 1024,
) {
    internal companion object {
        val DEFAULT: ArchiveLimits = ArchiveLimits()
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
 * Streams [url] to [target], hashing as it goes, and returns the hex SHA-256.
 *
 * Streaming rather than `readBytes()`: the archive is hundreds of megabytes. Hashing the same stream
 * that is written to disk guarantees the bytes verified are exactly the bytes kept.
 */
internal fun downloadVerifying(url: URL, target: File, limits: ArchiveLimits): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val connection = url.openConnection().apply {
        connectTimeout = limits.connectTimeoutMillis
        readTimeout = limits.readTimeoutMillis
    }
    connection.getInputStream().use { raw ->
        DigestInputStream(raw, digest).use { hashing ->
            target.outputStream().buffered().use { out ->
                copyBounded(hashing, out, limits.maxArchiveBytes, "Downloaded archive from $url", limits)
            }
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

/**
 * Expands [archive] into [into], enforcing entry, size and containment limits.
 *
 * Google ships exactly one top-level directory per archive, carrying the SDK version. It is
 * stripped so the extracted path stays stable across version bumps, and a second top-level entry is
 * rejected rather than merged.
 */
internal fun extractArchive(archive: File, into: File, source: String, limits: ArchiveLimits) {
    val root = into.canonicalFile.toPath()
    var entries = 0
    var expanded = 0L
    val archiveRoots = mutableSetOf<String>()

    archive.inputStream().buffered().use { fileStream ->
        ZipInputStream(fileStream).use { zis ->
            var entry: ZipEntry = zis.nextEntry ?: throw GradleException("Empty zip from $source")
            while (true) {
                if (++entries > limits.maxEntries) {
                    throw GradleException(
                        "Archive from $source has more than ${limits.maxEntries} entries."
                    )
                }
                archiveRoots += entry.name.substringBefore('/')
                if (archiveRoots.size > 1) {
                    throw GradleException(
                        "Archive from $source has multiple top-level entries " +
                            "(${archiveRoots.sorted().joinToString()}); expected exactly one."
                    )
                }
                expanded += writeEntry(zis, entry, into, root, source, expanded, limits)
                zis.closeEntry()
                entry = zis.nextEntry ?: break
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
    limits: ArchiveLimits,
): Long {
    val relative = entry.name.removePrefix(entry.name.substringBefore('/') + "/")
    if (relative.isEmpty()) {
        if (entry.isDirectory) return 0L
        throw GradleException("Archive from $source has a file at its root: ${entry.name}")
    }
    val target = File(into, relative)
    // Confined to the caller's OWN staging root. The previous check allowed anything under the
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
    val remainingOverall = limits.maxExpandedBytes - expandedSoFar
    if (remainingOverall <= 0) {
        throw GradleException("Archive from $source expands past ${limits.maxExpandedBytes} bytes.")
    }
    return target.outputStream().buffered().use { out ->
        // Streamed, not readAllBytes(): a single hostile-but-checksum-approved entry could otherwise
        // exhaust the daemon heap regardless of the archive's own size.
        copyBounded(
            source = zis,
            sink = out,
            limit = minOf(limits.maxEntryBytes, remainingOverall),
            what = "Zip entry ${entry.name} from $source",
            limits = limits,
        )
    }
}

/** Fails unless [staged] is a framework directory carrying every slice a test link needs. */
internal fun validateStaged(staged: File, baseName: String) {
    if (!staged.isDirectory) {
        throw GradleException(
            "Archive did not contain $baseName — zip layout changed? " +
                "Found: ${staged.parentFile?.list()?.sorted()?.joinToString().orEmpty()}"
        )
    }
    // Both slices, not just ios-arm64: a test link needs the simulator slice too, and checking only
    // the device slice is precisely why a partially damaged cache used to survive.
    val missing = REQUIRED_SLICES.filterNot { slice -> File(staged, slice).isDirectory }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "$baseName is missing required slice(s): ${missing.joinToString()}. " +
                "Present: ${staged.list()?.sorted()?.joinToString().orEmpty()}"
        )
    }
}

/**
 * Replaces [destination] with [staged], keeping the old tree recoverable until the new one is in
 * place. On any failure the previous tree is restored, so a refresh can never leave a build with no
 * usable framework.
 */
internal fun swapIntoPlace(staged: File, destination: File) {
    destination.parentFile?.mkdirs()
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

/**
 * Copies [source] into [sink] until it ends, failing once more than [limit] bytes have been read.
 *
 * Returns the number of bytes copied.
 */
internal fun copyBounded(
    source: InputStream,
    sink: OutputStream,
    limit: Long,
    what: String,
    limits: ArchiveLimits = ArchiveLimits.DEFAULT,
): Long {
    val buffer = ByteArray(limits.bufferBytes)
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
