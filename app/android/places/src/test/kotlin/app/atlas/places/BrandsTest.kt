package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandsTest {
    @Test
    fun byWikidataFirst() {
        val ah = Brands.forTags(mapOf("brand:wikidata" to "Q1653985", "shop" to "supermarket"))!!
        assertEquals("Albert Heijn", ah.name)
        assertEquals(mapOf("shop" to "supermarket"), ah.keyTags)
        assertEquals(0xFF00A0E2.toInt(), ah.colour)
        assertEquals(0xFFFFFFFF.toInt(), ah.textColour)
        assertNotNull(ah.logo)
        assertTrue(ah.availableIn("NL") && !ah.availableIn("de"))
        assertTrue(Brands.byWikidata("Q38076").size >= 2)
        assertEquals(ah, Brands.byId(ah.id))
    }

    @Test
    fun byNameAndMainTag() {
        // three different Jumbos: the Dutch supermarket, the South American one, the Greek toy shop
        assertEquals("Q2262314", Brands.forTags(mapOf("brand" to "Jumbo", "shop" to "supermarket"), "nl")!!.wikidata)
        assertEquals("Q6310864", Brands.forTags(mapOf("brand" to "Jumbo", "shop" to "supermarket"), "cl")!!.wikidata)
        assertEquals("Q3562323", Brands.forTags(mapOf("brand" to "Jumbo", "shop" to "toys"), "gr")!!.wikidata)
        // the name alone needs the exact main tag
        assertEquals("Q38076", Brands.forTags(mapOf("name" to "McDonald's", "amenity" to "fast_food"))!!.wikidata)
        assertNull(Brands.forTags(mapOf("name" to "McDonald's", "amenity" to "bench")))
        assertEquals("Q38076", Brands.forTags(mapOf("brand" to "Mcdonalds", "amenity" to "fast_food"))!!.wikidata)
        assertNull(Brands.forTags(mapOf("brand" to "No Such Chain Anywhere", "shop" to "bakery")))
        // the same name with another Wikidata item is another company
        assertNull(Brands.forTags(mapOf("brand" to "Jumbo", "brand:wikidata" to "Q999999999", "shop" to "supermarket")))
        assertNull(Brands.forTags(emptyMap()))
        assertEquals("mcdonalds", Brands.key("McDonald's"))
        assertEquals("gallandgall", Brands.key("Gall & Gall"))
        assertEquals("simonlevelt", Brands.key("Simon Lévelt"))
    }

    @Test
    fun aBrandsTagsForANewBranch() {
        val b = Brands.forTags(mapOf("brand:wikidata" to "Q37158"))!!
        val t = b.tags
        assertEquals("Starbucks", t["brand"])
        assertEquals("Starbucks", t["name"])
        assertEquals("Q37158", t["brand:wikidata"])
        assertEquals("cafe", t["amenity"])
    }

    @Test
    fun search() {
        assertEquals("Albert Heijn", Brands.search("albert", "nl").first().name)
        assertTrue(Brands.search("mcdonals").take(3).any { it.wikidata == "Q38076" })
        assertTrue(Brands.search("starbuks").take(3).any { it.wikidata == "Q37158" })
        assertTrue(Brands.search("").isEmpty())
        val hema = Brands.search("HEMA", "nl")
        assertEquals("Q903805", hema.first().wikidata)
        // one entry per chain
        val mc = Brands.search("McDonald's", limit = 50)
        assertEquals(1, mc.count { it.wikidata == "Q38076" })
        // as fast as typing needs: every brand name scanned per keystroke
        val queries = listOf("a", "al", "alb", "albe", "alber", "albert", "mcdon", "starb", "jumb", "kruidv")
        repeat(3) { queries.forEach { q -> Brands.search(q, "nl") } }
        val t0 = System.nanoTime()
        repeat(5) { queries.forEach { q -> Brands.search(q, "nl") } }
        val ms = (System.nanoTime() - t0) / 1e6 / (5 * queries.size)
        println("brand search: %.1f ms per query over ${Brands.all().size} brands".format(ms))
        assertTrue(ms < 200)
    }

    @Test
    fun allByCountry() {
        val all = Brands.all()
        assertTrue(all.size > 13000)
        val nl = Brands.all("nl")
        assertTrue(nl.any { it.wikidata == "Q1653985" })
        assertFalse(nl.any { it.name == "Jumbo (Cencosud)" })
        assertTrue(nl.size < all.size)
        assertTrue(all.first().spread >= all.last().spread)
    }

    @Test
    fun logosOfWellKnownChains() {
        val chains = mapOf(
            "McDonald's" to "Q38076", "Starbucks" to "Q37158", "Burger King" to "Q177054", "KFC" to "Q524757", "Lidl" to "Q151954",
            "Albert Heijn" to "Q1653985", "IKEA" to "Q54078", "Shell" to "Q110716465", "Tesla" to "Q478214", "Zara" to "Q147662",
            "Uniqlo" to "Q26070", "Tesco" to "Q487494", "Carrefour" to "Q217599", "REWE" to "Q16968817", "dm" to "Q266572",
            "Rossmann" to "Q316004", "Taco Bell" to "Q752941", "Aldi Nord" to "Q41171373",
        )
        val found = chains.filter { (_, q) -> Brands.byWikidata(q).any { it.logo != null } }.keys
        println("logos for ${found.size} of ${chains.size} well-known chains; missing: ${chains.keys - found}")
        assertTrue(found.size >= 12)
        val withLogo = Brands.all().count { it.logoSlug != null }
        println("brands with a logo: $withLogo of ${Brands.all().size}")
        for (b in Brands.all()) if (b.logoSlug != null) assertNotNull(b.id, b.logo)
    }
}
