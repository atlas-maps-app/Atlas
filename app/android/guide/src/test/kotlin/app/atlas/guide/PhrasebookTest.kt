package app.atlas.guide

import app.atlas.geo.LatLon
import app.atlas.geo.UnitSystem
import app.atlas.model.Lane
import app.atlas.model.Maneuver
import app.atlas.model.ManeuverType
import app.atlas.model.Step
import app.atlas.model.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/** Atlas's own phrases and quantities in four languages, and the osrm templates as the guide uses them. */
class PhrasebookTest {
    private val en = Phrasebook.of("en")
    private val nl = Phrasebook.of("nl")
    private val de = Phrasebook.of("de")
    private val fr = Phrasebook.of("fr")
    private val p = LatLon(52.37, 4.88)

    private fun step(type: ManeuverType, turn: Turn = Turn.STRAIGHT, street: String? = null, exit: Int? = null, destinations: String? = null, ref: String? = null) =
        Step(Maneuver(type, turn, exit, 0.0, 90.0), 1, p, 100.0, 10.0, street, ref, destinations)

    @Test fun metricDistancesRoundAsAVoiceSaysThem() {
        assertEquals("10 metres", en.distance(4.0))
        assertEquals("80 metres", en.distance(83.0))
        assertEquals("150 metres", en.distance(160.0))
        assertEquals("400 metres", en.distance(433.0))
        assertEquals("1 kilometre", en.distance(1040.0))
        assertEquals("1.5 kilometres", en.distance(1490.0))
        assertEquals("12 kilometres", en.distance(12_400.0))
        assertEquals("1 metre", en.quantity(Spoken("m", 1.0, 0)))
    }

    @Test fun imperialSpeaksFeetYardsAndFractionsOfAMile() {
        val us = Phrasebook.of("en", UnitSystem.IMPERIAL)
        assertEquals("400 feet", us.distance(122.0))
        assertEquals("a quarter mile", us.distance(420.0))
        assertEquals("half a mile", us.distance(800.0))
        assertEquals("three quarters of a mile", us.distance(1200.0))
        assertEquals("2.5 miles", us.distance(4000.0))
        assertEquals("in a quarter mile", "in " + us.distance(400.0, DistanceForm.IN))
        val uk = Phrasebook.of("en", UnitSystem.IMPERIAL_UK)
        assertEquals("200 yards", uk.distance(180.0))
        assertEquals("13 feet 6 inches", uk.height(4.115))
        assertEquals("In 200 yards, turn left", uk.inDistance(180.0, Instr("Turn left")).text)
    }

    @Test fun atSeaItIsCablesAndNauticalMiles() {
        val sea = Phrasebook.of("en", UnitSystem.NAUTICAL)
        assertEquals("100 metres", sea.distance(100.0))
        assertEquals("2 cables", sea.distance(370.0))
        assertEquals("half a mile", sea.distance(926.0))
        assertEquals("8 cables", sea.distance(1480.0))
        assertEquals("1.5 nautical miles", sea.distance(2800.0))
        assertEquals("In 2 cables, keep red 6 to starboard", sea.inDistance(370.0, Instr("Keep red 6 to starboard")).text)
        assertEquals("2 kabellengtes", Phrasebook.of("nl", UnitSystem.NAUTICAL).distance(370.0))
        assertEquals("In 2 Kabellängen", "In " + Phrasebook.of("de", UnitSystem.NAUTICAL).distance(370.0, DistanceForm.IN))
        assertEquals("135 feet", Phrasebook.of("en", UnitSystem.NAUTICAL, feet = true).height(41.15))
    }

    @Test fun distancesInDutchGermanAndFrench() {
        assertEquals("Over 200 meter, ga linksaf", nl.inDistance(200.0, nl.instruction(step(ManeuverType.TURN, Turn.LEFT))).text)
        assertEquals("In 200 Metern, links abbiegen", de.inDistance(200.0, de.instruction(step(ManeuverType.TURN, Turn.LEFT))).text)
        assertEquals("In einem Kilometer, links abbiegen", de.inDistance(1000.0, de.instruction(step(ManeuverType.TURN, Turn.LEFT))).text)
        assertEquals("Dans 200 mètres, tourner à gauche", fr.inDistance(200.0, fr.instruction(step(ManeuverType.TURN, Turn.LEFT))).text)
        assertEquals("1,5 kilometer", nl.distance(1500.0))
        assertEquals("1,5 kilomètre", fr.distance(1500.0))
    }

    @Test fun pluralsFollowEachLanguage() {
        val ru = Phrasebook.of("ru")
        assertEquals("1 метр", ru.quantity(Spoken("m", 1.0, 0)))
        assertEquals("3 метра", ru.quantity(Spoken("m", 3.0, 0)))
        assertEquals("5 метров", ru.quantity(Spoken("m", 5.0, 0)))
        assertEquals("21 метр", ru.quantity(Spoken("m", 21.0, 0)))
        val pl = Phrasebook.of("pl")
        assertEquals("2 metry", pl.quantity(Spoken("m", 2.0, 0)))
        assertEquals("12 metrów", pl.quantity(Spoken("m", 12.0, 0)))
        assertEquals("one", Plurals.category("fr", 1.5, 1))
        assertEquals("other", Plurals.category("en", 1.5, 1))
        assertEquals("other", Plurals.category("ja", 1.0, 0))
    }

    @Test fun durationsTimesAndPercentages() {
        assertEquals("1 minute", en.duration(50.0))
        assertEquals("1 hour 25 minutes", en.duration(5100.0))
        assertEquals("2 Stunden", de.duration(7200.0))
        assertEquals("14:32", en.time(Instant.parse("2026-10-13T14:32:00Z"), ZoneOffset.UTC))
        assertEquals("2:32 PM", Phrasebook.of("en", UnitSystem.IMPERIAL).time(Instant.parse("2026-10-13T14:32:00Z"), ZoneOffset.UTC))
        assertEquals("19:00", nl.timeOfDay(LocalTime.of(19, 0)))
        assertEquals("7 PM", Phrasebook.of("en", UnitSystem.IMPERIAL).timeOfDay(LocalTime.of(19, 0)))
        assertEquals("6 percent", en.percent(6))
        assertEquals("3.5 tonnes", en.tonnes(3.5))
        assertEquals("0.3 metres", en.margin(0.3))
    }

    @Test fun instructionsForEveryManeuver() {
        assertEquals("Turn left onto Singel", en.instruction(step(ManeuverType.TURN, Turn.LEFT, "Singel")).text)
        assertEquals("Make a sharp right onto Singel", en.instruction(step(ManeuverType.TURN, Turn.SHARP_RIGHT, "Singel")).text)
        assertEquals("Keep left towards Haarlem", en.instruction(step(ManeuverType.KEEP, Turn.SLIGHT_LEFT, destinations = "Haarlem")).text)
        assertEquals("Enter the roundabout and take the 2nd exit", en.instruction(step(ManeuverType.ROUNDABOUT, Turn.RIGHT, exit = 2)).text)
        assertEquals("Take the ramp towards Haarlem", en.instruction(step(ManeuverType.RAMP_OFF, Turn.SLIGHT_RIGHT, destinations = "Haarlem")).text)
        assertEquals("Take the ramp on the left towards Haarlem", en.instruction(step(ManeuverType.RAMP_OFF, Turn.SLIGHT_LEFT, destinations = "Haarlem")).text)
        assertEquals("Merge left onto A10", en.instruction(step(ManeuverType.MERGE, Turn.SLIGHT_LEFT, ref = "A10")).text)
        assertEquals("Make a U-turn", en.instruction(step(ManeuverType.U_TURN, Turn.U_TURN)).text)
        assertEquals("Turn right onto Prinsengracht", en.instruction(step(ManeuverType.END_OF_ROAD, Turn.RIGHT, "Prinsengracht")).text)
        assertEquals("Continue onto Leidsestraat", en.instruction(step(ManeuverType.CONTINUE, Turn.STRAIGHT, "Leidsestraat")).text)
        assertEquals("Take the ferry", en.instruction(step(ManeuverType.FERRY_BOARD)).text)
        assertEquals("Leave the ferry onto Buiksloterweg", en.instruction(step(ManeuverType.FERRY_LEAVE, street = "Buiksloterweg")).text)
        assertEquals("Enter the lock", en.instruction(step(ManeuverType.LOCK)).text)
        assertEquals("Pass under the bridge", en.instruction(step(ManeuverType.BRIDGE)).text)
        assertEquals("Head east on Westerstraat", en.instruction(step(ManeuverType.DEPART, street = "Westerstraat")).text)
        assertEquals("You have arrived at your destination, on the left", en.instruction(step(ManeuverType.ARRIVE), StepContext(arrivalSide = Side.LEFT)).text)
        assertEquals("You will arrive at your 2nd destination", en.instruction(step(ManeuverType.WAYPOINT), StepContext(upcoming = true, legIndex = 1, legCount = 3)).text)
        assertEquals("Left", en.instruction(step(ManeuverType.TURN, Turn.LEFT, "Singel"), StepContext(short = true)).text)
    }

    @Test fun linkedAndNowAndContinue() {
        val a = Instr("Turn right onto Marnixstraat")
        val b = Instr("Turn left onto Elandsgracht")
        assertEquals("Turn right onto Marnixstraat, then immediately turn left onto Elandsgracht", en.linked(a, b, 30.0, true).text)
        assertEquals("Turn right onto Marnixstraat, then, in 150 metres, turn left onto Elandsgracht", en.linked(a, b, 150.0, false).text)
        assertEquals("Turn right onto Marnixstraat, then turn left onto Elandsgracht", en.linked(a, b, null, false).text)
        assertEquals("Now turn left onto Elandsgracht", en.now(b).text)
        assertEquals("Continue on A10 for 3 kilometres", en.continueFor(3000.0, "A10").text)
        assertEquals("Continue straight for 3 kilometres", en.continueFor(3000.0, null).text)
        assertEquals("Head north on A10 for 6 kilometres", en.departFor(step(ManeuverType.DEPART, ref = "A10").copy(maneuver = Maneuver(ManeuverType.DEPART, bearingAfter = 0.0)), 6000.0).text)
        // a name keeps its capital inside a sentence
        assertEquals("In 200 metres, Vijzelstraat", en.inDistance(200.0, Instr("Vijzelstraat", startsWithName = true)).text)
    }

    @Test fun landmarksSitInTheSentenceTheWayEachLanguageSaysIt() {
        val ref = LandmarkRef("traffic_signals", null, Relation.AT, Side.AHEAD, 5.0, 10.0)
        val lm = Landmarks()
        assertEquals("Turn right onto Prinsengracht at the traffic lights", en.withLandmark(Instr("Turn right onto Prinsengracht"), lm.phrase(ref, en)).text)
        assertEquals("Ga rechtsaf naar Prinsengracht bij het verkeerslicht", nl.withLandmark(nl.instruction(step(ManeuverType.TURN, Turn.RIGHT, "Prinsengracht")), lm.phrase(ref, nl)).text)
        assertEquals("An der Ampel rechts abbiegen auf Prinsengracht", de.withLandmark(de.instruction(step(ManeuverType.TURN, Turn.RIGHT, "Prinsengracht")), lm.phrase(ref, de)).text)
        assertEquals("Tourner à droite sur Prinsengracht au feu", fr.withLandmark(fr.instruction(step(ManeuverType.TURN, Turn.RIGHT, "Prinsengracht")), lm.phrase(ref, fr)).text)
        val bridge = LandmarkRef("bridge", null, Relation.AFTER, Side.AHEAD, 40.0, 8.0)
        assertEquals("after the bridge", lm.phrase(bridge, en))
        assertEquals("na de brug", lm.phrase(bridge, nl))
        assertEquals("at the 2nd traffic lights", lm.phrase(ref.copy(nth = 2), en))
        assertEquals("at Albert Heijn", lm.phrase(LandmarkRef("named", "Albert Heijn", Relation.AT, Side.LEFT, 20.0, 5.0), en))
    }

    @Test fun lanesAndMarks() {
        val keepRight = listOf(Lane(setOf(Turn.STRAIGHT), false), Lane(setOf(Turn.STRAIGHT), false), Lane(setOf(Turn.RIGHT), true))
        assertEquals("Keep right", en.lanes(keepRight))
        assertEquals("Rechts aanhouden", nl.lanes(keepRight))
        assertEquals(null, en.lanes(keepRight.map { it.copy(valid = true) }))
        assertEquals("red 6", en.markName(mapOf("seamark:buoy_lateral:colour" to "red", "seamark:name" to "6"), null))
        assertEquals("the green buoy", en.markName(mapOf("seamark:buoy_lateral:colour" to "green"), null))
        assertEquals("de groene boei", nl.markName(mapOf("seamark:buoy_lateral:colour" to "green"), null))
    }

    @Test fun everyLanguageHasEveryPhraseOrFallsBackToEnglish() {
        for (lang in listOf("nl", "de", "fr")) {
            val book = Phrasebook.of(lang)
            assertTrue(lang, book.hasOwnPhrases)
            for (key in listOf("dest_left", "off_route", "rerouted", "climb", "bridge_clear", "lock_vhf", "crossing_signals", "hgv_conflict", "report.jam")) {
                assertTrue("$lang lacks $key", book.has(key))
            }
        }
        val es = Phrasebook.of("es")
        assertFalse(es.hasOwnPhrases)
        assertEquals("Steps ahead", es.phrase("steps"))
        assertEquals("200 metros", es.distance(200.0))
        assertEquals("en", Phrasebook.of("xx").code)
    }

    @Test fun eachTableNamesTheSameKeysAsEnglish() {
        val keys = { code: String ->
            val root = OsrmInstructions.readJson("atlas/$code.json")!!
            root.obj("phrases")!!.keys.filterNot { it.startsWith("unit.") }.toSet()
        }
        val english = keys("en")
        for (lang in listOf("nl", "de", "fr")) {
            val own = keys(lang)
            assertEquals("$lang misses", emptySet<String>(), english - own - setOf("now"))
            assertEquals("$lang has extra", emptySet<String>(), own - english - setOf("arrive_upcoming", "arrive_upcoming_left", "arrive_upcoming_right"))
        }
    }
}
