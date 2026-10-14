package app.atlas.mapview

import app.atlas.engine.Camera
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator

/**
 * The ground as one frame's camera sees it, for overlays: local metres around the target (x east, y south), depth
 * along the view, and the projection to screen pixels, without allocating.
 */
class GroundView(val camera: Camera) {
    private val ex = camera.eyeX
    private val ey = camera.eyeY
    private val ez = camera.eyeZ
    private val fx = camera.fx
    private val fy = camera.fy
    private val fz = camera.fz
    private val rx = camera.rx
    private val ry = camera.ry
    private val ux = camera.ux
    private val uy = camera.uy
    private val uz = camera.uz
    val focal = camera.focal
    private val px = camera.principalX
    private val py = camera.principalY
    val targetX = camera.targetX
    val targetY = camera.targetY
    val metresPerUnit = camera.metresPerUnit

    /** The nearest depth overlays are drawn at: a little beyond the near plane. */
    val nearClip = camera.near * 1.5

    val width = camera.width.toFloat()
    val height = camera.height.toFloat()

    /** Local metres of a unit-Mercator point. */
    fun localX(unitX: Double) = (unitX - targetX) * metresPerUnit
    fun localY(unitY: Double) = (unitY - targetY) * metresPerUnit

    fun localX(p: LatLon) = localX(Mercator.x(p.lon))
    fun localY(p: LatLon) = localY(Mercator.y(p.lat))

    /** Depth of a ground point (local metres) along the view. */
    fun depth(x: Double, y: Double): Double = (x - ex) * fx + (y - ey) * fy - ez * fz

    /** Screen x of a ground point at depth [d] (which must be positive). */
    fun screenX(x: Double, y: Double, d: Double): Float = (px + focal * ((x - ex) * rx + (y - ey) * ry) / d).toFloat()

    fun screenY(x: Double, y: Double, d: Double): Float = (py - focal * ((x - ex) * ux + (y - ey) * uy - ez * uz) / d).toFloat()

    /** Pixels per metre of ground at depth [d]. */
    fun pixelsPerMetre(d: Double): Double = focal / d

    /** Screen pixel of [p] on the ground into [out] (x, y), or false when it is behind the near plane. */
    fun project(p: LatLon, out: FloatArray): Boolean {
        val x = localX(p)
        val y = localY(p)
        val d = depth(x, y)
        if (d < nearClip) return false
        out[0] = screenX(x, y, d)
        out[1] = screenY(x, y, d)
        return true
    }

    /** Whether a screen box meets the view, grown by [margin] pixels. */
    fun onScreen(x0: Float, y0: Float, x1: Float, y1: Float, margin: Float = 0f): Boolean =
        x1 >= -margin && x0 <= width + margin && y1 >= -margin && y0 <= height + margin
}
