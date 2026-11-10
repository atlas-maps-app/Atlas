package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.rendertiles.model.Grid
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LinearRing
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.io.File
import kotlin.math.abs
import kotlin.math.cos

/**
 * `packer world-plan`: the regions of a world's run (ENGINE.md step 5's world.yml, its plan job). The L6 blocks the
 * area's polygons meet are weighted by how much there is to build in each, then split again and again into two, across
 * the longer side of their box where the weight is shared as the regions to come are, until there are as many regions
 * as asked: each a rectangle of blocks (those of the area in it), built by a job of its own from an extract cut by its
 * blocks' box and a margin. The weights: the last run's fine packs' bytes by block (its index), else extracts' sizes
 * spread over their polygons by area, else even, or a last world of another extract within the area by its packs, as many
 * bytes as its extract, and the other extracts by their sizes (the planet's first run, after Europe's); a block of none
 * (the open sea, where Planetiler finds nothing) still weighs a hundredth of the mean, its tiles being walked all the same.
 */
object WorldPlan {
    const val USAGE = """packer world-plan <area.geojson> <out-dir> [--regions N] [--group N] [--margin DEG]
                   [--weights-index world.idx] [--weights-sizes sizes.geojson] [--index-bytes N]
      The regions of a world's run (world.yml's plan): the L6 blocks the area's polygons meet (a GeoJSON geometry,
      feature or feature collection), weighted by the last run's fine packs' bytes (--weights-index, its world.idx) or
      by extracts' sizes spread over their polygons by area (--weights-sizes: features with a "bytes" property; one made
      of others is left out, one holding others counts its bytes less theirs), else evenly, and split into N regions (20
      unless given) of about equal weight, each a
      rectangle of blocks. <out-dir>/regions.json lists each region's id, blocks, weight and the box its extract is cut by
      (its blocks', the margin beyond: 0.2° unless given); extract-1.json, … are osmium extract's configurations, N
      regions a pass (--group, 5 unless given)."""

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "world-plan") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String>()
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                if (s !in VALUED) throw UsageException("unknown option $s; usage: $USAGE")
                if (i + 1 >= args.size) throw UsageException("$s needs a value")
                options[s] = args[++i]
            } else {
                positional += s
            }
            i++
        }
        if (positional.size != 2) throw UsageException("world-plan takes the area and the output folder; usage: $USAGE")
        fun int(name: String, default: Int, range: IntRange): Int =
            options[name]?.let { it.toIntOrNull()?.takeIf { n -> n in range } ?: throw UsageException("bad $name '$it': want $range") } ?: default
        val regions = int("--regions", 20, 1..200)
        val group = int("--group", 5, 1..50)
        val margin = options["--margin"]?.let { it.toDoubleOrNull()?.takeIf { m -> m in 0.0..2.0 } ?: throw UsageException("bad --margin '$it': want 0 to 2 degrees") } ?: 0.2
        val both = "--weights-index" in options && "--weights-sizes" in options
        if (both != ("--index-bytes" in options)) throw UsageException("--weights-index with --weights-sizes takes --index-bytes, the bytes of the extract the index's world was built of, and only then")
        val indexBytes = options["--index-bytes"]?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: throw UsageException("bad --index-bytes '$it': want a count of bytes") }
        val area = File(positional[0])
        if (!area.isFile) throw UsageException("no area $area")
        val outDir = File(positional[1])
        val geometry = GeoJson.geometry(Json.parseToJsonElement(area.readText()))
        val index = options["--weights-index"]?.let { f -> Weights.ofIndex(WorldIndex.read(File(f).readBytes())) }
        val sizes = options["--weights-sizes"]?.let { f -> Weights.ofSizes(Json.parseToJsonElement(File(f).readText()), out) }
        val weights = if (index != null && sizes != null && indexBytes != null) Weights.combined(sizes, index, indexBytes) else index ?: sizes
        val plan = plan(geometry, weights, regions)
        write(plan, outDir, group, margin, area.nameWithoutExtension)
        out("world-plan: ${plan.blocks} L6 blocks of ${area.name} in ${plan.regions.size} regions (${plan.weighted}):")
        for (r in plan.regions) out("  ${r.id}: ${r.blocks.size} blocks, %.1f %% of the weight, columns ${r.blocks.minOf { it.first }}..${r.blocks.maxOf { it.first }}, rows ${r.blocks.minOf { it.second }}..${r.blocks.maxOf { it.second }}".format(100 * r.weight / plan.total))
        return 0
    }

    private val VALUED = setOf("--regions", "--group", "--margin", "--weights-index", "--weights-sizes", "--index-bytes")

    /** A region: its id, its blocks (rows from the north, each from the west) and their weight. */
    class Region(val id: String, val blocks: List<Pair<Int, Int>>, val weight: Double)

    /** A plan: the area's blocks' count, their regions, the weights' total and where they came from. */
    class Plan(val blocks: Int, val regions: List<Region>, val total: Double, val weighted: String)

    /** Each L6 block's weight, and where the weights came from. */
    class Weights(val of: Map<Pair<Int, Int>, Double>, val from: String) {
        companion object {
            /** The fine packs' bytes by block, of a run's index. */
            fun ofIndex(index: WorldIndex): Weights =
                Weights(index.entries.filter { it.z == WorldIndex.FINE_BLOCK }.associate { (it.x to it.y) to it.length.toDouble() }, "the last run's fine packs")

            /**
             * Extracts' sizes ("bytes", a feature's property) spread over the blocks by the share of each extract's area
             * in each (degrees² by the cosine of the latitude), each extract's data counted once. Largest first, an
             * extract nine tenths of whose area smaller ones kept cover is made of them and is left out (Geofabrik's dach
             * of Germany, Austria and Switzerland, the alps, us of its states). Then, smallest first, each counts its bytes
             * less those the extracts within it (nine tenths of whose area it covers) count, never below none: one inside
             * another (Monaco in France, Beijing in Hebei), or the parts of one whose polygon reaches further out to sea
             * than theirs (Geofabrik's us-south of its states, sea of South-East Asia's countries), which keep their own.
             */
            fun ofSizes(json: JsonElement, log: (String) -> Unit = {}): Weights {
                val features = json.jsonObject.getValue("features").jsonArray.map { it.jsonObject }.mapNotNull { f ->
                    val bytes = (f["properties"] as? JsonObject)?.get("bytes")?.takeIf { it !is JsonNull }?.jsonPrimitive?.long ?: return@mapNotNull null
                    val name = (f["properties"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: "?"
                    Triple(name, GeoJson.geometry(f), bytes)
                }
                val areas = features.map { area(it.second) }
                val kept = features.indices.filter { areas[it] > 0 }.toMutableSet()
                val madeOf = ArrayList<String>()
                for (a in kept.sortedByDescending { areas[it] }) {
                    val g = features[a].second
                    val parts = kept.filter { it != a && areas[it] < areas[a] && g.envelopeInternal.intersects(features[it].second.envelopeInternal) }
                        .map { g.intersection(features[it].second) }.filter { !it.isEmpty }
                    if (parts.isNotEmpty() && area(UnaryUnionOp.union(parts)) >= 0.9 * areas[a]) {
                        kept -= a
                        madeOf += features[a].first
                    }
                }
                if (madeOf.isNotEmpty()) log("world-plan: left out as made of the others: ${madeOf.sorted().joinToString()}")
                val own = LinkedHashMap<Int, Double>()
                val holding = ArrayList<String>()
                for (e in kept.sortedBy { areas[it] }) {
                    val g = features[e].second
                    val inside = own.keys.filter { g.envelopeInternal.intersects(features[it].second.envelopeInternal) && area(g.intersection(features[it].second)) >= 0.9 * areas[it] }
                    own[e] = maxOf(0.0, features[e].third - inside.sumOf { own.getValue(it) })
                    if (inside.isNotEmpty()) holding += "${features[e].first} (${inside.size})"
                }
                if (holding.isNotEmpty()) log("world-plan: less the extracts within them: ${holding.sorted().joinToString()}")
                val out = HashMap<Pair<Int, Int>, Double>()
                for (k in kept.sorted()) {
                    val g = features[k].second
                    val bytes = own.getValue(k)
                    if (bytes <= 0) continue
                    for (b in blocksMeeting(g)) {
                        val share = area(g.intersection(box(b.first, b.second))) / areas[k]
                        if (share > 0) out.merge(b, bytes * share, Double::plus)
                    }
                }
                return Weights(out, "the extracts' sizes")
            }

            /**
             * A last world of another extract within the area ([index], its fine packs' bytes by block) as many bytes in
             * all as that extract ([indexBytes]), added to the other extracts' [sizes] (the planet's first run after
             * Europe's: Europe's blocks by its packs, the rest by Geofabrik's extracts but Europe's own). Where the two
             * hold the same data (European Russia, in Europe's extract and Russia's), a block weighs it twice: its region
             * comes out smaller, never longer to build.
             */
            fun combined(sizes: Weights, index: Weights, indexBytes: Long): Weights {
                val packs = index.of.values.sum()
                val out = HashMap(sizes.of)
                if (packs > 0) for ((b, v) in index.of) out.merge(b, v * indexBytes / packs, Double::plus)
                return Weights(out, "${index.from} as $indexBytes bytes, and ${sizes.from}")
            }
        }
    }

    /** [geometry]'s blocks split into [count] regions by [weights] (even where null). */
    fun plan(geometry: Geometry, weights: Weights?, count: Int): Plan {
        val blocks = blocksMeeting(geometry)
        require(blocks.isNotEmpty()) { "the area meets no L6 block" }
        val raw = blocks.associateWith { weights?.of?.get(it) ?: 0.0 }
        val mean = raw.values.sum() / blocks.size
        val floor = if (mean > 0) mean / 100 else 1.0
        val w = raw.mapValues { maxOf(it.value, floor) }
        val parts = split(blocks, w, count)
        val regions = parts.withIndex().map { (i, p) -> Region("r%02d".format(i + 1), p.sortedWith(compareBy({ it.second }, { it.first })), p.sumOf { w.getValue(it) }) }
        return Plan(blocks.size, regions, w.values.sum(), weights?.from ?: "even")
    }

    /**
     * [blocks] in [n] parts: across the longer side of their box (in blocks, which are square in Mercator), else the other,
     * where the weight before the cut is nearest the share of the first n/2 parts, each side split again; fewer where the
     * blocks cannot be cut so (one block).
     */
    fun split(blocks: List<Pair<Int, Int>>, w: Map<Pair<Int, Int>, Double>, n: Int): List<List<Pair<Int, Int>>> {
        if (n <= 1 || blocks.size <= 1) return listOf(blocks)
        val cols = blocks.maxOf { it.first } - blocks.minOf { it.first }
        val rows = blocks.maxOf { it.second } - blocks.minOf { it.second }
        val total = blocks.sumOf { w.getValue(it) }
        val k = n / 2
        val target = total * k / n
        val axes = if (cols >= rows) listOf(0, 1) else listOf(1, 0)
        for (axis in axes) {
            fun along(b: Pair<Int, Int>) = if (axis == 0) b.first else b.second
            val values = blocks.map(::along).distinct().sorted()
            if (values.size < 2) continue
            var best = -1
            var bestGap = Double.MAX_VALUE
            var before = 0.0
            val byValue = blocks.groupBy(::along)
            for (j in 0 until values.size - 1) {
                before += byValue.getValue(values[j]).sumOf { w.getValue(it) }
                val gap = abs(before - target)
                if (gap < bestGap) {
                    bestGap = gap
                    best = j
                }
            }
            val cut = values[best + 1]
            val left = blocks.filter { along(it) < cut }
            val right = blocks.filter { along(it) >= cut }
            return split(left, w, k) + split(right, w, n - k)
        }
        return listOf(blocks)
    }

    /** The L6 blocks whose box meets [g] (lon/lat), rows from the north, each from the west. */
    fun blocksMeeting(g: Geometry): List<Pair<Int, Int>> {
        val e = g.envelopeInternal
        val lat = Grid.round(85.0511)
        val x0 = Grid.col(Grid.round(e.minX.coerceIn(-180.0, 179.9999999)), 6)
        val x1 = Grid.col(Grid.round(e.maxX.coerceIn(-180.0, 179.9999999)), 6)
        val y0 = Grid.row(Grid.round(e.maxY).coerceIn(-lat, lat), 6)
        val y1 = Grid.row(Grid.round(e.minY).coerceIn(-lat, lat), 6)
        val out = ArrayList<Pair<Int, Int>>()
        for (y in y0..y1) for (x in x0..x1) if (box(x, y).intersects(g)) out += x to y
        return out
    }

    private val factory = GeometryFactory()

    /** L6 block ([x], [y])'s box in lon/lat. */
    fun box(x: Int, y: Int): Polygon {
        val b = Grid.tileBox(WorldIndex.FINE_BLOCK, x, y)
        val s = Grid.deg(b[0])
        val w = Grid.deg(b[1])
        val n = Grid.deg(b[2])
        val e = Grid.deg(b[3])
        return factory.createPolygon(arrayOf(Coordinate(w, s), Coordinate(e, s), Coordinate(e, n), Coordinate(w, n), Coordinate(w, s)))
    }

    /** [g]'s area (lon/lat) in degrees² at the equator: each polygon's by the cosine of its centre's latitude. */
    fun area(g: Geometry): Double {
        var a = 0.0
        for (i in 0 until g.numGeometries) {
            val p = g.getGeometryN(i)
            if (p.isEmpty) continue
            a += p.area * cos(Math.toRadians(p.envelopeInternal.centre().y))
        }
        return a
    }

    /** [plan] into [outDir]: regions.json and osmium extract's configurations of [group] regions each. */
    fun write(plan: Plan, outDir: File, group: Int, margin: Double, area: String) {
        outDir.mkdirs()
        fun boxOf(r: Region): List<Double> {
            val bs = r.blocks.map { Grid.tileBox(WorldIndex.FINE_BLOCK, it.first, it.second) }
            fun round(v: Double) = Math.round(v * 1e6) / 1e6
            return listOf(
                round((Grid.deg(bs.minOf { it[1] }) - margin).coerceAtLeast(-180.0)), round((Grid.deg(bs.minOf { it[0] }) - margin).coerceAtLeast(-90.0)),
                round((Grid.deg(bs.maxOf { it[3] }) + margin).coerceAtMost(180.0)), round((Grid.deg(bs.maxOf { it[2] }) + margin).coerceAtMost(90.0)),
            )
        }
        val groups = plan.regions.chunked(group)
        val json = Json { prettyPrint = true }
        val regions = buildJsonObject {
            put("area", area)
            put("blocks", plan.blocks)
            put("weighted", plan.weighted)
            putJsonArray("regions") {
                for (r in plan.regions) add(buildJsonObject {
                    put("id", r.id)
                    put("blocks", WorldTiles.blocksText(r.blocks))
                    put("count", r.blocks.size)
                    put("weight", Math.round(1e4 * r.weight / plan.total) / 1e4)
                    putJsonArray("box") { for (v in boxOf(r)) add(v) }
                })
            }
            putJsonArray("groups") { for (g in groups) addJsonArray { for (r in g) add(r.id) } }
        }
        File(outDir, "regions.json").writeText(json.encodeToString(JsonObject.serializer(), regions) + "\n")
        for ((k, g) in groups.withIndex()) {
            val config = buildJsonObject {
                putJsonArray("extracts") {
                    for (r in g) add(buildJsonObject {
                        put("output", "scratch-${r.id}.osm.pbf")
                        put("output_format", "pbf")
                        putJsonArray("bbox") { for (v in boxOf(r)) add(v) }
                    })
                }
            }
            File(outDir, "extract-${k + 1}.json").writeText(json.encodeToString(JsonObject.serializer(), config) + "\n")
        }
    }

    /** GeoJSON's polygons as JTS's, in lon/lat: a geometry, a feature or a collection of them, mended where invalid. */
    object GeoJson {
        fun geometry(e: JsonElement): Geometry {
            val o = e.jsonObject
            return when (o["type"]?.jsonPrimitive?.content) {
                "FeatureCollection" -> union(o.getValue("features").jsonArray.map { geometry(it) })
                "Feature" -> geometry(o.getValue("geometry"))
                "GeometryCollection" -> union(o.getValue("geometries").jsonArray.map { geometry(it) })
                "Polygon" -> polygon(o.getValue("coordinates").jsonArray)
                "MultiPolygon" -> factory.createMultiPolygon(o.getValue("coordinates").jsonArray.map { polygon(it.jsonArray) }.toTypedArray())
                else -> throw IllegalArgumentException("a GeoJSON ${o["type"]} where polygons were wanted")
            }.let { if (it.isValid) it else GeometryFixer.fix(it) }
        }

        private fun union(gs: List<Geometry>): Geometry = factory.buildGeometry(gs).union()

        private fun polygon(rings: JsonArray): Polygon {
            val r = rings.map { ring(it.jsonArray) }
            return factory.createPolygon(r.first(), r.drop(1).toTypedArray())
        }

        private fun ring(points: JsonArray): LinearRing {
            val c = points.map { p -> p.jsonArray.let { Coordinate(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }.toMutableList()
            if (c.first() != c.last()) c += Coordinate(c.first())
            return factory.createLinearRing(c.toTypedArray())
        }
    }
}
