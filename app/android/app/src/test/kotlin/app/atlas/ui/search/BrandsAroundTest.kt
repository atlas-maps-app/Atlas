package app.atlas.ui.search

import app.atlas.Services
import app.atlas.places.CategoryTree
import app.atlas.search.PlaceRecord
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

    /** A kind's chains share one stack (the owner, 6 November: "Brands around stacks by kind?"). */
    @Test
    fun aKindsChainsShareAStack() = runBlocking {
        val chains = BrandsAround.near(testServices(places = true), Services.WESTERKERK, BRANDS_RADIUS_M).chains
        val stacks = BrandsAround.stacks(chains)
        println("stacks: " + stacks.joinToString(" | ") { s -> "${s.kind?.id}: ${s.chains.joinToString { "${it.brand.name} ${it.branches.size}" }}" })
        // Every chain on one stack, once; the stacks in the order of their first chains, most branches first in each.
        assertEquals(chains.map { it.brand.id }.sorted(), stacks.flatMap { s -> s.chains.map { it.brand.id } }.sorted())
        val first = stacks.map { chains.indexOf(it.chains.first()) }
        assertEquals(first.sorted(), first)
        for (s in stacks) assertTrue(s.chains.zipWithNext().all { (a, b) -> chains.indexOf(a) < chains.indexOf(b) })
        // A chain's stack is the kind most of its branches are.
        for (s in stacks) for (c in s.chains) {
            val kinds = c.branches.map { CategoryTree.of(it.tags, PlaceRecord.geometryOf(it))?.id }
            assertTrue("${c.brand.name}: $kinds on ${s.kind?.id}", kinds.count { it == s.kind?.id } == kinds.groupingBy { it }.eachCount().values.max())
        }
        // Around the Westerkerk the clothes chains stack, the most branches on top; Albert Heijn, the one supermarket
        // chain, is a sign of its own.
        assertTrue("${stacks.size} stacks of ${chains.size}", stacks.size < chains.size)
        val clothes = stacks.first { it.kind?.id == "preset:shop/clothes" }
        assertTrue(clothes.chains.map { it.brand.name }.toString(), clothes.chains.size >= 5)
        assertEquals(clothes.chains.maxOf { it.branches.size }, clothes.chains.first().branches.size)
        assertEquals(listOf("Albert Heijn"), stacks.first { s -> s.chains.any { it.brand.name == "Albert Heijn" } }.chains.map { it.brand.name })
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

    /** The branches a brand's page lists ([SearchSheetModel]'s chain), nearest first, from the region's store. */
    @Test
    fun aBrandsBranchesAreNearestFirstFromTheStore() = runBlocking {
        val got = testServices(places = true).localData.branches("albertheijn-1cf55d", Services.WESTERKERK, 3_000.0)
        val branches = got.value
        assertEquals(PlaceOrigin.STORE, got.origin)
        assertTrue("branches: ${branches.size}", branches.isNotEmpty())
        assertTrue(branches.all { app.atlas.places.Brands.forTags(it.tags)?.id == "albertheijn-1cf55d" })
        assertTrue(branches.zipWithNext().all { (a, b) ->
            app.atlas.geo.Geo.distance(Services.WESTERKERK, a.location) <= app.atlas.geo.Geo.distance(Services.WESTERKERK, b.location)
        })
    }
}
