package app.atlas.mapview

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import kotlin.math.abs
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A smooth flight between two cameras (van Wijk and Nuij, "Smooth and efficient zooming and panning", 2003): a long
 * jump zooms out, travels and zooms back in, as a person would; a short one barely leaves its zoom. Bearing and pitch
 * turn along the eased time, the shortest way round.
 *
 * [viewSizeDp] is the larger side of the view in dp: how much of the world one screen holds.
 */
class FlyTo(private val from: CameraPosition, private val to: CameraPosition, viewSizeDp: Double) {
    private val x0 = Mercator.x(from.target.lon)
    private val y0 = Mercator.y(from.target.lat)
    private val dx: Double
    private val dy = Mercator.y(to.target.lat) - y0
    private val w0 = max(1.0, viewSizeDp)
    private val w1: Double
    private val u1: Double
    private val r0: Double
    private val k: Double

    /** The length of the path in van Wijk's units (screens travelled, zooming counted): how long the flight wants to be. */
    val length: Double

    private val straight: Boolean

    init {
        // Across the antimeridian the short way round.
        var ddx = Mercator.x(to.target.lon) - x0
        if (ddx > 0.5) ddx -= 1.0
        if (ddx < -0.5) ddx += 1.0
        dx = ddx
        val scale0 = 256.0 * Math.pow(2.0, from.zoom)
        w1 = w0 / Math.pow(2.0, to.zoom - from.zoom)
        u1 = sqrt(dx * dx + dy * dy) * scale0
        val rho2 = RHO * RHO
        if (u1 < 1e-6) {
            straight = true
            k = sign(w1 - w0)
            length = abs(ln(w1 / w0)) / RHO
            r0 = 0.0
        } else {
            val b0 = (w1 * w1 - w0 * w0 + rho2 * rho2 * u1 * u1) / (2 * w0 * rho2 * u1)
            val b1 = (w1 * w1 - w0 * w0 - rho2 * rho2 * u1 * u1) / (2 * w1 * rho2 * u1)
            val ra = ln(sqrt(b0 * b0 + 1) - b0)
            val rb = ln(sqrt(b1 * b1 + 1) - b1)
            val s = (rb - ra) / RHO
            if (s.isFinite()) {
                straight = false
                r0 = ra
                k = 0.0
                length = s
            } else {
                straight = true
                k = sign(w1 - w0)
                length = abs(ln(w1 / w0)) / RHO
                r0 = 0.0
            }
        }
    }

    /** The camera at [t] in 0..1 of the flight's time (eased here: slow away, slow in). */
    fun at(t: Double): CameraPosition {
        if (t <= 0.0) return from
        if (t >= 1.0) return to
        val e = ease(t)
        val s = e * length
        // The span of ground in view relative to the start's, and the share of the way travelled.
        val span: Double
        val u: Double
        if (straight) {
            span = if (length == 0.0) 1.0 else exp(k * RHO * s)
            u = e
        } else {
            span = cosh(r0) / cosh(r0 + RHO * s)
            u = w0 * (cosh(r0) * tanh(r0 + RHO * s) - sinh(r0)) / (RHO * RHO) / u1
        }
        val zoom = if (straight && length == 0.0) from.zoom + (to.zoom - from.zoom) * e else from.zoom - ln(span) / LN2
        val x = x0 + dx * u
        val y = (y0 + dy * u).coerceIn(0.0, 1.0)
        val bearing = from.bearing + CameraOps.turn(from.bearing, to.bearing) * e
        val pitch = from.pitch + (to.pitch - from.pitch) * e
        return CameraPosition(LatLon(Mercator.lat(y), Mercator.lon(wrap(x))), zoom, bearing, pitch)
    }

    private fun wrap(x: Double): Double = x - kotlin.math.floor(x)

    companion object {
        /** How much the flight zooms out on long jumps: van Wijk's ρ, as most maps use it. */
        const val RHO = 1.42
        private val LN2 = ln(2.0)

        /** Ease in and out (cubic). */
        fun ease(t: Double): Double = if (t < 0.5) 4 * t * t * t else 1 - Math.pow(-2 * t + 2, 3.0) / 2
    }
}
