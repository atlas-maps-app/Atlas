package app.atlas.guide

import app.atlas.model.Landmark
import app.atlas.model.ManeuverType
import app.atlas.model.Mode
import app.atlas.model.Place
import app.atlas.model.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Choosing the landmark that makes a turn easy, and saying it. */
class LandmarksTest {

    /** East 600 m, then the maneuver at 600 m. */
    private fun base(mode: Mode = Mode.CAR) = Sketch(mode, speed = 10.0).leg(90.0, 600.0, "Rozengracht")

    private fun say(sk: Sketch, next: Leg, lookup: FeatureLookup? = null, mode: Mode = Mode.CAR): String {
        val r = sk.leg(next).route()
        val index = RouteIndex(r)
        val lm = Landmarks(lookup)
        val ref = lm.forStep(index, 1, mode.group)
        val book = Phrasebook.of("en")
        return book.withLandmark(book.instruction(r.steps[1]), ref?.let { lm.phrase(it, book) }).text
    }

    private fun place(at: app.atlas.geo.LatLon, vararg tags: Pair<String, String>) = Place(null, at, tags.toMap())

    /** A point [east] and [north] metres from the turn at 600 m. */
    private fun nearTurn(sk: Sketch, east: Double, north: Double): app.atlas.geo.LatLon =
        app.atlas.geo.LocalFrame(sk.at(600.0)).toLatLon(east, north)

    @Test fun turnLeftAfterTheBridge() {
        val sk = base()
        val bridge = Landmark("bridge", null, sk.at(560.0))
        assertEquals("Turn left onto Prinsengracht after the bridge", say(sk, Leg(0.0, 300.0, "Prinsengracht", landmarks = listOf(bridge))))
    }

    @Test fun atTheTrafficLightsTurnRight() {
        val sk = base()
        val lights = place(sk.at(598.0), "highway" to "traffic_signals")
        assertEquals("Turn right onto Prinsengracht at the traffic lights", say(sk, Leg(180.0, 300.0, "Prinsengracht"), { _, _ -> listOf(lights) }))
    }

    @Test fun turnRightAtTheChurch() {
        val sk = base()
        val church = place(nearTurn(sk, -22.0, -25.0), "amenity" to "place_of_worship", "religion" to "christian", "name" to "Westerkerk")
        val cafe = place(nearTurn(sk, -10.0, 15.0), "amenity" to "cafe", "name" to "'t Smalle")
        assertEquals("Turn right onto Prinsengracht at the church", say(sk, Leg(180.0, 300.0, "Prinsengracht"), { _, _ -> listOf(cafe, church) }))
    }

    @Test fun aBigBrandStoreIsNamed() {
        val sk = base()
        val shop = place(nearTurn(sk, -15.0, 20.0), "shop" to "supermarket", "brand" to "Albert Heijn", "name" to "Albert Heijn Rozengracht")
        assertEquals("Turn left onto Marnixstraat at Albert Heijn", say(sk, Leg(0.0, 300.0, "Marnixstraat"), { _, _ -> listOf(shop) }))
    }

    @Test fun theSecondTrafficLightsAreCounted() {
        val sk = base()
        val lights = listOf(
            place(sk.at(300.0), "highway" to "traffic_signals"),
            place(sk.at(310.0), "highway" to "traffic_signals"), // same junction, other approach
            place(sk.at(599.0), "highway" to "traffic_signals"),
        )
        assertEquals("Turn right onto Prinsengracht at the 2nd traffic lights", say(sk, Leg(180.0, 300.0, "Prinsengracht"), { p, r -> lights.filter { app.atlas.geo.Geo.distance(it.location, p) <= r } }))
    }

    @Test fun keepLeftAtTheForkTowardsHaarlemAndTakeTheSecondExit() {
        assertEquals("Keep left towards Haarlem", say(base(), Leg(70.0, 300.0, null, type = ManeuverType.KEEP, turn = Turn.SLIGHT_LEFT, destinations = "Haarlem")))
        assertEquals(
            "Enter the roundabout and take the 2nd exit onto Haarlemmerweg",
            say(base(), Leg(45.0, 300.0, "Haarlemmerweg", type = ManeuverType.ROUNDABOUT, exit = 2)),
        )
    }

    @Test fun nothingUsefulNearbyMeansNoLandmark() {
        val sk = base()
        val far = place(nearTurn(sk, -100.0, 200.0), "amenity" to "place_of_worship", "religion" to "christian")
        val bench = place(nearTurn(sk, -5.0, 5.0), "amenity" to "bench")
        val r = sk.leg(Leg(180.0, 300.0, "Prinsengracht")).route()
        assertNull(Landmarks { _, _ -> listOf(far, bench) }.forStep(RouteIndex(r), 1, ModeGroup.CAR))
        assertNull(Landmarks().forStep(RouteIndex(r), 0, ModeGroup.CAR))
    }

    @Test fun theSideOfTheLandmarkIsKnown() {
        val sk = base()
        val station = place(sk.beside(560.0, -25.0), "railway" to "station", "name" to "Centraal")
        val r = sk.leg(Leg(0.0, 300.0, "Prinsengracht")).route()
        val ref = Landmarks { _, _ -> listOf(station) }.forStep(RouteIndex(r), 1, ModeGroup.CAR)!!
        assertEquals("station", ref.kind)
        assertEquals(Side.LEFT, ref.side)
        assertEquals(Relation.AFTER, ref.relation)
    }

    @Test fun aPlaceOfWorshipOfNoKnownReligionIsCalledByItsName() {
        assertEquals("named" to "De Krijtberg", Landmarks.classify(Landmark("place_of_worship", "De Krijtberg", WESTERKERK)))
        assertNull(Landmarks.classify(Landmark("place_of_worship", null, WESTERKERK)))
        val sk = base()
        val chapel = Landmark("place_of_worship", "De Krijtberg", nearTurn(sk, -6.0, -12.0))
        assertEquals("Turn right onto Prinsengracht at De Krijtberg", say(sk, Leg(180.0, 300.0, "Prinsengracht", landmarks = listOf(chapel))))
    }

    @Test fun theLandmarkOnTheCornerYouTurnRoundWins() {
        // two petrol stations of equal standing, one on each side of the junction
        val left = { sk: Sketch -> place(nearTurn(sk, -12.0, 14.0), "amenity" to "fuel", "brand" to "Shell") }
        val right = { sk: Sketch -> place(nearTurn(sk, -12.0, -14.0), "amenity" to "fuel", "brand" to "Esso") }
        val a = base()
        assertEquals("Turn left onto Marnixstraat at Shell", say(a, Leg(0.0, 300.0, "Marnixstraat"), { _, _ -> listOf(right(a), left(a)) }))
        val b = base()
        assertEquals("Turn right onto Marnixstraat at Esso", say(b, Leg(180.0, 300.0, "Marnixstraat"), { _, _ -> listOf(left(b), right(b)) }))
    }

    @Test fun classifiesTagsAndRouterLandmarks() {
        assertEquals("traffic_signals" to null, Landmarks.classify(Landmark("traffic_lights", null, WESTERKERK)))
        assertEquals("mosque" to null, Landmarks.classify(Landmark("place_of_worship", null, WESTERKERK, tags = mapOf("religion" to "muslim"))))
        assertEquals("named" to "Shell", Landmarks.classify(place(WESTERKERK, "amenity" to "fuel", "brand" to "Shell")))
        assertEquals("fuel" to null, Landmarks.classify(place(WESTERKERK, "amenity" to "fuel")))
        assertEquals("roundabout" to null, Landmarks.classify(place(WESTERKERK, "highway" to "mini_roundabout")))
        assertNull(Landmarks.classify(place(WESTERKERK, "shop" to "clothes", "name" to "Zara")))
    }
}
