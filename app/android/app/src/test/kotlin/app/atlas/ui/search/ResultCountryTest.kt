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
