package app.atlas.ui.search

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.search.MemoryPlaces
import app.atlas.search.PlaceRecord
import app.atlas.search.PlaceSource
import app.atlas.testing.testServices
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The regions are asked once a kilometre (the app maintainer, 18 November 2026, batch 5 B12). A list's rows read their
 * places' country off the main thread together (`rememberLocaleOf`), and the rows of one kilometre each asked the
 * regions before the first had answered: 136 questions for 500 saved places in 14 kilometres. And past 256 kilometres
 * what they had said was let go whole, so a list of 500 cafés across the country asked every kilometre again each time
 * it opened. What the regions said is kept per kilometre and count of places for the whole run ([HereContext]), so
 * each test has places of its own, out at sea off the Dutch coast, in a number no other test's store holds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegionsAskedOnceTest {
    /** A store counting the regions' question (the 2 km sample of 400 around a point), each taking [slowMs] to answer. */
    private class Counted(private val inner: PlaceSource, private val slowMs: Long = 0) : PlaceSource by inner {
        val asked = AtomicInteger()

        override fun inBox(box: BBox, limit: Int): List<PlaceRecord> {
            if (limit == 400) {
                asked.incrementAndGet()
                if (slowMs > 0) Thread.sleep(slowMs)
            }
            return inner.inBox(box, limit)
        }
    }

    private fun cafe(id: Long, at: LatLon) = Place(OsmId.parse("n$id")!!, at, mapOf("amenity" to "cafe", "name" to "Café $id"))

    @Test
    fun theRowsOfOneKilometreAskTheRegionsOnce() {
        // four kilometres, 61 cafés in each, read by 61 rows at once from a store as slow as a phone's
        val corners = listOf(LatLon(53.31, 3.21), LatLon(53.31, 3.25), LatLon(53.35, 3.21), LatLon(53.35, 3.25))
        val cafes = corners.flatMapIndexed { k, c ->
            (0 until 61).map { i -> cafe(97_100_000L + k * 100 + i, LatLon(c.lat + (i % 7 - 3) * 0.0005, c.lon + (i / 7 - 4) * 0.0005)) }
        }
        val store = Counted(MemoryPlaces(cafes, country = null), slowMs = 50)
        val s = testServices(stores = listOf(store))
        val rows = Executors.newFixedThreadPool(61)
        val go = CountDownLatch(1)
        try {
            val read = cafes.map { c -> rows.submit { go.await(); HereContext.region(s, c.location) } }
            go.countDown()
            read.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            rows.shutdownNow()
        }
        assertEquals("the regions asked once for each of the four kilometres", 4, store.asked.get())
    }

    @Test
    fun aListAcrossMoreThan256KilometresIsNotAskedAgain() {
        // 501 cafés each in a kilometre of its own, read one after another as a list's rows are, then again
        val cafes = (0 until 501).map { i -> cafe(97_200_000L + i, LatLon(53.61 + (i / 30) * 0.02, 3.21 + (i % 30) * 0.02)) }
        val store = Counted(MemoryPlaces(cafes, country = null))
        val s = testServices(stores = listOf(store))
        for (c in cafes) HereContext.region(s, c.location)
        assertEquals("each kilometre asked once", 501, store.asked.get())
        store.asked.set(0)
        for (c in cafes) HereContext.region(s, c.location)
        assertEquals("the list opened again asks nothing", 0, store.asked.get())
    }
}
