package dev.avinya.admob.showcase.domain.ad

import dev.avinya.ads.AdFormat
import dev.avinya.ads.AdSizePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards on the placement catalog.
 *
 * These exist because the catalog silently accumulated five placements with no
 * call site — including a feed banner the product had stopped rendering. Dead
 * entries are not inert: the Inspector builds a controller for every listed
 * placement, so an orphan requests inventory for a surface nobody can see.
 */
class ShowcasePlacementsTest {

    @Test
    fun everyPlacementIsListedExactlyOnce() {
        val ids = ShowcasePlacements.allPlacements.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate placement id in the catalog")
    }

    @Test
    fun consumerPlacementsAreASubsetOfTheCatalog() {
        val catalog = ShowcasePlacements.allPlacements.toSet()
        ShowcasePlacements.consumerPlacements.forEach { placement ->
            assertTrue(placement in catalog, "${placement.id} is not in allPlacements")
        }
    }

    @Test
    fun theOnlyConsumerBannerIsTheCollapsibleReaderSlot() {
        // A banner welded to an infinite feed is the integration this sample
        // argues against, so exactly one consumer banner is allowed: the
        // reader's anchored, collapsible, dismissible slot.
        val banners = ShowcasePlacements.consumerPlacements.filter { it.format == AdFormat.Banner }
        assertEquals(listOf(ShowcasePlacements.articleBanner), banners)
    }

    @Test
    fun labPlacementsAreNamespacedAndConsumerPlacementsAreNot() {
        val lab = ShowcasePlacements.allPlacements - ShowcasePlacements.consumerPlacements.toSet()
        lab.forEach { placement ->
            assertTrue(placement.id.startsWith("lab_"), "${placement.id} should be lab-namespaced")
        }
        ShowcasePlacements.consumerPlacements.forEach { placement ->
            assertTrue(!placement.id.startsWith("lab_"), "${placement.id} should not be lab-namespaced")
        }
    }

    @Test
    fun everyPlacementIsInStrictTestMode() {
        ShowcasePlacements.allPlacements.forEach { placement ->
            assertTrue(placement.strictTestMode, "${placement.id} must fail closed on production ids")
        }
    }

    @Test
    fun theCatalogCoversEverySupportedFormat() {
        val covered = ShowcasePlacements.allPlacements.map { it.format }.toSet()
        assertEquals(AdFormat.entries.toSet(), covered)
    }

    @Test
    fun theFixedBannerDemoHasItsOwnPlacement() {
        // The SDK keeps one banner controller per placement, so the adaptive and fixed demos
        // sharing one placement left one of the two slots empty.
        val fixed = ShowcasePlacements.labBannerFixed
        assertEquals("lab_banner_fixed", fixed.id)
        assertTrue(fixed.id != ShowcasePlacements.labBanner.id, "the fixed demo must not share lab_banner")
        assertEquals(AdFormat.Banner, fixed.format)
        assertEquals(AdSizePolicy.Fixed(widthDp = 320, heightDp = 50), fixed.bannerSizePolicy)
        assertTrue(fixed in ShowcasePlacements.allPlacements, "lab_banner_fixed must be in the catalog")
    }
}
