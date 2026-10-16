package app.atlas.ui.mine

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.services.Units
import app.atlas.ui.mine.offline.PickerModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

private val T0 = Instant.parse("2026-10-14T12:00:00Z")

/** The barometer's dial: the degrees to a hectopascal, the words, and the trend against three hours ago. */
class BarometerTest {
    @Test
    fun theNeedleStandsUpAtStandardPressureAndTurnsThreeAndAHalfDegreesToTheHectopascal() {
        assertEquals(0.0, Barometer.angle(1013.25), 1e-9)
        assertEquals(-34.04, Barometer.angle(1004.0), 0.01)
        assertEquals(130.0, Barometer.angle(1200.0), 1e-9)
        assertEquals(-130.0, Barometer.angle(900.0), 1e-9)
    }

    @Test
    fun theWordIsTheOneNearestTheNeedle() {
        assertEquals(Barometer.Word.STORMY, Barometer.wordAt(-105.0))
        assertEquals(Barometer.Word.RAIN, Barometer.wordAt(-82.5))
        assertEquals(Barometer.Word.RAIN, Barometer.wordAt(-60.0))
        assertEquals(Barometer.Word.CHANGE, Barometer.wordAt(0.0))
        assertEquals(Barometer.Word.FAIR, Barometer.wordAt(30.0))
        assertEquals(Barometer.Word.FAIR, Barometer.wordAt(60.0))
    }

    @Test
    fun fallingFourHectopascalsInThreeHoursIsFallingAndTheSetHandRemembersWhereItWas() {
        val log = listOf(PressureReading(T0.minus(Duration.ofHours(3)), 1010.0), PressureReading(T0, 1006.0))
        val b = Barometer.of(log, T0)!!
        assertEquals(PressureTrend.FALLING, b.trend)
        assertEquals(1006.0, b.hPa, 0.0)
        assertEquals(Barometer.angle(1010.0), b.setHandDeg!!, 1e-9)
    }

    @Test
    fun aChangeUnderOneHectopascalIsSteadyAndOverIsRising() {
        fun trend(a: Double, b: Double) = Barometer.of(listOf(PressureReading(T0.minus(Duration.ofHours(3)), a), PressureReading(T0, b)), T0)!!.trend
        assertEquals(PressureTrend.STEADY, trend(1012.0, 1012.6))
        assertEquals(PressureTrend.RISING, trend(1012.0, 1013.5))
    }

    @Test
    fun withOneReadingThereIsNoTrendAndWithNoneTheForecastStandsInOrThereIsNoDial() {
        assertNull(Barometer.of(listOf(PressureReading(T0, 1000.0)), T0)!!.trend)
        val fallback = Barometer.of(emptyList(), T0, fallback = 1021.0)!!
        assertEquals(1021.0, fallback.hPa, 0.0)
        assertNull(fallback.trend)
        assertNull(Barometer.of(emptyList(), T0))
    }

    @Test
    fun aReadingFromTheFutureIsNotTheLatest() {
        val b = Barometer.of(listOf(PressureReading(T0.minusSeconds(60), 1001.0), PressureReading(T0.plusSeconds(600), 990.0)), T0)!!
        assertEquals(1001.0, b.hPa, 0.0)
    }
}

class PressureLogTest {
    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun readingsComeBackOldestFirstAndImpossibleOnesAreRefused() {
        val log = PressureLog(dir.newFile("p.csv"))
        log.add(1010.5, T0)
        log.add(1011.0, T0.plusSeconds(900))
        log.add(250.0, T0.plusSeconds(1800))
        log.add(Double.NaN, T0.plusSeconds(2700))
        log.add(1500.0, T0.plusSeconds(3600))
        val r = log.readings()
        assertEquals(listOf(1010.5, 1011.0), r.map { it.hPa })
        assertEquals(T0, r.first().time)
    }

    @Test
    fun whatIsOlderThanFortyEightHoursIsForgottenOnTheNextWrite() {
        val log = PressureLog(dir.newFile("p.csv"))
        log.add(1000.0, T0.minus(Duration.ofHours(50)))
        log.add(1001.0, T0.minus(Duration.ofHours(30)))
        log.add(1002.0, T0)
        assertEquals(listOf(1001.0, 1002.0), log.readings().map { it.hPa })
    }

    @Test
    fun aReadingTakenWithTheScreenOpenIsKeptOnlyWhenTheLastIsOldEnough() {
        val log = PressureLog(dir.newFile("p.csv"))
        assertTrue(log.addIfDue(1010.0, T0))
        assertFalse("four minutes later is the same reading", log.addIfDue(1010.2, T0.plusSeconds(240)))
        assertTrue(log.addIfDue(1009.0, T0.plusSeconds(11 * 60)))
        assertEquals(listOf(1010.0, 1009.0), log.readings().map { it.hPa })
    }

    @Test
    fun clearEmptiesIt() {
        val log = PressureLog(dir.newFile("p.csv"))
        log.add(1000.0, T0)
        log.clear()
        assertTrue(log.readings().isEmpty())
    }
}

class TrailStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val a = LatLon(52.3733, 4.8840)

    @Test
    fun aFixNearTheLastInSpaceAndTimeIsNotKept() {
        val t = TrailStore(dir.newFile("trail.csv"))
        assertTrue(t.record(a, T0))
        assertFalse(t.record(LatLon(a.lat + 0.0001, a.lon), T0.plusSeconds(30)))
        assertEquals(1, t.count())
    }

    @Test
    fun aFixFarFromTheLastOrLongAfterItIsKept() {
        val t = TrailStore(dir.newFile("trail.csv"))
        t.record(a, T0)
        assertTrue(t.record(LatLon(a.lat + 0.001, a.lon), T0.plusSeconds(30)))
        assertTrue(t.record(LatLon(a.lat + 0.001, a.lon), T0.plusSeconds(30 + TrailStore.MIN_SECONDS + 1)))
        assertEquals(3, t.count())
    }

    @Test
    fun placesCountsDifferentSquaresNotFixes() {
        val t = TrailStore(dir.newFile("trail.csv"))
        for (i in 0 until 5) t.record(LatLon(a.lat, a.lon + i * 0.0001), T0.plusSeconds(i * 1000L)) // standing about
        assertEquals(1, t.places())
        t.record(LatLon(a.lat + 0.02, a.lon), T0.plusSeconds(9000))
        t.record(LatLon(a.lat + 0.04, a.lon), T0.plusSeconds(10000))
        assertEquals(3, t.places())
    }

    @Test
    fun pruneDropsTheOldAndClearDropsAll() {
        val t = TrailStore(dir.newFile("trail.csv"))
        t.record(a, T0.minus(Duration.ofDays(40)))
        t.record(LatLon(a.lat + 0.01, a.lon), T0.minus(Duration.ofDays(1)))
        t.prune(T0.minus(Duration.ofDays(30)))
        assertEquals(1, t.count())
        assertEquals(T0.minus(Duration.ofDays(1)), t.points().single().time)
        t.clear()
        assertEquals(0, t.count())
        assertEquals(0L, t.bytes())
    }

    @Test
    fun aNewStoreOnTheSameFileKnowsTheLastPoint() {
        val f = dir.newFile("trail.csv")
        TrailStore(f).record(a, T0)
        assertFalse(TrailStore(f).record(a, T0.plusSeconds(10)))
    }
}

class PickerModelTest {
    private val centre = LatLon(52.3733, 4.8840)

    @Test
    fun theModeComesFromTheRouteParameter() {
        assertEquals(PickerModel.Mode.AREA, PickerModel.Mode.of(null))
        assertEquals(PickerModel.Mode.ROUTE, PickerModel.Mode.of("route"))
        assertEquals(PickerModel.Mode.COUNTRY, PickerModel.Mode.of("country"))
        assertEquals(PickerModel.Mode.AREA, PickerModel.Mode.of("nonsense"))
    }

    @Test
    fun aRadiusBoxIsAsWideAsTwiceTheRadius() {
        val (w, h) = PickerModel.sides(PickerModel.radiusBox(centre, 12.0))
        assertEquals(24.0, w, 0.3)
        assertEquals(24.0, h, 0.3)
    }

    @Test
    fun aLoopBecomesTheBoxAroundItAndAScribbleBecomesNothing() {
        val loop = listOf(LatLon(52.40, 4.85), LatLon(52.40, 4.95), LatLon(52.35, 4.95), LatLon(52.35, 4.85))
        val b = PickerModel.lassoBox(loop)!!
        assertEquals(4.85, b.west, 1e-9)
        assertEquals(4.95, b.east, 1e-9)
        assertEquals(52.35, b.south, 1e-9)
        assertEquals(52.40, b.north, 1e-9)
        assertNull(PickerModel.lassoBox(loop.take(2)))
    }

    @Test
    fun aTinyLoopIsGrownToAKilometre() {
        val tiny = listOf(centre, LatLon(centre.lat + 0.0001, centre.lon), LatLon(centre.lat, centre.lon + 0.0001))
        val (w, h) = PickerModel.sides(PickerModel.lassoBox(tiny)!!)
        assertTrue("$w x $h", w >= 1.0 && h >= 1.0)
    }

    @Test
    fun theCanvasMapsToTheEarthAndBack() {
        val view = BBox(4.0, 52.0, 5.0, 53.0)
        val nw = PickerModel.toLatLon(0f, 0f, 200f, 100f, view)
        assertEquals(53.0, nw.lat, 1e-9); assertEquals(4.0, nw.lon, 1e-9)
        val se = PickerModel.toLatLon(200f, 100f, 200f, 100f, view)
        assertEquals(52.0, se.lat, 1e-9); assertEquals(5.0, se.lon, 1e-9)
    }

    @Test
    fun aFittedViewHasTheShapeAskedForAndKeepsItsCentreAndAKilometreIsAsLongAcrossAsDown() {
        val view = BBox.around(centre, 10_000.0)
        val fitted = PickerModel.fitView(view, 1.5f)
        val (w, h) = PickerModel.sides(fitted)
        assertEquals(1.5, w / h, 0.01)
        assertEquals(centre.lat, fitted.center.lat, 1e-9)
        assertTrue(fitted.west <= view.west && fitted.north >= view.north)
        assertEquals(fitted, PickerModel.fitView(fitted, 1.5f).let { f -> if (abs(f.west - fitted.west) < 1e-9) fitted else f })
    }

    @Test
    fun aLongDriveIsCutIntoBoxesThatFollowItInsteadOfOneBigRectangle() {
        // Amsterdam to Rotterdam by Haarlem and The Hague, sampled every few hundred metres.
        val way = listOf(LatLon(52.3733, 4.8840), LatLon(52.3874, 4.6462), LatLon(52.0705, 4.3007), LatLon(51.9225, 4.4792))
        val points = ArrayList<LatLon>()
        for (i in 0 until way.size - 1) {
            val n = (Geo.distance(way[i], way[i + 1]) / 400).toInt()
            for (k in 0 until n) points += LatLon(way[i].lat + (way[i + 1].lat - way[i].lat) * k / n, way[i].lon + (way[i + 1].lon - way[i].lon) * k / n)
        }
        points += way.last()
        val boxes = PickerModel.corridorBoxes(points, 5_000.0)
        assertTrue("more than one box: ${boxes.size}", boxes.size > 1)
        for (p in points) assertTrue("every point of the road is inside a box", boxes.any { p in it })
        val whole = BBox.of(points)!!.buffer(5_000.0)
        assertTrue(boxes.sumOf { app.atlas.ui.mine.offline.RegionEngine.area(it) } < app.atlas.ui.mine.offline.RegionEngine.area(whole) * 1.2)
        for (b in boxes) { val (w, h) = PickerModel.sides(b); assertTrue("$w x $h", maxOf(w, h) <= 40.0 + 2 * 5.0 + 1.0) }
    }

    @Test
    fun theBoxesAreFewerThanTheLimitHoweverLongTheRoute() {
        val line = (0..600).map { LatLon(40.0 + it * 0.02, -3.0 + it * 0.01) }
        assertTrue(PickerModel.corridorBoxes(line, 5_000.0, maxParts = 12).size <= 12)
        assertTrue(PickerModel.corridorBoxes(emptyList(), 5_000.0).isEmpty())
    }

    @Test
    fun theGridStepIsARoundNumberGivingAFewLines() {
        assertEquals(10.0, PickerModel.gridStepKm(50.0), 0.0)
        assertEquals(20.0, PickerModel.gridStepKm(66.0), 0.0)
        assertEquals(1.0, PickerModel.gridStepKm(4.0), 0.0)
        assertEquals(0.2, PickerModel.gridStepKm(1.0), 0.0)
    }

    @Test
    fun twoRegionsOfOneNameGetNumbers() {
        assertEquals("Around Amsterdam", PickerModel.unique("Around Amsterdam", emptySet()))
        assertEquals("Around Amsterdam 2", PickerModel.unique("Around Amsterdam", setOf("around-amsterdam")))
        assertEquals("Around Amsterdam 3", PickerModel.unique("Around Amsterdam", setOf("around-amsterdam", "around-amsterdam-2")))
    }

    @Test
    fun aHugeDrawnAreaIsRefusedUnlessAPackCoversIt() {
        val huge = BBox(2.0, 48.0, 12.0, 54.0)
        assertTrue(PickerModel.tooBig(huge, packed = false))
        assertFalse(PickerModel.tooBig(huge, packed = true))
        assertFalse(PickerModel.tooBig(PickerModel.radiusBox(centre, 60.0), packed = false))
    }
}

class SatellitesModelTest {
    private val sky = MineFixtures.sky()

    @Test
    fun theZenithIsTheCentreAndTheHorizonTheRim() {
        val (zx, zy) = SatellitesModel.point(123f, 90f)
        assertEquals(SatellitesModel.CENTRE, zx, 1e-3f); assertEquals(SatellitesModel.CENTRE, zy, 1e-3f)
        val (nx, ny) = SatellitesModel.point(0f, 0f)
        assertEquals(SatellitesModel.CENTRE, nx, 1e-3f); assertEquals(SatellitesModel.CENTRE - SatellitesModel.RADIUS, ny, 1e-3f)
        val (ex, ey) = SatellitesModel.point(90f, 0f)
        assertEquals(SatellitesModel.CENTRE + SatellitesModel.RADIUS, ex, 1e-3f); assertEquals(SatellitesModel.CENTRE, ey, 1e-3f)
    }

    @Test
    fun theCountsAreTheUsedOverTheInViewPerSystem() {
        val counts = SatellitesModel.counts(sky)
        assertEquals(listOf(10, 7, 6, 6), counts.map { it.third })
        for ((c, used, view) in counts) assertEquals("$c", sky.satellites.count { it.constellation == c.code && it.usedInFix }, used.also { assertTrue(it <= view) })
        assertEquals(4, SatellitesModel.systems(sky))
    }

    @Test
    fun theBarsAreTheStrongestUsedFirstAndHeightsAreOfFiftyDecibelHertz() {
        val bars = SatellitesModel.bars(sky)
        assertTrue(bars.size <= 18 && bars.all { it.usedInFix })
        assertEquals(bars.map { it.cn0 }.sortedDescending(), bars.map { it.cn0 })
        assertEquals(100f, SatellitesModel.barHeight(50f, 100f), 1e-3f)
        assertEquals(100f, SatellitesModel.barHeight(70f, 100f), 1e-3f)
        assertEquals(5f, SatellitesModel.barHeight(0f, 100f), 1e-3f)
        assertEquals(40f, SatellitesModel.barHeight(20f, 100f), 1e-3f)
    }

    @Test
    fun theLabelNamesTheSystemAndPadsTheNumber() {
        assertEquals("G06", SatellitesModel.label(app.atlas.services.Satellite(6, 1, 40f, 0f, 45f, true)))
        assertEquals("E19", SatellitesModel.label(app.atlas.services.Satellite(19, 6, 40f, 0f, 45f, true)))
        assertEquals("?03", SatellitesModel.label(app.atlas.services.Satellite(3, 99, 40f, 0f, 45f, true)))
        assertEquals(6, SatellitesModel.named(sky).size)
    }

    @Test
    fun everyShapeIsOneSystemsAndTheFourAreListed() {
        assertEquals(setOf(Constellation.GPS, Constellation.GALILEO, Constellation.GLONASS, Constellation.BEIDOU), Constellation.MAJOR.toSet())
        assertEquals(Constellation.GALILEO, Constellation.of(6))
        assertNull(Constellation.of(42))
        assertTrue(Constellation.GPS.listed && !Constellation.SBAS.listed)
    }
}

class WatchModelTest {
    private fun g(t: app.atlas.model.ManeuverType, turn: app.atlas.model.Turn) = WatchModel.glyph(t, turn)

    @Test
    fun theGlyphFollowsTheTurn() {
        val tp = app.atlas.model.ManeuverType.TURN
        assertEquals(WatchModel.Glyph.RIGHT, g(tp, app.atlas.model.Turn.SHARP_RIGHT))
        assertEquals(WatchModel.Glyph.LEFT, g(tp, app.atlas.model.Turn.SLIGHT_LEFT))
        assertEquals(WatchModel.Glyph.U_TURN, g(tp, app.atlas.model.Turn.U_TURN))
        assertEquals(WatchModel.Glyph.STRAIGHT, g(app.atlas.model.ManeuverType.CONTINUE, app.atlas.model.Turn.STRAIGHT))
        assertEquals(WatchModel.Glyph.ARRIVE, g(app.atlas.model.ManeuverType.ARRIVE, app.atlas.model.Turn.RIGHT))
        assertEquals(WatchModel.Glyph.STRAIGHT, WatchModel.glyph(null, null))
    }

    @Test
    fun theDotsOnTheFaceAreThePulsesOfTheVibrationTheGuideMakes() {
        for (glyph in WatchModel.Glyph.entries) assertEquals("$glyph", WatchModel.haptic(glyph).pulses, WatchModel.dots(glyph))
        assertEquals(2, WatchModel.rhythm(WatchModel.Glyph.LEFT).size)
        assertEquals(3, WatchModel.rhythm(WatchModel.Glyph.RIGHT).size)
    }

    @Test
    fun theBezelHasSixtyTicksAndALongOneEveryFifth() {
        val ticks = WatchModel.ticks()
        assertEquals(60, ticks.size)
        fun len(t: FloatArray) = Math.hypot((t[2] - t[0]).toDouble(), (t[3] - t[1]).toDouble())
        assertTrue(len(ticks[0]) > len(ticks[1]))
        assertEquals(len(ticks[0]), len(ticks[5]), 1e-3)
        assertEquals(150f, ticks[0][0], 1e-3f); assertEquals(22f, ticks[0][1], 1e-3f)
    }

    @Test
    fun theArcEndsWhereTheFractionSaysAndFlagsTheLongWayRound() {
        val (x, y, big) = WatchModel.arcEnd(0.25f)
        assertEquals(262f, x, 0.01f); assertEquals(150f, y, 0.01f); assertFalse(big)
        assertTrue(WatchModel.arcEnd(0.75f).third)
        val full = WatchModel.arcEnd(2f)
        assertTrue(abs(full.first - 150f) < 1f)
    }
}

class SettingsModelTest {
    @Test
    fun theUnitsGoRoundInOrder() {
        assertEquals(Units.IMPERIAL, SettingsModel.nextUnits(Units.METRIC))
        assertEquals(Units.NAUTICAL, SettingsModel.nextUnits(Units.IMPERIAL))
        assertEquals(Units.METRIC, SettingsModel.nextUnits(Units.NAUTICAL))
    }

    @Test
    fun temperaturesAndClocksFollowTheSettings() {
        assertEquals("18°", SettingsModel.temperature(17.6, false))
        assertEquals("64°", SettingsModel.temperature(18.0, true))
        assertEquals("18:40", SettingsModel.clock(18, 40, false))
        assertEquals("6:40 pm", SettingsModel.clock(18, 40, true))
        assertEquals("12:05 am", SettingsModel.clock(0, 5, true))
        assertEquals("12:30 pm", SettingsModel.clock(12, 30, true))
    }

    @Test
    fun speedsAndDistancesFollowTheUnits() {
        assertEquals("50 km/h", SettingsModel.speed(50.0, Units.METRIC))
        assertEquals("31 mph", SettingsModel.speed(50.0, Units.IMPERIAL))
        assertEquals("27 kn", SettingsModel.speed(50.0, Units.NAUTICAL))
        assertEquals("350 m", SettingsModel.distance(352.0, Units.METRIC))
        assertEquals("1.2 km", SettingsModel.distance(1234.0, Units.METRIC))
        assertEquals("0.8 mi", SettingsModel.distance(1300.0, Units.IMPERIAL))
        assertEquals("330 ft", SettingsModel.distance(100.0, Units.IMPERIAL))
        assertEquals("0.7 nm", SettingsModel.distance(1300.0, Units.NAUTICAL))
    }

    @Test
    fun languagesAreNamedInTheirOwnTongue() {
        assertEquals("Nederlands", SettingsModel.languageName("nl"))
        assertEquals("Deutsch", SettingsModel.languageName("de"))
        assertNull(SettingsModel.NAME_LANGUAGES.first())
    }
}

class SourcesModelTest {
    @Test
    fun aLicenceIsShortenedToWhatFitsATag() {
        assertEquals("ODbL", SourcesModel.licenceLabel("ODbL"))
        assertEquals("CC BY 4.0", SourcesModel.licenceLabel(" CC BY 4.0 "))
        assertEquals("open data", SourcesModel.licenceLabel("Open data licence of the Dutch government"))
        assertEquals("own terms", SourcesModel.licenceLabel("Free for non-commercial use under their terms of service"))
        assertTrue(SourcesModel.licenceLabel("Something entirely different and long").endsWith("…"))
    }

    @Test
    fun theHostIsWhatTheNetworkSees() {
        assertEquals("overpass-api.de", SourcesModel.hostOf("https://overpass-api.de/api/interpreter"))
        assertEquals("open-meteo.com", SourcesModel.hostOf("https://www.open-meteo.com/v1/forecast?x=1"))
        assertNull(SourcesModel.hostOf("not a url at all"))
    }

    @Test
    fun theKeySlotsAreForTheFourKeyedSources() {
        val rows = SourcesModel.rows(emptySet(), emptyMap())
        val ids = SourcesModel.keyed(rows).map { it.info.id }
        assertTrue("tankerkoenig" in ids)
        assertTrue(rows.any { it.info.id == "tankerkoenig" })
        assertEquals(0, SourcesModel.summary(rows).second)
        val withKey = SourcesModel.rows(emptySet(), mapOf("tankerkoenig" to "k"))
        assertEquals(1, SourcesModel.summary(withKey).second)
    }

    @Test
    fun switchingASourceOffShowsInTheRows() {
        val rows = SourcesModel.rows(setOf("open-meteo"), emptyMap())
        assertFalse(rows.first { it.info.id == "open-meteo" }.on)
        // A source that needs a key is off until the key is entered; then it is on.
        assertFalse(rows.first { it.info.id == "tankerkoenig" }.on)
        assertTrue(SourcesModel.rows(emptySet(), mapOf("tankerkoenig" to "k")).first { it.info.id == "tankerkoenig" }.on)
        assertTrue("a keyless source with nothing against it stays on", rows.any { it.on && !it.needsKey })
        assertNotNull(SourcesModel.open(rows))
    }
}

class StreetCoverageTest {
    @Test
    fun noWaysNoCoverage() {
        assertNull(StreetCoverage.of(emptyList()))
        assertNull(StreetCoverage.of(listOf(mapOf("highway" to "motorway"), mapOf("waterway" to "canal"))))
    }

    @Test
    fun theSharesAreOfTheWaysThatCouldCarryTheTag() {
        val ways = listOf(
            mapOf("highway" to "residential", "lit" to "yes", "surface" to "asphalt", "sidewalk" to "both", "parking:left" to "lane"),
            mapOf("highway" to "residential"),
            mapOf("highway" to "footway", "lit" to "yes", "surface" to "paving_stones"),
            mapOf("highway" to "primary", "surface" to "asphalt", "sidewalk:left" to "yes"),
        )
        val c = StreetCoverage.of(ways)!!
        assertEquals(4, c.ways)
        assertEquals(0.5f, c.lighting, 1e-6f)      // 2 of 4
        assertEquals(0.75f, c.surfaces, 1e-6f)     // 3 of 4
        assertEquals(2f / 3f, c.sidewalks, 1e-6f)  // 2 of the 3 roads
        assertEquals(1f / 3f, c.parking, 1e-6f)    // 1 of the 3 roads
        assertEquals(((0.5f + 0.75f + 2f / 3f + 1f / 3f) / 4f * 100).toInt(), c.known)
    }

    @Test
    fun theQueryAsksForTagsOnlyAroundThePoint() {
        val q = StreetCoverage.query(LatLon(52.37331, 4.88403), 400)
        assertTrue(q.contains("around:400,52.37331,4.88403") && q.endsWith("out tags;"))
    }
}
