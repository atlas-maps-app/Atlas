package app.atlas.ui.search

import android.os.Looper
import app.atlas.Services
import app.atlas.ServicesConfig
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.search.MemoryPlaces
import app.atlas.search.PlaceOutline
import app.atlas.search.PlaceRecord
import app.atlas.search.PlaceSource
import app.atlas.search.PlaceStore
import app.atlas.search.TravelMode
import app.atlas.services.SettingsKeys
import app.atlas.testing.TempFiles
import app.atlas.testing.testServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections

/**
 * No region's store is asked on the main thread (the app maintainer, 18 November 2026, batch 4 A3): the search sheet's
 * moment read the regions' places 2 km around as it was made, in the home's first composition, and again at each
 * setting, move, change of sky and grid; and a brand's branches asked the country around as their flow was made, on
 * the caller's thread. The main thread reads what is known without them ([HereContext.regionNow]); they are asked off
 * it, and the moment made again where they say otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreOffTheMainThreadTest {
    /**
     * Two Bavarian towns, one for each test: what the regions said is kept per kilometre and count of places for the
     * whole run ([HereContext]), so a point another test asked about would be known before this one asks.
     */
    private val augsburg = LatLon(48.3705, 10.8978)
    private val regensburg = LatLon(49.0134, 12.1016)

    /** A third, for the regions' own files. */
    private val nuremberg = LatLon(49.4521, 11.0767)

    /** Epiphany 2026, a Tuesday, at 10:00 in Bavaria: a holiday there, not in Germany as a whole. */
    private val epiphany: Clock = Clock.fixed(Instant.parse("2026-01-06T09:00:00Z"), ZoneId.of("Europe/Berlin"))

    /** A store that notes each question it is asked on the main thread, with where it was asked from. */
    private class Watched(private val inner: PlaceSource) : PlaceSource {
        val onMain: MutableList<String> = Collections.synchronizedList(ArrayList())

        private fun <T> asked(what: String, answer: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onMain += what + " from " + Throwable().stackTrace.drop(2).take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
            }
            return answer()
        }

        override val size: Int get() = asked("size") { inner.size }
        override val bounds: BBox? get() = asked("bounds") { inner.bounds }
        override fun get(id: OsmId): PlaceRecord? = asked("get") { inner.get(id) }
        override fun inBox(box: BBox, limit: Int): List<PlaceRecord> = asked("inBox") { inner.inBox(box, limit) }
        override fun byTag(key: String, value: String?, box: BBox?, limit: Int): List<PlaceRecord> = asked("byTag") { inner.byTag(key, value, box, limit) }
        override fun byCategory(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> = asked("byCategory") { inner.byCategory(ids, box, limit) }
        override fun byBrand(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> = asked("byBrand") { inner.byBrand(ids, box, limit) }
        override fun text(text: String, near: LatLon?, box: BBox?, limit: Int): List<PlaceRecord> = asked("text") { inner.text(text, near, box, limit) }
        override fun estimate(key: String, value: String?): Int = asked("estimate") { inner.estimate(key, value) }
        override fun estimate(box: BBox): Int = asked("estimate") { inner.estimate(box) }
        override fun outline(id: OsmId): PlaceOutline? = asked("outline") { inner.outline(id) }
    }

    private fun place(id: Long, at: LatLon, vararg tags: Pair<String, String>) = Place(OsmId.parse("n$id")!!, at, tags.toMap())

    /** A quarter at [at] whose places name their country and Bavaria. */
    private fun quarter(at: LatLon) = listOf(
        place(71, LatLon(at.lat + 0.001, at.lon), "shop" to "bakery", "name" to "Bäckerei", "addr:country" to "DE", "addr:state" to "Bayern"),
        place(72, LatLon(at.lat + 0.001, at.lon + 0.001), "shop" to "supermarket", "name" to "Markt", "addr:country" to "DE",
            "addr:state" to "Bayern", "opening_hours" to "Mo-Sa 07:00-20:00; PH off"),
        place(73, LatLon(at.lat - 0.001, at.lon + 0.001), "amenity" to "pharmacy", "name" to "Apotheke", "addr:country" to "DE", "addr:state" to "Bayern"),
        // 900 m north, so the store holds the point the sheet moves to
        place(74, LatLon(at.lat + 0.008, at.lon), "amenity" to "cafe", "name" to "Café", "addr:country" to "DE", "addr:state" to "Bayern"),
    )

    /** Runs the main thread's queue until [done], at most 20 s. */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
    }

    @Test
    fun theSheetAsksTheRegionsOffTheMainThread() {
        val store = Watched(MemoryPlaces(quarter(augsburg), country = null))
        val s = testServices(clock = epiphany, stores = listOf(store))
        // made, moved, the sky changed, the grid read, a setting changed: on the main thread, as the home does
        val m = SearchSheetModel(s, augsburg, "en")
        // 670 m north: another kilometre, which the regions are asked about again, the quarter still within 2 km
        m.moveTo(LatLon(augsburg.lat + 0.006, augsburg.lon))
        m.skyChanged()
        until { m.state.value.gridOrigin != null }
        assertTrue("the grid came", m.state.value.gridOrigin != null)
        runBlocking { s.settings.edit { it[SettingsKeys.SEARCH_MODE] = TravelMode.BIKE.name } }
        until { m.state.value.prefs.mode == TravelMode.BIKE }
        // the moment reads Bavaria's holidays all the same, once the regions have said where it is
        until { m.state.value.moment.hours.publicHolidays?.isHoliday(LocalDate.of(2026, 1, 6)) == true }
        assertEquals("DE", m.state.value.moment.hours.country)
        assertTrue("Epiphany in Bavaria", m.state.value.moment.hours.publicHolidays?.isHoliday(LocalDate.of(2026, 1, 6)) == true)
        assertEquals("asked on the main thread: ${store.onMain.joinToString("; ")}", 0, store.onMain.size)
        s.close()
    }

    /**
     * The home's first frame opens no region's store (the app maintainer, 18 November 2026, batch 5 A5): the sheet's
     * moment and the rows' clocks read [HereContext.regionNow] in the first composition, which read the stores' count,
     * and the first to read the stores opens every region's file (`Services.placeStores`), on the main thread. The app's
     * own way here, no stores by the config: a file that does not open shows when they were opened.
     */
    @Test
    fun theHomesFirstFrameOpensNoRegionsStore() {
        val root = TempFiles.dir("atlas-cold")
        val places = File(root, "places").apply { mkdirs() }
        PlaceStore.open(File(places, "bavaria.db")).apply { importPlaces(quarter(nuremberg), regionName = "Bavaria") }.close()
        val damaged = File(places, "damaged.db").apply { writeText("not a database, though named as one") }
        val s = Services(RuntimeEnvironment.getApplication(), ServicesConfig(root = root, offline = true, mapEngine = false, clock = epiphany))
        assertTrue(Looper.myLooper() == Looper.getMainLooper())
        // what the home's first frame reads: the sheet's moment, the rows' countries and clocks
        HereContext.regionNow(s, nuremberg)
        HereContext.countryNow(s, nuremberg)
        HereContext.localeNow(s, place(7101, nuremberg), nuremberg, epiphany.zone)
        assertEquals("the regions' files were opened on the main thread", emptySet<File>(), s.unreadableStores.value.keys)
        // off it they are, and say Bavaria
        val region = runBlocking(Dispatchers.IO) { HereContext.region(s, nuremberg) }
        assertEquals("DE", region.country)
        assertTrue("Epiphany in Bavaria", region.hours(nuremberg, epiphany.zone).publicHolidays?.isHoliday(LocalDate.of(2026, 1, 6)) == true)
        assertEquals(setOf(damaged), s.unreadableStores.value.keys)
        // and the main thread reads what they said
        assertEquals("DE", HereContext.regionNow(s, nuremberg).country)
        s.close()
    }

    @Test
    fun aBrandsBranchesAskTheCountryInTheirFlow() {
        val store = Watched(MemoryPlaces(quarter(regensburg), country = null))
        val s = testServices(clock = epiphany, stores = listOf(store))
        // made on the main thread, as the sheet makes it: collected off it
        s.localData.branchesAsFound("albertheijn-0b5a2d", regensburg, 500.0)
        assertEquals("asked on the main thread: ${store.onMain.joinToString("; ")}", 0, store.onMain.size)
        s.close()
    }
}
