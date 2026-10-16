package app.atlas.ui.mine

import app.atlas.services.GnssInfo
import app.atlas.services.Satellite
import kotlin.math.cos
import kotlin.math.sin

/** The satellite systems the plot tells apart, with the board's colours and the shapes that mark them. */
enum class Constellation(val code: Int, val letter: Char, val colour: Long, val shape: Shape, val key: String) {
    GPS(1, 'G', 0xFF4D9FFF, Shape.CIRCLE, "gps"),
    GALILEO(6, 'E', 0xFFFFD24A, Shape.SQUARE, "galileo"),
    GLONASS(3, 'R', 0xFFFF6B8A, Shape.DIAMOND, "glonass"),
    BEIDOU(5, 'C', 0xFF7CFFB2, Shape.TRIANGLE, "beidou"),
    QZSS(4, 'J', 0xFFB9A6FF, Shape.CIRCLE, "qzss"),
    IRNSS(7, 'I', 0xFFB9A6FF, Shape.CIRCLE, "irnss"),
    SBAS(2, 'S', 0xFF9AA6D0, Shape.CIRCLE, "sbas");

    /** GPS circles, Galileo squares, GLONASS diamonds, BeiDou triangles. */
    enum class Shape { CIRCLE, SQUARE, DIAMOND, TRIANGLE }

    /** The four the legend counts; the rest are drawn but not listed. */
    val listed: Boolean get() = this in MAJOR

    companion object {
        val MAJOR = listOf(GPS, GALILEO, GLONASS, BEIDOU)
        fun of(code: Int): Constellation? = entries.firstOrNull { it.code == code }
    }
}

/** What the Satellites screen works out from what the GNSS chip says. */
object SatellitesModel {
    /** The plot's half-size and where it is centred, in the board's 340-unit square. */
    const val RADIUS = 150f
    const val CENTRE = 170f

    /** Where a satellite at [azimuth] (degrees clockwise from north) and [elevation] sits on the plot: the zenith is the centre. */
    fun point(azimuth: Float, elevation: Float): Pair<Float, Float> {
        val r = RADIUS * (90f - elevation.coerceIn(0f, 90f)) / 90f
        val a = Math.toRadians(azimuth.toDouble())
        return (CENTRE + r * sin(a)).toFloat() to (CENTRE - r * cos(a)).toFloat()
    }

    /** The name of a satellite on the plot and the bars: "G06", "R04", "E19". */
    fun label(s: Satellite): String = "${Constellation.of(s.constellation)?.letter ?: '?'}${s.svid.toString().padStart(2, '0')}"

    /** Per listed system: satellites used in the fix of those in view. */
    fun counts(info: GnssInfo): List<Triple<Constellation, Int, Int>> = Constellation.MAJOR.map { c ->
        val mine = info.satellites.filter { it.constellation == c.code }
        Triple(c, mine.count { it.usedInFix }, mine.size)
    }

    /** The satellites in use, strongest signal first, for the bars. */
    fun bars(info: GnssInfo, max: Int = 18): List<Satellite> = info.satellites.filter { it.usedInFix && it.cn0 > 0f }.sortedByDescending { it.cn0 }.take(max)

    /** The bar height for [cn0] dB-Hz in a box [maxHeight] tall: 50 dB-Hz fills it (typical best signals are 45–50). */
    fun barHeight(cn0: Float, maxHeight: Float): Float = (cn0 / 50f).coerceIn(0.05f, 1f) * maxHeight

    /** How many different systems have a satellite in view. */
    fun systems(info: GnssInfo): Int = info.satellites.map { it.constellation }.distinct().size

    /** The satellites to name on the plot: the strongest few of those in use. */
    fun named(info: GnssInfo, n: Int = 6): Set<Satellite> = info.satellites.filter { it.usedInFix }.sortedByDescending { it.cn0 }.take(n).toSet()
}
