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
import app.atlas.search.PlaceStore
import app.atlas.testing.TempFiles
import app.atlas.testing.testServices
import app.atlas.ui.mine.offline.RegionFiles
import app.atlas.ui.mine.offline.RegionMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

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

    /** The Netherlands' pack as Atlas publishes it (`packs.json`), its box the extract's, which holds Antwerp too. */
    private val box = BBox(3.08, 50.75, 7.23, 53.7)
    private val published = """
        {"format": 1, "generated": "2026-11-01T00:00:00Z", "packs": [
          {"id": "netherlands", "name": "Netherlands", "country": "NL", "bbox": [3.08, 50.75, 7.23, 53.7],
           "files": [{"kind": "places", "url": "https://example.invalid/netherlands.db.zst", "bytes": 1}]}
        ]}
    """.trimIndent()

    private val kept = MapEngine.countries to MapEngine.countryHolds

    @Before
    fun noTiles() {
        MapEngine.countries = emptyList()
        MapEngine.countryHolds = null
    }

    @After
    fun tilesAsTheyWere() {
        MapEngine.countries = kept.first
        MapEngine.countryHolds = kept.second
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
        // the tiles' index: the Dutch towns' windows are listed, Antwerp's are not
        MapEngine.countryHolds = MapEngine.CountryHolds { c, lat, lon ->
            c.id == "netherlands" && towns.any { Geo.distance(it, LatLon(lat, lon)) < 5_000.0 }
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
        // a country whose tiles' index is not on the phone yet says nothing by its box
        MapEngine.countryHolds = MapEngine.CountryHolds { _, _, _ -> null }
        assertFalse("by the box alone", HereContext.region(s, maastricht).country == "NL")
        s.close()
    }
}
