package app.atlas.rendertiles.model

/**
 * A ring (closed, its first vertex not repeated) or a line on the grid: the latitude and longitude of each vertex in
 * 1e-7 degrees ([Grid]), and for a simplified ring its [keep] codes ([Keep]). Equal by content.
 */
class Geom(val lat: IntArray, val lon: IntArray, val keep: ByteArray?) {
    init {
        require(lat.size == lon.size) { "a geometry of ${lat.size} latitudes and ${lon.size} longitudes" }
        require(keep == null || keep.size == lat.size) { "${keep?.size} keep codes for ${lat.size} vertices" }
    }

    val size: Int get() = lat.size

    /** The same vertices with [codes]. */
    fun withKeep(codes: ByteArray?): Geom = Geom(lat, lon, codes)

    /** [south, west, north, east] of the vertices. */
    fun box(): IntArray {
        require(size > 0) { "an empty geometry has no box" }
        var s = Int.MAX_VALUE
        var w = Int.MAX_VALUE
        var n = Int.MIN_VALUE
        var e = Int.MIN_VALUE
        for (i in 0 until size) {
            if (lat[i] < s) s = lat[i]
            if (lat[i] > n) n = lat[i]
            if (lon[i] < w) w = lon[i]
            if (lon[i] > e) e = lon[i]
        }
        return intArrayOf(s, w, n, e)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Geom || !lat.contentEquals(other.lat) || !lon.contentEquals(other.lon)) return false
        val a = keep
        val b = other.keep
        return if (a == null || b == null) a == null && b == null else a.contentEquals(b)
    }

    override fun hashCode(): Int = (lat.contentHashCode() * 31 + lon.contentHashCode()) * 31 + (keep?.contentHashCode() ?: 0)

    override fun toString(): String = "Geom($size vertices${if (keep != null) ", keep" else ""})"

    companion object {
        val EMPTY = Geom(IntArray(0), IntArray(0), null)
    }
}

/** One point on the grid: latitude and longitude in 1e-7 degrees. */
data class GridPoint(val lat: Int, val lon: Int)
