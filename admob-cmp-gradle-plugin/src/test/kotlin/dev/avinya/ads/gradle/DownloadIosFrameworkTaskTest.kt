package dev.avinya.ads.gradle

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * The task under real Gradle, because the defect this replaces was *about* Gradle's up-to-date
 * behaviour: the action short-circuited on a version marker, so the very re-runs Gradle scheduled to
 * repair a damaged or stale output did nothing and reported success.
 *
 * The task is registered directly rather than through [AdMobCmpPlugin], which would drag in the
 * Kotlin Multiplatform plugin and its resolution. Every defect being covered lives in the task.
 */
class DownloadIosFrameworkTaskTest {

    private val projectDir: File =
        File(System.getProperty("java.io.tmpdir"), "admob-testkit-${System.nanoTime()}").apply { mkdirs() }

    private val frameworkDir: File
        get() = File(projectDir, "build/frameworks/${ArchiveFixtures.FRAMEWORK}")

    @AfterTest
    fun cleanUp() {
        projectDir.deleteRecursively()
    }

    private fun writeBuild(baseUrl: String, sha: String, version: String = ArchiveFixtures.VERSION) {
        File(projectDir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
        // The task class has to be on the build script's own compile classpath. GradleRunner's
        // withPluginClasspath() only feeds plugin *resolution* through the plugins {} block, so a
        // direct class reference needs an explicit buildscript classpath.
        val classpath = pluginClasspath.joinToString(", ") { file ->
            "\"${file.invariantSeparatorsPath}\""
        }
        File(projectDir, "build.gradle.kts").writeText(
            """
            buildscript {
                dependencies { classpath(files($classpath)) }
            }

            tasks.register("downloadFixture", dev.avinya.ads.gradle.DownloadIosFramework::class.java) {
                baseUrl.set("$baseUrl")
                version.set("$version")
                expectedSha256.set("$sha")
                frameworkDir.set(layout.buildDirectory.dir("frameworks/${ArchiveFixtures.FRAMEWORK}"))
                markerFile.set(frameworkDir.file(".gma_downloaded"))
            }
            """.trimIndent()
        )
    }

    private fun run(vararg args: String) = runner(*args).build()
    private fun runAndFail(vararg args: String) = runner(*args).buildAndFail()

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(projectDir)
        .withArguments("downloadFixture", "--no-configuration-cache", *args)
        .forwardOutput()

    /**
     * The classpath `java-gradle-plugin` publishes for TestKit, read from the metadata resource it
     * puts on the test runtime classpath.
     */
    private val pluginClasspath: List<File> by lazy {
        val resource = checkNotNull(
            javaClass.classLoader.getResource("plugin-under-test-metadata.properties")
        ) { "plugin-under-test-metadata.properties missing — is java-gradle-plugin applied?" }
        val properties = java.util.Properties().apply {
            resource.openStream().use { load(it) }
        }
        val raw = checkNotNull(properties.getProperty("implementation-classpath")) {
            "implementation-classpath missing from plugin-under-test-metadata.properties"
        }
        raw.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
    }

    private fun outcome(result: org.gradle.testkit.runner.BuildResult) =
        result.task(":downloadFixture")?.outcome

    // --- the happy path and up-to-date behaviour ----------------------------------------

    @Test
    fun `a valid archive materialises only the framework`() {
        val bytes = ArchiveFixtures.validArchive()
        FixtureServer(bytes).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(bytes))

            assertEquals(TaskOutcome.SUCCESS, outcome(run()))

            REQUIRED_SLICES.forEach { slice ->
                assertTrue(File(frameworkDir, slice).isDirectory, "missing slice $slice")
            }
            assertEquals(ArchiveFixtures.VERSION, File(frameworkDir, ".gma_downloaded").readText())
            // The archive also carries Licenses/ and a placeholder source. Those used to be written
            // into the SHARED frameworks parent as undeclared outputs, in the directory the sibling
            // download task also writes to.
            val parent = frameworkDir.parentFile
            assertFalse(File(parent, "Licenses").exists(), "sibling payload must not be materialised")
            assertFalse(File(parent, "GoogleMobileAdsPlaceholder.swift").exists())
            assertEquals(
                listOf(ArchiveFixtures.FRAMEWORK),
                parent.list()!!.sorted(),
                "the framework should be the task's only product"
            )
        }
    }

    @Test
    fun `an intact output is up to date and is not downloaded again`() {
        val bytes = ArchiveFixtures.validArchive()
        FixtureServer(bytes).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(bytes))
            run()
            val afterFirst = server.requestCount

            assertEquals(TaskOutcome.UP_TO_DATE, outcome(run()))
            assertEquals(afterFirst, server.requestCount, "an up-to-date task must not hit the network")
        }
    }

    // --- cache repair: what the old short-circuit prevented ------------------------------

    @Test
    fun `a deleted simulator slice is repaired`() {
        val bytes = ArchiveFixtures.validArchive()
        FixtureServer(bytes).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(bytes))
            run()

            // The old guard checked the marker and ios-arm64 only, so this damage survived every
            // subsequent build and surfaced as undefined symbols at the simulator test link.
            File(frameworkDir, "ios-arm64_x86_64-simulator").deleteRecursively()

            assertEquals(TaskOutcome.SUCCESS, outcome(run()))
            assertTrue(File(frameworkDir, "ios-arm64_x86_64-simulator").isDirectory)
        }
    }

    @Test
    fun `a truncated binary inside the kept slice is repaired`() {
        val bytes = ArchiveFixtures.validArchive()
        FixtureServer(bytes).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(bytes))
            run()
            val binary = File(frameworkDir, "ios-arm64/GoogleMobileAds.framework/GoogleMobileAds")
            binary.writeText("")

            assertEquals(TaskOutcome.SUCCESS, outcome(run()))
            assertEquals("binary-ios-arm64", binary.readText())
        }
    }

    @Test
    fun `a changed baseUrl at the same version re-downloads`() {
        val bytes = ArchiveFixtures.validArchive()
        val sha = ArchiveFixtures.sha256(bytes)
        FixtureServer(bytes).use { first ->
            writeBuild(first.baseUrl, sha)
            run()
        }
        // A different origin serving the same version used to be ignored entirely: the marker still
        // named the current version, so the task short-circuited without re-fetching.
        FixtureServer(bytes).use { second ->
            writeBuild(second.baseUrl, sha)
            assertEquals(TaskOutcome.SUCCESS, outcome(run()))
            assertEquals(1, second.requestCount, "the new origin should have been contacted")
        }
    }

    @Test
    fun `a changed checksum at the same version is enforced`() {
        val bytes = ArchiveFixtures.validArchive()
        FixtureServer(bytes).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(bytes))
            run()

            // Same version, different expected checksum: previously accepted without a re-check.
            writeBuild(server.baseUrl, "0".repeat(64))
            val failure = runAndFail()
            assertTrue(failure.output.contains("checksum mismatch"), failure.output)
        }
    }

    // --- recovery: a failed refresh must not destroy a working cache ----------------------

    @Test
    fun `a checksum mismatch preserves the previous framework`() {
        val good = ArchiveFixtures.validArchive()
        FixtureServer(good).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(good))
            run()
        }
        val before = ArchiveFixtures.treeDigest(frameworkDir)

        FixtureServer(ArchiveFixtures.notAZip()).use { bad ->
            writeBuild(bad.baseUrl, ArchiveFixtures.sha256(good), version = "9.9.9")
            runAndFail()
        }
        assertEquals(before, ArchiveFixtures.treeDigest(frameworkDir), "cache must survive untouched")
    }

    @Test
    fun `a network failure preserves the previous framework`() {
        val good = ArchiveFixtures.validArchive()
        val sha = ArchiveFixtures.sha256(good)
        FixtureServer(good).use { server ->
            writeBuild(server.baseUrl, sha)
            run()
        }
        val before = ArchiveFixtures.treeDigest(frameworkDir)

        // Nothing listening: the old code had already deleted the directory by this point.
        writeBuild("http://127.0.0.1:1", sha, version = "9.9.9")
        runAndFail()

        assertEquals(before, ArchiveFixtures.treeDigest(frameworkDir), "cache must survive untouched")
    }

    @Test
    fun `a malformed archive preserves the previous framework`() {
        val good = ArchiveFixtures.validArchive()
        FixtureServer(good).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(good))
            run()
        }
        val before = ArchiveFixtures.treeDigest(frameworkDir)

        val garbage = ArchiveFixtures.notAZip()
        FixtureServer(garbage).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(garbage), version = "9.9.9")
            runAndFail()
        }
        assertEquals(before, ArchiveFixtures.treeDigest(frameworkDir), "cache must survive untouched")
    }

    @Test
    fun `an archive missing a required slice fails and preserves the previous framework`() {
        val good = ArchiveFixtures.validArchive()
        FixtureServer(good).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(good))
            run()
        }
        val before = ArchiveFixtures.treeDigest(frameworkDir)

        val partial = ArchiveFixtures.missingSimulatorSlice()
        FixtureServer(partial).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(partial), version = "9.9.9")
            val failure = runAndFail()
            assertTrue(failure.output.contains("ios-arm64_x86_64-simulator"), failure.output)
        }
        assertEquals(before, ArchiveFixtures.treeDigest(frameworkDir), "cache must survive untouched")
    }

    @Test
    fun `a second archive root fails without writing outside the task output`() {
        val hostile = ArchiveFixtures.twoArchiveRoots()
        FixtureServer(hostile).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(hostile))
            val failure = runAndFail()
            assertTrue(failure.output.contains("multiple top-level"), failure.output)
        }
        assertFalse(
            File(projectDir, "build/frameworks/UserMessagingPlatform.xcframework").exists(),
            "the sibling task's output must never be touched"
        )
    }

    @Test
    fun `a first failure leaves nothing partial behind`() {
        val garbage = ArchiveFixtures.notAZip()
        FixtureServer(garbage).use { server ->
            writeBuild(server.baseUrl, ArchiveFixtures.sha256(garbage))
            runAndFail()
        }
        // No previous cache existed, so a half-written tree here would be the worst outcome: a
        // partially populated directory that a later run might treat as usable.
        assertFalse(File(frameworkDir, ".gma_downloaded").exists(), "no marker without a valid tree")
    }
}
