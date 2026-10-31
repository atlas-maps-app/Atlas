package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.rendertiles.codec.Format3Writer
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.Point
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
 * whether it is a capital, ranked by scalerank, then population. The places of OSM, with their names in every script,
 * need the planet (step 10).
 */
object Globe {
    const val USAGE = """packer globe <naturalearth-dir> <simplified-land-polygons-complete-3857.zip> <out.atlas> [--threads N]
      The world's base, L0 to L5, as one format-3 pack: Natural Earth's land (1:110m at L0 and L1, 1:50m at L2 and L3)
      and osmdata's simplified land polygons (L4 and L5), the sea outside them, Natural Earth's lakes, rivers, glaciers,
      ice shelves and populated places, each tile cut at its box and its cells. <naturalearth-dir> holds Natural
      Earth's zips as naturalearthdata.com names them (ne_110m_land.zip, …); --threads 3 unless given."""

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
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s == "--threads") {
                if (i + 1 >= args.size) throw UsageException("--threads needs a value")
                threads = args[++i].toIntOrNull()?.takeIf { it in 1..64 } ?: throw UsageException("bad --threads '${args[i]}'")
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
        build(ne, osm, File(positional[2]), threads, out)
        return 0
    }

    /** A source of features at some levels: their profile layer, attributes and geometry in Mercator's unit square. */
    private class Source(val layer: String, val items: List<Pair<Map<String, Any?>, Geometry>>) {
        private val tree = STRtree().also { t -> for (it in items) t.insert(it.second.envelopeInternal, it) }

        @Suppress("UNCHECKED_CAST")
        fun near(env: Envelope): List<Pair<Map<String, Any?>, Geometry>> = tree.query(env) as List<Pair<Map<String, Any?>, Geometry>>
    }

    /** What a build made. */
    class Built(val file: File, val tiles: Int, val levels: Map<Int, WorldTiles.LevelStats>, val worstCover: Double, val ringsOutside: Int, val seconds: Double)

    fun build(ne: File, osmLand: File, out: File, threads: Int, log: (String) -> Unit): Built {
        val t0 = System.nanoTime()
        fun shapes(name: String): List<Shapefile.Shape> = Shapefile.read(File(ne, "$name.zip"), name, WorldGeometry.FLOAT)
        fun world(name: String): List<Pair<Map<String, Any?>, Geometry>> = shapes(name).mapNotNull { s -> s.geometry?.let { s.attrs to WorldGeometry.valid(WorldGeometry.worldOfDegrees(it)) } }
        fun attr(m: Map<String, Any?>, key: String): Any? = m.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

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
            m["name"] = attr(a, "NAME")
            m["population"] = (attr(a, "POP_MAX") as? Number)?.toLong()?.takeIf { it > 0 }?.toString()
            if ((attr(a, "ADM0CAP") as? Number)?.toInt() == 1) m["capital"] = "yes"
            m["scalerank"] = rank
            m to g
        })
        val places110 = places("ne_110m_populated_places", 1)
        val places50 = places("ne_50m_populated_places", 4)
        log("globe: Natural Earth and osmdata read in %.0f s, %d of their polygons mended".format((System.nanoTime() - t0) / 1e9, WorldGeometry.mendedSources.get()))

        fun landOf(z: Int) = when (z) {
            0, 1 -> land110
            2, 3 -> land50
            else -> landOsm
        }
        fun sourcesOf(z: Int): List<Source> = if (z <= 1) {
            listOf(lakes.first, rivers.first, glaciers.first, shelves, places110)
        } else {
            listOf(lakes.second, rivers.second, glaciers.second, shelves, if (z == 2) places110 else places50)
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
                if (s.layer == WorldProfile.PLACE) {
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
