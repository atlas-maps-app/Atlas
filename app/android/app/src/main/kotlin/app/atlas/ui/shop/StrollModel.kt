package app.atlas.ui.shop

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** One street walked shop by shop: its name, the way it runs, and its shops in order along it. */
internal class StrollStreet(
    val name: String,
    /** The way the shops are ordered, as a compass word ("N", "SE"). */
    val direction: String,
    val shops: List<ShopPlace>,
    /** The shop nearest the person: where the stroll starts. */
    val start: Int,
)

/**
 * "Stroll": the shopfronts along one street in the order you would pass them. The street is the one of the nearest
 * shop that has an address (`addr:street`); its shops are put in order along the line they lie on (the principal axis
 * of their positions), north-ish or east-ish first. Where the shops carry no addresses, the nearest few are taken and
 * ordered the same way.
 */
internal object Stroll {
    private const val MAX_SHOPS = 14
    private val WORDS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    fun street(area: ShopArea, here: LatLon): StrollStreet? {
        val shops = area.places.filter { it.tags["shop"] != null && it.tags["shop"] !in setOf("vacant", "yes") && it.distanceM <= 700 }
            .sortedBy { Geo.distance(here, it.location) }
        if (shops.isEmpty()) return null
        val near = shops.firstOrNull { it.tags["addr:street"] != null }
        val named = near?.tags?.get("addr:street")
        val onStreet = if (named != null) shops.filter { it.tags["addr:street"] == named }.take(MAX_SHOPS) else emptyList()
        val chosen = if (onStreet.size >= 3) onStreet else shops.take(8)
        val title = if (onStreet.size >= 3) named!! else chosen.firstNotNullOfOrNull { it.tags["addr:street"] } ?: ""
        val (ordered, bearing) = along(chosen, here)
        val dir = WORDS[((bearing + 22.5) / 45.0).toInt() % 8]
        val start = ordered.indices.minByOrNull { Geo.distance(here, ordered[it].location) } ?: 0
        return StrollStreet(title, dir, ordered, start)
    }

    /** [shops] ordered along their principal axis (heading north or east), and that heading in degrees. */
    fun along(shops: List<ShopPlace>, origin: LatLon): Pair<List<ShopPlace>, Double> {
        if (shops.size < 2) return shops to 0.0
        val kx = Math.cos(Math.toRadians(origin.lat))
        val xs = shops.map { (it.location.lon - origin.lon) * kx }
        val ys = shops.map { it.location.lat - origin.lat }
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in shops.indices) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        var ax = cos(theta); var ay = sin(theta)
        // North-ish or east-ish first.
        if (Math.abs(ay) >= Math.abs(ax)) { if (ay < 0) { ax = -ax; ay = -ay } } else if (ax < 0) { ax = -ax; ay = -ay }
        val order = shops.indices.sortedBy { (xs[it] - mx) * ax + (ys[it] - my) * ay }.map { shops[it] }
        val bearing = (Math.toDegrees(atan2(ax, ay)) + 360) % 360
        return order to bearing
    }

    /** The arrow of a compass word: "N ↑". */
    fun arrow(word: String): String = when (word) {
        "N" -> "↑"; "NE" -> "↗"; "E" -> "→"; "SE" -> "↘"; "S" -> "↓"; "SW" -> "↙"; "W" -> "←"; else -> "↖"
    }

    internal fun roundBearing(b: Double) = b.roundToInt()
}
