package app.atlas.search

import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Geometry
import app.atlas.places.Kinds
import app.atlas.places.Presets
import app.atlas.providers.OverpassElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A kind looks the same wherever it shows: kinds.tsv says how, and everything that draws a kind asks [Kinds.symbolOf].
 * The owner, 9 November: "Why is it inconsistent? Do they not share the same code?"
 */
class OneSymbolTableTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // the library: the table gives it the boards' library, id-tagging-schema Maki's open book (the bookbinder's)
    private val library = Presets.byId("amenity/library")!!
    private val amsterdam = LatLon(52.3658, 4.8856)

    /** The kinds the table draws otherwise than id-tagging-schema: the ones a second lookup would give away. */
    private val redrawn by lazy {
        Kinds.lines().mapNotNull { Presets.byId(it.kind) }.filter { p -> p.icon != null && Kinds.symbolOf(p).let { it != null && it != p.icon } }
    }

    @Test
    fun aKindIsFiledAsTheTableDrawsIt() {
        assertTrue("only ${redrawn.size} kinds are redrawn", redrawn.size > 100)
        for (p in redrawn) assertEquals(p.id, Kinds.symbolOf(p), PlaceRecord.symbolOf(p, null, p.tags, Geometry.POINT))
    }

    @Test
    fun aKindInTheWordsOfSearchIsAsTheTableDrawsIt() {
        var seen = 0
        for (p in redrawn) {
            val words = Words.split(p.fullName("en")).map { it.text }.filter { it != "&" && it != "," }
            if (words.isEmpty()) continue
            for (entry in Lexicon.lookup(words, 0, "en")) {
                val noun = entry.meaning as? Meaning.Noun ?: continue
                if (noun.id != "preset:${p.id}") continue
                assertEquals(p.id, Kinds.symbolOf(p), noun.symbol)
                seen++
            }
        }
        assertTrue("only $seen words of redrawn kinds", seen > 100)
    }

    @Test
    fun whatIsTypedShowsTheSymbolTheMapShows() {
        assertNotEquals("the library no longer tells the two lookups apart", library.icon, Kinds.symbolOf(library))
        PlaceStore.open(File(tmp.root, "typed.db")).use { s ->
            s.writer(regionName = "Grachtengordel").use { w ->
                w.add(OverpassElement(OsmType.NODE, 1, mapOf("amenity" to "library", "name" to "OBA Oosterdok"), amsterdam.lat, amsterdam.lon))
                w.finish()
            }
            val r = runBlocking { SearchEngine(s).search("library", SearchContext(location = amsterdam, lang = "en", now = Fixtures.saturday)) }
            assertEquals(Kinds.symbolOf(library), r.items.filterIsInstance<SearchItem.Kind>().first().symbol)
            assertEquals(Kinds.symbolOf(library), r.places.first().hit.symbol)
        }
    }

    @Test
    fun aPlaceFiledUnderAnEarlierSymbolFollowsTheTableWithNoFilingAnew() {
        val filedEarlier = OsmId(OsmType.NODE, 7)
        val noKind = OsmId(OsmType.NODE, 8)
        PlaceStore.open(File(tmp.root, "earlier.db")).use { s ->
            s.writer(regionName = "Grachtengordel").use { w ->
                val place = Place(filedEarlier, amsterdam, mapOf("amenity" to "library", "name" to "OBA Oosterdok"))
                val filed = PlaceRecord.of(place, Geometry.POINT, null)
                assertEquals(library.id, filed.preset)
                w.add(PlaceRecord(filed.place, filed.category, filed.brand, filed.preset, library.icon!!))
                // no kind to ask about: what was filed stands
                w.add(PlaceRecord(Place(noKind, amsterdam, mapOf("name" to "Iets")), null, null, null, "maki-star"))
                w.finish()
            }
            assertEquals(Kinds.symbolOf(library), s.get(filedEarlier)!!.symbol)
            assertEquals("maki-star", s.get(noKind)!!.symbol)
        }
    }
}
