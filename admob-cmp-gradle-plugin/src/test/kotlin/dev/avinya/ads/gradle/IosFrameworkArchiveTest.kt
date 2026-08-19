package dev.avinya.ads.gradle

import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.GradleException

/**
 * The download, extraction, validation and swap behaviour, driven directly so the resource bounds
 * can be exercised with small limits instead of half-gigabyte fixtures.
 *
 * Task wiring and Gradle's up-to-date behaviour are covered separately, by
 * [DownloadIosFrameworkTaskTest].
 */
class IosFrameworkArchiveTest {

    private val temp: File = createTempDirectory()
    private val small = ArchiveLimits(
        connectTimeoutMillis = 2_000,
        readTimeoutMillis = 1_500,
        maxArchiveBytes = 1_000_000,
        maxExpandedBytes = 1_000_000,
        maxEntryBytes = 4_096,
        maxEntries = 64,
        bufferBytes = 1_024,
    )

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun createTempDirectory(): File =
        File(System.getProperty("java.io.tmpdir"), "admob-archive-${System.nanoTime()}").apply { mkdirs() }

    private fun dir(name: String): File = File(temp, name).apply { mkdirs() }

    // --- download -----------------------------------------------------------------------

    @Test
    fun `download hashes exactly the bytes it writes`() {
        val bytes = ArchiveFixtures.validArchive()
        FixtureServer(bytes).use { server ->
            val target = File(temp, "archive.zip")
            val sha = downloadVerifying(URI("${server.baseUrl}/a.zip").toURL(), target, small)

            assertEquals(ArchiveFixtures.sha256(bytes), sha)
            assertContentEquals(bytes, target.readBytes())
        }
    }

    @Test
    fun `a response larger than the archive cap fails instead of filling the heap`() {
        val oversized = ByteArray(small.maxArchiveBytes.toInt() + 1_024) { 'z'.code.toByte() }
        FixtureServer(oversized).use { server ->
            val failure = assertFailsWith<GradleException> {
                downloadVerifying(URI("${server.baseUrl}/a.zip").toURL(), File(temp, "big.zip"), small)
            }
            assertTrue(failure.message!!.contains("byte limit"), failure.message!!)
        }
    }

    @Test
    fun `a server that withholds the body fails on the read timeout`() {
        // Before the timeout this hung the build indefinitely.
        FixtureServer(stallForever = true).use { server ->
            val elapsed = kotlin.system.measureTimeMillis {
                assertFailsWith<java.io.IOException> {
                    downloadVerifying(URI("${server.baseUrl}/a.zip").toURL(), File(temp, "s.zip"), small)
                }
            }
            assertTrue(
                elapsed < 30_000,
                "should have given up near the ${small.readTimeoutMillis}ms read timeout, took ${elapsed}ms"
            )
        }
    }

    // --- extraction bounds and containment ----------------------------------------------

    @Test
    fun `a valid archive expands with its version prefix stripped`() {
        val into = dir("into")
        extractArchive(writeFixture(ArchiveFixtures.validArchive()), into, "test", small)

        val framework = File(into, ArchiveFixtures.FRAMEWORK)
        assertTrue(framework.isDirectory, "framework should be at the root after stripping")
        REQUIRED_SLICES.forEach { slice ->
            assertTrue(File(framework, slice).isDirectory, "missing slice $slice")
        }
        // Sibling payload still expands into the staging area; the task simply does not promote it.
        assertTrue(File(into, "Licenses/OpenSSL-LICENSE").isFile)
    }

    @Test
    fun `an entry escaping the extraction root is rejected`() {
        val into = dir("into")
        val failure = assertFailsWith<GradleException> {
            extractArchive(writeFixture(ArchiveFixtures.zipSlip()), into, "test", small)
        }
        assertTrue(failure.message!!.contains("escapes"), failure.message!!)
        assertFalse(File(temp, "escaped.txt").exists(), "nothing may be written outside the root")
        assertFalse(File(into.parentFile, "escaped.txt").exists())
    }

    @Test
    fun `a second archive root is rejected before it can reach a sibling output`() {
        val into = dir("into")
        val failure = assertFailsWith<GradleException> {
            extractArchive(writeFixture(ArchiveFixtures.twoArchiveRoots()), into, "test", small)
        }
        assertTrue(failure.message!!.contains("multiple top-level"), failure.message!!)
        assertFalse(
            File(into, "UserMessagingPlatform.xcframework/hijacked").exists(),
            "the sibling framework's payload must never be written"
        )
    }

    @Test
    fun `an archive with too many entries is rejected`() {
        val into = dir("into")
        val failure = assertFailsWith<GradleException> {
            extractArchive(writeFixture(ArchiveFixtures.manyEntries(small.maxEntries + 5)), into, "test", small)
        }
        assertTrue(failure.message!!.contains("more than ${small.maxEntries} entries"), failure.message!!)
    }

    @Test
    fun `a single oversized entry is rejected`() {
        val into = dir("into")
        val failure = assertFailsWith<GradleException> {
            extractArchive(
                writeFixture(ArchiveFixtures.oversizedEntry(small.maxEntryBytes.toInt() + 512)),
                into, "test", small
            )
        }
        assertTrue(failure.message!!.contains("byte limit"), failure.message!!)
    }

    @Test
    fun `a malformed archive is rejected`() {
        val into = dir("into")
        assertFailsWith<GradleException> {
            extractArchive(writeFixture(ArchiveFixtures.notAZip()), into, "test", small)
        }
    }

    // --- slice validation ----------------------------------------------------------------

    @Test
    fun `validation requires every slice a test link needs`() {
        val into = dir("into")
        extractArchive(writeFixture(ArchiveFixtures.missingSimulatorSlice()), into, "test", small)

        val failure = assertFailsWith<GradleException> {
            validateStaged(File(into, ArchiveFixtures.FRAMEWORK), ArchiveFixtures.FRAMEWORK)
        }
        // The old code accepted this: it only ever checked ios-arm64, so a device-only cache passed
        // and the simulator test link failed much later with undefined symbols.
        assertTrue(failure.message!!.contains("ios-arm64_x86_64-simulator"), failure.message!!)
    }

    @Test
    fun `validation rejects a missing framework directory`() {
        val failure = assertFailsWith<GradleException> {
            validateStaged(File(dir("into"), "Absent.xcframework"), "Absent.xcframework")
        }
        assertTrue(failure.message!!.contains("zip layout changed"), failure.message!!)
    }

    // --- the swap ------------------------------------------------------------------------

    @Test
    fun `the swap replaces a previous tree wholesale`() {
        val destination = dir("dest/GoogleMobileAds.xcframework")
        ArchiveFixtures.seedCache(destination, version = "old")
        File(destination, "stale-leftover.txt").writeText("should not survive")

        val staged = dir("staged/GoogleMobileAds.xcframework")
        File(staged, "Info.plist").writeText("<plist>fresh</plist>")

        swapIntoPlace(staged, destination)

        assertEquals("<plist>fresh</plist>", File(destination, "Info.plist").readText())
        assertFalse(File(destination, "stale-leftover.txt").exists(), "a swap is not a merge")
        assertFalse(
            File(destination.parentFile, "${destination.name}.previous").exists(),
            "the backup should be cleaned up on success"
        )
    }

    @Test
    fun `the swap leaves no backup directory behind when there was nothing to replace`() {
        val destination = File(dir("dest"), "GoogleMobileAds.xcframework")
        val staged = dir("staged/GoogleMobileAds.xcframework")
        File(staged, "Info.plist").writeText("<plist/>")

        swapIntoPlace(staged, destination)

        assertTrue(File(destination, "Info.plist").isFile)
        assertFalse(File(destination.parentFile, "${destination.name}.previous").exists())
    }

    private fun writeFixture(bytes: ByteArray): File =
        File(temp, "fixture-${System.nanoTime()}.zip").apply { writeBytes(bytes) }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual), "downloaded bytes differ from what was served")
    }
}
