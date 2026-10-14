package app.atlas.ui.search

import app.atlas.Services
import app.atlas.services.PlaceOrigin
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Chains and independents around the Westerkerk, from the real Amsterdam extract and from the fixture tiles. */
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
    fun withoutARegionTheTilesStillTell() = runBlocking {
        val n = BrandsAround.near(testServices(), Services.WESTERKERK, BRANDS_RADIUS_M)
        assertEquals(PlaceOrigin.TILES, n.origin)
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
}
