package dev.avinya.admob.showcase.feature.lab

import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdPlacement
import dev.avinya.admob.showcase.domain.ad.ShowcasePlacements
import dev.avinya.admob.showcase.domain.lab.SdkLabScenario
import dev.avinya.admob.showcase.domain.lab.SupportedAdFormat
import dev.avinya.admob.showcase.nav.AppOpenLabRoute
import dev.avinya.admob.showcase.nav.BannerLabRoute
import dev.avinya.admob.showcase.nav.DiagnosticsLabRoute
import dev.avinya.admob.showcase.nav.FullScreenLabRoute
import dev.avinya.admob.showcase.nav.NativeLabRoute
import dev.avinya.admob.showcase.nav.PrivacyLabRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdkLabCoverageTest {

    @Test
    fun everySupportedAdFormat_hasALabScenario() {
        assertEquals(
            SupportedAdFormat.entries.toSet(),
            SdkLabScenario.entries.map { it.format }.toSet(),
        )
    }

    @Test
    fun everyScenario_referencesACatalogPlacement() {
        val catalog = ShowcasePlacements.allPlacements.map { it.id }.toSet()
        SdkLabScenario.entries.forEach { scenario ->
            assertTrue(
                scenario.placement.id in catalog,
                "scenario ${scenario.format} references unknown placement ${scenario.placement.id}",
            )
        }
    }

    @Test
    fun appOpen_hasItsOwnScenarioScreen() {
        // App-open has no button to press, so it is the format most likely to
        // look broken during an integration. Folding it into Diagnostics —
        // which is what this used to do — demonstrated nothing about it.
        val appOpen = SdkLabScenario.entries.single { it.format == SupportedAdFormat.AppOpen }
        assertEquals(AppOpenLabRoute, appOpen.destination)
    }

    @Test
    fun everyFormatWithADedicatedScreen_ownsItAlone() {
        val exclusive = listOf(BannerLabRoute, NativeLabRoute, AppOpenLabRoute)
        exclusive.forEach { route ->
            val owners = SdkLabScenario.entries.filter { it.destination == route }
            assertEquals(1, owners.size, "$route should host exactly one format, got $owners")
        }
    }

    @Test
    fun everyScenarioPlacement_matchesItsFormat() {
        SdkLabScenario.entries.forEach { scenario ->
            val expectedFormat = AdFormat.valueOf(scenario.format.name)
            assertEquals(
                expectedFormat,
                scenario.placement.format,
                "scenario ${scenario.format} uses placement ${scenario.placement.id} with a different format",
            )
        }
    }

    @Test
    fun everyScenario_destinationsAreLabRoutes() {
        val labRoutes = setOf(
            BannerLabRoute,
            NativeLabRoute,
            FullScreenLabRoute,
            AppOpenLabRoute,
            PrivacyLabRoute,
            DiagnosticsLabRoute,
        )
        SdkLabScenario.entries.forEach { scenario ->
            assertTrue(
                scenario.destination in labRoutes,
                "scenario ${scenario.format} must map to a Lab route, got ${scenario.destination}",
            )
        }
    }

    @Test
    fun everyLabPlacement_isStrictTestMode() {
        SdkLabScenario.entries.forEach { scenario ->
            assertTrue(
                scenario.placement.strictTestMode,
                "scenario ${scenario.format} must use strictTestMode (fail closed on production ids)",
            )
        }
    }

    @Test
    fun fullScreenLab_scenariosShareTheDestinationButUseDistinctPlacements() {
        val fullScreen = SdkLabScenario.entries.filter { it.destination == FullScreenLabRoute }
        val ids = fullScreen.map { it.placement.id }

        assertEquals(ids.size, ids.toSet().size, "full-screen scenarios must not share a placement")
    }
}
