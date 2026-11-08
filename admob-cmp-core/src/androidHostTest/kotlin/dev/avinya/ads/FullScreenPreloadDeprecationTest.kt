package dev.avinya.ads

import kotlin.DeprecationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the deprecation of `FullScreenAdController.preload()`.
 *
 * `preload()` is a default interface method that just calls `load()`. Its deprecation is
 * part of the public API contract (IDE quick-fix to `load()`, a warning for existing callers),
 * so it is checked against the compiled JVM signature. Plain Java reflection is used so the
 * test needs no kotlin-reflect dependency.
 *
 * `preload` is a suspend fun, so its JVM signature carries an extra Continuation parameter;
 * the method is therefore found by name rather than by parameter types.
 */
class FullScreenPreloadDeprecationTest {

    private fun preloadMethod(): java.lang.reflect.Method {
        val candidates = FullScreenAdController::class.java.declaredMethods.filter { it.name == "preload" }
        assertEquals(1, candidates.size, "expected exactly one preload method on FullScreenAdController")
        return candidates.single()
    }

    @Test
    fun `preload is annotated as deprecated`() {
        val annotation = preloadMethod().getAnnotation(kotlin.Deprecated::class.java)
        assertNotNull(annotation, "preload() must carry @Deprecated")
    }

    @Test
    fun `preload deprecation points at load with a warning level`() {
        val annotation = assertNotNull(
            preloadMethod().getAnnotation(kotlin.Deprecated::class.java),
            "preload() must carry @Deprecated",
        )
        assertEquals("load(requestOptions)", annotation.replaceWith.expression)
        assertEquals(DeprecationLevel.WARNING, annotation.level)
    }
}
