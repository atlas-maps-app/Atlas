package app.atlas.search

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Degrees as integers of 1e-7 degree, as OSM keeps them: about a centimetre, and four bytes each. Seven decimals in,
 * the same double out ([deg] of [of] is the double nearest the decimal, as an Overpass import parses it).
 */
internal object E7 {
    const val SCALE = 1e7

    fun of(degrees: Double): Int = Math.round(degrees * SCALE).toInt()

    fun deg(e7: Int): Double = e7 / SCALE

    fun deg(e7: Long): Double = e7 / SCALE

    /** The least integer whose degrees are at least [degrees]: a box's lower edge, exact for every stored value. */
    fun atLeast(degrees: Double): Int {
        if (degrees <= -1000.0) return Int.MIN_VALUE
        var t = ceil(degrees * SCALE).toLong()
        while (deg(t - 1) >= degrees) t--
        while (deg(t) < degrees) t++
        return t.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /** The greatest integer whose degrees are at most [degrees]: a box's upper edge. */
    fun atMost(degrees: Double): Int {
        if (degrees >= 1000.0) return Int.MAX_VALUE
        var t = floor(degrees * SCALE).toLong()
        while (deg(t + 1) <= degrees) t++
        while (deg(t) > degrees) t--
        return t.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}

/** A box in 1e-7 degree: the integers inside a [BBox], for testing many stored points against it. */
internal class E7Box(box: app.atlas.geo.BBox) {
    private val south = E7.atLeast(box.south)
    private val north = E7.atMost(box.north)
    private val west = E7.atLeast(box.west)
    private val east = E7.atMost(box.east)

    fun has(lat: Int, lon: Int): Boolean = lat in south..north && lon in west..east
}
