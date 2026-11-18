package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A place's brand is matched in its country (the reviewer, 17 November, finding 9): the place's own `addr:country`,
 * else the country of the map at it, which the caller gives. Among several companies of a name, the one trading there;
 * a name alone is a chain's only where the chain trades, and where no country is known, only a chain's that trades
 * everywhere.
 */
class BrandCountryTest {
    private val jumbo = mapOf("brand" to "Jumbo", "shop" to "supermarket")

    @Test
    fun aBrandIsTheCompanyOfItsCountry() {
        assertEquals("jumbo-1cf55d", Brands.forTags(jumbo, "NL")?.id)
        assertEquals("jumbo-99a0d7", Brands.forTags(jumbo, "CL")?.id)
        val coop = mapOf("brand" to "Coop", "shop" to "supermarket")
        assertEquals("coop-5811b5", Brands.forTags(coop, "NL")?.id)
        assertEquals("coop-c57afb", Brands.forTags(coop, "SE")?.id)
    }

    @Test
    fun thePlacesOwnCountryComesBeforeTheMaps() {
        assertEquals("jumbo-99a0d7", Brands.forTags(jumbo + ("addr:country" to "CL"), "NL")?.id)
        assertEquals("jumbo-1cf55d", Brands.forTags(jumbo + ("addr:country" to "nl"), "CL")?.id)
    }

    @Test
    fun aNameAloneIsAChainOnlyWhereItTrades() {
        // "farmacia" is any pharmacy in Italy and Spain, a chain in Ukraine and another in Croatia
        val farmacia = mapOf("amenity" to "pharmacy", "name" to "Farmacia")
        assertNull(Brands.forTags(farmacia, "IT"))
        assertNull(Brands.forTags(farmacia, "ES"))
        assertEquals("farmacia-996e17", Brands.forTags(farmacia, "UA")?.id)
        assertEquals("farmacia-e8d74c", Brands.forTags(farmacia, "HR")?.id)
        // a kiosk named Kiosk is the Dutch railway's at home only
        val kiosk = mapOf("shop" to "kiosk", "name" to "Kiosk")
        assertNull(Brands.forTags(kiosk, "DE"))
        assertEquals("kiosk-c47323", Brands.forTags(kiosk, "NL")?.id)
    }

    @Test
    fun whereNoCountryIsKnownANameAloneIsAWorldwideChainsOnly() {
        assertNull(Brands.forTags(mapOf("shop" to "kiosk", "name" to "Kiosk"), null))
        assertNull(Brands.forTags(mapOf("amenity" to "pharmacy", "name" to "Farmacia"), null))
        assertEquals("Q38076", Brands.forTags(mapOf("amenity" to "fast_food", "name" to "McDonald's"), null)?.wikidata)
    }
}
