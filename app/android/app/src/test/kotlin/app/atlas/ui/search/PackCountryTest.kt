package app.atlas.ui.search

import app.atlas.Dirs
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.mapview.CountryTiles
import app.atlas.mapview.MapEngine
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.render.data.Countries
import app.atlas.search.MemoryPlaces
import app.atlas.search.PlaceStore
import app.atlas.services.PackCountries
import app.atlas.services.RegionLayer
import app.atlas.testing.TempFiles
import app.atlas.testing.testServices
import app.atlas.ui.mine.MineFixtures
import app.atlas.ui.mine.offline.LayerRecord
import app.atlas.ui.mine.offline.RegionFiles
import app.atlas.ui.mine.offline.RegionMeta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.Locale

/**
 * Near a border the country is the pack's that holds the point, before the boxes' (the app maintainer, 18 November 2026,
 * batch 4 A5): [Countries]' smallest box puts Maastricht, Eindhoven, Venlo and Terneuzen in Belgium, so where neither a
 * place nor those around named their country they kept Belgium's holidays and no Dutch chain by name. A downloaded
 * region says its pack's country where its store holds places around the point; where none is downloaded, the
 * published country whose tiles serve the windows there. Neither does by its box alone: the Netherlands' reaches
 * Antwerp, which stays Belgian.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackCountryTest {
    private val maastricht = LatLon(50.8514, 5.6910)
    private val eindhoven = LatLon(51.4416, 5.4697)
    private val venlo = LatLon(51.3704, 6.1724)
    private val terneuzen = LatLon(51.3353, 3.8278)
    private val towns = listOf(maastricht, eindhoven, venlo, terneuzen)
    private val antwerp = LatLon(51.2194, 4.4025)

    /** Sluis, in Zeelandic Flanders: with it the Dutch store's box reaches Antwerp, as the real one's does, with none of its places there. */
    private val sluis = LatLon(51.3086, 3.3861)

    /** Lanaken, in Belgium, 5 km north-west of the Vrijthof: under the L12 tile over Maastricht's windows. */
    private val lanaken = LatLon(50.8931, 5.6472)

    /** Kanne, in Belgium, 4 km south of the Vrijthof, on the border: in both countries' boxes. */
    private val kanne = LatLon(50.8106, 5.6683)

    /** The Netherlands' pack as Atlas publishes it (`packs.json`), its box the extract's, which holds Antwerp too. */
    private val box = BBox(3.08, 50.75, 7.23, 53.7)
    private val published = """
        {"format": 1, "generated": "2026-11-01T00:00:00Z", "packs": [
          {"id": "netherlands", "name": "Netherlands", "country": "NL", "bbox": [3.08, 50.75, 7.23, 53.7],
           "files": [{"kind": "places", "url": "https://example.invalid/netherlands.db.zst", "bytes": 1}]}
        ]}
    """.trimIndent()

    /** The Netherlands' pack and Belgium's beside it, as Atlas would publish a second country. */
    private val belgiumBox = BBox(2.5, 49.4, 6.5, 51.6)
    private val twoPublished = """
        {"format": 1, "generated": "2026-11-01T00:00:00Z", "packs": [
          {"id": "netherlands", "name": "Netherlands", "country": "NL", "bbox": [3.08, 50.75, 7.23, 53.7],
           "files": [{"kind": "places", "url": "https://example.invalid/netherlands.db.zst", "bytes": 1}]},
          {"id": "belgium", "name": "Belgium", "country": "BE", "bbox": [2.5, 49.4, 6.5, 51.6],
           "files": [{"kind": "places", "url": "https://example.invalid/belgium.db.zst", "bytes": 1}]}
        ]}
    """.trimIndent()

    private val kept = MapEngine.countries to MapEngine.countryHolds

    /** The phone's locale as the test found it: a test that sets another has it put back. */
    private var phone: Locale = Locale.getDefault()

    @Before
    fun noTiles() {
        MapEngine.countries = emptyList()
        MapEngine.countryHolds = null
        phone = Locale.getDefault()
    }

    @After
    fun tilesAsTheyWere() {
        MapEngine.countries = kept.first
        MapEngine.countryHolds = kept.second
        Locale.setDefault(phone)
    }

    private fun dutchHolidays(at: LatLon, hours: app.atlas.places.OpeningHoursContext) {
        assertEquals("$at", "NL", hours.country)
        // King's Day, not Belgium's national day
        assertEquals("$at", true, hours.publicHolidays?.isHoliday(LocalDate.of(2027, 4, 27)))
        assertEquals("$at", false, hours.publicHolidays?.isHoliday(LocalDate.of(2027, 7, 21)))
    }

    @Test
    fun theBoxesAloneSayBelgium() {
        // what the four towns read before, where nothing else said: the finding
        for (t in towns + antwerp) assertEquals("$t", "BE", Countries.of(t))
    }

    @Test
    fun theNetherlandsRegionSaysItsCountry() {
        val root = TempFiles.dir("atlas-border")
        val dirs = Dirs(root, File(root, "cache"))
        // the region's store, its places naming no country, as a Geofabrik extract's mostly don't
        val store = PlaceStore.open(File(dirs.places, "netherlands.db"))
        store.importPlaces(
            (towns + sluis).flatMapIndexed { i, at ->
                listOf(0.002, -0.002).mapIndexed { j, d ->
                    Place(OsmId.parse("n${9100 + 2 * i + j}")!!, LatLon(at.lat + d, at.lon + d), mapOf("shop" to "bakery", "name" to "Bakkerij $i$j"))
                }
            },
            regionName = "Netherlands",
        )
        assertTrue("the store's box reaches Antwerp", store.bounds?.let { antwerp in it } == true)
        RegionFiles(dirs.regions, dirs.places, dirs.segments).save(RegionMeta("netherlands", "Netherlands", box, 0L, 0L, packId = "netherlands"))
        // the published list as Mine kept it when the region was taken
        File(dirs.regions, "packs-latest.json").writeText(published)
        val s = testServices(root = root, stores = listOf(store))
        val zone = ZoneId.of("Europe/Amsterdam")
        for (t in towns) {
            assertEquals("$t", "NL", HereContext.region(s, t).country)
            dutchHolidays(t, HereContext.hours(s, t, zone))
            // and as the main thread reads it once the regions were asked
            assertEquals("$t", "NL", HereContext.regionNow(s, t).country)
        }
        // the store's box holds Antwerp, its places do not
        assertEquals("BE", HereContext.region(s, antwerp).country)
        s.close()
        store.close()
    }

    @Test
    fun theNetherlandsTilesSayItsCountryWhereNoRegionIs() {
        val root = TempFiles.dir("atlas-border")
        // the published list as the map's windows kept it
        File(root, "engine/windows/index/packs-latest.json").apply { parentFile?.mkdirs() }.writeText(published)
        val s = testServices(root = root)
        assertEquals("no region downloaded", 0, s.placeStores.size)
        MapEngine.countries = listOf(CountryTiles("netherlands", "Netherlands", box, levels = true))
        // the tiles' index in memory: the Dutch towns' windows are listed, Antwerp's are not
        s.packCountries.tiles = PackCountries.Tiles { id, lat, lon ->
            if (id == "netherlands" && towns.any { Geo.distance(it, LatLon(lat, lon)) < 5_000.0 }) 14 else 0
        }
        val zone = ZoneId.of("Europe/Amsterdam")
        for (t in towns) {
            assertEquals("$t", "NL", HereContext.region(s, t).country)
            dutchHolidays(t, HereContext.hours(s, t, zone))
        }
        assertEquals("BE", HereContext.region(s, antwerp).country)
        // the main thread reads the same, from what is known without a store or a file
        for (t in towns) assertEquals("$t", "NL", HereContext.regionNow(s, t).country)
        assertEquals("BE", HereContext.regionNow(s, antwerp).country)
        // a country whose tiles' index is not in memory yet says nothing by its box
        s.packCountries.tiles = PackCountries.Tiles { _, _, _ -> null }
        assertFalse("by the box alone", HereContext.region(s, maastricht).country == "NL")
        s.close()
    }

    /**
     * The country question reads the tiles' indexes in memory and never has one read or fetched (the app maintainer, 18
     * November 2026, batch 5 A3): asked where a published country's index is not in memory, it asks the map's own hook
     * nothing (MapData's, which has the index downloaded, 1.5 MB for the Netherlands, where only the camera asked
     * before), and the boxes say.
     */
    @Test
    fun aCountryWhoseTilesIndexIsNotInMemoryIsAskedForNothingAndTheBoxesSay() {
        val root = TempFiles.dir("atlas-border")
        File(root, "engine/windows/index/packs-latest.json").apply { parentFile?.mkdirs() }.writeText(published)
        // a region whose one place names no country, of no pack: the regions say none, so their state's reading asks too
        val around = MemoryPlaces(listOf(Place(OsmId.parse("n9301")!!, LatLon(venlo.lat + 0.001, venlo.lon), mapOf("shop" to "bakery"))), country = null)
        val s = testServices(root = root, stores = listOf(around))
        MapEngine.countries = listOf(CountryTiles("netherlands", "Netherlands", box, levels = true))
        // the map's own hook as MapData answers it before the index is in memory: null, having started its download
        val asked = Collections.synchronizedList(ArrayList<String>())
        MapEngine.countryHolds = MapEngine.CountryHolds { c, _, _ -> asked += c.id; null }
        val zone = ZoneId.of("Europe/Amsterdam")
        for (t in towns + antwerp) {
            assertEquals("$t", Countries.of(t), HereContext.region(s, t).country)
            assertEquals("$t", Countries.of(t), HereContext.regionNow(s, t).country)
            assertEquals("$t", Countries.of(t), HereContext.hours(s, t, zone).country)
            HereContext.localeAt(s, t, maastricht, zone)
        }
        assertEquals("the country question asked for a tiles' index", emptyList<String>(), asked)
        s.close()
    }

    /**
     * How finely two countries' tiles' indexes list a point by these towns: the Netherlands' window at Maastricht and its
     * L12 tile over Lanaken and Kanne, Belgium's window at Lanaken and its L12 tile over Maastricht and Kanne; nothing
     * elsewhere; null for a country in [unread], whose index is not in memory.
     */
    private fun level(id: String, lat: Double, lon: Double, unread: Set<String>): Int? {
        if (id in unread) return null
        val p = LatLon(lat, lon)
        return when {
            Geo.distance(maastricht, p) < 1_000.0 -> if (id == "netherlands") 14 else 12
            Geo.distance(lanaken, p) < 1_000.0 -> if (id == "belgium") 14 else 12
            Geo.distance(kanne, p) < 1_000.0 -> 12
            else -> 0
        }
    }

    /**
     * Where two countries' tiles hold a point, the finer tile says whose it is, then the boxes (the app maintainer, 18
     * November 2026, batch 5 A3): a level-12 tile spans a border, Maastricht's holds Lanaken, so the first country in the
     * list whose tiles hold the point is no answer. Where a country whose box holds the point has no index in memory, a
     * level tile of the other's says nothing; its window still does.
     */
    @Test
    fun whereTwoCountriesTilesHoldAPointTheFinerTileSaysThenTheBoxes() {
        val root = TempFiles.dir("atlas-border")
        File(root, "engine/windows/index/packs-latest.json").apply { parentFile?.mkdirs() }.writeText(twoPublished)
        val s = testServices(root = root)
        assertEquals("BE", Countries.of(lanaken))
        var unread = emptySet<String>()
        s.packCountries.tiles = PackCountries.Tiles { id, lat, lon -> level(id, lat, lon, unread) }
        // the map's own hook has an index fetched where it is not in memory: the country question never asks it
        MapEngine.countryHolds = MapEngine.CountryHolds { _, _, _ -> error("the country question asked the camera's hook") }
        val nl = CountryTiles("netherlands", "Netherlands", box, levels = true)
        val be = CountryTiles("belgium", "Belgium", belgiumBox, levels = true)
        for (order in listOf(listOf(nl, be), listOf(be, nl))) {
            MapEngine.countries = order
            val ids = order.map { it.id }
            unread = emptySet()
            // each town its own country's window's, not the other's level tile across the border
            assertEquals("$ids", "NL", HereContext.region(s, maastricht).country)
            assertEquals("$ids", "BE", HereContext.region(s, lanaken).country)
            assertEquals("$ids", "BE", HereContext.regionNow(s, lanaken).country)
            // two level tiles alike: the boxes say
            assertEquals("$ids", Countries.of(kanne), HereContext.region(s, kanne).country)
            // Belgium's index not in memory: the Netherlands' window still says Maastricht; its level tile over Lanaken,
            // which Belgium's windows may hold, says nothing
            unread = setOf("belgium")
            assertEquals("$ids", "NL", HereContext.region(s, maastricht).country)
            assertEquals("$ids", "BE", HereContext.region(s, lanaken).country)
        }
        s.close()
    }

    /**
     * A region whose pack is named at the end of its download says its pack's country from the next ask, in the same
     * run (the app maintainer, 19 November 2026, batch 6 A1): its file named no pack while its places were attached
     * and asked, as its routing and voices still came, and what was kept for its store then (no pack) and for the
     * kilometres asked meanwhile (no country) was kept for the run: a typed search's hits asked each cell again, and
     * Maastricht read the box's Belgium till the app was killed. ff1d7649 read the file at each ask. Both are let go
     * once the file is written again, by its time and length.
     */
    @Test
    fun aRegionWhosePackIsNamedAtTheEndOfItsDownloadSaysItsCountryInTheSameRun() {
        // a phone whose country's box holds none of these towns, so that the phone's country says nothing here
        Locale.setDefault(Locale.US)
        val root = TempFiles.dir("atlas-written")
        val dirs = Dirs(root, File(root, "cache"))
        // seven places naming no country: no other store by these towns holds seven, and what the regions said is kept
        // for the whole JVM by their count
        val store = PlaceStore.open(File(dirs.places.apply { mkdirs() }, "netherlands.db"))
        val near = listOf(maastricht, maastricht, maastricht, venlo, venlo, eindhoven, eindhoven)
        store.importPlaces(
            near.mapIndexed { i, at ->
                Place(OsmId.parse("n${9_700 + i}")!!, LatLon(at.lat + 0.001 * (i % 3), at.lon - 0.001 * (i % 2)), mapOf("shop" to "bakery", "name" to "Bakker $i"))
            },
            regionName = "Netherlands",
        )
        File(dirs.regions.apply { mkdirs() }, "packs-latest.json").writeText(published)
        val s = testServices(root = root, stores = listOf(store))
        s.mine.useDeps(MineFixtures.packDeps(s, s.clock.instant()))
        // begun as a download begins it, in the box of the Netherlands' pack: its file names no pack yet
        s.mine.engine.begin("netherlands", "Netherlands", BBox(3.3, 50.75, 7.2, 53.5), setOf(RegionLayer.PLACES))
        // its places in, recorded as their install records them: the store is attached, and asked, from here on
        s.mine.files.edit("netherlands") { it.copy(layers = it.layers + (RegionLayer.PLACES to LayerRecord("pack", 1L, 0L, "p"))) }
        assertNull(s.packCountries.ofStore(store))
        assertEquals("the box's, no pack named yet", "BE", HereContext.region(s, maastricht).country)
        // the rest come: its pack written as resume writes it at its end, every layer it wants being in
        runBlocking { s.mine.engine.resume("netherlands") }
        assertEquals("netherlands", s.mine.files.get("netherlands")?.packId)
        // the next asks, in the same run
        assertEquals("NL", s.packCountries.ofStore(store))
        val hit = store.inBox(BBox.around(maastricht, 500.0), 5).first()
        assertEquals("a typed search's hit", "NL", HereContext.regionOf(s, hit))
        assertEquals("the kilometre asked before", "NL", HereContext.region(s, maastricht).country)
        assertEquals("NL", HereContext.regionNow(s, maastricht).country)
        dutchHolidays(maastricht, HereContext.hours(s, maastricht, ZoneId.of("Europe/Amsterdam")))
        assertEquals("a kilometre asked for the first time", "NL", HereContext.region(s, venlo).country)
        s.close()
        store.close()
    }

    /**
     * Its neighbour, as before (batch 5 A6): a region of no pack whose file is not written again is read once. Its time
     * and length say it is the same file, so one changed under them, as no writer of a region's file changes it, is not
     * read again.
     */
    @Test
    fun aRegionOfNoPackWhoseFileIsNotWrittenAgainIsReadOnce() {
        val root = TempFiles.dir("atlas-once")
        val dirs = Dirs(root, File(root, "cache"))
        val store = PlaceStore.open(File(dirs.places.apply { mkdirs() }, "custom.db"))
        store.importPlaces(listOf(Place(OsmId.parse("n9721")!!, maastricht, mapOf("shop" to "bakery"))), regionName = "Custom")
        val files = RegionFiles(dirs.regions, dirs.places, dirs.segments)
        // a region of no pack, its file's time a whole second
        val first = RegionMeta("custom", "Custom" + "x".repeat(21), box, 0L, 0L)
        files.save(first)
        val copy = File(dirs.regions, "packs-latest.json").apply { writeText(published) }
        val countries = PackCountries(files, listOf(copy))
        val at = 1_700_000_000_000L
        assertTrue(files.meta("custom").setLastModified(at))
        assertNull(countries.ofStore(store))
        // the same time and length, the file naming a pack (its name 21 characters shorter)
        val named = RegionMeta("custom", "Custom", box, 0L, 0L, packId = "netherlands")
        assertEquals(first.toJson().length, named.toJson().length)
        files.meta("custom").apply {
            writeText(named.toJson())
            assertTrue(setLastModified(at))
        }
        assertNull("its file was read again", countries.ofStore(store))
        store.close()
    }
}
