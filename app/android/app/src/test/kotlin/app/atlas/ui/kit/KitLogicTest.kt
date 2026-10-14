package app.atlas.ui.kit

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.ChangeKind
import app.atlas.places.OpenStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class KitLogicTest {
    @Test
    fun walkingMinutesAndDistancesAsTheBoardsWriteThem() {
        assertEquals("1′", walkMinutes(20.0))
        assertEquals("4′", walkMinutes(240.0))
        assertEquals("1 h 05", walkMinutes(65 * 60.0))
        assertEquals("350 m", shortDistance(347.0))
        assertEquals("10 m", shortDistance(2.0))
        assertEquals("1.2 km", shortDistance(1_234.0))
        assertEquals("12 km", shortDistance(12_400.0))
        // 350 m in a city is about five minutes on foot.
        assertEquals("6′", walkMinutes(WalkTime.seconds(350.0)))
    }

    @Test
    fun aPlaceSummary() {
        val cafe = Place(
            OsmId(OsmType.NODE, 1), LatLon(52.3740, 4.8840),
            mapOf("amenity" to "cafe", "name" to "Café Papeneiland", "opening_hours" to "Mo-Su 10:00-23:00"),
        )
        val s = PlaceSummary.of(cafe, LatLon(52.37452, 4.88377), "en", LocalDateTime.of(2026, 10, 13, 19, 0))
        assertEquals("Café Papeneiland", s.name)
        assertEquals("Cafe", s.kind)
        assertTrue(s.symbol, hasSymbol(s.symbol))
        assertTrue(s.open!!.isOpen)
        assertEquals(LocalDateTime.of(2026, 10, 13, 23, 0), s.open?.changesAt)
        assertTrue(s.distanceM!! in 50.0..80.0)
        assertNotNull(s.walk)
    }

    @Test
    fun aPlaceWithoutNameOrHours() {
        val bench = Place(null, LatLon(52.0, 4.0), mapOf("amenity" to "bench"))
        val s = PlaceSummary.of(bench, null, "en")
        assertEquals("Bench", s.name)
        assertNull(s.kind)
        assertNull(s.open)
        assertNull(s.walk)
        assertFalse(s.alwaysOpen)
    }

    @Test
    fun appSymbolsAllParseAndThePlacesSetIsReachable() {
        for (id in AppSymbols.ids) {
            val icon = AppSymbols.icon(id)!!
            assertTrue(id, icon.paths.all { it.commands.size > 0 })
        }
        assertTrue(hasSymbol("ui-wheelchair") && hasSymbol("maki-cafe") && hasSymbol("ui-near"))
        assertFalse(hasSymbol("ui-no-such-thing"))
    }

    @Test
    fun anElevationProfileIsReducedAndNormalised() {
        val e = DoubleArray(1_000) { 10.0 + it * 0.05 }
        val shape = ProfileShape.of(e, DoubleArray(1_000) { it * 10.0 }, 0.05)
        assertEquals(ProfileShape.MAX_POINTS, shape.size)
        assertEquals(0f, shape.x.first())
        assertEquals(1f, shape.x.last())
        assertTrue(shape.y.all { it in 0f..1f })
        assertTrue(shape.y.toList().zipWithNext().all { (a, b) -> a <= b })
        assertEquals(0, shape.indexAt(0f))
        assertEquals(shape.size - 1, shape.indexAt(1f))
        // A gentle 0.5 % climb is not steep; a wall is.
        assertTrue(shape.steep.none { it })
        val wall = ProfileShape.of(doubleArrayOf(0.0, 0.0, 50.0, 50.0), doubleArrayOf(0.0, 100.0, 200.0, 300.0), 0.05)
        assertTrue(wall.steep[1])
        assertEquals(0, ProfileShape.of(DoubleArray(1), null, 0.05).size)
    }

    @Test
    fun distancesNearAUnitBoundaryAndNonsenseDoNotMisleadOrCrash() {
        // 996 m rounds to 1 000 m: that is a kilometre, written as one.
        assertEquals("1.0 km", shortDistance(996.0))
        assertEquals("990 m", shortDistance(994.0))
        assertEquals("9.9 km", shortDistance(9_949.0))
        assertEquals("10 km", shortDistance(9_960.0))
        // A distance that cannot be measured (a NaN from bad coordinates) shows a dash rather than crashing the list.
        assertEquals("–", shortDistance(Double.NaN))
        assertEquals("–", shortDistance(-5.0))
        assertEquals("–", walkMinutes(Double.NaN))
        assertEquals("–", walkMinutes(Double.POSITIVE_INFINITY))
    }

    @Test
    fun anElevationProfileWithoutDistancesClaimsNoGrades() {
        // Without distances the spacing is unknown, so no stretch can be called steep: 5 m between samples could be
        // 1 % over 500 m or a wall.
        val climb = ProfileShape.of(doubleArrayOf(0.0, 5.0, 10.0, 15.0, 20.0), null, 0.05)
        assertTrue(climb.steep.none { it })
        assertEquals(5, climb.size)
        // Gaps in the heights (NaN) are skipped, not drawn as nonsense.
        val gappy = ProfileShape.of(doubleArrayOf(10.0, Double.NaN, 12.0, 14.0), doubleArrayOf(0.0, 100.0, 200.0, 300.0), 0.05)
        assertTrue(gappy.y.none { it.isNaN() })
        assertEquals(0, ProfileShape.of(DoubleArray(3) { Double.NaN }, null, 0.05).size)
    }

    private fun summary(open: OpenStatus?, always: Boolean = false) =
        PlaceSummary(Place(null, LatLon(52.0, 4.0), mapOf("name" to "X")), "X", null, "maki-marker", 0, open, always, null)

    @Test
    fun theOpenLineSaysOnlyWhatTheHoursSay() {
        val now = LocalDateTime.of(2026, 10, 13, 19, 0)
        assertEquals(OpenWords.Always, OpenWords.of(summary(null, always = true), now))
        assertNull(OpenWords.of(summary(null), now))
        assertNull(OpenWords.of(summary(OpenStatus.Unknown), now))
        assertEquals(OpenWords.OpenUntil(now.withHour(23)), OpenWords.of(summary(OpenStatus(true, now.withHour(23), ChangeKind.CLOSES, false)), now))
        assertEquals(OpenWords.ClosesIn(25), OpenWords.of(summary(OpenStatus(true, now.plusMinutes(25), ChangeKind.CLOSES, false)), now))
        // Open now, but from 19:30 the hours do not say: "open", not "open 24/7" and not "closes in 30 min".
        assertEquals(OpenWords.Open, OpenWords.of(summary(OpenStatus(true, now.plusMinutes(30), ChangeKind.BECOMES_UNKNOWN, false)), now))
        // "17:00+": open with no stated end, so the closing time is a guess and not shown.
        assertEquals(OpenWords.Open, OpenWords.of(summary(OpenStatus(true, now.plusMinutes(20), ChangeKind.CLOSES, false, openEnd = true)), now))
        // Open and never changing within a year.
        assertEquals(OpenWords.Always, OpenWords.of(summary(OpenStatus(true, null, null, false)), now))
        // Closed: opens later today, or on another day (which the line must then name).
        assertEquals(OpenWords.Opens(now.withHour(21), sameDay = true), OpenWords.of(summary(OpenStatus(false, now.withHour(21), ChangeKind.OPENS, false)), now))
        val monday = LocalDateTime.of(2026, 10, 19, 9, 0)
        assertEquals(OpenWords.Opens(monday, sameDay = false), OpenWords.of(summary(OpenStatus(false, monday, ChangeKind.OPENS, false)), now))
        assertEquals(OpenWords.Closed, OpenWords.of(summary(OpenStatus(false, null, null, false)), now))
    }

    @Test
    fun everySymbolTheAppNamesCanBeDrawn() {
        // A symbol id that does not exist draws a plain marker without a word of warning: catch the typo here.
        val src = java.io.File(System.getProperty("atlas.repo")!!, "app/android/app/src/main/kotlin")
        val literal = Regex(""""((?:ui|maki|temaki)-[a-z0-9-]+)"""")
        val missing = src.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> literal.findAll(f.readText()).map { it.groupValues[1] to f.name } }
            .filter { (id, _) -> !hasSymbol(id) }
            .toList()
        assertTrue("not drawable: $missing", missing.isEmpty())
    }
}
