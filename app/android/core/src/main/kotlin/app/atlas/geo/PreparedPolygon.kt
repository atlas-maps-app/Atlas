package app.atlas.geo

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A polygon (outer ring, then holes) prepared for many point-in-polygon tests, such as every café against a route
 * corridor: its edges sit in horizontal bands, so a test reads only the few edges of one band.
 * Even-odd over all rings; a point exactly on an edge counts as inside.
 */
class PreparedPolygon(polygon: List<List<LatLon>>) {
    val bbox: BBox

    private val x0: DoubleArray
    private val y0: DoubleArray
    private val x1: DoubleArray
    private val y1: DoubleArray
    private val bands: Int
    private val bandHeight: Double
    private val bandStart: IntArray
    private val bandEdges: IntArray

    init {
        var edges = 0
        for (ring in polygon) if (ring.size >= 2) edges += ring.size
        x0 = DoubleArray(edges); y0 = DoubleArray(edges); x1 = DoubleArray(edges); y1 = DoubleArray(edges)
        var e = 0
        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY
        for (ring in polygon) {
            if (ring.size < 2) continue
            var prev = ring[ring.size - 1]
            for (p in ring) {
                x0[e] = prev.lon; y0[e] = prev.lat; x1[e] = p.lon; y1[e] = p.lat
                e++
                minX = min(minX, p.lon); maxX = max(maxX, p.lon)
                minY = min(minY, p.lat); maxY = max(maxY, p.lat)
                prev = p
            }
        }
        bbox = if (edges == 0) BBox(0.0, 0.0, 0.0, 0.0) else BBox(minX, minY, maxX, maxY)
        bands = if (edges == 0) 1 else sqrt(edges.toDouble()).toInt().coerceIn(1, 1024)
        bandHeight = max((maxY - minY) / bands, 1e-12)

        // Edges per band, as compressed rows: count, prefix-sum, fill.
        bandStart = IntArray(bands + 1)
        for (i in 0 until edges) {
            for (k in firstBand(i)..lastBand(i)) bandStart[k + 1]++
        }
        for (k in 0 until bands) bandStart[k + 1] += bandStart[k]
        bandEdges = IntArray(bandStart[bands])
        val fill = bandStart.copyOf(bands)
        for (i in 0 until edges) {
            for (k in firstBand(i)..lastBand(i)) bandEdges[fill[k]++] = i
        }
    }

    fun contains(p: LatLon): Boolean = contains(p.lat, p.lon)

    fun contains(lat: Double, lon: Double): Boolean {
        if (bandEdges.isEmpty() || lat < bbox.south || lat > bbox.north || lon < bbox.west || lon > bbox.east) return false
        val band = band(lat)
        var inside = false
        for (k in bandStart[band] until bandStart[band + 1]) {
            val i = bandEdges[k]
            val ax = x0[i]; val ay = y0[i]; val bx = x1[i]; val by = y1[i]
            if (lon >= min(ax, bx) && lon <= max(ax, bx) && lat >= min(ay, by) && lat <= max(ay, by) &&
                (bx - ax) * (lat - ay) - (by - ay) * (lon - ax) == 0.0
            ) return true
            if ((ay > lat) != (by > lat) && lon < (bx - ax) * (lat - ay) / (by - ay) + ax) inside = !inside
        }
        return inside
    }

    private fun band(lat: Double): Int = floor((lat - bbox.south) / bandHeight).toInt().coerceIn(0, bands - 1)

    private fun firstBand(i: Int): Int = band(min(y0[i], y1[i]))
    private fun lastBand(i: Int): Int = band(max(y0[i], y1[i]))
}
