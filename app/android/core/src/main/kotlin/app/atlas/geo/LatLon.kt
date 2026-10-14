package app.atlas.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A point on Earth in WGS84 degrees. */
data class LatLon(val lat: Double, val lon: Double) {
    override fun toString(): String = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)

    companion object {
        /** Parses "lat,lon" as written by [toString]. */
        fun parse(s: String): LatLon? {
            val p = s.split(',').map { it.trim().toDoubleOrNull() }
            return if (p.size == 2 && p[0] != null && p[1] != null) LatLon(p[0]!!, p[1]!!) else null
        }
    }
}

/** A box in WGS84 degrees; west may exceed east only across the antimeridian, which Atlas does not use. */
data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)
    val width: Double get() = east - west
    val height: Double get() = north - south

    operator fun contains(p: LatLon): Boolean = p.lat in south..north && p.lon in west..east
    fun intersects(o: BBox): Boolean = o.west <= east && o.east >= west && o.south <= north && o.north >= south
    fun union(o: BBox) = BBox(min(west, o.west), min(south, o.south), max(east, o.east), max(north, o.north))
    fun extend(p: LatLon) = BBox(min(west, p.lon), min(south, p.lat), max(east, p.lon), max(north, p.lat))

    /** Grows the box by at least [metres] on every side (the longitude margin is sized at the poleward edge). */
    fun buffer(metres: Double): BBox {
        val dLat = Geo.metresToDegreesLat(metres)
        val dLon = Geo.metresToDegreesLon(metres, max(abs(south), abs(north)) + dLat)
        return BBox(west - dLon, south - dLat, east + dLon, north + dLat)
    }

    override fun toString(): String = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", west, south, east, north)

    companion object {
        val WORLD = BBox(-180.0, -85.05112878, 180.0, 85.05112878)
        fun of(points: Iterable<LatLon>): BBox? {
            var b: BBox? = null
            for (p in points) b = b?.extend(p) ?: BBox(p.lon, p.lat, p.lon, p.lat)
            return b
        }
        fun around(c: LatLon, metres: Double) = BBox(c.lon, c.lat, c.lon, c.lat).buffer(metres)
    }
}
