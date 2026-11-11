package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.CategoryTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a walk lists and says: its greens, none a pocket garden, each with about how long a lap takes, and every other
 * place worth walking to, a view, a monument, a statue, a mill.
 */
class WalkResultsTest {
    private val here = LatLon(52.37, 4.88)
    private val moment = Moment(Fixtures.saturday)
    private val walk = Intents.all.first { it.id == Intents.WALK }
    private val parks = CategoryTree.category("parks")!!

    private var ids = 0L

    private fun green(north: Double, across: Double?, vararg tags: Pair<String, String>): PlaceRecord {
        val at = Geo.destination(here, 0.0, north)
        return PlaceRecord.of(Place(OsmId(OsmType.WAY, ++ids), at, tags.toMap(), across?.let { BBox.around(at, it / 2) }))
    }

    private val big = green(500.0, 600.0, "leisure" to "park", "name" to "Het Grote Park")
    private val nameless = green(200.0, 300.0, "leisure" to "park")
    private val pocket = green(60.0, 40.0, "leisure" to "park", "name" to "Postzegelpark")
    private val hidden = green(120.0, 300.0, "leisure" to "garden", "access" to "private", "name" to "Besloten Tuin")
    private val point = green(90.0, null, "leisure" to "park", "name" to "Plantsoen")
    private val cafe = PlaceRecord.of(Place(OsmId(OsmType.NODE, 900), Geo.destination(here, 90.0, 80.0), mapOf("amenity" to "cafe", "name" to "Koffie")))
    private val pool = listOf(big, nameless, pocket, hidden, point, cafe)

    private fun spot(north: Double, vararg tags: Pair<String, String>) =
        PlaceRecord.of(Place(OsmId(OsmType.NODE, ++ids), Geo.destination(here, 0.0, north), tags.toMap()))

    private val view = spot(30.0, "tourism" to "viewpoint")
    private val statue = spot(40.0, "tourism" to "artwork", "artwork_type" to "statue", "name" to "Anne Frank")
    private val mill = spot(2500.0, "man_made" to "windmill", "name" to "De Gooyer")
    private val stone = spot(20.0, "historic" to "memorial", "memorial" to "stolperstein", "name" to "Salomon Vleesdraager")
    private val plaque = spot(25.0, "historic" to "memorial", "memorial" to "plaque", "name" to "Tótfalusi Kis")
    private val mural = spot(35.0, "tourism" to "artwork", "artwork_type" to "mural")

    /** A monument drawn as its own small outline: no green, so no room is asked of it. */
    private val monument = green(70.0, 30.0, "historic" to "memorial", "memorial" to "sculpture", "name" to "Homomonument")

    @Test
    fun aLapIsAboutTheWayRoundTheBoxAtAWalkingPace() {
        // 1.6 km by 500 m, a Vondelpark: about 3.3 km round
        val box = BBox.around(here, 800.0).let { BBox(it.west, here.lat - Geo.metresToDegreesLat(250.0), it.east, here.lat + Geo.metresToDegreesLat(250.0)) }
        assertEquals(3300.0, Laps.metres(box)!!, 50.0)
        assertEquals(40, Laps.minutes(Laps.metres(box)!!))
    }

    @Test
    fun aLapIsRoundedAsItIsSaid() {
        assertNull("a pocket garden", Laps.minutes(300.0))
        assertEquals(5, Laps.minutes(420.0))
        assertEquals(25, Laps.minutes(2000.0))
        assertEquals(135, Laps.minutes(10_900.0))
        assertNull("most of a day", Laps.minutes(40_000.0))
    }

    @Test
    fun aPlaceDrawnAsAPointHasNoSize() {
        assertNull(Laps.metres(null))
        assertNull(Laps.metres(BBox(4.88, 52.37, 4.88, 52.37)))
    }

    @Test
    fun aWalkTakesRoomyGreensAndOnesOfNoKnownSize() {
        assertTrue(walk.takes(big))
        assertTrue(walk.takes(nameless))
        assertTrue("may be big: nothing says not", walk.takes(point))
        assertFalse("a pocket garden", walk.takes(pocket))
        assertFalse("not open to walk in", walk.takes(hidden))
        assertFalse(walk.takes(cafe))
    }

    @Test
    fun aWalkGathersEveryPlaceWorthWalkingTo() {
        for (r in listOf(view, statue, mill, monument)) assertTrue(r.tags.toString(), walk.takes(r))
        for (r in listOf(stone, plaque, mural)) assertFalse("no walk: ${r.tags}", walk.takes(r))
    }

    @Test
    fun onlyAGreenIsGoneRoundIn() {
        assertTrue(walk.goesRound(big))
        assertTrue(walk.goesRound(point))
        assertFalse(walk.goesRound(monument))
        assertFalse(walk.goesRound(view))
    }

    @Test
    fun aMonumentSaysNoLapAndAParkStill() {
        val r = Browse.results(parks, pool + monument + view, here, moment, intent = walk)
        assertTrue(r.hits.first { it.record === monument }.tells.none { it.key == TellKey.LAP })
        assertTrue(r.hits.first { it.record === big }.tells.any { it.key == TellKey.LAP })
    }

    @Test
    fun aWalksKindsAreThoseAmongItsPlaces() {
        val r = Browse.results(parks, pool + monument + view + statue, here, moment, filter = walk.filter, intent = walk)
        assertEquals(r.hits.size, r.subcategories.sumOf { it.second })
        assertTrue(r.subcategories.map { it.first.id }.toString(), r.subcategories.size >= 3)
    }

    @Test
    fun theChipNamesAGreenThoughAViewIsNearer() {
        val p = Browse.pulse(walk, parks, pool + view + statue, here, moment)
        assertEquals(point, p.nearest)
        assertEquals("every place it takes is counted", 5, p.count)
    }

    @Test
    fun withNoGreenTheNearestPlaceToWalkToIsNamed() {
        val p = Browse.pulse(walk, parks, listOf(view, statue, mill, cafe), here, moment)
        assertEquals(statue, p.nearest)
        assertEquals(3, p.count)
    }

    @Test
    fun anIntentAskingNoRoomTakesWhatItsFilterTakes() {
        val picnic = Intents.all.first { it.id == "picnic" }
        assertTrue(picnic.takes(pocket))
    }

    @Test
    fun aWalksResultsAreItsOwnAndEachSaysALap() {
        val r = Browse.results(parks, pool, here, moment, intent = walk)
        val names = r.hits.map { it.record.name() }
        assertTrue(names.containsAll(listOf("Het Grote Park", "Plantsoen", null)))
        assertFalse("Postzegelpark" in names)
        assertFalse("Besloten Tuin" in names)
        val lap = r.hits.first { it.record === big }.tells.first { it.key == TellKey.LAP }
        assertEquals("≈ 25 min lap", lap.text)
        assertTrue("no size, no lap", r.hits.first { it.record === point }.tells.none { it.key == TellKey.LAP })
    }

    @Test
    fun aParksKindStillListsItsPocketGardensUnlapped() {
        val r = Browse.results(parks, pool, here, moment)
        val garden = r.hits.first { it.record === pocket }
        assertTrue(garden.tells.none { it.key == TellKey.LAP })
    }

    @Test
    fun aLapIsSaidInEachLanguage() {
        val now = Fixtures.saturday.toLocalDateTime()
        val box = big.place.bbox
        fun said(lang: String) = Tells.of(listOf(TellKey.LAP), emptyMap(), lang, now, box = box).single().text
        assertEquals("≈ 25 min lap", said("en"))
        assertEquals("≈ 25 min rondje", said("nl"))
        assertEquals("≈ 25 Min. Runde", said("de"))
        assertEquals("tour ≈ 25 min", said("fr"))
    }

    @Test
    fun anHourAndMoreIsSaidInHours() {
        val now = Fixtures.saturday.toLocalDateTime()
        // about 5.8 km round: seventy-odd minutes, said to the quarter hour
        val box = BBox.around(here, 925.0)
        assertEquals("≈ 1 h 15 lap", Tells.of(listOf(TellKey.LAP), emptyMap(), "en", now, box = box).single().text)
    }

    @Test
    fun aWalksFigureNamesTheNearestNamedParkAndCountsNothingOpen() {
        val p = Browse.pulse(walk, parks, pool, here, moment)
        // the nameless green is nearer, but there is a name to say
        assertEquals(point, p.nearest)
        assertEquals(TellKind.NEAREST, p.tell)
        assertEquals(3, p.count)
        assertEquals(0, p.open)
        assertEquals(p.minutes.toString(), p.value)
    }

    @Test
    fun whereNoGreenHasANameTheNearestStandsAnyway() {
        val p = Browse.pulse(walk, parks, listOf(nameless), here, moment)
        assertEquals(nameless, p.nearest)
    }

    @Test
    fun aWalkWithNoGreenAroundHasNoFigure() {
        val p = Browse.pulse(walk, parks, listOf(pocket, cafe), here, moment)
        assertEquals(TellKind.NONE, p.tell)
        assertNull(p.nearest)
    }

    @Test
    fun anIndoorIntentKeepsItsOrdinaryFigureOverItsOwnPlaces() {
        val coffee = Intents.all.first { it.id == "coffee" }
        val restaurants = CategoryTree.category("restaurants")!!
        val p = Browse.pulse(coffee, restaurants, pool, here, moment)
        assertEquals(1, p.count)
        assertEquals(cafe, p.nearest)
    }
}
