package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a region's store covers (the user, 30 October: "What exactly is happening across the app when youre online vs
 * offline?"): the places by its own data, not its box. The Netherlands' store's box, the country's rectangle, reaches
 * Antwerp and Aachen; there it holds nothing, so neither counts as covered, while a border town and the countryside do.
 */
class CoversTest {
    private var next = 1L

    private fun place(lat: Double, lon: Double, tags: Map<String, String>, bbox: BBox? = null) =
        Place(OsmId(OsmType.NODE, next++), LatLon(lat, lon), tags, bbox = bbox)

    /**
     * The Netherlands as its store holds it, in a few places: the country's own outline (a relation whose box is its
     * rectangle, and so the store's), cafés by the Westerkerk, an address in Vaals at the German border, a farm in
     * Drenthe, and a bench in Baarle-Nassau.
     */
    private val netherlands = MemoryPlaces(
        listOf(
            place(52.15, 5.30, mapOf("boundary" to "administrative", "admin_level" to "2", "name" to "Nederland"), BBox(3.31, 50.75, 7.23, 53.56)),
            place(52.3746, 4.8838, mapOf("amenity" to "cafe", "name" to "'t Smalle")),
            place(52.3741, 4.8846, mapOf("amenity" to "cafe", "name" to "Café Papeneiland")),
            place(50.7706, 6.0182, mapOf("addr:street" to "Maastrichterlaan", "addr:housenumber" to "10")),
            place(52.8920, 6.5310, mapOf("addr:street" to "Hoofdweg", "addr:housenumber" to "3")),
            place(51.4440, 4.9300, mapOf("amenity" to "bench")),
        ),
    )

    @Test
    fun antwerpAndAachenLieInTheCountrysBoxButAreNotCovered() {
        val antwerp = LatLon(51.2194, 4.4011)
        val aachen = LatLon(50.7762, 6.0838)
        // both inside the store's box: until 30 October each counted as covered
        assertTrue(antwerp in netherlands.bounds!!)
        assertTrue(aachen in netherlands.bounds!!)
        assertFalse(netherlands.covers(antwerp))
        assertFalse(netherlands.covers(aachen))
    }

    @Test
    fun theTownsAndTheCountrysideOfTheCountryAreCovered() {
        assertTrue(netherlands.covers(LatLon(52.3745, 4.8840)))
        // a kilometre from the farm, and from the address at the border
        assertTrue(netherlands.covers(LatLon(52.9010, 6.5310)))
        assertTrue(netherlands.covers(LatLon(50.7750, 6.0300)))
        // beyond its box, or with nothing of its own near: not
        assertFalse(netherlands.covers(LatLon(48.8566, 2.3522)))
        assertFalse(netherlands.covers(LatLon(52.6000, 5.4000)))
        assertFalse(MemoryPlaces(emptyList()).covers(LatLon(52.3745, 4.8840)))
    }

    @Test
    fun aBoxAcrossTheBorderIsCoveredOnlyInPart() {
        // a few hundred metres by the Westerkerk: whole
        assertTrue(netherlands.coversAll(BBox.around(LatLon(52.3745, 4.8840), 300.0)))
        // Vaals and Aachen beside it: the network completes it
        assertFalse(netherlands.coversAll(BBox.around(LatLon(50.7706, 6.0182), 5_000.0)))
    }
}
