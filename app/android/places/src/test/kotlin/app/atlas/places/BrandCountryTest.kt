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
        // Etos trades in the Netherlands alone
        val etos = mapOf("shop" to "chemist", "name" to "Etos")
        assertEquals("etos-994f1e", Brands.forTags(etos, "NL")?.id)
        assertNull(Brands.forTags(etos, "DE"))
    }

    /**
     * A kind's own word is no brand by itself (the app maintainer, 18 November 2026, batch 4 B16): a fuel station named
     * "Station Service" was E.Leclerc's everywhere but Poland, a kiosk named "Kiosk" the Dutch stations' Kiosk at home and
     * a pharmacy named "Farmacia" a chain in Ukraine and another in Croatia. A name that is its kind's in any bundled
     * language (French "Station-service", English "Kiosk", Spanish "Farmacia") matches no brand; by its brand it is the
     * brand, and a brand's own name matches as before.
     */
    @Test
    fun aKindsOwnWordIsNoBrand() {
        assertNull(Brands.forTags(mapOf("amenity" to "fuel", "name" to "Station Service"), "FR"))
        assertNull(Brands.forTags(mapOf("amenity" to "fuel", "name" to "Station-service"), "BE"))
        assertNull(Brands.forTags(mapOf("shop" to "kiosk", "name" to "Kiosk"), "NL"))
        val farmacia = mapOf("amenity" to "pharmacy", "name" to "Farmacia")
        assertNull(Brands.forTags(farmacia, "UA"))
        assertNull(Brands.forTags(farmacia, "HR"))
        assertNull(Brands.forTags(farmacia, "IT"))
        assertEquals("kiosk-c47323", Brands.forTags(mapOf("shop" to "kiosk", "name" to "Kiosk", "brand" to "Kiosk"), "NL")?.id)
        assertEquals("stationserviceeleclerc-16b3c2", Brands.forTags(mapOf("amenity" to "fuel", "name" to "E.Leclerc"), "FR")?.id)
    }

    @Test
    fun whereNoCountryIsKnownANameAloneIsAWorldwideChainsOnly() {
        assertNull(Brands.forTags(mapOf("shop" to "kiosk", "name" to "Kiosk"), null))
        assertNull(Brands.forTags(mapOf("amenity" to "pharmacy", "name" to "Farmacia"), null))
        assertEquals("Q38076", Brands.forTags(mapOf("amenity" to "fast_food", "name" to "McDonald's"), null)?.wikidata)
    }
}
