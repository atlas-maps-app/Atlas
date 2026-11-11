package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * How long a walk round a green takes, told from the box of its outline, the one measure of it on the phone: the way
 * round an ellipse filling the box (a long park lying aslant comes out about right too), at the app's walking pace, and
 * always as "about": to five minutes under the hour, to a quarter of an hour over it, and in kilometres where it would
 * take most of a day (a national park).
 */
object Laps {
    /** Less than this round, about five minutes, and a green is a pocket garden: somewhere to sit, not to walk. */
    const val POCKET_M = 400.0

    private const val LONGEST_MINUTES = 180

    /** The way round a green filling [box], in metres; null where it has no size (drawn as a point). */
    fun metres(box: BBox?): Double? {
        if (box == null || box.width <= 0.0 || box.height <= 0.0) return null
        val c = box.center
        val wide = Geo.distance(LatLon(c.lat, box.west), LatLon(c.lat, box.east))
        val tall = Geo.distance(LatLon(box.south, c.lon), LatLon(box.north, c.lon))
        return PI * (wide + tall) / 2
    }

    /** The ground [box] covers, in square metres: to compare greens by, not to say (an outline fills less of its box). */
    fun ground(box: BBox?): Double? {
        if (box == null || box.width <= 0.0 || box.height <= 0.0) return null
        val c = box.center
        return Geo.distance(LatLon(c.lat, box.west), LatLon(c.lat, box.east)) * Geo.distance(LatLon(box.south, c.lon), LatLon(box.north, c.lon))
    }

    /**
     * About how many kilometres a way round [metres] long is, where walking it would take most of a day ([minutes] says
     * nothing): to the kilometre, to five over twenty. Null where it is shorter.
     */
    fun km(metres: Double): Int? {
        if (metres < POCKET_M || minutes(metres) != null) return null
        val km = metres / 1000
        return if (km < 20) km.roundToInt() else (km / 5).roundToInt() * 5
    }

    /** About how many minutes a walk of [metres] takes, rounded as it is said; null for a pocket garden or most of a day. */
    fun minutes(metres: Double): Int? {
        if (metres < POCKET_M) return null
        val minutes = metres / (TravelMode.WALK.kmh * 1000 / 60)
        val step = if (minutes < 60) 5 else 15
        return ((minutes / step).roundToInt() * step).coerceAtLeast(5).takeIf { it <= LONGEST_MINUTES }
    }
}
