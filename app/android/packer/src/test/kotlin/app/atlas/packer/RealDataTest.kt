package app.atlas.packer

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.packer.pbf.PbfReader
import app.atlas.search.OverpassImport
import app.atlas.search.PlaceStore
import app.atlas.search.SearchContext
import app.atlas.search.SearchEngine
import app.atlas.search.SearchItem
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Geofabrik's Monaco extract of 13 October 2026 (kept in the test resources, 0.7 MB): the reader's counts against
 * `osmium` (pyosmium 4 gave exactly these), and a pack built from it answering real searches. Luxembourg (48 MB) runs
 * too when it has been downloaded to cache/packs/luxembourg.osm.pbf.
 */
class RealDataTest {
    companion object {
        private lateinit var dir: File
        private lateinit var pack: PlacesPack
        private lateinit var store: PlaceStore

        val monaco: File by lazy { File(RealDataTest::class.java.getResource("/monaco-2026-10-13.osm.pbf")!!.toURI()) }

        @BeforeClass
        @JvmStatic
        fun build() {
            dir = Files.createTempDirectory("packs").toFile()
            val db = File(dir, "monaco.places.db")
            pack = PlacesPackBuilder.build(monaco, db, PackOptions(threads = 3), name = "Monaco", keepDb = true)
            store = PlaceStore.open(db)
        }

        @AfterClass
        @JvmStatic
        fun clean() {
            store.close()
            dir.deleteRecursively()
        }
    }

    /** What a search found, for the test report. */
    private fun report(r: app.atlas.search.SearchResults) {
        val top = r.items.take(4).joinToString(" | ") { item ->
            when (item) {
                is SearchItem.PlaceResult -> "${item.title} (${item.hit.record.id}, ${item.hit.distance?.toInt()} m)"
                is SearchItem.Kind -> "${item.label} ×${item.count}"
                else -> item::class.simpleName ?: "?"
            }
        }
        println("\"${r.text}\" in ${r.micros / 1000} ms: $top")
    }

    @Test
    fun theReaderCountsWhatOsmiumCounts() {
        val r = PbfReader(monaco)
        val h = r.header
        assertEquals("osmium/1.16.0", h.writingProgram)
        assertEquals(Instant.parse("2026-10-13T20:21:31Z"), h.replicationTime)
        assertEquals(4936L, h.replicationSequence)
        assertTrue(h.sortedByTypeThenId)
        assertEquals(7.408583, h.bbox!!.west, 1e-6)
        assertEquals(43.75293, h.bbox!!.north, 1e-6)
        val c = r.count()
        assertEquals(41_708L, c.nodes)
        assertEquals(6_249L, c.ways)
        assertEquals(348L, c.relations)
        assertEquals(4_252L, c.taggedNodes)
        assertEquals(50_625L, c.wayRefs)
        assertEquals(40_471L, c.members)
        assertEquals(45_195L, c.tags)
        assertEquals(14_219_141_884L, c.maxNodeId)
        assertEquals(1_562_479_484L, c.maxWayId)
        assertEquals(20_948_343L, c.maxRelationId)
        assertTrue(c.sorted)
        // one thread or several, the same
        assertEquals(c.nodes, PbfReader(monaco, threads = 1).count().nodes)
    }

    @Test
    fun monacoBecomesAWholeStore() {
        val s = pack.stats
        assertEquals(41_708L, s.nodes)
        assertTrue("${s.places} places", s.places in 4_000..6_000)
        assertEquals(640, s.trees)
        assertEquals(0L, s.unlocated)
        assertEquals(s.places, store.size)
        assertEquals("2026-10-13T20:21:31Z", store.meta("osm_timestamp"))
        assertTrue(pack.file.name.endsWith(".gz") && pack.file.length() < pack.rawBytes / 2)
        // everything sits inside the extract's box (relations reach a little beyond, into the sea and France)
        val near = BBox(7.2, 43.3, 7.7, 43.8)
        assertTrue(store.inBox(near, Int.MAX_VALUE).size == store.size)
    }

    @Test
    fun placesSitWhereTheyAre() {
        val casino = store.get(OsmId(OsmType.NODE, 4416197079))!!
        assertEquals("Casino de Monte Carlo", casino.tags["name"])
        assertEquals("amenity/casino", casino.preset)
        // the museum building: its pin inside its outline
        val museum = store.get(OsmId(OsmType.WAY, 23715051))!!
        assertTrue(museum.location in museum.place.bbox!!)
        assertEquals("tourism/museum", museum.preset)
        // the principality: a boundary relation, pinned on land in Monaco
        val monaco = store.get(OsmId(OsmType.RELATION, 36990))!!
        assertTrue(monaco.location in BBox(7.409, 43.724, 7.440, 43.752))
        assertEquals(store.trees(BBox(7.40, 43.72, 7.44, 43.76)).size, pack.stats.trees)
    }

    @Test
    fun realSearchesFindRealPlaces() = runBlocking {
        val engine = SearchEngine(store)
        val here = SearchContext(location = LatLon(43.7384, 7.4246), lang = "fr")
        fun SearchItem.osmId(): OsmId? = (this as? SearchItem.PlaceResult)?.hit?.record?.id

        val casino = engine.search("casino de monte carlo", here)
        assertEquals(OsmId(OsmType.NODE, 4416197079), casino.places.first().hit.record.id)

        val museum = engine.search("musee oceanographique", here)
        assertTrue(museum.items.take(4).any { it.osmId() == OsmId(OsmType.WAY, 23715051) })

        val pharmacies = engine.search("pharmacie", here)
        val kind = pharmacies.items.filterIsInstance<SearchItem.Kind>().firstOrNull()
        val count = kind?.count ?: pharmacies.places.count { it.hit.record.tags["amenity"] == "pharmacy" }
        assertTrue("$count pharmacies", count >= 15)
        assertEquals(store.byTag("amenity", "pharmacy", null, 100).size, store.count("amenity", "pharmacy"))

        val typo = engine.search("stade lous II", here)
        assertTrue(typo.places.take(3).any { it.hit.record.tags["name"] == "Stade Louis II" })
        for (r in listOf(casino, museum, pharmacies, typo)) report(r)
    }

    /**
     * The same places as an Overpass import: RegionImport's query for the extract's box, saved once to
     * cache/packs/monaco-overpass.json, imported by PlaceStore itself. Elements the extract does not have (France
     * around Monaco) are left out; what remains must agree but for the few edits between the two data times.
     */
    @Test
    fun monacoKeepsWhatAnOverpassImportKeeps() {
        val json = File(System.getProperty("atlas.repo"), "cache/packs/monaco-overpass.json")
        assumeTrue("save RegionImport.ql(extract box) answered by Overpass to $json to run this", json.isFile)
        val inExtract = HashSet<OsmId>()
        PbfReader(monaco).read(object : app.atlas.packer.pbf.OsmHandler {
            override fun node(node: app.atlas.packer.pbf.PbfNode) { if (node.tags.isNotEmpty()) inExtract += OsmId(OsmType.NODE, node.id) }
            override fun way(way: app.atlas.packer.pbf.PbfWay) { if (way.tags.isNotEmpty()) inExtract += OsmId(OsmType.WAY, way.id) }
            override fun relation(relation: app.atlas.packer.pbf.PbfRelation) { if (relation.tags.isNotEmpty()) inExtract += OsmId(OsmType.RELATION, relation.id) }
        })
        val world = BBox(-180.0, -90.0, 180.0, 90.0)
        val ours = store.inBox(world, Int.MAX_VALUE).mapNotNull { it.id }.toSet()
        val overpass = PlaceStore.inMemory()
        var acrossTheAntimeridian = 0
        overpass.writer(regionName = "Monaco (Overpass)").use { w ->
            // Overpass gives a box with west > east to a relation across the antimeridian (France, with its overseas
            // lands); the store's R*Tree refuses those, so they stay out here
            json.bufferedReader().use { r -> OverpassImport.read(r) { e -> if (e.bounds.let { it == null || it.west <= it.east }) w.add(e) else acrossTheAntimeridian++ } }
            w.finish()
        }
        val theirs = overpass.inBox(world, Int.MAX_VALUE).mapNotNull { it.id }.filter { it in inExtract }.toSet()
        val onlyOurs = ours - theirs
        val onlyTheirs = theirs - ours
        println("Monaco: ${ours.size} places from the PBF, ${theirs.size} from Overpass within the extract; only PBF ${onlyOurs.size} ${onlyOurs.take(8)}, only Overpass ${onlyTheirs.size} ${onlyTheirs.take(8)}")
        assertTrue(theirs.size > 4_000)
        assertTrue("${onlyOurs.size + onlyTheirs.size} differ", onlyOurs.size + onlyTheirs.size <= ours.size / 100)
        // and the shapes agree: ways (whole in Geofabrik's extracts) have Overpass's box, and both pins lie in it,
        // Overpass's at the box's middle, the packer's at the centroid; relations reach beyond the extract, so not them
        overpass.use { o ->
            var ways = 0
            var moved = 0
            val both = (ours intersect theirs).filter { it.type != OsmType.RELATION }
            for (id in both) {
                val a = store.get(id)!!
                val b = o.get(id)!!
                // the same seven decimals give the same doubles, to the bit; an element edited between the two data
                // times may have moved
                if (id.type == OsmType.NODE) {
                    if (a.location != b.location) moved++
                    continue
                }
                val box = a.place.bbox ?: continue
                val theirBox = b.place.bbox!!
                if (theirBox != box) { moved++; continue }
                assertTrue("$id", a.location in box.buffer(0.5))
                ways++
            }
            println("  $ways way boxes agree, $moved elements moved")
            assertTrue(ways > 1_000)
            assertTrue(moved <= both.size / 200)
        }
        println("  ($acrossTheAntimeridian Overpass elements across the antimeridian left out)")
    }

    /**
     * Planetiler's tiles for Luxembourg, when built into cache/packs/out (as the workflow runs it): the archive opens
     * with `:tiles`, its z14 tile in the city has the OpenMapTiles layers, its POI ids join the place store, and the
     * manifest reads its zooms, schema and OSM time.
     */
    @Test
    fun planetilerTilesWhenBuilt() = runBlocking {
        val out = File(System.getProperty("atlas.repo"), "cache/packs/out")
        val pmtiles = File(out, "luxembourg.pmtiles")
        assumeTrue("run Planetiler on Luxembourg into $pmtiles to run this", pmtiles.isFile)
        app.atlas.tiles.PmTiles.open(pmtiles).use { pm ->
            assertEquals(0, pm.minZoom)
            assertEquals(14, pm.maxZoom)
            assertEquals("3.16.0", pm.metadata["version"])
            assertTrue(pm.attribution.contains("OpenStreetMap"))
            val centre = app.atlas.geo.Mercator.tileAt(LatLon(49.6116, 6.1319), 14)
            val tile = app.atlas.tiles.Mvt.decode(pm.tile(centre)!!)
            for (layer in listOf("transportation", "transportation_name", "building", "poi", "housenumber", "landuse")) {
                assertTrue("$layer in ${tile.layers.keys}", layer in tile.layers.keys)
            }
            val db = File(out, "luxembourg.places.db")
            if (db.isFile) PlaceStore.open(db).use { lux ->
                // a POI's id is the OSM id times ten plus 1, 2 or 3 for node, way or relation
                val pois = tile.layers.getValue("poi").features.filter { it.hasId && it.props.string("name") != null }
                val joined = pois.count { f ->
                    val type = when (f.id % 10) { 1L -> OsmType.NODE; 2L -> OsmType.WAY; else -> OsmType.RELATION }
                    lux.get(OsmId(type, f.id / 10))?.tags?.get("name") == f.props.string("name")
                }
                println("Luxembourg z14 ${centre}: ${tile.layers.size} layers, ${pois.size} named POIs, $joined found by id in the store")
                assertTrue("$joined of ${pois.size}", joined >= pois.size * 9 / 10)
            }
        }
        val dir = Files.createTempDirectory("pm").toFile()
        try {
            pmtiles.copyTo(File(dir, "luxembourg.pmtiles"))
            val e = Manifest.entry(dir, "luxembourg", "https://example.org/releases/download/packs-1", null)
            assertEquals(14, (e["maxzoom"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
            assertEquals("2026-10-13T20:21:31Z", (e["osm_timestamp"] as kotlinx.serialization.json.JsonPrimitive).content)
            val tiles = (e["files"] as kotlinx.serialization.json.JsonArray).single() as kotlinx.serialization.json.JsonObject
            assertEquals("openmaptiles 3.16.0", (tiles["schema"] as kotlinx.serialization.json.JsonPrimitive).content)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun luxembourgWhenDownloaded() {
        val pbf = File(System.getProperty("atlas.repo"), "cache/packs/luxembourg.osm.pbf")
        assumeTrue("download europe/luxembourg-latest.osm.pbf to $pbf to run this", pbf.isFile)
        val counts = PbfReader(pbf).count()
        assertTrue(counts.sorted)
        assertTrue(counts.nodes > 3_000_000 && counts.ways > 400_000 && counts.relations > 5_000)
        PlaceStore.inMemory().use { lux ->
            val stats = PlacePacker(PackOptions()).pack(pbf, lux, "Luxembourg") { println("luxembourg $it") }
            assertEquals(counts.nodes, stats.nodes)
            assertTrue("${stats.places} places", stats.places > 200_000)
            assertTrue(stats.trees > 50_000)
            val engine = SearchEngine(lux)
            val here = SearchContext(location = LatLon(49.6116, 6.1319), lang = "fr")
            runBlocking {
                val gare = engine.search("gare centrale", here)
                assertTrue(gare.places.take(5).any { (it.hit.record.tags["name"] ?: "").contains("Gare", ignoreCase = true) })
                val kirchberg = engine.search("kirchberg", here)
                assertTrue(kirchberg.places.isNotEmpty())
                report(gare)
                report(kirchberg)
                report(engine.search("boulangerie", here))
            }
            assertTrue(lux.count("amenity", "pharmacy") > 50)
            assertTrue(lux.count("addr:housenumber", null) > 100_000)
        }
    }
}
