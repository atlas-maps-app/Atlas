package app.atlas.engine

import app.atlas.geo.TileId
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Which tiles a camera needs, by screen-space error: fine tiles near the eye and coarser ones toward the horizon,
 * like a pitched map cover. Tiles nearest the camera come first, the order to load and build them in.
 */
object TileCover {
    /** At most this many tiles; the cover coarsens until it fits. */
    const val MAX_TILES = 60

    /**
     * The cover for [camera]: tiles between [minZoom] and [maxZoom] (scene zooms; data beyond the source's last zoom is
     * overzoomed) that touch the visible ground. [bias] adds detail (positive) or removes it (negative).
     */
    fun cover(camera: Camera, minZoom: Int = 0, maxZoom: Int = 18, maxTiles: Int = MAX_TILES, bias: Double = 0.0): List<TileId> {
        val ground = camera.groundPolygon()
        var b = bias
        repeat(12) {
            val out = ArrayList<TileId>()
            if (subdivide(camera, ground, TileId(0, 0, 0), minZoom, maxZoom, b, out, maxTiles * 4)) {
                if (out.size <= maxTiles) return sortNearFirst(camera, out)
            }
            b -= 0.5
        }
        // Coarse as it gets: whatever fits at the lowest useful zoom.
        val out = ArrayList<TileId>()
        subdivide(camera, ground, TileId(0, 0, 0), minZoom, minZoom, 0.0, out, Int.MAX_VALUE)
        return sortNearFirst(camera, out).take(maxTiles)
    }

    /** The zoom a tile wants at [metres] from the eye: the camera's zoom at its own distance, one less per doubling. */
    fun idealZoom(camera: Camera, metres: Double, bias: Double = 0.0): Double =
        camera.zoom + bias - ln(max(metres, camera.distance * 0.5) / camera.distance) / LN2

    private fun subdivide(
        camera: Camera, ground: DoubleArray, t: TileId, minZoom: Int, maxZoom: Int, bias: Double,
        out: MutableList<TileId>, limit: Int,
    ): Boolean {
        if (!intersects(ground, t)) return true
        // Rounded a little up, so tiles barely farther than the target keep the target's zoom.
        val want = floor(idealZoom(camera, nearestDistance(camera, t), bias) + 0.3).toInt().coerceIn(minZoom, maxZoom)
        if (t.z >= want || t.z >= maxZoom) {
            out += t
            return out.size <= limit
        }
        for (c in t.children()) if (!subdivide(camera, ground, c, minZoom, maxZoom, bias, out, limit)) return false
        return true
    }

    /** Metres from the eye to the nearest point of tile [t] on the ground. */
    fun nearestDistance(camera: Camera, t: TileId): Double {
        val x0 = camera.localX(t.unitX)
        val y0 = camera.localY(t.unitY)
        val s = t.unitSize * camera.metresPerUnit
        val dx = if (camera.eyeX > x0 + s) camera.eyeX - x0 - s else if (camera.eyeX < x0) x0 - camera.eyeX else 0.0
        val dy = if (camera.eyeY > y0 + s) camera.eyeY - y0 - s else if (camera.eyeY < y0) y0 - camera.eyeY else 0.0
        return sqrt(dx * dx + dy * dy + camera.eyeZ * camera.eyeZ)
    }

    private fun sortNearFirst(camera: Camera, tiles: List<TileId>): List<TileId> =
        tiles.sortedWith(compareBy<TileId> { nearestDistance(camera, it) }.thenByDescending { it.z })

    /** Whether tile [t] overlaps the convex polygon [poly] (unit Mercator x, y pairs), by separating axes. */
    fun intersects(poly: DoubleArray, t: TileId): Boolean {
        val x0 = t.unitX
        val y0 = t.unitY
        val x1 = x0 + t.unitSize
        val y1 = y0 + t.unitSize
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        val n = poly.size / 2
        for (i in 0 until n) {
            val px = poly[2 * i]; val py = poly[2 * i + 1]
            if (px < minX) minX = px
            if (px > maxX) maxX = px
            if (py < minY) minY = py
            if (py > maxY) maxY = py
        }
        if (maxX < x0 || minX > x1 || maxY < y0 || minY > y1) return false
        val cx = doubleArrayOf(x0, x1, x1, x0)
        val cy = doubleArrayOf(y0, y0, y1, y1)
        for (i in 0 until n) {
            val ax = poly[2 * i]; val ay = poly[2 * i + 1]
            val bx = poly[2 * ((i + 1) % n)]; val by = poly[2 * ((i + 1) % n) + 1]
            val nx = by - ay
            val ny = ax - bx
            if (nx == 0.0 && ny == 0.0) continue
            // Polygon side of this edge, from a vertex not on it.
            val ox = poly[2 * ((i + 2) % n)]; val oy = poly[2 * ((i + 2) % n) + 1]
            val side = (ox - ax) * nx + (oy - ay) * ny
            var allOut = true
            for (k in 0 until 4) {
                val d = (cx[k] - ax) * nx + (cy[k] - ay) * ny
                if (d * side >= 0 || side == 0.0) { allOut = false; break }
            }
            if (allOut) return false
        }
        return true
    }

    /**
     * What to draw for a [cover] while some scenes are still building: each wanted tile itself when [ready] has its scene,
     * else the nearest ready ancestor (shown only within the missing tiles), else whichever of its descendants are ready,
     * up to [MAX_DOWN] levels down (zooming out fast). Fallback ancestors come first and coarse before fine, so finer
     * ground paints over them.
     */
    fun plan(cover: List<TileId>, maxUp: Int = 8, ready: (TileId) -> TileScene?): List<DrawTile> {
        val exact = ArrayList<DrawTile>()
        val fallback = LinkedHashMap<TileId, MutableList<TileId>>()
        val fallbackScene = HashMap<TileId, TileScene>()
        val seen = HashSet<TileId>()
        for (w in cover) {
            val s = ready(w)
            if (s != null) {
                if (seen.add(w)) exact += DrawTile(s)
                continue
            }
            var found = false
            for (up in 1..minOf(maxUp, w.z)) {
                val a = w.ancestor(w.z - up)
                val sa = ready(a) ?: continue
                fallback.getOrPut(a) { ArrayList() } += w
                fallbackScene[a] = sa
                found = true
                break
            }
            if (found) continue
            // Zooming out: the finer scenes already built cover the tile as well as they can.
            descend(w, MAX_DOWN, ready, seen, exact)
        }
        val parents = fallback.entries.sortedBy { it.key.z }.map { DrawTile(fallbackScene[it.key]!!, it.value) }
        return parents + exact
    }

    /** Levels below a missing tile [plan] looks for ready scenes. */
    const val MAX_DOWN = 2

    private fun descend(t: TileId, levels: Int, ready: (TileId) -> TileScene?, seen: HashSet<TileId>, out: MutableList<DrawTile>) {
        for (c in t.children()) {
            val sc = ready(c)
            if (sc != null) {
                if (seen.add(c)) out += DrawTile(sc)
            } else if (levels > 1) {
                descend(c, levels - 1, ready, seen, out)
            }
        }
    }

    private val LN2 = ln(2.0)
}

/**
 * One scene to draw this frame. When [only] is set (a parent standing in for missing tiles), its buildings, trees,
 * shadows and labels are drawn only inside those tiles; its ground is drawn whole, under everything else.
 */
class DrawTile(val scene: TileScene, val only: List<TileId>? = null) {
    /** Whether a scene point (unit Mercator) belongs to what this tile draws. */
    fun includes(unitX: Double, unitY: Double): Boolean {
        val o = only ?: return true
        for (t in o) {
            if (unitX >= t.unitX && unitX < t.unitX + t.unitSize && unitY >= t.unitY && unitY < t.unitY + t.unitSize) return true
        }
        return false
    }
}
