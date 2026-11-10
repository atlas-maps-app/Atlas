package app.atlas.search

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.Shapes
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A green area's outline as a region's store keeps it ([Outlines]): closed [rings] (each ends on its first point), outer
 * ones and holes ([inner]), simplified to what its plan shows; and the [paths] its plan draws in it, where the store keeps
 * them: null for an area whose paths it does not keep (a landscape, whose plan draws none; a store from before), empty
 * for one that has none.
 */
class PlaceOutline(val rings: List<List<LatLon>>, val inner: List<Boolean>, val paths: List<List<LatLon>>? = null) {
    /** Its area on the ground in m², its holes' taken out. */
    val areaM2: Double by lazy {
        var a = 0.0
        for ((i, r) in rings.withIndex()) a += if (inner[i]) -Shapes.geodesicArea(r) else Shapes.geodesicArea(r)
        maxOf(a, 0.0)
    }
}

/**
 * The outlines a region's store keeps beside its places (the user, 10 November: "Offline outlines"): of the areas a park's
 * page draws ([keeps]), so the page draws a park's real shape offline where the phone holds no detailed map of it (a
 * country kept as its tiles); and the paths in those its plan draws them for, up to [PATHS_ACROSS_M] across ("Yes, paths
 * next"). The packer writes them from the extract, which has every area's and every path's whole geometry; an Overpass
 * import, which asks for none, writes none.
 *
 * An outline is simplified for its plan: a vertex closer than a thousandth of its box's diagonal (1 to 50 m) to the line
 * past it is dropped, and so is a ring smaller than twice that, which its plan would draw as a dot; its paths within the
 * same. Monaco's 17 outlines take 1,179 bytes, some seventy a park, each park's area within 1.32 % of the extract's.
 */
object Outlines {
    /** IUCN's classes 11 to 29 protect soil, water, culture or the military, not nature: Amsterdam's canal ring is a 22 (no 10 or 20 is given). */
    private val NOT_NATURE = Regex("^[12][0-9]$")

    /** Whether the store keeps the outline of an area with [tags]: a park, a nature reserve, a national park, an area protected for nature. */
    fun keeps(tags: Map<String, String>): Boolean =
        tags["leisure"] == "park" || tags["leisure"] == "nature_reserve" || tags["boundary"] == "national_park" ||
            (tags["boundary"] == "protected_area" && protectsNature(tags))

    /** A protected area kept for nature: a nature class, or none given on an area that is not a heritage site. */
    fun protectsNature(tags: Map<String, String>): Boolean =
        tags["protect_class"]?.trim()?.let { !NOT_NATURE.matches(it) } ?: ("heritage" !in tags && "historic" !in tags)

    /** The paths a park's plan draws, by their `highway`: the ways people walk in a park, the tracks of woods and heaths among them. */
    val PATHS = setOf("footway", "path", "pedestrian", "cycleway", "steps", "track", "bridleway")

    /**
     * The widest area whose paths the store keeps, its box's diagonal in metres: a park's plan draws them up to 3 km across
     * (its box with a little air round it), a landscape's none.
     */
    const val PATHS_ACROSS_M = 3_000.0

    /** The blobs' layout, their first byte: a store from a later Atlas may hold another, which this one leaves undrawn. */
    private const val FORMAT = 1

    /** The fewest and most metres a vertex may stray from its simplified ring. */
    private const val MIN_TOLERANCE_M = 1.0
    private const val MAX_TOLERANCE_M = 50.0

    /** The most points one outline keeps: beyond, it is simplified twice as much (a coast-long reserve stays some 100 KB). */
    private const val MAX_POINTS = 16_000

    private const val M_PER_DEG = Geo.EARTH_RADIUS * Math.PI / 180

    /** How far a point of the outline [rings], or of the paths in it, may stray when simplified: a thousandth of its box's diagonal, 1 to 50 m. */
    fun toleranceOf(rings: List<IntArray>): Double = (diagonalM(rings) / 1000).coerceIn(MIN_TOLERANCE_M, MAX_TOLERANCE_M)

    /** Whether the store keeps the paths in the area outlined by [rings] ((lat, lon) pairs in 1e-7 degree): one at most [PATHS_ACROSS_M] across. */
    fun keepsPathsOf(rings: List<IntArray>): Boolean = diagonalM(rings) <= PATHS_ACROSS_M

    /** The diagonal in metres of the box round [rings]' points ((lat, lon) pairs in 1e-7 degree). */
    private fun diagonalM(rings: List<IntArray>): Double {
        var south = Int.MAX_VALUE; var north = Int.MIN_VALUE; var west = Int.MAX_VALUE; var east = Int.MIN_VALUE
        for (r in rings) {
            for (k in 0 until r.size / 2) {
                south = minOf(south, r[2 * k]); north = maxOf(north, r[2 * k])
                west = minOf(west, r[2 * k + 1]); east = maxOf(east, r[2 * k + 1])
            }
        }
        if (south > north) return 0.0
        val kx = M_PER_DEG * cos(Math.toRadians((south / 2.0 + north / 2.0) / E7.SCALE))
        return hypot((east - west) / E7.SCALE * kx, (north - south) / E7.SCALE * M_PER_DEG)
    }

    /**
     * [rings] ((lat, lon) pairs in 1e-7 degree, each ending on its first point; [inner] marks the holes) simplified and
     * packed: the format, the ring count, then per ring its point count (shifted left, the low bit set for a hole) and
     * its points as zigzag deltas from the point before, the closing point left out. Null when no outer ring is left.
     */
    internal fun encode(rings: List<IntArray>, inner: List<Boolean>, sink: ByteSink = ByteSink(256)): ByteArray? {
        require(rings.size == inner.size) { "${rings.size} rings, ${inner.size} inner flags" }
        val closed = rings.indices.filter { closedRing(rings[it]) }
        if (closed.isEmpty()) return null
        val diagonal = diagonalM(closed.map { rings[it] })
        var tolerance = toleranceOf(closed.map { rings[it] })
        val kx = kxOf(rings[closed[0]])
        while (true) {
            val kept = ArrayList<Pair<IntArray, Boolean>>()
            var points = 0
            for (i in closed) {
                val s = simplifyRing(rings[i], tolerance, kx) ?: continue
                kept += s to inner[i]
                points += s.size / 2 - 1
            }
            if (kept.none { !it.second }) return null
            if (points > MAX_POINTS && tolerance < diagonal) { tolerance *= 2; continue }
            sink.reset()
            sink.varint(FORMAT)
            sink.varint(kept.size)
            var lat = 0L
            var lon = 0L
            for ((r, hole) in kept) {
                val n = r.size / 2 - 1
                sink.varint((n.toLong() shl 1) or (if (hole) 1L else 0L))
                for (k in 0 until n) {
                    sink.zigzag(r[2 * k] - lat); lat = r[2 * k].toLong()
                    sink.zigzag(r[2 * k + 1] - lon); lon = r[2 * k + 1].toLong()
                }
            }
            return sink.toByteArray()
        }
    }

    /** What [encode] packed, with the area's [paths] where the store keeps them; null for a format this Atlas does not read. */
    internal fun decode(blob: ByteArray, paths: List<List<LatLon>>? = null): PlaceOutline? {
        val src = ByteSource(blob)
        if (src.int() != FORMAT) return null
        val count = src.int()
        val rings = ArrayList<List<LatLon>>(count)
        val inner = ArrayList<Boolean>(count)
        var lat = 0L
        var lon = 0L
        repeat(count) {
            val head = src.varint()
            val n = (head ushr 1).toInt()
            val ring = ArrayList<LatLon>(n + 1)
            repeat(n) {
                lat += src.zigzag()
                lon += src.zigzag()
                ring += LatLon(E7.deg(lat), E7.deg(lon))
            }
            if (n < 3) return@repeat
            ring += ring[0]
            rings += ring
            inner += head and 1L == 1L
        }
        return PlaceOutline(rings, inner, paths).takeIf { inner.any { !it } }
    }

    /**
     * An area's paths ((lat, lon) pairs in 1e-7 degree, each line two points or more) simplified within [toleranceM] and
     * packed as [encode] packs rings: the format, the line count, then per line its point count and its points as zigzag
     * deltas, its last point kept. No lines pack to the format and a zero: the area has no paths.
     */
    internal fun encodeLines(lines: List<IntArray>, toleranceM: Double, sink: ByteSink = ByteSink(256)): ByteArray {
        val kept = lines.filter { it.size >= 4 }.map { simplifyLine(it, toleranceM) }
        sink.reset()
        sink.varint(FORMAT)
        sink.varint(kept.size)
        var lat = 0L
        var lon = 0L
        for (l in kept) {
            val n = l.size / 2
            sink.varint(n)
            for (k in 0 until n) {
                sink.zigzag(l[2 * k] - lat); lat = l[2 * k].toLong()
                sink.zigzag(l[2 * k + 1] - lon); lon = l[2 * k + 1].toLong()
            }
        }
        return sink.toByteArray()
    }

    /** What [encodeLines] packed; null for a format this Atlas does not read. */
    internal fun decodeLines(blob: ByteArray): List<List<LatLon>>? {
        val src = ByteSource(blob)
        if (src.int() != FORMAT) return null
        val count = src.int()
        val lines = ArrayList<List<LatLon>>(count)
        var lat = 0L
        var lon = 0L
        repeat(count) {
            val n = src.int()
            val line = ArrayList<LatLon>(n)
            repeat(n) {
                lat += src.zigzag()
                lon += src.zigzag()
                line += LatLon(E7.deg(lat), E7.deg(lon))
            }
            if (n >= 2) lines += line
        }
        return lines
    }

    /** A degree of longitude in metres at the latitude of [points]' first point ((lat, lon) pairs in 1e-7 degree). */
    private fun kxOf(points: IntArray): Double = M_PER_DEG * cos(Math.toRadians(points[0] / E7.SCALE))

    /** [points] ((lat, lon) pairs in 1e-7 degree) on a plane in metres about their first point, x east and y north. */
    private fun plane(points: IntArray, kx: Double): DoubleArray {
        val n = points.size / 2
        val xy = DoubleArray(2 * n)
        for (i in 0 until n) {
            xy[2 * i] = (points[2 * i + 1] - points[1]) / E7.SCALE * kx
            xy[2 * i + 1] = (points[2 * i] - points[0]) / E7.SCALE * M_PER_DEG
        }
        return xy
    }

    private fun pick(points: IntArray, keep: IntArray): IntArray {
        val out = IntArray(keep.size * 2)
        for (k in keep.indices) {
            out[2 * k] = points[2 * keep[k]]
            out[2 * k + 1] = points[2 * keep[k] + 1]
        }
        return out
    }

    /**
     * The closed ring [r] within [toleranceM] metres of itself, still closed: null when it is smaller than twice that, or
     * keeps fewer than three points. [kx] is a degree of longitude in metres where the outline lies.
     */
    private fun simplifyRing(r: IntArray, toleranceM: Double, kx: Double): IntArray? {
        val xy = plane(r, kx)
        var minX = 0.0; var maxX = 0.0; var minY = 0.0; var maxY = 0.0
        for (i in 0 until xy.size / 2) {
            minX = minOf(minX, xy[2 * i]); maxX = maxOf(maxX, xy[2 * i])
            minY = minOf(minY, xy[2 * i + 1]); maxY = maxOf(maxY, xy[2 * i + 1])
        }
        if (hypot(maxX - minX, maxY - minY) < 2 * toleranceM) return null
        val keep = Shapes.simplifyIndices(xy, toleranceM)
        // the first point is the last too: three more make the least ring with an area
        if (keep.size < 4) return null
        return pick(r, keep)
    }

    /** The line [l] within [toleranceM] metres of itself, its ends kept. */
    private fun simplifyLine(l: IntArray, toleranceM: Double): IntArray = pick(l, Shapes.simplifyIndices(plane(l, kxOf(l)), toleranceM))

    private fun closedRing(r: IntArray): Boolean {
        val n = r.size / 2
        return n >= 4 && r.size % 2 == 0 && r[0] == r[2 * n - 2] && r[1] == r[2 * n - 1]
    }
}
