package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.rendertiles.codec.Format3Writer
import org.locationtech.jts.algorithm.construct.MaximumInscribedCircle
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.LinearComponentExtracter
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * `packer globe`: the world's base, L0 to L5 (ENGINE.md step 5, the globe), one pack of at most 1,365 tiles. The land
 * is Natural Earth's 1:110m at L0 and L1 and its 1:50m at L2 and L3, then OSM's coastline from osmdata's simplified
 * land polygons at L4 and L5, so the coast meets L6's without a step; outside it the sea. Over it, at each level's
 * scale: Natural Earth's lakes (the water), rivers (lines), glaciers (land cover, kind `glacier`) and Antarctica's ice
 * shelves (areas over the water, kind `ice_shelf`), and its populated places: those of scalerank 1 or less at L0 to L2
 * (1:110m's, about the Globe board's 80) and of 4 or less at L3 to L5 (1:50m's), each a city, its population and
 * whether it is a capital, ranked by scalerank, then population. The water's names are Natural Earth's marine labels
 * (its geography's marine polygons: oceans, seas, bays and gulfs, straits and channels) and its lakes' (1:110m's at L0
 * and L1, 1:50m's from L2), each from the level its `min_label` asks for ([labelLevel]) at the centre of its largest
 * inscribed circle, with its area. With OpenStreetMap's names ([OsmNames], `--names`) a place or a water takes OSM's
 * `name` of its Wikidata id, in its own script (step 9's "Left"); without, Natural Earth's English names stay.
 */
object Globe {
    const val USAGE = """packer globe <naturalearth-dir> <simplified-land-polygons-complete-3857.zip> <out.atlas> [--names <names.tsv>] [--threads N]
      The world's base, L0 to L5, as one format-3 pack: Natural Earth's land (1:110m at L0 and L1, 1:50m at L2 and L3)
      and osmdata's simplified land polygons (L4 and L5), the sea outside them, Natural Earth's lakes, rivers, glaciers,
      ice shelves and populated places and its marine and lakes' names, each tile cut at its box and its cells.
      <naturalearth-dir> holds Natural Earth's zips as naturalearthdata.com names them (ne_110m_land.zip, …); --names
      OpenStreetMap's names by Wikidata id (world.yml's filter writes them: OsmNames); --threads 3 unless given."""

    const val LAST = 5

    /**
     * The globe's own land cover kind, Natural Earth's glaciers, which no region maps: drawn with the bare ground
     * (LAND's order 4, sand to scree), its colour the style ladder's (step 8).
     */
    const val GLACIER = "glacier"
    const val GLACIER_ORDER = 4

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "globe") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        var threads = 3
        var names: File? = null
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s == "--threads") {
                if (i + 1 >= args.size) throw UsageException("--threads needs a value")
                threads = args[++i].toIntOrNull()?.takeIf { it in 1..64 } ?: throw UsageException("bad --threads '${args[i]}'")
            } else if (s == "--names") {
                if (i + 1 >= args.size) throw UsageException("--names needs a file")
                names = File(args[++i]).also { if (!it.isFile) throw UsageException("no names $it") }
            } else if (s.startsWith("--")) {
                throw UsageException("unknown option $s; usage: $USAGE")
            } else positional += s
            i++
        }
        if (positional.size != 3) throw UsageException("globe takes Natural Earth's folder, the simplified land polygons and the pack; usage: $USAGE")
        val ne = File(positional[0])
        val osm = File(positional[1])
        if (!ne.isDirectory) throw UsageException("no folder $ne")
        if (!osm.isFile) throw UsageException("no land polygons $osm")
        build(ne, osm, File(positional[2]), threads, out, names?.let { OsmNames.read(it) })
        return 0
    }

    /**
     * The globe's level a Natural Earth label of `min_label` [minLabel] first shows at: a level's tiles are about 910
     * dp wide at its start, as a web map's of zoom z + log2(910 / 256) are 256 px, so from the level whose start is that
     * zoom or more; every level without one.
     */
    fun labelLevel(minLabel: Double?): Int =
        if (minLabel == null || minLabel.isNaN()) 0 else maxOf(0, Math.ceil(minLabel - LABEL_ZOOM - 1e-9).toInt())

    /** log2(910 / 256): a level's start against a web map's zoom. */
    val LABEL_ZOOM: Double = Math.log(910.0 / 256.0) / Math.log(2.0)

    /** The water's name's kind of a Natural Earth marine polygon's `featurecla` (null: no name drawn, a reef's). */
    fun marineKind(featurecla: String?): String? = when (featurecla?.trim()?.lowercase()) {
        "ocean" -> WorldProfile.OCEAN
        "sea" -> WorldProfile.SEA
        "bay", "gulf", "sound", "fjord", "inlet", "lagoon", "river" -> WorldProfile.BAY
        "strait", "channel" -> WorldProfile.STRAIT
        else -> null
    }

    /** The water's name's kind of a Natural Earth lake's `featurecla`: a reservoir, or a lake (a playa none). */
    fun lakeKind(featurecla: String?): String? = when {
        featurecla == null -> WorldProfile.LAKE
        featurecla.contains("reservoir", ignoreCase = true) -> WorldProfile.RESERVOIR
        featurecla.contains("lake", ignoreCase = true) -> WorldProfile.LAKE
        else -> null
    }

    /** A source of features at some levels: their profile layer, attributes and geometry in Mercator's unit square. */
    private class Source(val layer: String, val items: List<Pair<Map<String, Any?>, Geometry>>) {
        private val tree = STRtree().also { t -> for (it in items) t.insert(it.second.envelopeInternal, it) }

        @Suppress("UNCHECKED_CAST")
        fun near(env: Envelope): List<Pair<Map<String, Any?>, Geometry>> = tree.query(env) as List<Pair<Map<String, Any?>, Geometry>>
    }

    /** What a build made. */
    class Built(val file: File, val tiles: Int, val levels: Map<Int, WorldTiles.LevelStats>, val worstCover: Double, val ringsOutside: Int, val seconds: Double)

    fun build(ne: File, osmLand: File, out: File, threads: Int, log: (String) -> Unit, names: OsmNames? = null): Built {
        val t0 = System.nanoTime()
        fun shapes(name: String): List<Shapefile.Shape> = Shapefile.read(File(ne, "$name.zip"), name, WorldGeometry.FLOAT)
        fun world(name: String): List<Pair<Map<String, Any?>, Geometry>> = shapes(name).mapNotNull { s -> s.geometry?.let { s.attrs to WorldGeometry.valid(WorldGeometry.worldOfDegrees(it)) } }
        fun attr(m: Map<String, Any?>, key: String): Any? = m.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
        fun text(m: Map<String, Any?>, key: String): String? = attr(m, key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        var placesNamed = 0
        var watersNamed = 0

        val land110 = LandBase(world("ne_110m_land").map { it.second })
        val land50 = LandBase(world("ne_50m_land").map { it.second })
        val landOsm = LandBase(Shapefile.read(osmLand, "simplified_land_polygons", WorldGeometry.FLOAT, attributes = false).mapNotNull { it.geometry }.map { WorldGeometry.worldOfMetres(it) })
        fun scale(name110: String, name50: String, layer: String, attrs: (Map<String, Any?>) -> Map<String, Any?>): Pair<Source, Source> =
            Source(layer, world(name110).map { attrs(it.first) to it.second }) to Source(layer, world(name50).map { attrs(it.first) to it.second })
        val lakes = scale("ne_110m_lakes", "ne_50m_lakes", WorldProfile.WATER) { emptyMap() }
        val rivers = scale("ne_110m_rivers_lake_centerlines", "ne_50m_rivers_lake_centerlines", WorldProfile.RIVER) { emptyMap() }
        val glaciers = scale("ne_110m_glaciated_areas", "ne_50m_glaciated_areas", WorldProfile.LAND) { mapOf("kind" to GLACIER) }
        val shelves = Source(WorldCut.AREAS, world("ne_50m_antarctic_ice_shelves_polys").map { mapOf<String, Any?>("kind" to "ice_shelf") to it.second })
        fun places(name: String, most: Int): Source = Source(WorldProfile.PLACE, world(name).mapNotNull { (a, g) ->
            val rank = (attr(a, "SCALERANK") as? Number)?.toInt() ?: return@mapNotNull null
            if (rank > most) return@mapNotNull null
            val m = LinkedHashMap<String, Any?>()
            m["kind"] = "city"
            val at = (g as? Point)?.coordinate
            val osm = at?.let { names?.place(text(a, "WIKIDATAID"), com.onthegomap.planetiler.geo.GeoUtils.getWorldLat(it.y), com.onthegomap.planetiler.geo.GeoUtils.getWorldLon(it.x)) }
            if (osm != null) placesNamed++
            m["name"] = osm ?: attr(a, "NAME")
            m["population"] = (attr(a, "POP_MAX") as? Number)?.toLong()?.takeIf { it > 0 }?.toString()
            if ((attr(a, "ADM0CAP") as? Number)?.toInt() == 1) m["capital"] = "yes"
            m["scalerank"] = rank
            m to g
        })
        val places110 = places("ne_110m_populated_places", 1)
        val places50 = places("ne_50m_populated_places", 4)

        // the water's names: each polygon's label at the centre of its largest polygon's largest inscribed circle (in
        // degrees, so an ocean's is not drawn to the pole as Mercator's area would), from the level its min_label asks
        class Label(val attrs: Map<String, Any?>, val point: Geometry, val from: Int)
        fun labels(name: String, kind: (Map<String, Any?>) -> String?): List<Label> = shapes(name).mapNotNull { s ->
            val g = s.geometry ?: return@mapNotNull null
            val k = kind(s.attrs) ?: return@mapNotNull null
            val own = text(s.attrs, "name") ?: return@mapNotNull null
            val polygons = PolygonExtracter.getPolygons(WorldGeometry.valid(g)).map { it as Polygon }.filter { !it.isEmpty }
            if (polygons.isEmpty()) return@mapNotNull null
            val largest = polygons.maxBy { it.area }
            val env = largest.envelopeInternal
            val c = (MaximumInscribedCircle.getCenter(largest, maxOf(env.width, env.height) / 1000.0) as Point).coordinate
            val world = WorldGeometry.worldOfDegrees(WorldGeometry.FLOAT.createPoint(Coordinate(c.x, c.y)))
            val m2 = polygons.sumOf { p ->
                val w = WorldGeometry.worldOfDegrees(p) as Polygon
                WorldProfile.squareMetres(w.exteriorRing.coordinates) - (0 until w.numInteriorRing).sumOf { WorldProfile.squareMetres(w.getInteriorRingN(it).coordinates) }
            }
            val osm = names?.water(text(s.attrs, "wikidataid"))
            if (osm != null) watersNamed++
            val m = linkedMapOf<String, Any?>("kind" to k, "name" to (osm ?: own), "area" to Math.round(maxOf(0.0, m2)))
            Label(m, world, labelLevel((attr(s.attrs, "min_label") as? Number)?.toDouble()))
        }
        val waters110 = labels("ne_110m_geography_marine_polys") { marineKind(text(it, "featurecla")) } + labels("ne_110m_lakes") { lakeKind(text(it, "featurecla")) }
        val waters50 = labels("ne_50m_geography_marine_polys") { marineKind(text(it, "featurecla")) } + labels("ne_50m_lakes") { lakeKind(text(it, "featurecla")) }
        fun waterNames(z: Int): Source = Source(WorldProfile.WATER_NAME, (if (z <= 1) waters110 else waters50).filter { it.from <= z }.map { it.attrs to it.point })
        log("globe: Natural Earth and osmdata read in %.0f s, %d of their polygons mended".format((System.nanoTime() - t0) / 1e9, WorldGeometry.mendedSources.get()))
        if (names != null) {
            log("globe: OpenStreetMap's names (${names.size} Wikidata ids): $placesNamed of ${places110.items.size + places50.items.size} places, $watersNamed of ${waters110.size + waters50.size} waters")
        }

        fun landOf(z: Int) = when (z) {
            0, 1 -> land110
            2, 3 -> land50
            else -> landOsm
        }
        val waterNames = (0..LAST).map(::waterNames)
        fun sourcesOf(z: Int): List<Source> = if (z <= 1) {
            listOf(lakes.first, rivers.first, glaciers.first, shelves, places110, waterNames[z])
        } else {
            listOf(lakes.second, rivers.second, glaciers.second, shelves, if (z == 2) places110 else places50, waterNames[z])
        }

        val cut = WorldCut(StagePTags.STAGE_P.withLand(GLACIER, GLACIER_ORDER))
        val encoded = ArrayList<Format3Writer.Encoded>()
        val stats = java.util.TreeMap<Int, WorldTiles.LevelStats>()
        var worst = 0.0
        var outside = 0
        val pool = Executors.newFixedThreadPool(threads)
        try {
            for (z in 0..LAST) {
                val futures = ArrayList<Future<Pair<Triple<Int, Int, Int>, WorldTiles.Fitted>>>()
                // this level's land pieces by tile, cut down the quadtree from the whole world
                landOf(z).walk(0, 0, 0, z) { tz, tx, ty, pieces ->
                    if (tz == z) {
                        futures += pool.submit(Callable {
                            Triple(tz, tx, ty) to WorldTiles.fitted(cut, tz, tx, ty, items(tz, tx, ty, sourcesOf(z))) { tolerance -> landOf(z).land(tz, tx, ty, pieces, tolerance) }
                        })
                    }
                    true
                }
                for (f in futures) {
                    val (key, done) = f.get()
                    val c = done.cut
                    worst = maxOf(worst, c.cover.relative)
                    outside += c.cover.ringsOutside
                    val e = done.encoded ?: continue
                    if (done.tolerance > WorldProfile.TOLERANCE) log("globe: tile ${key.first}/${key.second}/${key.third} written at %.0f times the tolerance".format(done.tolerance / WorldProfile.TOLERANCE))
                    encoded += e
                    val s = stats.getOrPut(key.first) { WorldTiles.LevelStats() }
                    s.tiles++
                    s.raw += e.raw.size
                    s.features += e.featureCount
                }
                log("globe: L$z, ${stats[z]?.tiles ?: 0} tiles")
            }
        } finally {
            pool.shutdown()
        }
        val sources = listOf("Natural Earth (public domain): ${ne.listFiles()!!.filter { it.name.endsWith(".zip") }.map { it.name }.sorted().joinToString()}", "osmdata.openstreetmap.de (© OpenStreetMap contributors, ODbL 1.0): ${osmLand.name}")
        val source = WorldPack.sha256(ne.listFiles()!!.filter { it.name.endsWith(".zip") }.sortedBy { it.name }.map { "${it.name} ${WorldPack.sha256(it)}" } + "land ${WorldPack.sha256(osmLand)}" + "profile ${WorldTiles.PROFILE_VERSION}")
        val written = WorldPack.write(encoded, WorldPack.Spec("world-globe", 0, 0, 0, 0..LAST, null, sources, source), out)
        for (ch in written.chunks) stats.getValue(ch.z).stored += ch.stored
        val seconds = (System.nanoTime() - t0) / 1e9
        for ((z, s) in stats) log("L$z: ${s.tiles} tiles, ${s.features} features, ${s.stored} bytes stored (${s.raw} raw)")
        log("globe: ${encoded.size} tiles, ${out.length()} bytes in ${out.name}; the cells' ground within %.3g of their areas, %d rings across a cell's edge; %.0f s".format(worst, outside, seconds))
        return Built(out, encoded.size, stats, worst, outside, seconds)
    }

    /** The items of tile ([z], [x], [y]) from [sources]: each cut at the tile and its margin, in its pixels, simplified. */
    private fun items(z: Int, x: Int, y: Int, sources: List<Source>): List<WorldCut.Item> {
        val env = WorldGeometry.tileWorld(z, x, y, LandBase.MARGIN)
        val box = WorldGeometry.FLOAT.toGeometry(env)
        val out = ArrayList<WorldCut.Item>()
        for (s in sources) {
            for ((attrs, g) in s.near(env)) {
                if (s.layer == WorldProfile.PLACE || s.layer == WorldProfile.WATER_NAME) {
                    val p = g as? Point ?: (PolygonExtracter.getPolygons(g).firstOrNull() as? Geometry)?.centroid ?: continue
                    if (!WorldGeometry.tileWorld(z, x, y).contains(p.coordinate)) continue
                    out += WorldCut.Item(s.layer, attrs, WorldGeometry.worldToPixels(p, z, x, y))
                    continue
                }
                val clipped = if (env.contains(g.envelopeInternal)) g else OverlayNGRobust.overlay(g, box, OverlayNG.INTERSECTION)
                if (clipped.isEmpty) continue
                val px = DouglasPeuckerSimplifier.simplify(WorldGeometry.worldToPixels(clipped, z, x, y), WorldProfile.TOLERANCE)
                if (px.isEmpty) continue
                val lineal = s.layer == WorldProfile.RIVER
                if (lineal) {
                    if (LinearComponentExtracter.getLines(px).isNotEmpty()) out += WorldCut.Item(s.layer, attrs, px)
                } else if (px.area >= WorldProfile.MIN_AREA) {
                    out += WorldCut.Item(s.layer, attrs, px)
                }
            }
        }
        return out
    }
}
