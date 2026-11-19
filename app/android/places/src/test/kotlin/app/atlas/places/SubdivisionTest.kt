package app.atlas.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * A place's state or nation, where its country's holidays differ within it (the reviewer, 17 November, finding 10):
 * from its own tags only, and only where they say it for certain.
 */
class SubdivisionTest {
    private fun of(country: String?, vararg tags: Pair<String, String>) = PlaceCountry.subdivision(tags.toMap(), country)

    @Test
    fun aGermanStateByItsCodeOrItsName() {
        assertEquals("DE-BY", of("DE", "addr:state" to "BY"))
        assertEquals("DE-BY", of("DE", "addr:state" to "Bayern"))
        assertEquals("DE-BY", of("DE", "addr:state" to "Freistaat Bayern"))
        assertEquals("DE-BY", of("DE", "addr:state" to "Bavaria"))
        assertEquals("DE-BW", of("DE", "addr:state" to "Baden-Württemberg"))
        assertEquals("DE-TH", of("DE", "addr:state" to "Thüringen"))
        assertEquals("DE-NW", of("DE", "addr:state" to "Nordrhein-Westfalen"))
        assertEquals("DE-HH", of("DE", "addr:state" to "Freie und Hansestadt Hamburg"))
        assertEquals("DE-SN", of("DE", "ISO3166-2" to "DE-SN"))
        // a German postcode names no state: Munich's 80331 says nothing
        assertNull(of("DE", "addr:postcode" to "80331"))
        assertNull(of("DE", "addr:state" to "Oberbayern"))
    }

    @Test
    fun aFrenchDepartmentByItsPostcode() {
        assertEquals("FR-67", of("FR", "addr:postcode" to "67000"))
        assertEquals("FR-57", of("FR", "addr:postcode" to "57000"))
        assertEquals("FR-75", of("FR", "addr:postcode" to "75001"))
        assertEquals("FR-01", of("FR", "addr:postcode" to "01000"))
        // Corsica's two departments share 20; oversea's have three digits; neither is said by two
        assertNull(of("FR", "addr:postcode" to "20000"))
        assertNull(of("FR", "addr:postcode" to "97400"))
        assertNull(of("FR", "addr:postcode" to "6700"))
    }

    @Test
    fun aBritishNationByItsPostcodesArea() {
        assertEquals("GB-SCT", of("GB", "addr:postcode" to "EH1 1BB"))
        assertEquals("GB-SCT", of("GB", "addr:postcode" to "G12 8QQ"))
        assertEquals("GB-SCT", of("GB", "addr:postcode" to "ze1 0aa"))
        assertEquals("GB-SCT", of("GB", "addr:postcode" to "TD1 1AA"))
        assertEquals("GB-NIR", of("GB", "addr:postcode" to "BT1 5GS"))
        // Gloucester's GL and Guildford's GU are not Glasgow's G
        assertEquals("GB-EAW", of("GB", "addr:postcode" to "GL1 2AB"))
        assertEquals("GB-EAW", of("GB", "addr:postcode" to "SW1A 1AA"))
        assertEquals("GB-EAW", of("GB", "addr:postcode" to "CF10 1EP"))
        // the districts across the border, and the Channel Islands, which are not Britain
        assertNull(of("GB", "addr:postcode" to "TD15 1AA"))
        assertNull(of("GB", "addr:postcode" to "TD12 4AA"))
        assertNull(of("GB", "addr:postcode" to "GY1 1AA"))
        assertEquals("GB-SCT", of("GB", "addr:state" to "Scotland"))
    }

    /**
     * "UK", as many write Britain's code (the app maintainer, 18 November 2026, batch 4 B17), is GB: the place's
     * country, so its chains are Britain's, and its nation, so Edinburgh keeps Scotland's days.
     */
    @Test
    fun ukIsBritain() {
        assertEquals("GB", PlaceCountry.code("UK"))
        assertEquals("GB", PlaceCountry.code(" uk"))
        assertEquals("GB", PlaceCountry.of(mapOf("addr:country" to "UK"), around = "IE"))
        assertEquals("GB", PlaceCountry.of(emptyMap(), around = "UK"))
        val edinburgh = mapOf("addr:country" to "UK", "addr:postcode" to "EH1 1YZ")
        val country = PlaceCountry.of(edinburgh, around = null)
        assertEquals("GB-SCT", PlaceCountry.subdivision(edinburgh, country))
        assertEquals("GB-SCT", of("UK", "addr:postcode" to "EH1 1YZ"))
        assertEquals("GB-NIR", of("UK", "addr:state" to "Northern Ireland"))
        assertNotNull(PublicHolidays.forCountry(country!!, PlaceCountry.subdivision(edinburgh, country))?.holiday(LocalDate.of(2026, 1, 2)))
        // Greggs, Britain's alone (which of its three entries, a tie, is another matter)
        assertEquals("Q3403981", Brands.forTags(mapOf("amenity" to "fast_food", "name" to "Greggs"), "UK")?.wikidata)
        assertEquals("Q3403981", Brands.forTags(mapOf("amenity" to "fast_food", "name" to "Greggs", "addr:country" to "UK"), null)?.wikidata)
    }

    /**
     * An `ISO3166-2` tag says the place's state or nation only where the calendar tells it apart (the app maintainer, 18
     * November 2026, batch 4 B21): Edinburgh's council area, GB-EDH, gave England's days, and the Grand Est region,
     * FR-GES, lost Alsace-Moselle's. Any other code is passed by, and the postcode says.
     */
    @Test
    fun anIsoCodeSaysOnlyWhatTheCalendarTellsApart() {
        assertEquals("GB-SCT", of("GB", "ISO3166-2" to "GB-EDH", "addr:postcode" to "EH1 1YZ"))
        assertEquals("FR-67", of("FR", "ISO3166-2" to "FR-GES", "addr:postcode" to "67000"))
        assertNull(of("GB", "ISO3166-2" to "GB-EDH"))
        assertNull(of("ES", "ISO3166-2" to "ES-CT"))
        assertEquals("GB-NIR", of("GB", "ISO3166-2" to "GB-NIR"))
        assertEquals("FR-6AE", of("FR", "ISO3166-2" to "FR-6AE"))
        assertEquals("DE-BY", of("DE", "ISO3166-2" to "DE-BY", "addr:state" to "Berlin"))
        val easterMonday = LocalDate.of(2026, 4, 6)
        assertNull(PublicHolidays.forCountry("GB", of("GB", "ISO3166-2" to "GB-EDH", "addr:postcode" to "EH1 1YZ"))?.holiday(easterMonday))
        val goodFriday = LocalDate.of(2026, 4, 3)
        assertEquals("Good Friday", PublicHolidays.forCountry("FR", of("FR", "ISO3166-2" to "FR-GES", "addr:postcode" to "67000"))?.holiday(goodFriday)?.name)
    }

    @Test
    fun onlyInItsOwnCountry() {
        assertNull("a French postcode in Germany", of("DE", "addr:postcode" to "67000"))
        assertNull("a state of another country", of("FR", "ISO3166-2" to "DE-BY"))
        assertNull("no country, no state", of(null, "addr:state" to "Bayern"))
        assertNull("a country whose days are one", of("NL", "addr:postcode" to "1012 AB"))
    }

    @Test
    fun theStatesCalendarKeepsItsDays() {
        // what the tags give is what the calendar takes
        val epiphany = LocalDate.of(2026, 1, 6)
        assertEquals("Epiphany", PublicHolidays.forCountry("DE", of("DE", "addr:state" to "Bayern"))?.holiday(epiphany)?.name)
        assertNull(PublicHolidays.forCountry("DE", of("DE", "addr:state" to "Berlin"))?.holiday(epiphany))
        val easterMonday = LocalDate.of(2026, 4, 6)
        assertNull(PublicHolidays.forCountry("GB", of("GB", "addr:postcode" to "EH1 1BB"))?.holiday(easterMonday))
        assertEquals("Easter Monday", PublicHolidays.forCountry("GB", of("GB", "addr:postcode" to "CF10 1EP"))?.holiday(easterMonday)?.name)
    }
}
