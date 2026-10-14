package app.atlas.guide

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.LocalFrame
import app.atlas.model.Fix
import app.atlas.model.Mode
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime

/** The Ears-only mode: the world by ear, without a route. */
class EarsOnlyTest {
    private val here = WESTERKERK
    private val frame = LocalFrame(here)
    private val t0 = Instant.parse("2026-10-13T15:00:00Z")
    private val walking = GuideSettings(mode = Mode.WALK)

    private fun at(east: Double, north: Double) = frame.toLatLon(east, north)
    private var nextId = 1L
    private fun place(at: LatLon, vararg tags: Pair<String, String>) = Place(OsmId(OsmType.NODE, nextId++), at, tags.toMap())
    private fun fix(p: LatLon = here, t: Instant = t0, bearing: Double? = 0.0) = Fix(p, t, 5.0, 1.3, bearing)

    // The board: facing north, Café Molen 60 m slightly right, a crossing 22 m ahead, a tram stop 150 m left, the park gate 110 m behind right.
    private val cafe = place(at(25.0, 55.0), "amenity" to "cafe", "name" to "Café Molen")
    private val crossing = place(at(0.0, 22.0), "highway" to "crossing", "crossing" to "traffic_signals")
    private val tram = place(at(-150.0, 5.0), "railway" to "tram_stop", "name" to "Westermarkt")
    private val gate = place(at(60.0, -90.0), "leisure" to "park", "name" to "Park gate")
    private val bench = place(at(3.0, 3.0), "amenity" to "bench")
    private val places = listOf(bench, gate, tram, crossing, cafe)

    @Test fun aroundMeCallsOutTheNotablePlacesFromWhereTheyAre() {
        val ears = EarsOnly(walking)
        val cues = ears.aroundMe(fix(), 0.0, places).map { it as Cue.Speak }
        assertEquals(
            listOf(
                "Crossing, 20 metres, ahead",
                "Café Molen, 60 metres, slightly right",
                "Westermarkt, 150 metres, on your left",
                "Park gate, 110 metres, behind you, on the right",
            ),
            cues.map { it.text },
        )
        assertEquals(0f, cues[0].pan, 0.01f)
        assertTrue(cues[1].pan in 0.2f..0.6f)
        assertTrue(cues[2].pan < -0.9f)
        val radar = ears.nearby(fix(), 0.0, places)
        assertEquals("bench", radar.last().category)
        assertEquals(5, radar.size)
    }

    @Test fun theClockFaceAndCompassAreOtherWaysToSayDirections() {
        val ears = EarsOnly(walking.copy(directionStyle = DirectionStyle.CLOCK))
        assertEquals("at 1 o'clock", ears.direction(24.0, 0.0))
        assertEquals("at 9 o'clock", ears.direction(-88.0, 0.0))
        assertEquals("at 12 o'clock", ears.direction(-5.0, 0.0))
        assertEquals("at 6 o'clock", ears.direction(179.0, 0.0))
        // without a heading, compass points
        val cue = ears.aroundMe(fix(bearing = null), null, listOf(cafe)).single() as Cue.Speak
        assertEquals("Café Molen, 60 metres, northeast", cue.text)
    }

    @Test fun whereAmISaysTheStreetTheAddressTheWayYouFaceAndWhatIsNear() {
        val ears = EarsOnly(walking)
        val text = (ears.whereAmI(fix(), "Prinsengracht 279", "Prinsengracht", places, 0.0).single() as Cue.Speak).text
        assertEquals(
            "You are on Prinsengracht, near Prinsengracht 279, facing north. " +
                "Nearby: Crossing, 20 metres, ahead; Café Molen, 60 metres, slightly right; Westermarkt, 150 metres, on your left",
            text,
        )
        assertEquals("Your position is not known yet", (ears.whereAmI(fix(bearing = null), null, null, emptyList(), null).single() as Cue.Speak).text)
        val nl = EarsOnly(walking.copy(language = "nl"))
        assertEquals(
            "U bent op Prinsengracht, bij Prinsengracht 279, richting noord",
            (nl.whereAmI(fix(), "Prinsengracht 279", "Prinsengracht", emptyList(), 0.0).single() as Cue.Speak).text,
        )
    }

    @Test fun walkingAlongCallsOutCrossingsAheadAndPlacesAsYouPassThem() {
        val bakery = place(at(-12.0, 40.0), "shop" to "bakery")
        val cafe = place(at(15.0, 60.0), "amenity" to "cafe", "name" to "Café Molen")
        val opening = OpeningLookup { p, _ -> if (p.tags["shop"] == "bakery") LocalTime.of(19, 0) else null }
        val ears = EarsOnly(walking, opening)
        val heard = ArrayList<String>()
        val pans = ArrayList<Float>()
        for (i in 0..60) {
            val p = at(0.0, i * 1.3)
            for (c in ears.update(fix(p, t0.plusSeconds(i.toLong())), 0.0, listOf(crossing, bakery, cafe, bench))) {
                c as Cue.Speak
                heard += c.text
                pans += c.pan
            }
        }
        assertEquals(listOf("Crossing ahead, with signal", "Bakery on your left, open until 19:00", "Café Molen on your right"), heard)
        assertTrue(pans[1] < -0.5f && pans[2] > 0.5f)
        // once is enough: back again a minute later, nothing new
        assertTrue(ears.update(fix(at(0.0, 40.0), t0.plusSeconds(120)), 0.0, listOf(bakery)).isEmpty())
        // minimal verbosity: crossings only
        val minimal = EarsOnly(walking.copy(verbosity = Verbosity.MINIMAL))
        val said = (0..60).flatMap { i -> minimal.update(fix(at(0.0, i * 1.3), t0.plusSeconds(i.toLong())), 0.0, listOf(crossing, bakery)) }
        assertEquals(listOf("Crossing ahead, with signal"), said.map { (it as Cue.Speak).text })
    }

    @Test fun earsOnlySpeaksDutchGermanAndFrench() {
        val f = fix()
        assertEquals("Café Molen, 60 meter, iets naar rechts", (EarsOnly(walking.copy(language = "nl")).aroundMe(f, 0.0, listOf(cafe)).single() as Cue.Speak).text)
        assertEquals("Café Molen, 60 Meter, leicht rechts", (EarsOnly(walking.copy(language = "de")).aroundMe(f, 0.0, listOf(cafe)).single() as Cue.Speak).text)
        assertEquals("Café Molen, 60 mètres, légèrement à droite", (EarsOnly(walking.copy(language = "fr")).aroundMe(f, 0.0, listOf(cafe)).single() as Cue.Speak).text)
        assertEquals("Nothing nearby", (EarsOnly(walking.copy(language = "es")).aroundMe(f, 0.0, emptyList()).single() as Cue.Speak).text)
    }

    @Test fun categoriesAndImportance() {
        assertEquals("tram_stop", EarsOnly.category(tram))
        assertEquals("crossing", EarsOnly.category(crossing))
        assertEquals("bus_stop", EarsOnly.category(place(here, "highway" to "bus_stop")))
        assertEquals("entrance", EarsOnly.category(place(here, "entrance" to "main")))
        assertEquals("shop", EarsOnly.category(place(here, "shop" to "books")))
        assertEquals(null, EarsOnly.category(place(here, "building" to "yes")))
        assertTrue(EarsOnly.importance(tram, "tram_stop", 150.0) > EarsOnly.importance(bench, "bench", 5.0))
    }

    @Test fun theBeaconPullsTowardsTheTargetAndSaysWhenYouAreThere() {
        val target = at(0.0, 120.0)
        val beacon = Beacon(target, "Café Molen", walking, voiceEveryM = 50.0)
        // facing east: the target is on the left
        val first = beacon.update(fix(bearing = 90.0))
        val ping = first.filterIsInstance<Cue.Earcon>().single()
        assertEquals(EarconKind.BEACON, ping.kind)
        assertEquals(-1f, ping.pan, 0.01f)
        assertEquals(1.0f, ping.pitch, 0.05f)
        assertEquals("Café Molen, 120 metres, on your left", (first.filterIsInstance<Cue.Speak>().single()).text)
        // turning to face it: a higher ping and one long pulse
        val facing = beacon.update(fix(t = t0.plusSeconds(3), bearing = 5.0))
        assertEquals(1.5f, facing.filterIsInstance<Cue.Earcon>().single().pitch, 0.01f)
        assertEquals(HapticMeaning.STRAIGHT, facing.filterIsInstance<Cue.Haptic>().single().meaning)
        // walking there: a voice line every 50 m, pings faster when near
        val lines = ArrayList<String>()
        var pings = 0
        var arrived = false
        for (i in 1..100) {
            val cues = beacon.update(fix(at(0.0, i * 1.4), t0.plusSeconds(3L + i), 0.0))
            pings += cues.count { it is Cue.Earcon && it.kind == EarconKind.BEACON }
            lines += cues.filterIsInstance<Cue.Speak>().map { it.text }
            if (Cue.Arrived in cues) {
                arrived = true
                break
            }
        }
        assertTrue(arrived)
        assertEquals(listOf("Café Molen, 70 metres, ahead", "Café Molen, 20 metres, ahead", "You have arrived at Café Molen"), lines)
        assertTrue(pings > 40)
        assertTrue(beacon.arrived)
        assertTrue(beacon.update(fix(target)).isEmpty())
    }

    @Test fun beaconSoundsEncodeTheBearing() {
        assertEquals(1.5f, Beacon.pitch(0.0), 0.0f)
        assertEquals(1.25f, Beacon.pitch(15.1), 0.01f)
        assertEquals(0.75f, Beacon.pitch(180.0), 0.01f)
        assertTrue(Beacon.pitch(90.0) in 0.9f..1.1f)
        assertEquals(1f, Beacon.pan(90.0), 0.001f)
        assertEquals(-1f, Beacon.pan(-90.0), 0.001f)
        assertEquals(0f, Beacon.pan(null))
        assertEquals(2.0, Beacon.period(500.0), 0.0)
        assertEquals(0.6, Beacon.period(5.0), 0.0)
        assertTrue(Geo.distance(here, at(0.0, 120.0)) in 119.0..121.0)
    }
}
