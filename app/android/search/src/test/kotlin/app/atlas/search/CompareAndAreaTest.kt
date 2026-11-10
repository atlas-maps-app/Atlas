package app.atlas.search

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the readings of 4 November find: closed all of a day by the hours, taller than another place, kept out of a
 * town's centre, a count told by neighbourhood, and a way past its stops.
 */
class CompareAndAreaTest {
    private val heart = LatLon(52.3731, 4.8926)
    private var next = 0L

    private fun place(at: LatLon, vararg tags: Pair<String, String>) = Place(OsmId(OsmType.NODE, ++next), at, tags.toMap())
    private fun east(metres: Double, from: LatLon = heart) = Geo.destination(from, 90.0, metres)
    private fun ids(hits: List<Hit>) = hits.map { it.place.tags["name"] }.toSet()
    private fun parse(text: String, lang: String = "en") = QueryParser.parse(text, lang, Fixtures.saturday)
    private fun eval(places: List<Place>, text: String, lang: String = "en") =
        OfflineEvaluator(MemoryPlaces(places)).run(parse(text, lang).query, EvalContext(location = heart, now = Fixtures.saturday, limit = 100)).hits

    @Test
    fun closedOnADayGoesByTheHoursAndNotByTheirAbsence() {
        val museums = listOf(
            place(east(100.0), "tourism" to "museum", "name" to "Shut Mondays", "opening_hours" to "Tu-Su 10:00-17:00"),
            place(east(200.0), "tourism" to "museum", "name" to "Off Mondays", "opening_hours" to "Mo off; Tu-Su 10:00-17:00"),
            place(east(300.0), "tourism" to "museum", "name" to "Every day", "opening_hours" to "Mo-Su 10:00-17:00"),
            place(east(400.0), "tourism" to "museum", "name" to "Says nothing"),
        )
        assertEquals(setOf("Shut Mondays", "Off Mondays"), ids(eval(museums, "which museums are closed on monday")))
        assertEquals(setOf("Shut Mondays", "Off Mondays"), ids(eval(museums, "musea die op maandag dicht zijn", "nl")))
    }

    @Test
    fun tallerThanAnotherPlaceTakesThatPlacesHeight() {
        val places = listOf(
            place(east(50.0), "man_made" to "tower", "name" to "Westertoren", "height" to "87"),
            place(east(150.0), "building" to "yes", "name" to "Low", "height" to "50"),
            place(east(250.0), "building" to "yes", "name" to "High", "height" to "100"),
            place(east(350.0), "building" to "office", "name" to "Floors", "building:levels" to "40"),
            place(east(450.0), "building" to "yes", "name" to "Unsaid"),
        )
        assertEquals(setOf("High", "Floors"), ids(eval(places, "buildings taller than the westertoren")))
        // a place of that name nobody knows says no bound: nothing is vouched for
        assertTrue(eval(places, "buildings taller than the eiffeltoren").isEmpty())
    }

    @Test
    fun theCentreIsTheTownsQuarterCalledSo() {
        val places = listOf(
            place(heart, "place" to "city", "name" to "Amsterdam"),
            place(heart, "place" to "suburb", "name" to "Centrum"),
            place(east(300.0), "tourism" to "hotel", "name" to "Central"),
            place(east(4_000.0), "tourism" to "hotel", "name" to "Outer"),
        )
        val ctx = SearchContext(location = east(500.0), now = Fixtures.saturday)
        val engine = SearchEngine(MemoryPlaces(places))
        fun names(text: String, lang: String = "en") = runBlocking { engine.search(text, ctx.copy(lang = lang)) }.places.map { it.hit.tags["name"] }.toSet()
        assertEquals(setOf("Outer"), names("hotel not in the city centre"))
        assertEquals(setOf("Outer"), names("hotel buiten het centrum", "nl"))
        assertEquals(setOf("Central"), names("hotel in the centre"))
    }

    @Test
    fun aCountPerNeighbourhoodTellsEachItsOwn() {
        val west = east(-1_500.0)
        val mid = heart
        val far = east(1_500.0)
        val places = listOf(place(heart, "place" to "city", "name" to "Amsterdam")) +
            listOf(west to "Jordaan", mid to "Grachtengordel", far to "Plantage").map { (at, n) -> place(at, "place" to "neighbourhood", "name" to n) } +
            (1..3).map { place(east(40.0 * it, west), "amenity" to "bar", "name" to "W$it") } +
            place(east(30.0, far), "amenity" to "bar", "name" to "P1")
        val r = runBlocking { SearchEngine(MemoryPlaces(places)).search("how many bars per neighbourhood", SearchContext(location = heart, now = Fixtures.saturday)) }
        val count = r.items.first() as SearchItem.Count
        assertEquals(4, count.answer.total)
        assertEquals(listOf("Jordaan" to 3, "Plantage" to 1), count.answer.groups.map { it.label to it.count })
    }

    @Test
    fun aWayCallsAtItsStopsInTurn() {
        val places = listOf(
            place(east(2_000.0), "amenity" to "townhall", "name" to "Stadhuis Noord"),
            place(east(6_000.0), "amenity" to "library", "name" to "Bibliotheek Oost"),
        )
        val r = runBlocking { SearchEngine(MemoryPlaces(places)).search("route to bibliotheek oost via stadhuis noord", SearchContext(location = heart, now = Fixtures.saturday)) }
        val way = r.items.first() as SearchItem.Route
        assertEquals("Bibliotheek Oost", way.to.name)
        assertEquals(listOf("Stadhuis Noord"), way.via.map { it.name })
        // a stop nobody can find is no way: the words are searched as they are
        val lost = runBlocking { SearchEngine(MemoryPlaces(places)).search("route to bibliotheek oost via nergenshuizen", SearchContext(location = heart, now = Fixtures.saturday)) }
        assertTrue(lost.items.none { it is SearchItem.Route })
    }

    @Test
    fun addressesOneAfterTheOtherAreAWay() {
        val r = parse("prinsengracht 263, 500, 1012").route!!
        assertEquals("prinsengracht 1012", r.to)
        assertEquals(listOf("prinsengracht 263", "prinsengracht 500"), r.via)
        assertEquals(listOf("26 manhattan street", "125 manhattan street"), parse("26 manhattan street, 125, 487").route!!.via)
        // lines of a bus or a tram, and kinds in a row, are no addresses
        for (t in listOf("bus 26, 125", "tram 2, 5", "cafe, bakery, atm", "open 9, 17", "kalverstraat 92, 1012 ab amsterdam", "damrak 1, amsterdam")) {
            assertEquals(t, null, parse(t).route)
        }
        // how the way keeps clear makes a way of a place, not of a kind ("cafés without tolls")
        assertTrue(parse("los angeles no highways").route!!.prefs!!.avoidMotorways)
        assertEquals(null, parse("toilets without steps").route)
        assertEquals(listOf("amersfoort", "hilversum"), parse("naar utrecht via amersfoort en hilversum", "nl").route!!.via)
    }
}
