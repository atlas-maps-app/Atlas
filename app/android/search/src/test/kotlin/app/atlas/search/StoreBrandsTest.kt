package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brands
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
 * chemist named "Etos" among Italian addresses is no chain's, though a chain trades under that name in the Netherlands;
 * where nothing around names a country, the country the store's region is of says, else a name alone is a worldwide
 * chain's only. ("Farmacia", the Ukrainian and the Croatian chains' name, is a pharmacy's own word in Spanish: by
 * the name alone no chain's anywhere since batch 4 B16.)
 */
class StoreBrandsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val amsterdam = LatLon(52.3745, 4.8838)
    private val santiago = LatLon(-33.4372, -70.6506)
    private val rome = LatLon(41.9028, 12.4964)
    private val london = LatLon(51.5136, -0.0890)
    private val etos = mapOf("shop" to "chemist", "name" to "Etos")
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
        val roman = place(rome, etos)
        store(street(rome, "IT") + roman).use { s -> assertNull(s.get(roman.id!!)!!.brand) }
        val dutch = place(amsterdam, etos)
        store(street(amsterdam, "NL") + dutch).use { s -> assertEquals("etos-994f1e", s.get(dutch.id!!)!!.brand) }
    }

    /**
     * A chain also trades in a country where the store's own places name it by `brand:wikidata` or `brand` (the app
     * maintainer, 18 November 2026, batch 4 D): Sandro's list names France alone, so its branches in Amsterdam named only
     * "Sandro" were no chain's beside one its mapper tagged. Without such a branch, or with one the store names in
     * Belgium, a name alone stays no chain's in the Netherlands.
     */
    @Test
    fun aChainTradesWhereTheStoresOwnPlacesNameIt() {
        val sandro = mapOf("shop" to "clothes", "name" to "Sandro")
        val near = LatLon(amsterdam.lat + 0.002, amsterdam.lon)
        val tagged = place(amsterdam, sandro + ("brand:wikidata" to "Q3472022"))
        val named = place(near, sandro)
        val namedNl = place(LatLon(near.lat, near.lon + 0.002), sandro + ("addr:country" to "NL"))
        store(street(amsterdam, "NL") + tagged + named + namedNl).use { s ->
            assertEquals("sandro-90f7b7", s.get(named.id!!)!!.brand)
            assertEquals("sandro-90f7b7", s.get(namedNl.id!!)!!.brand)
        }
        val alone = place(near, sandro)
        store(street(amsterdam, "NL") + alone).use { s -> assertNull(s.get(alone.id!!)!!.brand) }
        val belgian = place(amsterdam, sandro + ("brand" to "Sandro") + ("addr:country" to "BE"))
        val dutch = place(near, sandro)
        store(street(amsterdam, "NL") + belgian + dutch).use { s -> assertNull(s.get(dutch.id!!)!!.brand) }
    }

    /**
     * By `brand` a store's place names a chain only where one chain bears that name, by `brand:wikidata` that item's
     * alone (the app maintainer, 18 November 2026, batch 5 B10): `brand=Coop` in a Belgian store made each of the
     * eleven chains named Coop trade in Belgium, so a supermarket there named only "Coop" became Coop Italia's.
     */
    @Test
    fun aNameManyChainsBearNamesNoneOfThem() {
        val brussels = LatLon(50.8467, 4.3525)
        val near = LatLon(brussels.lat + 0.002, brussels.lon)
        val coop = mapOf("shop" to "supermarket", "name" to "Coop")
        val tagged = place(brussels, coop + ("brand" to "Coop"))
        val named = place(near, coop)
        store(street(brussels, "BE") + tagged + named).use { s -> assertNull(s.get(named.id!!)!!.brand) }
        // by its item, Coop Switzerland's, the supermarket named only "Coop" is that item's
        val item = place(brussels, coop + ("brand:wikidata" to "Q432564"))
        val swiss = place(near, coop)
        store(street(brussels, "BE") + item + swiss).use { s -> assertEquals("coop-36e991", s.get(swiss.id!!)!!.brand) }
    }

    /**
     * What the store's own places bring back or change over the fixtures written as one store, as a pack is: the
     * places whose chain in the store is not the one their tags give in the Netherlands, a chain whose list leaves the
     * Netherlands out matched by name alone. Printed for the report, by name; Hope, Pastini, MOS and Black, which their
     * own sites show are not those chains', never.
     */
    @Test
    fun whatTheStoresOwnPlacesBringBackOverTheFixtures() {
        val back = sortedMapOf<String, Int>()
        PlaceStore.open(File(tmp.root, "fixtures.db")).use { s ->
            s.writer(regionName = "Amsterdam").use { w ->
                for (f in listOf(Fixtures.ams, Fixtures.eat, Fixtures.errands)) f.bufferedReader(Charsets.UTF_8, 1 shl 16).use { OverpassImport.read(it, w) }
                w.finish()
            }
            for (r in s.inBox(BBox.around(Fixtures.westerkerk, 30_000.0))) {
                val listed = app.atlas.places.Brands.forTags(r.tags, "NL")?.id
                if (r.brand == listed) continue
                val site = r.tags["website"] ?: r.tags["contact:website"] ?: "-"
                back.merge("${r.tags["name"]} (${r.brand ?: "none"}, by its tags ${listed ?: "none"}, ${r.tags["addr:street"] ?: "-"}, $site)", 1, Int::plus)
            }
        }
        println("brought back or changed by the store's own places: ${back.size} names, ${back.values.sum()} places")
        back.forEach { (k, n) -> println("  $n x $k") }
        for (wrong in listOf("hope", "pastini", "mos", "black")) {
            assertTrue(wrong, back.keys.none { it.substringBefore(" (").lowercase() == wrong })
        }
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
        val dutch = place(amsterdam, etos)
        store(listOf(dutch), country = "NL").use { s -> assertEquals("etos-994f1e", s.get(dutch.id!!)!!.brand) }
        val roman = place(rome, etos)
        store(listOf(roman), country = "IT").use { s -> assertNull(s.get(roman.id!!)!!.brand) }
        // and where they name one around them, theirs comes first: a border's other side in a country's extract
        val across = place(amsterdam, etos)
        store(street(amsterdam, "NL") + across, country = "BE").use { s -> assertEquals("etos-994f1e", s.get(across.id!!)!!.brand) }
    }

    /** "UK" in the addresses around is Britain (the app maintainer, 18 November 2026, batch 4 B17): the chains there are its. */
    @Test
    fun ukInTheAddressesAroundIsBritain() {
        val greggs = place(london, mapOf("amenity" to "fast_food", "name" to "Greggs"))
        store(street(london, "UK") + greggs).use { s -> assertEquals("Greggs, Britain's alone", "Q3403981", s.get(greggs.id!!)!!.brand?.let(Brands::byId)?.wikidata) }
    }

    @Test
    fun anAnswerInMemoryIsReadInTheCountryItIsIn() {
        val dutch = place(amsterdam, jumbo)
        assertEquals("jumbo-1cf55d", MemoryPlaces(listOf(dutch), "NL").get(dutch.id!!)!!.brand)
        assertEquals("jumbo-99a0d7", MemoryPlaces(listOf(dutch + ("addr:country" to "CL")), "NL").get(dutch.id!!)!!.brand)
    }

    private operator fun Place.plus(tag: Pair<String, String>) = copy(tags = tags + tag)
}
