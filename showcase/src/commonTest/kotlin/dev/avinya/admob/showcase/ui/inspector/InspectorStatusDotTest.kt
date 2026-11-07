package dev.avinya.admob.showcase.ui.inspector

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the status dot's modifier-chain shape across SDK status changes.
 *
 * Status changes must not change the chain's shape: a steady dot, a pulsing
 * dot, and a pulse at full opacity all have to build the same element types
 * in the same order. When a layer element left the end of the chain at
 * runtime, Compose drew the dot into a reused layer it never re-recorded, so a
 * shape change here reintroduces the vanishing-dot bug.
 */
class InspectorStatusDotTest {

    @Test
    fun steadyAndPulsingDotsHaveTheSameElementTypes() {
        val steady = Modifier.inspectorStatusDot(color = Color.Green, pulse = null)
        val pulsing = Modifier.inspectorStatusDot(color = Color.Red, pulse = mutableStateOf(0.35f))

        assertEquals(elementTypes(steady), elementTypes(pulsing))
    }

    @Test
    fun fullOpacityPulseHasTheSameElementTypesAsMidPulse() {
        // `Modifier.alpha(1f)` adds no element, so a pulse that sat at full
        // opacity used to drop the layer that a mid-pulse dot kept.
        val fullOpacity = Modifier.inspectorStatusDot(color = Color.Green, pulse = mutableStateOf(1f))
        val midPulse = Modifier.inspectorStatusDot(color = Color.Green, pulse = mutableStateOf(0.35f))

        assertEquals(elementTypes(fullOpacity), elementTypes(midPulse))
    }

    private fun elementTypes(modifier: Modifier): List<KClass<out Modifier.Element>> =
        modifier.foldIn(emptyList<KClass<out Modifier.Element>>()) { acc, element ->
            acc + element::class
        }
}
