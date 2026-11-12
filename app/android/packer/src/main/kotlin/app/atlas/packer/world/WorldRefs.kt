package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.packer.country.WindowPack
import app.atlas.render.data.PackManifest
import app.atlas.rendertiles.codec.Format3Writer
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Reach
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * `packer world-refs` (ENGINE.md step 5, "References around the world"; world.yml's refs job): the tiles the world's
 * references draw, cut from the world's parts into packs of their own, `world-refs` (with the globe, which is whole).
 *
 * The views are mapcorecheck's `likeness/world-refs.json`, which WorldPackRefsTest draws: each a place, the camera's
 * distance, pitch and bearing, on the frame the file names. For each, every L6 to L11 tile the world's cover may walk
 * to ([tiles]), the core's walk (scene/WorldTiles.cpp's walk_world) on the sphere: from L0, every tile the frame's
 * field may see with a fifth more each way, before the horizon and within half again the world's far plane, split
 * while its nearest point lies nearer the eye than its level's start, 18.8 km·2^(11 − z), with a quarter more (the
 * governor's reach is at most 1; the core's flat ground near the eye and its frame's degrees lie within that of the
 * sphere's). Every tile of the walk is kept, those split too, as the core draws a tile's holder where a finer one is
 * not held. A pack (a fine one an L6 block's, a coarse one an L3 block's) keeps the tiles chosen of it, each chunk's
 * tile, reach and features as the part holds them ([cut]), in a pack named as the world's folder names one
 * (`world-<z>-<x>-<y>.atlas`), so the references draw what the world does from a sliver of its bytes.
 */
object WorldRefs {
    const val USAGE = """packer world-refs <world.idx> <views.json> --needed
       packer world-refs <world.idx> <views.json> <packs-dir> <out-dir>
      The world's references' tiles (ENGINE.md step 5, its references around the world): for each view of <views.json>
      (mapcorecheck's likeness/world-refs.json) the L6 to L11 tiles the world's cover may draw there, from the packs of
      <world.idx>. --needed prints the packs holding them, a line each: its part, its offset and length there, and its
      file (world-<z>-<x>-<y>.atlas); otherwise each pack's tiles chosen are written into a pack of their own in
      <out-dir> (named as the world's folder names a pack), from the pack in <packs-dir> (its range of its part, as
      --needed names it) or from its part there, held to the index's SHA-256."""

    /** The core's walk: L11's start, the finest level, the world's first level of its own (the globe's are L0 to L5). */
    const val L11_START = 18_800.0
    const val FINEST = 11
    const val FIRST = 6

    /** The world's far plane (scene::world_far): 30 km at 18,000 km's knot, by the distance squared, at most the horizon. */
    const val FAR_AT_FROM = 30_000.0
    const val WORLD_FROM = 18_000.0

    /** The headroom on a level's start, on the field of view and on the far plane. */
    const val SPLIT_ROOM = 1.25
    const val FIELD_ROOM = 1.2
    const val FAR_ROOM = 1.5

    /** The core's field of view (view/Camera.hpp's field_of_view), degrees over the frame's height, and its Earth's radius. */
    const val FIELD_OF_VIEW = 36.0
    const val EARTH_RADIUS = 6_371_000.0

    /** A tile's points on a side, for its view, and on an edge, for its distance. */
    private const val LATTICE = 9
    private const val EDGE = 64

    /** One view: its name, its target, the eye's distance from it (m), its pitch from straight down and its bearing (degrees). */
    class View(val name: String, val lat: Double, val lon: Double, val distance: Double, val pitch: Double, val bearing: Double)

    /** The views' frame: its pixels and their density, and where the target stands (a share of its height from the top). */
    class Frame(val width: Int, val height: Int, val density: Double, val cy: Double)

    /** The views of [file] (likeness/world-refs.json: its `frame` and its `frames`) and their frame. */
    fun views(file: File): Pair<Frame, List<View>> {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        fun d(j: JsonObject, k: String, or: Double? = null): Double =
            j[k]?.jsonPrimitive?.double ?: or ?: throw UsageException("$file: ${j["name"]?.jsonPrimitive?.content ?: "the frame"} has no $k")
        val f = o["frame"]?.jsonObject ?: throw UsageException("$file names no frame")
        val frame = Frame(d(f, "width").toInt(), d(f, "height").toInt(), d(f, "density"), d(f, "cy"))
        val views = (o["frames"] ?: throw UsageException("$file has no frames")).jsonArray.map { e ->
            val v = e.jsonObject
            View(v["name"]!!.jsonPrimitive.content, d(v, "lat"), d(v, "lon"), d(v, "distance"), d(v, "pitch", 0.0), d(v, "bearing", 0.0))
        }
        return frame to views
    }

    /** A level's start: where the core's walk stops splitting its tiles (scene::world_level_start). */
    fun start(z: Int): Double = Math.scalb(L11_START, FINEST - z)

    /** A tile key packed as the core packs one (MapCore.wantedWindows' keys): z << 48 | x << 24 | y. */
    fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

    private fun level(k: Long) = (k ushr 48).toInt()

    /** The point at ([lat], [lon]) degrees on the sphere, [up] metres over it, from the Earth's centre (x to 0° E, z north). */
    private fun ecef(lat: Double, lon: Double, up: Double = 0.0): DoubleArray {
        val la = Math.toRadians(lat)
        val lo = Math.toRadians(lon)
        val r = EARTH_RADIUS + up
        return doubleArrayOf(r * cos(la) * cos(lo), r * cos(la) * sin(lo), r * sin(la))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun dist(a: DoubleArray, b: DoubleArray): Double {
        val x = a[0] - b[0]
        val y = a[1] - b[1]
        val z = a[2] - b[2]
        return sqrt(x * x + y * y + z * z)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun unit(a: DoubleArray): DoubleArray {
        val n = sqrt(dot(a, a))
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n)
    }

    /**
     * The tiles of L6 to L11 view [v] on [frame] may draw (packed by [key]), the core's walk on the sphere: each tile
     * the frame's field may see is kept, and split while its nearest point lies nearer the eye than its level's start.
     */
    fun tiles(v: View, frame: Frame): Set<Long> {
        // the target's axes: east, north and up
        val la = Math.toRadians(v.lat)
        val lo = Math.toRadians(v.lon)
        val east = doubleArrayOf(-sin(lo), cos(lo), 0.0)
        val north = doubleArrayOf(-sin(la) * cos(lo), -sin(la) * sin(lo), cos(la))
        val up = doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
        val target = ecef(v.lat, v.lon)
        // the eye behind the target by the pitch, against the bearing, the distance from it
        val p = Math.toRadians(v.pitch)
        val b = Math.toRadians(v.bearing)
        val facing = DoubleArray(3) { sin(b) * east[it] + cos(b) * north[it] }
        val eye = DoubleArray(3) { target[it] + v.distance * (-sin(p) * facing[it] + cos(p) * up[it]) }
        val forward = unit(DoubleArray(3) { target[it] - eye[it] })
        val right = DoubleArray(3) { cos(b) * east[it] - sin(b) * north[it] }
        val screenUp = cross(right, forward)
        // the field, the target off the frame's middle as it stands there, each way a fifth more
        val half = tan(Math.toRadians(FIELD_OF_VIEW / 2))
        val tanV = half * 2 * max(frame.cy, 1 - frame.cy) * FIELD_ROOM
        val tanH = half * frame.width / frame.height * FIELD_ROOM
        val height = sqrt(dot(eye, eye)) - EARTH_RADIUS
        val horizon = sqrt(2 * EARTH_RADIUS * height + height * height)
        val k = v.distance / WORLD_FROM
        val far = min(FAR_ROOM * FAR_AT_FROM * k * k, horizon * 1.05)
        val eyeLat = Math.toDegrees(Math.asin(eye[2] / sqrt(dot(eye, eye))))
        val eyeLon = Math.toDegrees(Math.atan2(eye[1], eye[0]))
        // the side planes' normals into the field, in the view's axes (right, up, forward)
        val planes = listOf(
            doubleArrayOf(1.0, 0.0, tanH), doubleArrayOf(-1.0, 0.0, tanH), doubleArrayOf(0.0, 1.0, tanV), doubleArrayOf(0.0, -1.0, tanV),
        ).map { n -> val s = sqrt(dot(n, n)); doubleArrayOf(n[0] / s, n[1] / s, n[2] / s) }

        val out = HashSet<Long>()
        fun visit(z: Int, x: Int, y: Int) {
            val g = Grid.tileBox(z, x, y)
            val south = Grid.deg(g[0])
            val west = Grid.deg(g[1])
            val north = Grid.deg(g[2])
            val eastDeg = Grid.deg(g[3])
            // the tile's lattice, each point standing for the cells about it: as far as its farthest neighbour
            val points = ArrayList<DoubleArray>(LATTICE * LATTICE)
            for (i in 0 until LATTICE) for (j in 0 until LATTICE) {
                points += ecef(south + (north - south) * i / (LATTICE - 1), west + (eastDeg - west) * j / (LATTICE - 1))
            }
            var pad = 0.0
            for (i in 0 until LATTICE) for (j in 0 until LATTICE) {
                val a = points[i * LATTICE + j]
                if (j + 1 < LATTICE) pad = max(pad, dist(a, points[i * LATTICE + j + 1]))
                if (i + 1 < LATTICE) pad = max(pad, dist(a, points[(i + 1) * LATTICE + j]))
            }
            val view = points.map { q ->
                val d = DoubleArray(3) { q[it] - eye[it] }
                doubleArrayOf(dot(d, right), dot(d, screenUp), dot(d, forward))
            }
            // outside the field: every point beyond one side, behind the eye, or beyond the far plane and the horizon
            for (n in planes) if (view.all { dot(it, n) < -pad }) return
            if (view.all { it[2] < -pad }) return
            if (points.all { dist(it, eye) > far + pad }) return
            if (z >= FIRST) out += key(z, x, y)
            if (z >= FINEST) return
            // the nearest point: under the eye where the tile holds it, else on its edges (the distance grows away from it)
            val inside = eyeLat in south..north && lonWithin(eyeLon, west, eastDeg)
            val near = if (inside) height else nearestEdge(south, west, north, eastDeg, eye)
            if (near < SPLIT_ROOM * start(z)) {
                for (q in 0 until 4) visit(z + 1, 2 * x + (q and 1), 2 * y + (q shr 1))
            }
        }
        visit(0, 0, 0)
        return out
    }

    private fun lonWithin(lon: Double, west: Double, east: Double): Boolean {
        var l = lon
        while (l < west) l += 360.0
        while (l >= west + 360.0) l -= 360.0
        return l <= east
    }

    /** The least distance from [eye] to a tile's edges, less the most a point between two of their samples may lie nearer. */
    private fun nearestEdge(south: Double, west: Double, north: Double, east: Double, eye: DoubleArray): Double {
        var best = Double.MAX_VALUE
        var step = 0.0
        val corners = listOf(doubleArrayOf(south, west), doubleArrayOf(south, east), doubleArrayOf(north, east), doubleArrayOf(north, west))
        for (c in 0 until 4) {
            val a = corners[c]
            val e = corners[(c + 1) % 4]
            var last: DoubleArray? = null
            for (s in 0..EDGE) {
                val q = ecef(a[0] + (e[0] - a[0]) * s / EDGE, a[1] + (e[1] - a[1]) * s / EDGE)
                best = min(best, dist(q, eye))
                last?.let { step = max(step, dist(it, q)) }
                last = q
            }
        }
        return best - step / 2
    }

    /** The index's packs holding [tiles], each with its tiles among them. */
    fun packs(index: WorldIndex, tiles: Collection<Long>): Map<WorldIndex.Entry, Set<Long>> {
        val out = LinkedHashMap<WorldIndex.Entry, MutableSet<Long>>()
        for (k in tiles.sorted()) {
            val e = index.holding(level(k), ((k ushr 24) and 0xFFFFFF).toInt(), (k and 0xFFFFFF).toInt()) ?: continue
            out.getOrPut(e) { LinkedHashSet() } += k
        }
        return out
    }

    /** What [cut] wrote: the pack's file, its tiles kept of those it held, its bytes. */
    class Cut(val file: File, val kept: Int, val held: Int, val bytes: Long)

    /** The file a pack is fetched to and cut into: the world's folder's name for it (WorldFolder's, WorldFetch's). */
    fun fileOf(entry: WorldIndex.Entry): String = "world-${entry.z}-${entry.x}-${entry.y}.atlas"

    /**
     * Pack [entry]'s tiles among [keys] into a pack of their own in [out]: read from [packs] (the pack's file, as
     * `--needed` names it, else its range of its part there) and held to the index's SHA-256; the pack's block, levels,
     * id, data's date, sources and inputs' hash, each tile's chunk decompressed to its bytes (held to its SHA-256)
     * with its reach and its features as the pack had them; null where it holds none of them.
     */
    fun cut(index: WorldIndex, entry: WorldIndex.Entry, keys: Set<Long>, packs: File, out: File): Cut? {
        val own = File(packs, fileOf(entry))
        val bytes = if (own.isFile) {
            own.readBytes().also { check(it.size.toLong() == entry.length) { "${own.name}: ${it.size} bytes, the index's pack ${entry.length}" } }
        } else {
            ByteArray(Math.toIntExact(entry.length)).also { b ->
                RandomAccessFile(File(packs, index.parts[entry.part].name), "r").use { f ->
                    f.seek(entry.offset)
                    f.readFully(b)
                }
            }
        }
        if (!MessageDigest.getInstance("SHA-256").digest(bytes).contentEquals(entry.sha256)) {
            throw IllegalStateException("pack ${entry.z}/${entry.x}/${entry.y} is not the index's (${if (own.isFile) own.name else index.parts[entry.part].name})")
        }
        val pack = WindowPack.read(bytes)
        val reach = reachOf(pack)
        val manifest = PackManifest(Json.parseToJsonElement(String(pack.manifest, Charsets.UTF_8)).jsonObject)
        val encoded = ArrayList<Format3Writer.Encoded>()
        for ((i, c) in pack.chunks.withIndex()) {
            if (key(c.z, c.x, c.y) !in keys) continue
            val raw = pack.tile(c)
            encoded += Format3Writer.Encoded(c.z, c.x, c.y, raw, c.count, reach[i], layerCounts(raw))
        }
        if (encoded.isEmpty()) return null
        val file = File(out, fileOf(entry))
        val source = (manifest.json["source_sha256"] as? JsonPrimitive)?.content ?: throw IllegalStateException("pack ${entry.z}/${entry.x}/${entry.y} names no inputs' hash")
        val spec = WorldPack.Spec(manifest.id, entry.z, entry.x, entry.y, entry.firstLevel..entry.lastLevel, manifest.dataTime, manifest.sources, source)
        WorldPack.write(encoded, spec, file)
        return Cut(file, encoded.size, pack.chunks.size, file.length())
    }

    /** The reach section's entries of [pack], in its chunks' order (Format's Reach: a header, then an entry a chunk). */
    private fun reachOf(pack: WindowPack): List<Reach> {
        val b = ByteBuffer.wrap(pack.stored(Format.SECTION_REACH)).order(ByteOrder.LITTLE_ENDIAN)
        val entry = b.getShort(6).toInt() and 0xFFFF
        val n = b.getInt(8)
        check(n == pack.chunks.size) { "a reach of $n entries for ${pack.chunks.size} chunks" }
        return List(n) { i ->
            val at = Reach.HEADER_BYTES + i * entry
            Reach(b.getInt(at), b.getInt(at + 4), b.getInt(at + 8), b.getInt(at + 12), b.getInt(at + 16))
        }
    }

    /** A tile's layers' features by label, from its layer table (Format's tile header: their count at 48, 16 bytes a layer from 52). */
    private fun layerCounts(raw: ByteArray): Map<String, Int> {
        val b = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getShort(48).toInt() and 0xFFFF
        val out = LinkedHashMap<String, Int>()
        for (i in 0 until n) {
            val at = Format.HEADER_BYTES + i * Format.LAYER_ENTRY_BYTES
            val layer = Layer.of(raw[at].toInt() and 0xFF) ?: throw IllegalStateException("a tile's layer ${raw[at].toInt() and 0xFF}")
            out.merge(layer.label, b.getInt(at + 4), Int::plus)
        }
        return out
    }

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "world-refs" || args.size !in 4..5) throw UsageException("usage: $USAGE")
        val indexFile = File(args[1]).also { if (!it.isFile) throw UsageException("no index $it") }
        val viewsFile = File(args[2]).also { if (!it.isFile) throw UsageException("no views $it") }
        val needed = args[3] == "--needed"
        if (needed != (args.size == 4)) throw UsageException("usage: $USAGE")
        val index = WorldIndex.read(indexFile.readBytes())
        val (frame, views) = views(viewsFile)
        val chosen = LinkedHashSet<Long>()
        for (v in views) {
            val t = tiles(v, frame)
            chosen += t
            val levels = t.groupingBy(::level).eachCount().toSortedMap()
            System.err.println("world-refs: ${v.name}: ${t.size} tiles (${levels.entries.joinToString { "L${it.key} ${it.value}" }})")
        }
        val packs = packs(index, chosen)
        if (needed) {
            for (e in packs.keys.sortedWith(compareBy({ index.parts[it.part].name }, { it.offset }))) {
                out("${index.parts[e.part].name} ${e.offset} ${e.length} ${fileOf(e)}")
            }
            return 0
        }
        val from = File(args[3]).also { if (!it.isDirectory) throw UsageException("no packs folder $it") }
        val dir = File(args[4]).apply { mkdirs() }
        if (from.canonicalFile == dir.canonicalFile) throw UsageException("the packs are cut from $from into another folder, not into it")
        var tiles = 0
        var bytes = 0L
        var written = 0
        for ((entry, keys) in packs) {
            val c = cut(index, entry, keys, from, dir) ?: continue
            written++
            tiles += c.kept
            bytes += c.bytes
            out("world-refs: ${c.file.name}: ${c.kept} of its ${c.held} tiles, ${c.bytes} bytes")
        }
        out("world-refs: ${views.size} views, ${chosen.size} tiles walked, $tiles of them in $written packs, $bytes bytes")
        return 0
    }
}
