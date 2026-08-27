package dev.avinya.ads.gradle

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fixture archives and a local HTTP origin, so the download task can be exercised without reaching
 * Google. Uses only the JDK — `com.sun.net.httpserver` and `java.util.zip` — so no test dependency
 * is added to the published plugin.
 */
internal object ArchiveFixtures {

    const val VERSION: String = "1.2.3"
    const val FRAMEWORK: String = "GoogleMobileAds.xcframework"

    /** The single top-level directory Google wraps its archives in. */
    private const val ROOT = "GoogleMobileAdsSdkiOS-$VERSION"

    /** A well-formed archive: one root, the framework, and every required slice. */
    fun validArchive(): ByteArray = zip {
        dir("$ROOT/")
        dir("$ROOT/$FRAMEWORK/")
        file("$ROOT/$FRAMEWORK/Info.plist", "<plist/>")
        REQUIRED_SLICES.forEach { slice ->
            dir("$ROOT/$FRAMEWORK/$slice/")
            dir("$ROOT/$FRAMEWORK/$slice/GoogleMobileAds.framework/")
            file("$ROOT/$FRAMEWORK/$slice/GoogleMobileAds.framework/GoogleMobileAds", "binary-$slice")
        }
        // Payload beside the framework, as the real GMA archive carries. Must not be materialised.
        dir("$ROOT/Licenses/")
        file("$ROOT/Licenses/OpenSSL-LICENSE", "license text")
        file("$ROOT/GoogleMobileAdsPlaceholder.swift", "// placeholder")
    }

    /** Missing the simulator slice — a device-only cache that must be rejected. */
    fun missingSimulatorSlice(): ByteArray = zip {
        dir("$ROOT/")
        dir("$ROOT/$FRAMEWORK/")
        dir("$ROOT/$FRAMEWORK/ios-arm64/")
        file("$ROOT/$FRAMEWORK/ios-arm64/binary", "device only")
    }

    /** Two top-level directories — the layout that could reach a sibling task's output. */
    fun twoArchiveRoots(): ByteArray = zip {
        dir("$ROOT/")
        dir("$ROOT/$FRAMEWORK/")
        REQUIRED_SLICES.forEach { dir("$ROOT/$FRAMEWORK/$it/") }
        dir("UserMessagingPlatform.xcframework/")
        file("UserMessagingPlatform.xcframework/hijacked", "should never be written")
    }

    /** A traversal attempt that resolves outside the extraction root. */
    fun zipSlip(): ByteArray = zip {
        dir("$ROOT/")
        file("$ROOT/../../escaped.txt", "escaped")
    }

    /** More entries than a caller's cap allows. */
    fun manyEntries(count: Int): ByteArray = zip {
        dir("$ROOT/")
        repeat(count) { index -> file("$ROOT/entry-$index.txt", "x") }
    }

    /** One entry larger than a caller's per-entry cap. */
    fun oversizedEntry(bytes: Int): ByteArray = zip {
        dir("$ROOT/")
        file("$ROOT/big.bin", "y".repeat(bytes))
    }

    /** Not a zip at all — models a truncated transfer or an error page served as content. */
    fun notAZip(): ByteArray = "<html>503 Service Unavailable</html>".toByteArray()

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    /** Writes a complete, valid framework tree, standing in for a previously good cache. */
    fun seedCache(frameworkDir: File, version: String = VERSION) {
        frameworkDir.mkdirs()
        File(frameworkDir, "Info.plist").writeText("<plist>seeded</plist>")
        File(frameworkDir, ".gma_downloaded").writeText(version)
        REQUIRED_SLICES.forEach { slice ->
            File(frameworkDir, "$slice/GoogleMobileAds.framework").mkdirs()
            File(frameworkDir, "$slice/GoogleMobileAds.framework/GoogleMobileAds")
                .writeText("seeded-$slice")
        }
    }

    /** A stable digest of a directory tree, for asserting a cache survived untouched. */
    fun treeDigest(dir: File): String {
        if (!dir.exists()) return "<absent>"
        val digest = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown().sortedBy { it.absolutePath }.forEach { file ->
            digest.update(file.relativeTo(dir).path.toByteArray())
            if (file.isFile) digest.update(file.readBytes())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun zip(build: ZipBuilder.() -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos -> ZipBuilder(zos).build() }
        return out.toByteArray()
    }

    internal class ZipBuilder(private val zos: ZipOutputStream) {
        fun dir(name: String) {
            zos.putNextEntry(ZipEntry(if (name.endsWith("/")) name else "$name/"))
            zos.closeEntry()
        }

        fun file(name: String, content: String) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.toByteArray())
            zos.closeEntry()
        }
    }
}

/**
 * Serves fixture bytes over loopback HTTP.
 *
 * [stallForever] models the failure the read timeout exists for: the server accepts the request,
 * sends headers, then never sends the body.
 */
internal class FixtureServer(
    private val body: ByteArray? = null,
    private val stallForever: Boolean = false,
) : AutoCloseable {
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    @Volatile
    private var closing = false

    var requestCount: Int = 0
        private set

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            requestCount++
            if (stallForever) {
                exchange.sendResponseHeaders(200, 1024)
                // Headers sent, body withheld: the client blocks in read until it times out. Stall
                // in short slices rather than one long sleep — HttpServer.stop() waits for the
                // in-flight handler, so a single long sleep would make close() hold the test open
                // long after the client had already given up.
                val deadline = System.nanoTime() + 30_000_000_000L
                while (!closing && System.nanoTime() < deadline) Thread.sleep(50)
                exchange.close()
                return@createContext
            }
            val payload = body ?: ByteArray(0)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.executor = null
        server.start()
    }

    override fun close() {
        closing = true
        server.stop(0)
    }
}
