package app.atlas.ui.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.Categories
import app.atlas.places.OpeningHoursContext
import app.atlas.providers.Forecast
import app.atlas.providers.Precipitation15
import app.atlas.services.PlaceOrigin
import app.atlas.testing.Fixtures
import app.atlas.testing.testServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Map quests around the Westerkerk from the Amsterdam extract, and the Where wheel's choices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuestsAndWhereTest {
    private val evening = LocalDateTime.now(Fixtures.evening)

    @Test
    fun questsAroundAreNearestFirstAndNotAskedTwice() = runBlocking {
        val s = testServices(places = true)
        val (found, origin) = QuestFinder.near(s, Services.WESTERKERK, "en", online = false)
        assertEquals(PlaceOrigin.STORE, origin)
        assertTrue("quests: ${found.size}", found.size >= 10)
        // Near first, in bands of 50 m.
        assertTrue(found.zipWithNext().all { (a, b) -> (a.distanceM / 50).toInt() <= (b.distanceM / 50).toInt() })
        assertTrue(found.all { it.distanceM <= QuestFinder.RADIUS_M && it.place.id != null && it.place.name() != null })
        assertTrue(found.groupBy { it.place.key }.values.all { it.size <= 2 })
        // What the place has mapped is never asked.
        for (q in found) assertNull("${q.place.name()} ${q.quest.key}", q.place.tags[q.quest.key])

        val first = found.first()
        s.saved.recordAnswer(first.place.key, first.quest.key, true, "https://www.openstreetmap.org/note/1")
        val (again, _) = QuestFinder.near(s, Services.WESTERKERK, "en", online = false)
        assertFalse(again.any { it.id == first.id })
        assertTrue(s.saved.answered(first.place.key, first.quest.key))
    }

    @Test
    fun withoutARegionAndOfflineThereAreNoQuests() = runBlocking {
        val (found, origin) = QuestFinder.near(testServices(), Services.WESTERKERK, "en", online = false)
        assertTrue(found.isEmpty())
        assertNull(origin)
    }

    @Test
    fun theWheelsWedgesFillFromTheRegion() = runBlocking {
        val s = testServices(places = true)
        val hours = HereContext.hours(s, Services.WESTERKERK, Fixtures.evening.zone)
        val wheel = WherePicker.places(s, Services.WESTERKERK, WhereFilters(), evening, hours)
        assertTrue(WherePicker.WEDGES.count { wheel.of(it).isNotEmpty() } >= 5)
        val r = WhereFilters().radiusM
        for (w in WherePicker.WEDGES) for (p in wheel.of(w)) assertTrue(Geo.distance(Services.WESTERKERK, p.location) <= r)
        // A spin lands on a wedge with places, on one of them.
        val (i, p) = WherePicker.spin(wheel, Services.WESTERKERK, Random(7))!!
        assertTrue(p in wheel.of(WherePicker.WEDGES[i]))
        // Indoors only: the outdoor wedges are empty.
        val indoors = WherePicker.places(s, Services.WESTERKERK, WhereFilters(indoors = true), evening, hours)
        assertTrue(WherePicker.WEDGES.filter { !it.indoors }.all { indoors.of(it).isEmpty() })
    }

    @Test
    fun theChipsAreHonest() {
        val at = LatLon(52.3745, 4.8838)
        val museum = WherePicker.WEDGES.first { it.id == "culture" }
        val park = WherePicker.WEDGES.first { it.id == "nature" }
        fun place(vararg t: Pair<String, String>) = Place(OsmId.parse("n1"), LatLon(52.3750, 4.8838), mapOf(*t), source = "osm")
        val ctx = OpeningHoursContext.Default
        val open = WhereFilters(openNow = true)
        val evening = LocalDateTime.of(2026, 10, 13, 19, 30)
        assertFalse(WherePicker.fits(place("tourism" to "museum"), museum, at, WhereFilters(), evening, ctx))
        assertTrue(WherePicker.fits(place("tourism" to "museum", "name" to "M"), museum, at, WhereFilters(), evening, ctx))
        // Never one known to be shut now, with no chip on (the wheel sent the morning to a pub that opens at four).
        assertFalse(WherePicker.fits(place("tourism" to "museum", "name" to "M", "opening_hours" to "Mo-Su 10:00-17:00"), museum, at, WhereFilters(), evening, ctx))
        assertTrue(WherePicker.fits(place("tourism" to "museum", "name" to "M", "opening_hours" to "Mo-Su 10:00-22:00"), museum, at, WhereFilters(), evening, ctx))
        assertFalse(WherePicker.fits(place("tourism" to "museum", "name" to "M", "opening_hours" to "Mo-Su 10:00-17:00"), museum, at, open, evening, ctx))
        // Indoors with no hours mapped may be shut; a park with none is open.
        assertFalse(WherePicker.fits(place("tourism" to "museum", "name" to "M"), museum, at, open, evening, ctx))
        assertTrue(WherePicker.fits(place("leisure" to "park", "name" to "P"), park, at, open, evening, ctx))
        assertFalse(WherePicker.fits(place("tourism" to "museum", "name" to "M", "fee" to "yes"), museum, at, WhereFilters(free = true), evening, ctx))
        assertFalse(WherePicker.fits(place("leisure" to "park", "name" to "P"), park, at, WhereFilters(indoors = true), evening, ctx))
        val far = Place(OsmId.parse("n2"), LatLon(52.40, 4.8838), mapOf("leisure" to "park", "name" to "Far"), source = "osm")
        assertFalse(WherePicker.fits(far, park, at, WhereFilters(walkMinutes = 15), evening, ctx))
        assertNotNull(WhereFilters(walkMinutes = 30).radiusM.takeIf { it > WhereFilters().radiusM })
        assertNull(WherePicker.spin(WheelPlaces(emptyMap()), at, Random(1)))
        assertTrue(BBox.around(at, 10.0).contains(at))
    }

    /** "Another" goes through every place before one comes round again, then starts over. */
    @Test
    fun anotherGoesThroughTheRestFirst() {
        val at = LatLon(52.3745, 4.8838)
        val five = (1..5).map { Place(OsmId.parse("n$it"), LatLon(52.3745 + it * 0.0002, 4.8838), mapOf("leisure" to "park", "name" to "P$it"), source = "osm") }
        val wheel = WheelPlaces(mapOf("nature" to five.take(3), "water" to five.drop(3)))
        val seen = mutableSetOf<String>()
        val random = Random(3)
        repeat(5) {
            val (_, p) = WherePicker.spin(wheel, at, random, seen = seen)!!
            assertTrue("${p.name()} came round again", seen.add(p.key))
        }
        assertNotNull(WherePicker.spin(wheel, at, random, seen = seen))
    }

    /** Wherever the wheel stands and whichever way it spins, it turns four to five times and stops with the wedge under the pointer. */
    @Test
    fun aSpinStopsItsWedgeUnderThePointer() {
        val n = WherePicker.WEDGES.size
        val sweep = 360f / n
        for (from in listOf(0f, 37.5f, -1654.5f, 2000f)) for (i in 0 until n) for (clockwise in listOf(true, false)) {
            val to = WheelArt.landing(from, i, n, clockwise)
            val under = ((((-(to + WheelArt.BASE_TURN)) % 360f) + 360f) % 360f / sweep).toInt() % n
            assertEquals("from $from to wedge $i", i, under)
            val turned = if (clockwise) to - from else from - to
            assertTrue("from $from: turned $turned", turned >= 1440f - 0.01f && turned < 1800f + 0.01f)
        }
    }

    /**
     * A wedge wears its places' family colour, or one of its own (the views' sunset, the night's sky) that no eye takes
     * for a family's; and no two wedges side by side look alike.
     */
    @Test
    fun theWedgesWearTheirPlacesColours() {
        val families = Categories.grid().map { it.colour.toLong() and 0xFFFFFFFFL }.distinct()
        val wedges = WherePicker.WEDGES
        for (w in wedges) {
            val family = Categories.byId(w.categories.first())!!.colour.toLong() and 0xFFFFFFFFL
            if (w.colour == family) continue
            for (f in families) assertTrue("${w.id} looks like a family's colour", oklab(w.colour, f) > 0.1)
        }
        for (i in wedges.indices) {
            val a = wedges[i]
            val b = wedges[(i + 1) % wedges.size]
            assertTrue("${a.id} and ${b.id} look alike", oklab(a.colour, b.colour) > 0.05)
        }
    }

    /** Rain now or within two hours, by a forecast under 12 hours old, starts the wheel indoors; dry, stale or none does not. */
    @Test
    fun rainStartsTheWheelIndoors() {
        val t = Instant.parse("2026-10-13T17:30:00Z")
        // four hours of quarters, the first begun ten minutes ago, rain in [wet] alone
        fun forecast(wet: Int?, fetched: Instant = t) = Forecast(
            Services.WESTERKERK, 2.0, "Europe/Amsterdam", 7200, null,
            (0 until 16).map { q -> Precipitation15(t.plusSeconds(q * 900L - 600), if (q == wet) 0.6 else 0.0) },
            emptyList(), emptyList(), fetched,
        )
        assertTrue("raining now", WherePicker.wet(forecast(0), t))
        assertTrue("in an hour and a half", WherePicker.wet(forecast(7), t))
        assertFalse("in nearly three hours", WherePicker.wet(forecast(12), t))
        assertFalse("dry", WherePicker.wet(forecast(null), t))
        assertFalse("no forecast", WherePicker.wet(null, t))
        assertFalse("13 hours old", WherePicker.wet(forecast(0, fetched = t.minusSeconds(13 * 3600L)), t))
    }

    /** The food wedge's card says Lunch or Dinner at those hours, as the search's Eat does; the others keep their names. */
    @Test
    fun theFoodCardSaysWhichMeal() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val food = WherePicker.WEDGES.first { it.id == "food" }
        val water = WherePicker.WEDGES.first { it.id == "water" }
        assertEquals("Lunch", ctx.getString(WherePicker.title(food, LocalTime.of(12, 30))))
        assertEquals("Food", ctx.getString(WherePicker.title(food, LocalTime.of(15, 30))))
        assertEquals("Dinner", ctx.getString(WherePicker.title(food, LocalTime.of(19, 30))))
        assertEquals("Water", ctx.getString(WherePicker.title(water, LocalTime.of(12, 30))))
    }

    /** The distance between two colours in OKLab, where the eye sees a step of about 0.02. */
    private fun oklab(a: Long, b: Long): Double {
        fun lab(c: Long): DoubleArray {
            fun lin(v: Long): Double = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            val r = lin((c shr 16) and 0xFF)
            val g = lin((c shr 8) and 0xFF)
            val bl = lin(c and 0xFF)
            val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * bl)
            val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * bl)
            val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * bl)
            return doubleArrayOf(
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
            )
        }
        val x = lab(a)
        val y = lab(b)
        return sqrt((x[0] - y[0]).pow(2) + (x[1] - y[1]).pow(2) + (x[2] - y[2]).pow(2))
    }
}
