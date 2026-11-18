package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A store's brands are those of the country of the map where its places are (the reviewer, 17 November, finding 9),
 * which the store's own places and addresses tell, as the app reads the regions on the phone: a supermarket tagged only
 * `brand=Jumbo` among Dutch addresses is the Dutch Jumbo, not the Chilean one, and its chain's search finds it; a
 * pharmacy named "Farmacia" among Italian addresses is no chain's, though a chain trades under that name in Ukraine;
 * where nothing around names a country, the country the store's region is of says, else a name alone is a worldwide
 * chain's only.
 */
class StoreBrandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val amsterdam = LatLon(52.3745, 4.8838)
    private val santiago = LatLon(-33.4372, -70.6506)
    private val rome = LatLon(41.9028, 12.4964)
    private val kyiv = LatLon(50.4501, 30.5234)
    private var next = 1L

    private fun place(at: LatLon, tags: Map<String, String>) = Place(OsmId(OsmType.NODE, next++), at, tags)

    /** A street of address points around [at] naming [country], as many in a city name theirs. */
    private fun street(at: LatLon, country: String) = (1..6).map { n ->
        place(LatLon(at.lat + n * 0.0003, at.lon + 0.0004), mapOf("addr:street" to "Hoofdstraat", "addr:housenumber" to "$n", "addr:country" to country))
    }

    /** A store of [places], written as an import writes them; [country] the one its region is of, if it is one's. */
    private fun store(places: List<Place>, country: String? = null): PlaceStore {
        val s = PlaceStore.open(File(tmp.root, "s$next.db"))
        s.writer(regionName = "test", country = country).use { w ->
            for (p in places) w.add(p)
            w.finish()
        }
        return s
    }

    private val jumbo = mapOf("shop" to "supermarket", "brand" to "Jumbo", "name" to "Jumbo")

    @Test
    fun aBrandTagIsTheChainOfTheCountryAround() {
        val dutch = place(amsterdam, jumbo)
        store(listOf(dutch) + street(amsterdam, "NL")).use { s ->
            assertEquals("jumbo-1cf55d", s.get(dutch.id!!)!!.brand)
            // the Dutch chain's search finds it
            assertTrue(s.byBrand(listOf("jumbo-1cf55d"), BBox.around(amsterdam, 1_000.0)).any { it.id == dutch.id })
        }
        val chilean = place(santiago, jumbo)
        store(listOf(chilean) + street(santiago, "CL")).use { s -> assertEquals("jumbo-99a0d7", s.get(chilean.id!!)!!.brand) }
    }

    @Test
    fun aNameAloneIsAChainOnlyWhereItTrades() {
        val farmacia = mapOf("amenity" to "pharmacy", "name" to "Farmacia")
        val roman = place(rome, farmacia)
        store(street(rome, "IT") + roman).use { s -> assertNull(s.get(roman.id!!)!!.brand) }
        val ukrainian = place(kyiv, farmacia)
        store(street(kyiv, "UA") + ukrainian).use { s -> assertEquals("farmacia-996e17", s.get(ukrainian.id!!)!!.brand) }
    }

    @Test
    fun whereNothingNamesACountryANameAloneIsAWorldwideChainsOnly() {
        val kiosk = place(amsterdam, mapOf("shop" to "kiosk", "name" to "Kiosk"))
        val burgers = place(amsterdam, mapOf("amenity" to "fast_food", "name" to "McDonald's"))
        store(listOf(kiosk, burgers)).use { s ->
            assertNull(s.get(kiosk.id!!)!!.brand)
            assertEquals("Q38076", s.get(burgers.id!!)!!.brand?.let { app.atlas.places.Brands.byId(it) }?.wikidata)
        }
    }

    @Test
    fun wherePlacesNameNoCountryTheRegionsOwnSays() {
        // a pack of one country's region (packer places --country): its places name none, the region does
        val farmacia = mapOf("amenity" to "pharmacy", "name" to "Farmacia")
        val ukrainian = place(kyiv, farmacia)
        store(listOf(ukrainian), country = "UA").use { s -> assertEquals("farmacia-996e17", s.get(ukrainian.id!!)!!.brand) }
        val roman = place(rome, farmacia)
        store(listOf(roman), country = "IT").use { s -> assertNull(s.get(roman.id!!)!!.brand) }
        // and where they name one around them, theirs comes first: a border's other side in a country's extract
        val across = place(kyiv, farmacia)
        store(street(kyiv, "UA") + across, country = "IT").use { s -> assertEquals("farmacia-996e17", s.get(across.id!!)!!.brand) }
    }

    @Test
    fun anAnswerInMemoryIsReadInTheCountryItIsIn() {
        val dutch = place(amsterdam, jumbo)
        assertEquals("jumbo-1cf55d", MemoryPlaces(listOf(dutch), "NL").get(dutch.id!!)!!.brand)
        assertEquals("jumbo-99a0d7", MemoryPlaces(listOf(dutch + ("addr:country" to "CL")), "NL").get(dutch.id!!)!!.brand)
    }

    private operator fun Place.plus(tag: Pair<String, String>) = copy(tags = tags + tag)
}
