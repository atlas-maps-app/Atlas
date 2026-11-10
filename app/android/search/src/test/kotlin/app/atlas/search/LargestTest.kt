package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.CategoryTree
import app.atlas.places.TagFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The largest greens: the words that ask for them in four languages, the order that lists them, how far round a huge
 * one is, and the region's largest read by their size (the user, 9 November: "Is it somewhere possible to see the
 * largest green areas in your country or around you?").
 */
class LargestTest {
    private val here = LatLon(52.37, 4.88)
    private var ids = 0L

    private fun parse(text: String, lang: String = "en") = QueryParser.parse(text, lang, Fixtures.saturday)

    /** The tags a query asks for, of the first of its parts that has any. */
    private fun kind(q: Query): TagFilter? {
        var out: TagFilter? = null
        q.walk { if (out == null && it is Query.Tags) out = it.filter }
        return out
    }

    private fun colours(q: Query): Int {
        var n = 0
        q.walk { if (it is Query.Coloured) n++ }
        return n
    }

    /** A place [north] metres north of here, an outline [across] metres wide and tall (a point where null). */
    private fun place(north: Double, across: Double?, vararg tags: Pair<String, String>, from: LatLon = here): Place {
        val at = Geo.destination(from, 0.0, north)
        return Place(OsmId(if (across == null) OsmType.NODE else OsmType.WAY, ++ids), at, tags.toMap(), across?.let { BBox.around(at, it / 2) })
    }

    @Test
    fun greenAreasAreEveryGreenInFourLanguages() {
        val park = mapOf("leisure" to "park", "name" to "Westerpark")
        val wood = mapOf("natural" to "wood", "name" to "Amsterdamse Bos")
        val national = mapOf("boundary" to "national_park", "name" to "Zuid-Kennemerland")
        val nameless = mapOf("natural" to "wood")
        val closed = mapOf("leisure" to "park", "access" to "private", "name" to "Besloten Tuin")
        val asked = listOf(
            "largest green areas" to "en", "biggest green spaces" to "en", "grootste groen" to "nl", "grootste groengebieden" to "nl",
            "größte Grünflächen" to "de", "les plus grands espaces verts" to "fr",
        )
        for ((text, lang) in asked) {
            val q = parse(text, lang).steps.single()
            assertTrue(text, q is Query.Sort && q.by == SortBy.LARGEST)
            val f = kind(q)!!
            assertTrue(text, f.matches(park) && f.matches(wood) && f.matches(national))
            assertFalse(text, f.matches(nameless) || f.matches(closed))
            // "green" is the greens here, not a colour
            assertEquals(text, 0, colours(q))
        }
    }

    @Test
    fun aColourBeforeAKindStaysItsColour() {
        val q = parse("groen restaurant", "nl").steps.single()
        assertEquals(1, colours(q))
        assertEquals("amenity=restaurant", kind(q)?.toText())
    }

    @Test
    fun nationalParksInFourLanguages() {
        val asked = listOf("largest national parks" to "en", "grootste nationale parken" to "nl", "größte Nationalparks" to "de", "les plus grands parcs nationaux" to "fr")
        for ((text, lang) in asked) {
            val q = parse(text, lang).steps.single()
            assertTrue(text, q is Query.Sort && q.by == SortBy.LARGEST)
            assertEquals(text, "boundary=national_park", kind(q)?.toText())
        }
    }

    @Test
    fun theCanalRingIsNoNatureReserve() {
        // Amsterdam's canal ring is a protected area for its culture (class 22): neither the category nor the typed words take it
        val canals = mapOf("boundary" to "protected_area", "protect_class" to "22", "heritage" to "1", "historic" to "heritage", "name" to "Grachtengordel van Amsterdam")
        val category = CategoryTree.category("nature_reserve")!!.filter
        assertFalse(category.matches(canals))
        assertTrue(category.matches(mapOf("boundary" to "protected_area", "protect_class" to "4", "name" to "Ilperveld")))
        assertTrue(category.matches(mapOf("boundary" to "national_park", "name" to "Nationaal Park Zuid-Kennemerland")))
        val typed = kind(parse("nature reserves", "en").steps.single())!!
        assertFalse(typed.toText(), typed.matches(canals) || typed.matches(canals - "protect_class"))
        assertTrue(typed.matches(mapOf("boundary" to "protected_area", "protect_class" to "4")))
        assertTrue(typed.matches(mapOf("leisure" to "nature_reserve")))
    }

    @Test
    fun largestListsTheGreensByTheirGroundAndAHugeOneSaysHowFarRound() {
        val parks = CategoryTree.category("parks")!!
        assertTrue(Order.LARGEST in Purposes.of(parks).orders)
        assertTrue(Order.LARGEST in Purposes.of(CategoryTree.category("nature_reserve")!!).orders)
        val small = PlaceRecord.of(place(200.0, 300.0, "leisure" to "park", "name" to "Klein"))
        val mid = PlaceRecord.of(place(2_000.0, 1_200.0, "leisure" to "park", "name" to "Middel"))
        val huge = PlaceRecord.of(place(30_000.0, 9_000.0, "leisure" to "park", "name" to "Groot"))
        val point = PlaceRecord.of(place(100.0, null, "leisure" to "park", "name" to "Punt"))
        val r = Browse.results(parks, listOf(small, point, huge, mid), here, Moment(Fixtures.saturday), order = Order.LARGEST)
        assertEquals(Order.LARGEST, r.order)
        // the largest first, one of no known size last
        assertEquals(listOf("Groot", "Middel", "Klein", "Punt"), r.hits.map { it.record.name("en") })
        // 9 km across, about 28 km round: most of a day's walk, so its lap says how far
        assertEquals("≈ 30 km lap", r.hits[0].tells.first { it.key == TellKey.LAP }.text)
        assertTrue(r.hits[1].tells.first { it.key == TellKey.LAP }.text!!.contains("min"))
        assertNull(r.hits[3].tells.firstOrNull { it.key == TellKey.LAP })
    }

    @Test
    fun aHugeLapIsToTheKilometreAndToFiveOverTwenty() {
        assertNull(Laps.km(3_000.0))
        assertEquals(16, Laps.km(16_200.0))
        assertEquals(30, Laps.km(28_300.0))
        assertNull(Laps.km(100.0))
    }

    @Test
    fun aStoreGivesItsLargestOfAKindByTheirBoxes() {
        PlaceStore.inMemory().use { s ->
            val parks = (1..30).map { i -> place(i * 100.0, 50.0 + i * 10, "leisure" to "park", "name" to "Park $i") }
            s.importPlaces(
                parks + listOf(
                    place(40_000.0, 6_000.0, "leisure" to "park", "name" to "Het Grootste"),
                    // larger, but a wood with no name is no green to list
                    place(10_000.0, 8_000.0, "natural" to "wood"),
                    place(12_000.0, 3_000.0, "natural" to "wood", "name" to "Het Bos"),
                ),
                listOf("en"), "test",
            )
            assertEquals(listOf("Het Grootste", "Park 30", "Park 29"), s.largest("leisure", "park", 3).map { it.name("en") })
            val greens = kind(parse("largest green areas").steps.single())!!
            assertEquals(listOf("Het Grootste", "Het Bos", "Park 30"), Largest.of(listOf(s), greens, 3).map { it.name("en") })
        }
    }

    @Test
    fun typedLargestIsOfTheRegionThatHoldsTheSearcher() {
        val paris = LatLon(48.86, 2.35)
        PlaceStore.inMemory().use { home ->
            PlaceStore.inMemory().use { away ->
                home.importPlaces(
                    listOf(
                        place(0.0, null, "amenity" to "cafe", "name" to "Thuis"),
                        place(3_000.0, 800.0, "leisure" to "park", "name" to "Thuispark"),
                        place(50_000.0, 4_000.0, "leisure" to "park", "name" to "Verderop"),
                    ),
                    listOf("en"), "home",
                )
                away.importPlaces(
                    listOf(place(0.0, null, "amenity" to "cafe", "name" to "Café", from = paris), place(2_000.0, 9_000.0, "leisure" to "park", "name" to "Bois", from = paris)),
                    listOf("en"), "away",
                )
                val q = parse("largest parks").steps.single()
                val r = OfflineEvaluator(Regions(listOf(home, away))).run(q, EvalContext(location = here, now = Fixtures.saturday))
                // the region holding the searcher, however far its largest lies; not another region on the phone
                assertEquals(listOf("Verderop", "Thuispark"), r.hits.map { it.record.name("en") })
            }
        }
    }
}
