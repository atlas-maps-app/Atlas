package app.atlas.ui.search

import android.os.Looper
import app.atlas.Dirs
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.search.PlaceStore
import app.atlas.search.SearchItem
import app.atlas.testing.TempFiles
import app.atlas.testing.testServices
import app.atlas.ui.mine.offline.RegionFiles
import app.atlas.ui.mine.offline.RegionMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.util.Collections

/**
 * A typed search's results that are a downloaded region's own places are written as the region's country writes them,
 * each region asked once (the app maintainer, 18 November 2026, batch 5 A6): each hit that named no country asked the
 * country at its point, for each kilometre cell a 2 km query of the regions, the region's file and a covers query,
 * before the results showed: about a second at a first search whose hits lie across the Netherlands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResultCountryTest {
    /** Five churches across the Netherlands, each with an address that names no country. */
    private val churches = listOf(
        Triple("Zwolle", LatLon(52.5125, 6.0944), "8011 PK"),
        Triple("Nijmegen", LatLon(51.8475, 5.8640), "6511 KH"),
        Triple("Breda", LatLon(51.5888, 4.7758), "4811 XM"),
        Triple("Leeuwarden", LatLon(53.2012, 5.7999), "8911 BK"),
        Triple("Goes", LatLon(51.5040, 3.8880), "4461 HV"),
    )

    private val published = """
        {"format": 1, "generated": "2026-11-01T00:00:00Z", "packs": [
          {"id": "netherlands", "name": "Netherlands", "country": "NL", "bbox": [3.08, 50.75, 7.23, 53.7],
           "files": [{"kind": "places", "url": "https://example.invalid/netherlands.db.zst", "bytes": 1}]}
        ]}
    """.trimIndent()

    /** Runs the main thread's queue until [done], at most 20 s. */
    private fun until(done: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!done() && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
            Thread.sleep(20)
        }
        assertTrue("timed out", done())
    }

    /**
     * The churches searched for through the search screen's context, in a store whose region was taken from pack
     * [packId]: the results, and each point whose country the engine asked.
     */
    private fun searched(packId: String?): Pair<List<SearchItem.PlaceResult>, List<LatLon>> {
        val root = TempFiles.dir("atlas-results")
        val dirs = Dirs(root, File(root, "cache"))
        val store = PlaceStore.open(File(dirs.places.apply { mkdirs() }, "netherlands.db"))
        store.importPlaces(
            churches.mapIndexed { i, (town, at, postcode) ->
                Place(
                    OsmId.parse("n${9_401 + i}")!!, at,
                    mapOf("name" to "Martinikerk", "amenity" to "place_of_worship", "addr:street" to "Kerkplein", "addr:housenumber" to "${i + 1}",
                        "addr:postcode" to postcode, "addr:city" to town),
                )
            },
            regionName = "Netherlands",
        )
        // the region the store was taken from, and the published list that names its pack's country
        RegionFiles(dirs.regions, dirs.places, dirs.segments).save(RegionMeta("netherlands", "Netherlands", BBox(3.08, 50.75, 7.23, 53.7), 0L, 0L, packId = packId))
        File(dirs.regions, "packs-latest.json").writeText(published)
        val s = testServices(root = root, stores = listOf(store))
        val asked = Collections.synchronizedList(ArrayList<LatLon>())
        val vm = SearchViewModel(s, "Martinikerk", null, { Services.WESTERKERK }, "en", online = { false }, search = { t, c ->
            s.search.search(t, c.copy(countryAt = { p -> asked += p; c.countryAt(p) }))
        })
        until { vm.state.value.results != null && !vm.state.value.searching }
        val results = vm.state.value.results!!.items.filterIsInstance<SearchItem.PlaceResult>()
        s.close()
        return results to asked.toList()
    }

    /** Each church's address as the Netherlands writes it: the street, then the number. */
    private fun assertWrittenAsTheNetherlandsWritesThem(results: List<SearchItem.PlaceResult>) {
        assertEquals(churches.size, results.size)
        for ((i, r) in results.sortedBy { it.hit.record.id.toString() }.withIndex()) {
            val (town, _, postcode) = churches[i]
            assertTrue("${r.subtitle}", r.subtitle.orEmpty().contains("Kerkplein ${i + 1}, $postcode $town"))
        }
    }

    @Test
    fun aRegionsOwnPlacesAreWrittenAsItsCountryWritesThemWithNoQueryAtEach() {
        val (results, asked) = searched(packId = "netherlands")
        assertWrittenAsTheNetherlandsWritesThem(results)
        assertEquals("the country asked at a point of the region's own", emptyList<LatLon>(), asked)
    }

    /** France's pack and Belgium's, as Atlas would publish them. */
    private val twoPublished = """
        {"format": 1, "generated": "2026-11-01T00:00:00Z", "packs": [
          {"id": "france", "name": "France", "country": "FR", "bbox": [-5.2, 41.3, 9.6, 51.1],
           "files": [{"kind": "places", "url": "https://example.invalid/france.db.zst", "bytes": 1}]},
          {"id": "belgium", "name": "Belgium", "country": "BE", "bbox": [2.5, 49.4, 6.5, 51.6],
           "files": [{"kind": "places", "url": "https://example.invalid/belgium.db.zst", "bytes": 1}]}
        ]}
    """.trimIndent()

    /**
     * A place two regions hold is written as the country at its point writes it, not as the first region's (the app
     * maintainer, 19 November 2026, batch 6 B9: the first region's country passed every test, as no test's place was
     * two regions'): a bakery in Comines, Belgium, on the Lys, held by France's region and by Belgium's, the places
     * around it naming Belgium; France's region first, then Belgium's. Belgium writes the street first, France the
     * number.
     */
    @Test
    fun aPlaceTwoRegionsHoldIsWrittenAsTheCountryAtItsPointWritesIt() {
        val comines = LatLon(50.7686, 3.0054)
        val bakery = Place(
            OsmId.parse("n9501")!!, comines,
            mapOf("name" to "Boulangerie du Pont", "shop" to "bakery", "addr:street" to "Rue de Wervik", "addr:housenumber" to "12",
                "addr:postcode" to "7780", "addr:city" to "Comines"),
        )
        val around = (0 until 5).map { i ->
            Place(OsmId.parse("n${9_511 + i}")!!, LatLon(comines.lat + 0.001 * (i + 1), comines.lon), mapOf("shop" to "convenience", "name" to "Magasin $i", "addr:country" to "BE"))
        }
        for (franceFirst in listOf(true, false)) {
            val root = TempFiles.dir("atlas-results")
            val dirs = Dirs(root, File(root, "cache"))
            val files = RegionFiles(dirs.regions, dirs.places, dirs.segments)
            fun region(id: String, box: BBox): PlaceStore {
                val store = PlaceStore.open(File(dirs.places.apply { mkdirs() }, "$id.db"))
                store.importPlaces(listOf(bakery) + around, regionName = id)
                files.save(RegionMeta(id, id, box, 0L, 0L, packId = id))
                return store
            }
            val france = region("france", BBox(-5.2, 41.3, 9.6, 51.1))
            val belgium = region("belgium", BBox(2.5, 49.4, 6.5, 51.6))
            File(dirs.regions, "packs-latest.json").writeText(twoPublished)
            val s = testServices(root = root, stores = if (franceFirst) listOf(france, belgium) else listOf(belgium, france))
            val vm = SearchViewModel(s, "Boulangerie du Pont", null, { comines }, "en", online = { false }, search = { t, c -> s.search.search(t, c) })
            until { vm.state.value.results != null && !vm.state.value.searching }
            val hit = vm.state.value.results!!.items.filterIsInstance<SearchItem.PlaceResult>().single { it.hit.record.id.toString() == "n9501" }
            val line = hit.subtitle.orEmpty()
            assertTrue("France's region first $franceFirst: $line", line.contains("Rue de Wervik 12") && !line.contains("12 Rue de Wervik"))
            s.close()
        }
    }

    /**
     * Its neighbour: a region taken from no pack names no country, so its places' country is asked at their points, as
     * before.
     */
    @Test
    fun aRegionTakenFromNoPackLeavesItsPlacesCountryToTheirPoints() {
        val (results, asked) = searched(packId = null)
        assertWrittenAsTheNetherlandsWritesThem(results)
        assertEquals(churches.map { it.second }.toSet(), asked.toSet())
    }
}
