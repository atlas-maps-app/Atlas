package app.atlas.packer.world

import app.atlas.packer.UsageException
import app.atlas.packer.country.WindowPack
import app.atlas.packer.windows.ClipPolygon
import app.atlas.render.data.ByteSource
import app.atlas.render.data.PackFormatException
import app.atlas.render.data.PackWriter
import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid
import app.atlas.tiles.WorldIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.locationtech.jts.densify.Densifier
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.simplify.TopologyPreservingSimplifier
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * `packer world-keep` (ENGINE.md step 11, packs for offline use): the world's tiles a kept country draws offline, from
 * the world's first distance (18 km, where its own tiles hand over) out to the globe, listed for the app beside each
 * world (`world-keep.json`), so keeping a country keeps them with its tiles until it is removed.
 *
 * A view draws the world's L6 to L11 tiles its walk takes ([WorldRefs.tiles], the core's walk with room on its field,
 * its levels' starts and its far plane), and how far from the view's target a level's tiles lie is bounded: by the
 * distance at which the walk still splits to that level and how far a view sees there. Each level's [reach] is that
 * bound measured, the farthest of its tiles from the target over views from 18 km to 20,000 km, every pitch the camera
 * allows there (`CameraOps.maxPitch` by the camera's zoom, for a frame [TALL_DP] high, 2° more, `MapCamera`'s 72° at
 * most), every bearing, on a frame whose field holds a phone's either way up ([WIDE]), at the equator, where a level's
 * tiles are largest; with a tenth more and half a tile's diagonal for the views between those sampled. A country keeps
 * each level's tiles within its reach of the country's area (the polygon Geofabrik cut its extract with, packs.yml's
 * `<id>.poly`, within the region's box where the region clips its extract), so every view whose target lies in the
 * country finds every tile it draws kept: WorldKeepTest holds that over random views.
 *
 * Each tile kept is the chunk its pack holds (open sea has none), fetched as the phone fetches the world: the pack's
 * head (its bytes but its chunks, [Head]) and the tiles' chunks by their ranges, so a country's bytes are its packs'
 * heads and its tiles' chunks as stored.
 */
object WorldKeep {
    const val USAGE = """packer world-keep <world.idx> <regions.json> <polys-dir> --needed
       packer world-keep <world.idx> <regions.json> <polys-dir> <heads-dir> <out.json> [--release TAG] [--threads N]
       packer world-keep cut <world.idx> <world-keep.json> <id> --needed
       packer world-keep cut <world.idx> <world-keep.json> <id> <packs-dir> <out-dir>
      The world's tiles a kept country draws offline (ENGINE.md step 11): for each region of <regions.json> with
      windows, every L6 to L11 tile within its level's reach of the polygon <polys-dir>/<id>.poly (within the region's
      box where it clips), from the packs of <world.idx>. --needed prints the packs holding them, a line each: its part,
      the offset and length of its first bytes there (its head's: header, manifest, directory and chunk table) and its
      file (world-<z>-<x>-<y>.atlas); otherwise <out.json> lists each country's packs and tiles with their bytes (each
      pack's head and its tiles' chunks), read from those first bytes (or the whole pack) in <heads-dir>. cut packs one
      country's tiles of <world-keep.json> into packs of their own in <out-dir>, from the whole packs in <packs-dir>
      (--needed prints their ranges), as world-refs cuts its views' tiles."""

    /** world-keep.json's format. */
    const val FORMAT = 1

    /** The first bytes of a pack read for its head: header, manifest, directory and chunk table (a fine pack's ~100 KB). */
    const val HEAD_PROBE = 256 * 1024

    /** The views' distances: from the world's first (WorldRefs.WORLD_FROM: under it a country's own tiles draw) to the farthest. */
    const val NEAREST = WorldRefs.WORLD_FROM
    const val FARTHEST = 20_000_000.0

    /** The views sampled for the reach: distances a tenth apart, pitches 5° apart (and the steepest), these bearings. */
    const val DISTANCE_STEP = 1.1
    const val PITCH_STEP = 5.0
    val BEARINGS = doubleArrayOf(0.0, 30.0, 45.0)

    /** The views' targets sampled, within an L6 tile at the equator, from this seed. */
    const val TARGETS = 6
    const val SEED = 11L

    /** The room on the measured reach: a share more, and a share of a tile's diagonal at its level (at the equator). */
    const val MARGIN = 1.1
    const val DIAGONAL_SHARE = 0.5

    /** The tallest frame (dp) the pitch limit is taken for, a large tablet upright; its zoom by a distance is the highest. */
    const val TALL_DP = 1_400.0

    /** CameraOps' gestures' pitch: flat at zoom 3 and below, its steepest from zoom 8 on; MapCamera's 72° at most. */
    const val FLAT_PITCH = 30.0
    const val STEEP_PITCH = 70.0
    const val CAMERA_PITCH = 72.0
    const val PITCH_ROOM = 2.0

    /** Web Mercator's ground metres per dp at zoom 0 at the equator, for 256-dp tiles (Mercator.metresPerPixel). */
    const val METRES_PER_DP = 156_543.03392804097

    /**
     * The frame whose field holds a phone's either way up: the sideways frame's width over its height (the field's
     * 36° over the short side) and the upright frame's target, 0.48 of the height from the top (WorldRefs takes the
     * farther edge, 0.52 of it, both ways).
     */
    val WIDE = WorldRefs.Frame(2400, 1080, 1080.0 / 412.0, 0.48)

    /** The country's outline as compared: simplified by this much, its edges cut this short (degrees); the slack both leave (m). */
    const val SIMPLIFY = 0.002
    const val DENSIFY = 0.01
    const val SLACK = 1_000.0

    private const val R = WorldRefs.EARTH_RADIUS

    /** One region of regions.json: its id and names, its box (west, south, east, north), whether it clips its extract to it. */
    class Region(val id: String, val name: String, val country: String?, val bbox: DoubleArray, val clip: Boolean)

    /** The regions of [file] (packs.yml's regions.json) whose countries have tiles: all but those without windows. */
    fun regions(file: File): List<Region> {
        val o = Json.parseToJsonElement(file.readText()).jsonObject
        return (o["regions"] ?: throw UsageException("$file has no regions")).jsonArray.map { it.jsonObject }
            .filter { (it["windows"] as? JsonPrimitive)?.boolean != false }
            .map { r ->
                val id = r["id"]!!.jsonPrimitive.content
                val box = r["bbox"]?.jsonArray?.map { it.jsonPrimitive.double }?.toDoubleArray()
                if (box == null || box.size != 4 || box[0] >= box[2] || box[1] >= box[3]) throw UsageException("$file: $id has no box")
                Region(id, r["name"]?.jsonPrimitive?.content ?: id, r["country"]?.jsonPrimitive?.content, box, (r["clip"] as? JsonPrimitive)?.boolean == true)
            }
    }

    /** A country's area as kept, and what it was taken from. */
    class Area(val geometry: Geometry, val from: String)

    /** [region]'s area: its polygon (`<id>.poly` in [polys]: its outer rings less its holes), within its box where it clips. */
    fun area(region: Region, polys: File): Area {
        val file = File(polys, "${region.id}.poly")
        if (!file.isFile) throw UsageException("no ${file.name} in $polys: the polygon ${region.id}'s extract is cut with")
        val f = GeometryFactory()
        val poly = ClipPolygon.read(file)
        fun ring(r: ClipPolygon.Ring): Geometry {
            val c = ArrayList<Coordinate>(r.size + 1)
            for (i in 0 until r.size) c += Coordinate(r.lon[i], r.lat[i])
            if (c.first() != c.last()) c += Coordinate(c.first())
            return f.createPolygon(c.toTypedArray()).buffer(0.0)
        }
        var g: Geometry = f.createGeometryCollection(poly.rings.filter { !it.hole }.map(::ring).toTypedArray()).union()
        val holes = poly.rings.filter { it.hole }
        if (holes.isNotEmpty()) g = g.difference(f.createGeometryCollection(holes.map(::ring).toTypedArray()).union())
        if (!region.clip) return Area(g, "poly")
        val b = region.bbox
        return Area(g.intersection(f.toGeometry(Envelope(b[0], b[2], b[1], b[3]))), "poly within the box")
    }

    /** The steepest pitch a view at [distance] may have: CameraOps.maxPitch at its zoom on a [TALL_DP] frame at the equator, with room. */
    fun pitchLimit(distance: Double): Double {
        val focal = TALL_DP / 2 / tan(Math.toRadians(WorldRefs.FIELD_OF_VIEW / 2))
        val zoom = log2(focal * METRES_PER_DP / distance)
        val gesture = when {
            zoom >= 8.0 -> STEEP_PITCH
            zoom <= 3.0 -> FLAT_PITCH
            else -> FLAT_PITCH + (STEEP_PITCH - FLAT_PITCH) * (zoom - 3.0) / 5.0
        }
        return min(CAMERA_PITCH, gesture + PITCH_ROOM)
    }

    /** The views' distances, [NEAREST] to [FARTHEST] a [DISTANCE_STEP] apart, both ends included. */
    fun distances(): List<Double> {
        val out = ArrayList<Double>()
        var d = NEAREST
        while (d < FARTHEST) {
            out += d
            d *= DISTANCE_STEP
        }
        return out + FARTHEST
    }

    /** A view's pitches sampled at [distance]: from 0 a [PITCH_STEP] apart, and the steepest. */
    fun pitches(distance: Double): List<Double> {
        val limit = pitchLimit(distance)
        val out = ArrayList<Double>()
        var p = 0.0
        while (p < limit) {
            out += p
            p += PITCH_STEP
        }
        return out + limit
    }

    /** The great-circle distance (m) between two places (degrees). */
    fun distance(lat0: Double, lon0: Double, lat1: Double, lon1: Double): Double {
        val a = Math.toRadians(lat0)
        val b = Math.toRadians(lat1)
        val s = sin((b - a) / 2)
        val t = sin(Math.toRadians(lon1 - lon0) / 2)
        return 2 * R * asin(min(1.0, sqrt(s * s + cos(a) * cos(b) * t * t)))
    }

    /**
     * The great-circle distance (m) from a place to a box (degrees, at most 180° of longitude wide; 0 inside it):
     * straight north or south where its longitude lies within the box's, else to the nearer point of its west or east
     * edge, each a meridian's arc (the nearest point of a parallel's edge is then a corner). Along a meridian the
     * distance falls toward the point of its great circle nearest the place, so an edge's nearest point is that one
     * where the edge holds it, else the nearer of its ends: more than 90° of longitude away that point lies beyond a
     * pole, and the nearer end may be the edge's north or its south.
     */
    fun boxDistance(lat: Double, lon: Double, south: Double, west: Double, north: Double, east: Double): Double {
        fun off(l: Double): Double {
            var d = (l - lon) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }
        // the box's longitudes from the place's: within them where the west edge lies west of it and the east east
        val w = off(west)
        if (w <= 0.0 && w + (east - west) >= 0.0) {
            return R * Math.toRadians(max(0.0, max(south - lat, lat - north)))
        }
        fun meridian(edge: Double): Double {
            val dl = Math.toRadians(off(edge))
            val p = Math.toRadians(lat)
            // the point of the edge's great circle nearest the place (a latitude beyond ±90° lies past a pole)
            val foot = Math.toDegrees(atan2(sin(p), cos(p) * cos(dl)))
            val ends = min(distance(lat, lon, south, edge), distance(lat, lon, north, edge))
            return if (foot in south..north) min(ends, distance(lat, lon, foot, edge)) else ends
        }
        return min(meridian(west), meridian(east))
    }

    /** A tile's box (south, west, north, east in degrees). */
    fun box(z: Int, x: Int, y: Int): DoubleArray = Grid.tileBox(z, x, y).let { g -> doubleArrayOf(Grid.deg(g[0]), Grid.deg(g[1]), Grid.deg(g[2]), Grid.deg(g[3])) }

    private fun level(k: Long) = (k ushr 48).toInt()

    private fun tileX(k: Long) = ((k ushr 24) and 0xFFFFFF).toInt()

    private fun tileY(k: Long) = (k and 0xFFFFFF).toInt()

    /**
     * Each level's reach (metres; L6 to L11 at their index, 0 below): the farthest a view's tile of that level lies
     * from the view's target, over the views sampled (see the object's doc), with [MARGIN] and [DIAGONAL_SHARE] of a
     * tile's diagonal more, and at least the next finer level's. On [threads] threads (the same on any number), once
     * a process.
     */
    fun reach(threads: Int = 1): DoubleArray = synchronized(this) { measured ?: measure(threads).also { measured = it } }.copyOf()

    private var measured: DoubleArray? = null

    private fun measure(threads: Int): DoubleArray {
        val random = Random(SEED)
        // within the L6 tile north-east of 0°, 0°: Mercator's tiles are largest at the equator
        val targets = List(TARGETS) { doubleArrayOf(random.nextDouble() * 5.5, random.nextDouble() * 5.625) }
        val tasks = ArrayList<Callable<DoubleArray>>()
        for (t in targets) for (d in distances()) {
            tasks += Callable {
                val far = DoubleArray(WorldRefs.FINEST + 1)
                for (p in pitches(d)) for (b in BEARINGS) {
                    for (k in WorldRefs.tiles(WorldRefs.View("keep", t[0], t[1], d, p, b), WIDE)) {
                        val z = level(k)
                        val g = box(z, tileX(k), tileY(k))
                        far[z] = max(far[z], boxDistance(t[0], t[1], g[0], g[1], g[2], g[3]))
                    }
                }
                far
            }
        }
        val far = DoubleArray(WorldRefs.FINEST + 1)
        val pool = Executors.newFixedThreadPool(max(1, threads))
        try {
            for (f in pool.invokeAll(tasks)) f.get().forEachIndexed { z, v -> far[z] = max(far[z], v) }
        } finally {
            pool.shutdownNow()
        }
        val out = DoubleArray(WorldRefs.FINEST + 1)
        for (z in WorldRefs.FINEST downTo WorldRefs.FIRST) {
            val diagonal = sqrt(2.0) * 2 * PI * R / (1 shl z)
            out[z] = MARGIN * far[z] + DIAGONAL_SHARE * diagonal
            if (z < WorldRefs.FINEST) out[z] = max(out[z], out[z + 1])
        }
        return out
    }

    /** The column of the tile holding longitude [lon] at level [z]. */
    private fun column(z: Int, lon: Double): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

    /** The row of the tile holding latitude [lat] (within Mercator's) at level [z]. */
    private fun row(z: Int, lat: Double): Int {
        val p = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = (1 - ln(tan(p) + 1 / cos(p)) / PI) / 2 * (1 shl z)
        return floor(y).toInt().coerceIn(0, (1 shl z) - 1)
    }

    /**
     * The L6 to L11 tiles (keys as WorldRefs.key) within each level's [reach] of [area]: those whose box holds a point
     * of its simplified outline's edges, cut [DENSIFY] short, within the reach and [SLACK], or whose middle it holds.
     */
    fun tiles(area: Geometry, reach: DoubleArray): Set<Long> {
        val simple = TopologyPreservingSimplifier.simplify(area, SIMPLIFY)
        val outline = Densifier.densify(simple.boundary, DENSIFY).coordinates
        val inside = PreparedGeometryFactory.prepare(simple)
        val f = GeometryFactory()
        val env = simple.envelopeInternal
        val out = LinkedHashSet<Long>()
        for (z in WorldRefs.FIRST..WorldRefs.FINEST) {
            val r = reach[z] + SLACK
            val dLat = Math.toDegrees(r / R)
            val south = max(-85.05112878, env.minY - dLat)
            val north = min(85.05112878, env.maxY + dLat)
            val dLon = min(180.0, dLat / cos(Math.toRadians(max(abs(south), abs(north)))))
            val n = 1 shl z
            val columns = if (env.maxX - env.minX + 2 * dLon >= 360.0) (0 until n).toList()
            else (column(z, env.minX - dLon) - 1..column(z, env.maxX + dLon) + 1).map { Math.floorMod(it, n) }.distinct()
            for (x in columns) for (y in max(0, row(z, north) - 1)..min(n - 1, row(z, south) + 1)) {
                val g = box(z, x, y)
                val near = outline.any { c -> boxDistance(c.y, c.x, g[0], g[1], g[2], g[3]) <= r } ||
                    inside.contains(f.createPoint(Coordinate((g[1] + g[3]) / 2, (g[0] + g[2]) / 2)))
                if (near) out += WorldRefs.key(z, x, y)
            }
        }
        return out
    }

    /** A pack's head as the phone fetches it: its bytes but its chunks section, and each tile's chunk's stored bytes by key. */
    class Head(val bytes: Long, val chunks: Map<Long, Int>)

    /**
     * The head of a pack of [length] bytes from its first bytes [first] (or the whole pack): its header, manifest and
     * directory, the sections end to end in the core's order, and the chunk table, its CRC-32 checked.
     */
    fun head(first: ByteArray, length: Long, what: String): Head {
        if (first.size < WindowPack.HEADER || String(first, 0, 8, Charsets.US_ASCII) != PackWriter.MAGIC) throw PackFormatException("$what: not an Atlas pack")
        if (first[9].toInt() != Format.VERSION) throw PackFormatException("$what: not a render pack of format ${Format.VERSION}")
        val h = ByteSource(first, 12, WindowPack.HEADER)
        val manifest = h.u32()
        val directory = h.u32()
        val end = WindowPack.HEADER + manifest + directory
        if (end > first.size) throw PackFormatException("$what: its first ${first.size} bytes end inside its directory")
        val d = ByteSource(first, (WindowPack.HEADER + manifest).toInt(), end.toInt())
        class S(val name: String, val offset: Long, val stored: Long, val crc: Long)
        val sections = ArrayList<S>()
        var at = end
        while (d.remaining > 0) {
            val name = d.string()
            val offset = d.u64()
            val stored = d.u64()
            d.u64()
            val crc = d.u32()
            d.byte()
            if (offset != at || stored < 0) throw PackFormatException("$what: section $name lies at $offset, not at $at")
            sections += S(name, offset, stored, crc)
            at = offset + stored
        }
        if (sections.map { it.name } != WindowPack.ORDER) throw PackFormatException("$what: sections ${sections.joinToString { it.name }}")
        if (at != length) throw PackFormatException("$what: its sections end at $at, not at its $length bytes")
        val tiles = sections[0]
        val chunks = sections[2]
        if (tiles.offset + tiles.stored > first.size) throw PackFormatException("$what: its first ${first.size} bytes end inside its chunk table")
        if (CRC32().also { it.update(first, tiles.offset.toInt(), tiles.stored.toInt()) }.value != tiles.crc) throw PackFormatException("$what: its chunk table is damaged")
        val t = ByteSource(first, tiles.offset.toInt(), (tiles.offset + tiles.stored).toInt())
        val n = t.varLong()
        val out = LinkedHashMap<Long, Int>()
        var sum = 0L
        repeat(Math.toIntExact(n)) {
            val z = t.byte()
            val x = t.varLong().toInt()
            val y = t.varLong().toInt()
            val stored = t.varLong()
            t.varLong()
            t.varLong()
            t.bytes(32)
            if (stored <= 0) throw PackFormatException("$what: chunk $z/$x/$y of $stored bytes")
            out[WorldRefs.key(z, x, y)] = Math.toIntExact(stored)
            sum += stored
        }
        if (t.remaining != 0 || sum != chunks.stored) throw PackFormatException("$what: its chunk table does not fill its chunks ($sum of ${chunks.stored} bytes)")
        return Head(length - chunks.stored, out)
    }

    /** A pack's share of a country: its head's bytes, the tiles kept of it (its chunks there) and each one's chunk's bytes. */
    class Kept(val entry: WorldIndex.Entry, val head: Long, val tiles: List<Long>, val sizes: List<Int>) {
        val bytes: Long get() = sizes.sumOf { it.toLong() }
    }

    /** A country kept: its region, what its area was taken from, its packs' shares. */
    class Country(val region: Region, val area: String, val packs: List<Kept>) {
        val heads: Long get() = packs.sumOf { it.head }
        val bytes: Long get() = heads + packs.sumOf { it.bytes }
        val tiles: Int get() = packs.sumOf { it.tiles.size }
    }

    /** [region]'s packs of [index] with the tiles kept of them, their heads read from [heads] (each pack's first bytes, or the whole pack). */
    fun country(index: WorldIndex, region: Region, area: Area, reach: DoubleArray, heads: File): Country {
        val packs = WorldRefs.packs(index, tiles(area.geometry, reach)).map { (entry, keys) ->
            val file = File(heads, WorldRefs.fileOf(entry))
            if (!file.isFile) throw UsageException("no ${file.name} in $heads: pack ${entry.z}/${entry.x}/${entry.y}'s first bytes (--needed)")
            val first = file.readBytes()
            if (first.size.toLong() == entry.length && !MessageDigest.getInstance("SHA-256").digest(first).contentEquals(entry.sha256)) {
                throw IllegalStateException("${file.name} is not the index's pack ${entry.z}/${entry.x}/${entry.y}")
            }
            val head = head(first, entry.length, file.name)
            val kept = keys.filter { it in head.chunks }
            Kept(entry, head.bytes, kept, kept.map { head.chunks.getValue(it) })
        }.filter { it.tiles.isNotEmpty() }
        return Country(region, area.from, packs)
    }

    /** world-keep.json: the world it was made for (its release and index), the reach, and each country's packs and tiles. */
    fun json(release: String?, indexFile: File, reach: DoubleArray, countries: List<Country>): JsonObject = buildJsonObject {
        put("format", FORMAT)
        release?.let { put("release", it) }
        putJsonObject("index") {
            put("bytes", indexFile.length())
            put("sha256", MessageDigest.getInstance("SHA-256").digest(indexFile.readBytes()).joinToString("") { "%02x".format(it) })
        }
        put(
            "about",
            "The world's tiles a kept country draws offline from 18 km out to the globe (ENGINE.md step 11): each level's " +
                "tiles within its reach (metres) of the country's area, by pack: its head (its bytes but its chunks, " +
                "fetched as for any view) and its tiles' chunks (z, then x and y pairs); bytes are what keeping it fetches.",
        )
        putJsonObject("reach") { for (z in WorldRefs.FIRST..WorldRefs.FINEST) put("$z", Math.round(reach[z])) }
        putJsonArray("countries") {
            for (c in countries) add(
                buildJsonObject {
                    put("id", c.region.id)
                    put("name", c.region.name)
                    c.region.country?.let { put("country", it) }
                    put("area", c.area)
                    put("bytes", c.bytes)
                    put("heads", c.heads)
                    put("tiles", c.tiles)
                    putJsonObject("levels") {
                        val all = c.packs.flatMap { p -> p.tiles.zip(p.sizes) }
                        for ((z, ks) in all.groupBy { level(it.first) }.toSortedMap()) {
                            putJsonObject("$z") {
                                put("tiles", ks.size)
                                put("bytes", ks.sumOf { it.second.toLong() })
                            }
                        }
                    }
                    putJsonArray("packs") {
                        for (p in c.packs) add(
                            buildJsonObject {
                                put("z", p.entry.z)
                                put("x", p.entry.x)
                                put("y", p.entry.y)
                                put("head", p.head)
                                put("bytes", p.bytes)
                                putJsonObject("tiles") {
                                    for ((z, ks) in p.tiles.groupBy(::level).toSortedMap()) {
                                        put("$z", buildJsonArray { for (k in ks) { add(tileX(k)); add(tileY(k)) } })
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }
    }

    /** One country of a world-keep.json: its packs (z, x, y) and their tiles' keys. */
    fun kept(json: JsonObject, id: String): Map<Triple<Int, Int, Int>, Set<Long>> {
        val c = json["countries"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["id"]!!.jsonPrimitive.content == id }
            ?: throw UsageException("world-keep.json keeps no $id")
        return c["packs"]!!.jsonArray.associate { e ->
            val p = e.jsonObject
            val keys = LinkedHashSet<Long>()
            for ((z, xy) in p["tiles"]!!.jsonObject) {
                val v = xy.jsonArray.map { it.jsonPrimitive.int }
                for (i in v.indices step 2) keys += WorldRefs.key(z.toInt(), v[i], v[i + 1])
            }
            Triple(p["z"]!!.jsonPrimitive.int, p["x"]!!.jsonPrimitive.int, p["y"]!!.jsonPrimitive.int) to keys
        }
    }

    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "world-keep") throw UsageException("usage: $USAGE")
        if (args.getOrNull(1) == "cut") return cut(args, out)
        val positional = ArrayList<String>()
        var release: String? = null
        var threads = 1
        var i = 1
        while (i < args.size) {
            when (val a = args[i]) {
                "--release" -> release = args.getOrNull(++i) ?: throw UsageException("--release needs a tag")
                "--threads" -> threads = args.getOrNull(++i)?.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("--threads needs a number")
                else -> positional += a
            }
            i++
        }
        val needed = positional.getOrNull(3) == "--needed"
        if (positional.size != (if (needed) 4 else 5)) throw UsageException("usage: $USAGE")
        val indexFile = File(positional[0]).also { if (!it.isFile) throw UsageException("no index $it") }
        val regionsFile = File(positional[1]).also { if (!it.isFile) throw UsageException("no regions $it") }
        val polys = File(positional[2]).also { if (!it.isDirectory) throw UsageException("no polygons' folder $it") }
        val index = WorldIndex.read(indexFile.readBytes())
        val regions = regions(regionsFile)
        val t0 = System.nanoTime()
        val reach = reach(threads)
        System.err.println(
            "world-keep: reach ${(WorldRefs.FIRST..WorldRefs.FINEST).joinToString { "L$it ${Math.round(reach[it] / 1000.0)} km" }} " +
                "(${(System.nanoTime() - t0) / 1_000_000} ms)",
        )
        if (needed) {
            val packs = LinkedHashSet<WorldIndex.Entry>()
            for (r in regions) {
                val keys = tiles(area(r, polys).geometry, reach)
                val held = WorldRefs.packs(index, keys)
                System.err.println("world-keep: ${r.id}: ${keys.size} tiles within reach, ${held.size} packs hold some")
                packs += held.keys
            }
            for (e in packs.sortedWith(compareBy({ index.parts[it.part].name }, { it.offset }))) {
                out("${index.parts[e.part].name} ${e.offset} ${min(e.length, HEAD_PROBE.toLong())} ${WorldRefs.fileOf(e)}")
            }
            return 0
        }
        val heads = File(positional[3]).also { if (!it.isDirectory) throw UsageException("no heads' folder $it") }
        val outFile = File(positional[4])
        val countries = regions.map { r -> country(index, r, area(r, polys), reach, heads) }
        outFile.absoluteFile.parentFile?.mkdirs()
        outFile.writeText(Json.encodeToString(JsonObject.serializer(), json(release, indexFile, reach, countries)) + "\n")
        for (c in countries) {
            out("world-keep: ${c.region.id}: ${c.tiles} tiles in ${c.packs.size} packs, ${c.bytes} bytes (heads ${c.heads})")
        }
        return 0
    }

    private fun cut(args: List<String>, out: (String) -> Unit): Int {
        if (args.size !in 5..7) throw UsageException("usage: $USAGE")
        val indexFile = File(args[2]).also { if (!it.isFile) throw UsageException("no index $it") }
        val keepFile = File(args[3]).also { if (!it.isFile) throw UsageException("no world-keep.json $it") }
        val id = args[4]
        val index = WorldIndex.read(indexFile.readBytes())
        val json = Json.parseToJsonElement(keepFile.readText()).jsonObject
        val sha = MessageDigest.getInstance("SHA-256").digest(indexFile.readBytes()).joinToString("") { "%02x".format(it) }
        if (json["index"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content != sha) throw UsageException("$keepFile was not made for $indexFile")
        val kept = kept(json, id).mapKeys { (k, _) ->
            index.find(k.first, k.second, k.third) ?: throw IllegalStateException("$indexFile has no pack ${k.first}/${k.second}/${k.third}")
        }
        if (args.getOrNull(5) == "--needed" && args.size == 6) {
            for (e in kept.keys.sortedWith(compareBy({ index.parts[it.part].name }, { it.offset }))) {
                out("${index.parts[e.part].name} ${e.offset} ${e.length} ${WorldRefs.fileOf(e)}")
            }
            return 0
        }
        if (args.size != 7) throw UsageException("usage: $USAGE")
        val from = File(args[5]).also { if (!it.isDirectory) throw UsageException("no packs folder $it") }
        val dir = File(args[6]).apply { mkdirs() }
        if (from.canonicalFile == dir.canonicalFile) throw UsageException("the packs are cut from $from into another folder, not into it")
        var tiles = 0
        var bytes = 0L
        for ((entry, keys) in kept) {
            val c = WorldRefs.cut(index, entry, keys, from, dir) ?: throw IllegalStateException("pack ${entry.z}/${entry.x}/${entry.y} holds none of $id's tiles")
            if (c.kept != keys.size) throw IllegalStateException("pack ${entry.z}/${entry.x}/${entry.y} holds ${c.kept} of $id's ${keys.size} tiles of it")
            tiles += c.kept
            bytes += c.bytes
            out("world-keep: ${c.file.name}: ${c.kept} of its ${c.held} tiles, ${c.bytes} bytes")
        }
        out("world-keep: $id: $tiles tiles in ${kept.size} packs, $bytes bytes")
        return 0
    }
}
