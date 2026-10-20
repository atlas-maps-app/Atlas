package app.atlas.ui.search

import app.atlas.Services
import app.atlas.services.PlaceOrigin
import app.atlas.testing.testServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chains and independents around the Westerkerk, from the real Amsterdam extract and from the bundled pack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrandsAroundTest {
    @Test
    fun theStoreKnowsTheChainsAndTheIndependents() = runBlocking {
        val n = BrandsAround.near(testServices(places = true), Services.WESTERKERK, BRANDS_RADIUS_M)
        assertEquals(PlaceOrigin.STORE, n.origin)
        assertTrue("chains: ${n.chains.size}", n.chains.size >= 5)
        assertTrue("independents: ${n.independents.size}", n.independents.size > n.chains.size)
        assertTrue(n.chains.any { it.brand.name == "Albert Heijn" })
        // Most branches first; branches nearest first.
        assertTrue(n.chains.zipWithNext().all { (a, b) -> a.branches.size >= b.branches.size })
        for (c in n.chains) assertTrue(c.branches.zipWithNext().all { (a, b) ->
            app.atlas.geo.Geo.distance(Services.WESTERKERK, a.location) <= app.atlas.geo.Geo.distance(Services.WESTERKERK, b.location)
        })
        // Nothing here is both a chain and an independent.
        val chainKeys = n.chains.flatMap { c -> c.branches.map { it.key } }.toSet()
        assertTrue(n.independents.none { it.key in chainKeys })
    }

    @Test
    fun withoutARegionTheBundledPackTells() = runBlocking {
        val n = BrandsAround.near(testServices(), Services.WESTERKERK, BRANDS_RADIUS_M)
        assertEquals(PlaceOrigin.PACK, n.origin)
        assertTrue("chains: ${n.chains.map { it.brand.name }}", n.chains.isNotEmpty())
        assertTrue(n.independents.isNotEmpty())
    }

    @Test
    fun tradesAreShopsEateriesAndHotels() {
        assertTrue(BrandsAround.isTrade(mapOf("shop" to "bakery")))
        assertTrue(BrandsAround.isTrade(mapOf("amenity" to "cafe")))
        assertTrue(BrandsAround.isTrade(mapOf("tourism" to "hotel")))
        assertTrue(!BrandsAround.isTrade(mapOf("amenity" to "bench")))
        assertTrue(!BrandsAround.isTrade(mapOf("historic" to "monument", "name" to "Homomonument")))
    }

    @Test
    fun aBrandsBranchesAreNearestFirstFromTheStore() = runBlocking {
        val (branches, origin) = BrandsAround.branches(testServices(places = true), "albertheijn-1cf55d", Services.WESTERKERK, BRAND_RADIUS_M)
        assertEquals(PlaceOrigin.STORE, origin)
        assertTrue("branches: ${branches.size}", branches.isNotEmpty())
        assertTrue(branches.all { app.atlas.places.Brands.forTags(it.tags)?.id == "albertheijn-1cf55d" })
        assertTrue(branches.zipWithNext().all { (a, b) ->
            app.atlas.geo.Geo.distance(Services.WESTERKERK, a.location) <= app.atlas.geo.Geo.distance(Services.WESTERKERK, b.location)
        })
    }

    @Test
    fun aCategorysLinesCountWhatIsOpen() {
        val s = testServices(places = true)
        val now = java.time.LocalDateTime.now(s.clock)
        val found = kotlinx.coroutines.runBlocking { s.places.nearby("drinks", Services.WESTERKERK, CATEGORY_RADIUS_M, CATEGORY_LIMIT).first() }
        val lines = CategoryPlaces.of(s, found, Services.WESTERKERK, "en", now)
        assertEquals(PlaceOrigin.STORE, lines.origin)
        assertEquals(found.places.size, lines.lines.size)
        assertTrue(lines.open in 1..lines.lines.size)
        assertEquals(lines.lines.count { it.tone == Tone.OPEN || it.tone == Tone.SOON }, lines.open)
    }
}
