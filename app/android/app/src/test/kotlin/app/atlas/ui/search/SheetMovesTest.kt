package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.search.MemoryPlaces
import app.atlas.search.PlaceRecord
import app.atlas.search.PlaceSource
import app.atlas.services.PlaceOrigin
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * After a move the sheet shows no centre's places but the new one's (the app maintainer, 18 November 2026, batch 5 A4):
 * the reading at the new centre began at once, and the region settled there showed the last centre's places, counted
 * from the new one, before the new centre's came, or after them. Until they come the grid keeps the last centre's tiles
 * as counted there, as it did before the region was settled off the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetMovesTest {
    /**
     * Würzburg, and 3.3 km north of it; Bamberg for [theLastCentresPlacesAreNotShownAfterTheNewOnes]. [HereContext] keeps
     * what it was told for the run, so each test asks about cells no other asks about, this class's other test included.
     */
    private var c1 = LatLon(49.7913, 9.9534)
    private val c2 get() = LatLon(c1.lat + 0.03, c1.lon)

    /** Epiphany 2026, a Tuesday, at 10:00 in Bavaria: a holiday there, not in Germany as a whole. */
    private val epiphany: Clock = Clock.fixed(Instant.parse("2026-01-06T09:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val day = LocalDate.of(2026, 1, 6)

    private val bakery = OsmId.parse("n8101")!!
    private val atC1 = setOf(bakery, OsmId.parse("n8102")!!)
    private val atC2 = setOf(OsmId.parse("n8201")!!, OsmId.parse("n8202")!!)

    /** A store whose read of the grid's circle around [at] waits off the main thread while [gate] is set, as on a slow phone. */
    private class Gated(private val inner: PlaceSource, private val at: LatLon) : PlaceSource by inner {
        @Volatile var gate: CountDownLatch? = null

        override fun inBox(box: BBox, limit: Int): List<PlaceRecord> {
            val g = gate
            if (g != null && limit == PlaceSource.UNLIMITED && box == BBox.around(at, SearchSheetModel.POOL_RADIUS_M) &&
                Looper.myLooper() != Looper.getMainLooper()
            ) g.await(20, TimeUnit.SECONDS)
            return inner.inBox(box, limit)
        }
    }

    /** A place's tags that wait off the main thread while [trap] is set, and count the reads that waited. */
    private class Trapped(private val inner: Map<String, String>) : Map<String, String> by inner {
        @Volatile var trap: CountDownLatch? = null
        val hits = AtomicInteger()

        private fun <T> read(answer: () -> T): T {
            val t = trap
            if (t != null && Looper.myLooper() != Looper.getMainLooper()) {
                hits.incrementAndGet()
                t.await(20, TimeUnit.SECONDS)
            }
            return answer()
        }

        override fun get(key: String): String? = read { inner[key] }
        override fun containsKey(key: String): Boolean = read { inner.containsKey(key) }
        override val entries: Set<Map.Entry<String, String>> get() = read { inner.entries }
        override val keys: Set<String> get() = read { inner.keys }
        override fun equals(other: Any?): Boolean = inner == other
        override fun hashCode(): Int = inner.hashCode()
        override fun toString(): String = inner.toString()
    }

    private fun bavarian(vararg tags: Pair<String, String>) = mapOf(*tags, "addr:country" to "DE", "addr:state" to "Bayern")

    private val bakeryTags = bavarian("shop" to "bakery", "name" to "Bäckerei", "opening_hours" to "Mo-Sa 06:30-18:00")

    /**
     * A bakery and a supermarket at [c1], a pharmacy and a café at [c2], each naming Bavaria: one store holds both, its
     * box both centres (the bakery south-west of [c1]), so it covers each ([app.atlas.search.covers]).
     */
    private fun store(tagsOfBakery: Map<String, String>) = MemoryPlaces(
        listOf(
            Place(bakery, LatLon(c1.lat - 0.001, c1.lon - 0.001), tagsOfBakery),
            Place(OsmId.parse("n8102")!!, LatLon(c1.lat + 0.001, c1.lon + 0.001), bavarian("shop" to "supermarket", "name" to "Markt", "opening_hours" to "Mo-Sa 07:00-20:00; PH off")),
            Place(OsmId.parse("n8201")!!, LatLon(c2.lat - 0.001, c2.lon + 0.001), bavarian("amenity" to "pharmacy", "name" to "Apotheke")),
            Place(OsmId.parse("n8202")!!, LatLon(c2.lat + 0.001, c2.lon), bavarian("amenity" to "cafe", "name" to "Café")),
        ),
        country = null,
    )

    /** Runs the main thread's queue until [done], at most 20 s. */
    private fun until(what: String, done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out: $what", done())
    }

    /**
     * Runs the main thread's queue until none of the sheet's readings is under way ([SearchSheetModel.busy]) but those
     * in [heldBack], which the test holds, and nothing is left on the queue, at most 20 s (the app maintainer,
     * 19 November 2026, batch 6 B9: it waited for the state to stay the same over a tenth of a second, so a reading
     * slower than that was read in flight).
     */
    private fun rest(m: SearchSheetModel, vararg heldBack: String) {
        val main = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + 20_000
        while (true) {
            main.idle()
            val under = m.busy - heldBack.toSet()
            if (under.isEmpty() && main.isIdle) return
            assertTrue("not at rest in 20 s but for ${heldBack.toList()}: $under under way", System.currentTimeMillis() < end)
            main.idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    private fun nearest(s: SearchSheetState) = s.pulses.values.mapNotNull { it.nearest?.place?.id }.toSet()

    /** The tiles that count a place from beyond the grid's circle. */
    private fun far(s: SearchSheetState) = s.pulses.values.filter { (it.metres ?: 0.0) > SearchSheetModel.POOL_RADIUS_M }.map { it.node.id to it.metres }

    private fun bavarianHoliday(m: SearchSheetModel) = m.state.value.moment.hours.publicHolidays?.isHoliday(day) == true

    /** The sheet opened at [c1], its grid read and at rest in Bavaria. */
    private fun openedAtC1(s: Services): Pair<SearchSheetModel, SearchSheetState> {
        val m = SearchSheetModel(s, c1, "en")
        m.moveTo(c1)
        until("c1's grid") { nearest(m.state.value).let { it.isNotEmpty() && it.all { id -> id in atC1 } } }
        rest(m)
        val before = m.state.value
        assertTrue("c1's places on the grid: ${nearest(before)}", nearest(before).isNotEmpty() && nearest(before).all { it in atC1 })
        assertTrue("Epiphany at c1", before.moment.hours.publicHolidays?.isHoliday(day) == true)
        return m to before
    }

    @Test
    fun aMoveShowsNoPlacesButTheNewCentres() {
        val gated = Gated(store(bakeryTags), c2)
        val s = testServices(clock = epiphany, stores = listOf(gated))
        val (m, before) = openedAtC1(s)
        // 3.3 km north, the grid's circle there held back: the region there is settled first
        val gate = CountDownLatch(1).also { gated.gate = it }
        m.moveTo(c2)
        assertFalse("Bavaria not yet known at c2", bavarianHoliday(m))
        until("Bavaria settled at c2") { bavarianHoliday(m) }
        // all of it but the grid's read at c2, which the gate holds
        rest(m, "pool")
        val during = m.state.value
        assertTrue("tiles counted from c2 over places beyond its circle: ${far(during)}", far(during).isEmpty())
        assertSame("the grid keeps c1's tiles until c2's come", before.pulses, during.pulses)
        gate.countDown()
        until("c2's grid") { m.state.value.pulses !== during.pulses }
        rest(m)
        val after = m.state.value
        assertEquals(PlaceOrigin.STORE, after.gridOrigin)
        assertTrue("c2's places on the grid: ${nearest(after)}", nearest(after).isNotEmpty() && nearest(after).all { it in atC2 })
        assertTrue("tiles over places beyond c2's circle: ${far(after)}", far(after).isEmpty())
        s.close()
    }

    /**
     * The quests are counted where the grid's places were read (the app maintainer, 19 November 2026, batch 6 B9:
     * counted around the new centre over the last centre's places, they passed every test, as none counted them after
     * a move): moved 3.3 km north, c2's grid held back, the grid's places are still c1's, and their quests c1's.
     */
    @Test
    fun theQuestsAreCountedWhereTheGridsPlacesWereRead() {
        c1 = LatLon(49.5897, 11.0120)
        val gated = Gated(store(bakeryTags), c2)
        val s = testServices(clock = epiphany, stores = listOf(gated))
        val (m, _) = openedAtC1(s)
        val there = QuestFinder.countAround(s, gated.inBox(BBox.around(c1, 1_000.0), PlaceSource.UNLIMITED).map { it.place }, c1, "en")
        assertTrue("c1's places hold quests", there > 0)
        val gate = CountDownLatch(1).also { gated.gate = it }
        try {
            m.moveTo(c2)
            m.countQuests()
            until("the quests counted") { m.state.value.quests != null }
            assertEquals(there, m.state.value.quests)
        } finally {
            gate.countDown()
        }
        s.close()
    }

    /**
     * Nor after them: c1's bakery is read again only by what counts c1's places, which waits while the trap is set, so a
     * count of them from c2 that has begun before c2's places came ends after them.
     */
    @Test
    fun theLastCentresPlacesAreNotShownAfterTheNewOnes() {
        c1 = LatLon(49.8917, 10.8868)
        val tags = Trapped(bakeryTags)
        val gated = Gated(store(tags), c2)
        val s = testServices(clock = epiphany, stores = listOf(gated))
        val (m, _) = openedAtC1(s)
        val gate = CountDownLatch(1).also { gated.gate = it }
        val trap = CountDownLatch(1).also { tags.trap = it }
        m.moveTo(c2)
        assertFalse("Bavaria not yet known at c2", bavarianHoliday(m))
        until("Bavaria settled at c2") { bavarianHoliday(m) }
        // all of it but the grid's read at c2, which the gate holds, and a count of c1's places from c2 begun, which
        // the trap would hold: the region settled at c2 showing them, as before batch 5 A4
        rest(m, "pool", "region")
        val held = m.state.value
        gate.countDown()
        until("c2's grid") { m.state.value.pulses !== held.pulses }
        // c2's grid read and shown; such a count still held
        rest(m, "region")
        trap.countDown()
        rest(m)
        val after = m.state.value
        val read = "c1's bakery read ${tags.hits.get()} times off the main thread after the move"
        assertEquals(PlaceOrigin.STORE, after.gridOrigin)
        assertTrue("c2's places on the grid ($read): ${nearest(after)}", nearest(after).isNotEmpty() && nearest(after).all { it in atC2 })
        assertTrue("tiles over places beyond c2's circle ($read): ${far(after)}", far(after).isEmpty())
        s.close()
    }
}
