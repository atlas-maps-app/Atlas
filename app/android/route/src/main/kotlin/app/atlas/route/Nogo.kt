package app.atlas.route

import app.atlas.geo.Geo
import app.atlas.geo.LatLon

/**
 * Somewhere the router must not go. [weight] null forbids it outright; a number makes it cost that many metres' worth instead.
 */
sealed interface Nogo {
    val weight: Double?

    /** Everything within [radiusMetres] of [center]. */
    data class Circle(val center: LatLon, val radiusMetres: Double, override val weight: Double? = null) : Nogo

    /** Any way crossing or running along this line. */
    data class Line(val points: List<LatLon>, override val weight: Double? = null) : Nogo {
        init {
            require(points.size >= 2) { "a nogo line needs two points" }
        }
    }

    /** Everything inside this ring (closed implicitly). */
    data class Area(val ring: List<LatLon>, override val weight: Double? = null) : Nogo {
        init {
            require(ring.size >= 3) { "a nogo area needs three points" }
        }
    }

    companion object {
        /**
         * A short line across a way at [at], at right angles to its [bearing]: it blocks that way without touching its junctions,
         * and only ways passing within [halfWidth] metres of [at] are hit.
         */
        fun across(at: LatLon, bearing: Double, halfWidth: Double = 2.5): Line =
            Line(listOf(Geo.destination(at, bearing - 90, halfWidth), Geo.destination(at, bearing + 90, halfWidth)))
    }
}
