package app.atlas.mapview

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.render.live.RegionFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The view a camera is drawn into: [width] × [height] physical pixels at [density] pixels per dp, the principal point
 * moved [offsetY] pixels down from the centre (content padding, or the navigation view's look ahead), over the ground
 * [frame] of the region the engine draws (null: Web Mercator).
 */
data class Viewport(val width: Int, val height: Int, val density: Float, val offsetY: Double = 0.0, val frame: RegionFrame? = null) {
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * Camera maths for gestures and animations, on [CameraPosition]s: every move keeps a chosen point of the ground under a
 * chosen pixel, so pans, pinches and rotations feel attached to the fingers in pitched views too.
 */
object CameraOps {
    const val MIN_ZOOM = 1.0
    const val MAX_ZOOM = 20.5

    /** The steepest pitch gestures reach (the camera allows up to [MapCamera.MAX_PITCH]). */
    const val MAX_PITCH = 70.0

    /** The camera for [p] in [v]: the engine's, in the region's own frame when [v] has one. */
    fun camera(p: CameraPosition, v: Viewport): MapCamera =
        MapCamera(
            p.target, p.zoom, p.bearing, p.pitch, max(1, v.width), max(1, v.height), v.density, v.offsetY,
            v.frame?.let { GroundFrame.Region(it) } ?: GroundFrame.Mercator(p.target.lat),
        )

    /** The steepest pitch allowed at [zoom]: flat at world scale, the full tilt from zoom 8. */
    fun maxPitch(zoom: Double): Double = when {
        zoom >= 8.0 -> MAX_PITCH
        zoom <= 3.0 -> 30.0
        else -> 30.0 + (MAX_PITCH - 30.0) * (zoom - 3.0) / 5.0
    }

    /** [p] inside the limits: zoom, pitch for its zoom, a bearing in 0..360 and a target on the Mercator square. */
    fun clamp(p: CameraPosition): CameraPosition {
        val zoom = p.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val lat = p.target.lat.coerceIn(-Mercator.MAX_LAT, Mercator.MAX_LAT)
        var lon = p.target.lon
        if (lon < -180.0 || lon > 180.0) lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val bearing = ((p.bearing % 360.0) + 360.0) % 360.0
        val pitch = p.pitch.coerceIn(0.0, maxPitch(zoom))
        return if (zoom == p.zoom && lat == p.target.lat && lon == p.target.lon && bearing == p.bearing && pitch == p.pitch) p
        else CameraPosition(LatLon(lat, lon), zoom, bearing, pitch)
    }

    /** The ground under pixel ([x], [y]), or null above the horizon. */
    fun groundAt(p: CameraPosition, v: Viewport, x: Float, y: Float): LatLon? = camera(p, v).unproject(x.toDouble(), y.toDouble())

    /**
     * The ground a gesture at pixel ([x], [y]) holds on to: what [groundAt] finds, but only where the engine draws ground,
     * nearer than its far plane. In the haze just under the horizon the ray meets the ground hundreds of kilometres
     * away, and holding on to that would throw the camera across the country.
     */
    private fun anchorAt(p: CameraPosition, v: Viewport, x: Float, y: Float): LatLon? {
        val c = camera(p, v)
        val out = DoubleArray(3)
        if (!c.unprojectLocal(x.toDouble(), y.toDouble(), out) || out[2] > c.far) return null
        return c.latLonOf(out[0], out[1])
    }

    /**
     * Moves the camera, keeping its zoom, bearing and pitch, so the ground point [anchor] lands under pixel ([x], [y]).
     * A pixel above the horizon leaves the camera as it is.
     */
    fun place(p: CameraPosition, v: Viewport, anchor: LatLon, x: Float, y: Float): CameraPosition {
        var cur = p
        // A rigid shift of the camera over the ground moves every pixel's ground point by the same amount; the scale of
        // a Mercator frame changes slightly with latitude, so two rounds settle it.
        repeat(2) {
            val c = camera(cur, v)
            val out = DoubleArray(3)
            if (!c.unprojectLocal(x.toDouble(), y.toDouble(), out)) return cur
            val ax = c.localX(anchor.lat, anchor.lon)
            val ay = c.localY(anchor.lat, anchor.lon)
            val t = c.latLonOf(ax - out[0], ay - out[1])
            cur = cur.copy(target = LatLon(t.lat.coerceIn(-Mercator.MAX_LAT, Mercator.MAX_LAT), t.lon))
        }
        return clamp(cur)
    }

    /**
     * One finger moved from ([x0], [y0]) to ([x1], [y1]): the ground under it follows. Above the horizon or in the haze
     * under it, where no ground is drawn to hold, the map moves by the finger's motion at the far plane's scale.
     */
    fun pan(p: CameraPosition, v: Viewport, x0: Float, y0: Float, x1: Float, y1: Float): CameraPosition {
        val anchor = anchorAt(p, v, x0, y0)
        if (anchor == null || anchorAt(p, v, x1, y1) == null) return panFlat(p, v, x1 - x0, y1 - y0, farScale(p, v))
        return place(p, v, anchor, x1, y1)
    }

    /** How much more ground a pixel spans across the view at the far plane than at the target. */
    private fun farScale(p: CameraPosition, v: Viewport): Double {
        val c = camera(p, v)
        return c.far / c.distance
    }

    /**
     * A pan by a screen delta where no ground is held: moves the target as if looking straight down, [scale] times the
     * target's metres per pixel.
     */
    private fun panFlat(p: CameraPosition, v: Viewport, dx: Float, dy: Float, scale: Double = 1.0): CameraPosition {
        val mpp = Mercator.metresPerPixel(p.target.lat, p.zoom) / v.density * scale
        val b = Math.toRadians(p.bearing)
        // Screen right is (cos b, sin b) in east/south metres, screen down is (-sin b, cos b).
        val east = -(dx * cos(b) - dy * sin(b)) * mpp
        val south = -(dx * sin(b) + dy * cos(b)) * mpp
        val unit = Mercator.metresPerUnit(p.target.lat)
        val tx = Mercator.x(p.target.lon) + east / unit
        val ty = (Mercator.y(p.target.lat) + south / unit).coerceIn(0.0, 1.0)
        return clamp(p.copy(target = LatLon(Mercator.lat(ty), Mercator.lon(tx))))
    }

    /** Zooms by [dz] levels keeping the ground under pixel ([x], [y]) in place. */
    fun zoomAround(p: CameraPosition, v: Viewport, x: Float, y: Float, dz: Double): CameraPosition {
        val anchor = anchorAt(p, v, x, y)
        val zoomed = clamp(p.copy(zoom = p.zoom + dz))
        return if (anchor == null) zoomed else place(zoomed, v, anchor, x, y)
    }

    /** Turns the map [degrees] clockwise on screen around pixel ([x], [y]): the bearing falls by as much. */
    fun rotateAround(p: CameraPosition, v: Viewport, x: Float, y: Float, degrees: Double): CameraPosition {
        val anchor = anchorAt(p, v, x, y)
        val turned = clamp(p.copy(bearing = p.bearing - degrees))
        return if (anchor == null) turned else place(turned, v, anchor, x, y)
    }

    /**
     * Two fingers moved: the ground under their old centre ([x0], [y0]) goes to the new centre ([x1], [y1]), scaled by
     * [scale] (the change of their span) and turned [rotation] degrees clockwise around it.
     */
    fun transform(
        p: CameraPosition, v: Viewport, x0: Float, y0: Float, x1: Float, y1: Float, scale: Double, rotation: Double,
    ): CameraPosition {
        val anchor = anchorAt(p, v, x0, y0)
        val dz = if (scale > 0.0) ln(scale) / LN2 else 0.0
        val changed = clamp(p.copy(zoom = p.zoom + dz, bearing = p.bearing - rotation))
        if (anchor == null || anchorAt(changed, v, x1, y1) == null) return panFlat(changed, v, x1 - x0, y1 - y0, farScale(changed, v))
        return place(changed, v, anchor, x1, y1)
    }

    /** Tilts by [degrees] (positive toward the horizon), within the pitch allowed at the camera's zoom. */
    fun tilt(p: CameraPosition, degrees: Double): CameraPosition = clamp(p.copy(pitch = (p.pitch + degrees).coerceAtLeast(0.0)))

    /**
     * The camera that shows [box] inside the view less [padding] pixels on each side (left, top, right, bottom), at
     * [p]'s bearing and pitch. Looking straight down the box is fitted exactly; pitched, its projection (a trapezoid)
     * is centred in the padded region and the zoom is the highest that keeps all four corners in.
     */
    fun fit(p: CameraPosition, v: Viewport, box: BBox, padding: FloatArray): CameraPosition {
        val availW = max(16.0, v.width - padding[0].toDouble() - padding[2])
        val availH = max(16.0, v.height - padding[1].toDouble() - padding[3])
        // The box's centre in Mercator, so the result is centred on screen whatever the latitude span.
        val x0 = Mercator.x(box.west); val x1 = Mercator.x(box.east)
        val y0 = Mercator.y(box.north); val y1 = Mercator.y(box.south)
        val centre = LatLon(Mercator.lat((y0 + y1) / 2), Mercator.lon((x0 + x1) / 2))
        // The box rotated into screen axes, in unit Mercator.
        val b = Math.toRadians(p.bearing)
        val w = abs((x1 - x0) * cos(b)) + abs((y1 - y0) * sin(b))
        val h = abs((x1 - x0) * sin(b)) + abs((y1 - y0) * cos(b))
        // Unit Mercator per physical pixel at zoom z is 1 / (256 · 2^z · density).
        val zx = if (w > 0) log2(availW / (w * 256.0 * v.density)) else MAX_ZOOM
        val zy = if (h > 0) log2(availH / (h * 256.0 * v.density)) else MAX_ZOOM
        val flat = min(min(zx, zy), MAX_ZOOM).coerceAtLeast(MIN_ZOOM)
        // The padded region's centre.
        val cx = padding[0] + availW / 2
        val cy = padding[1] + availH / 2
        if (p.pitch <= 0.5) return position(p, v, centre, flat, cx, cy)
        val corners = arrayOf(LatLon(box.north, box.west), LatLon(box.north, box.east), LatLon(box.south, box.east), LatLon(box.south, box.west))
        // Pitched: the far side shrinks, so the zoom may go higher than looking down, or must go lower when the far
        // corners reach for the horizon. Bisect on the zoom, centring the trapezoid at each try.
        var lo = (flat - 6.0).coerceAtLeast(MIN_ZOOM)
        var hi = min(flat + 2.0, MAX_ZOOM)
        var best = centred(p, v, centre, lo, cx, cy, corners)
        if (!inside(best, v, corners, padding)) return best
        repeat(18) {
            val mid = (lo + hi) / 2
            val c = centred(p, v, centre, mid, cx, cy, corners)
            if (inside(c, v, corners, padding)) { lo = mid; best = c } else hi = mid
        }
        return best
    }

    /** The camera at [zoom] whose projection of [corners] is centred on ([cx], [cy]). */
    private fun centred(p: CameraPosition, v: Viewport, centre: LatLon, zoom: Double, cx: Double, cy: Double, corners: Array<LatLon>): CameraPosition {
        var cur = position(p, v, centre, zoom, cx, cy)
        repeat(4) {
            val c = camera(cur, v)
            var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            for (q in corners) {
                val s = c.project(q) ?: return cur
                minX = min(minX, s.x.toDouble()); maxX = max(maxX, s.x.toDouble())
                minY = min(minY, s.y.toDouble()); maxY = max(maxY, s.y.toDouble())
            }
            val mx = (minX + maxX) / 2
            val my = (minY + maxY) / 2
            if (abs(mx - cx) < 0.5 && abs(my - cy) < 0.5) return cur
            // The ground under the trapezoid's centre goes to the padded centre.
            val g = c.unproject(mx, my) ?: return cur
            cur = place(cur, v, g, cx.toFloat(), cy.toFloat())
        }
        return cur
    }

    private fun inside(p: CameraPosition, v: Viewport, corners: Array<LatLon>, padding: FloatArray): Boolean {
        val c = camera(p, v)
        for (q in corners) {
            val s = c.project(q) ?: return false
            if (s.x < padding[0] - 1 || s.x > v.width - padding[2] + 1 || s.y < padding[1] - 1 || s.y > v.height - padding[3] + 1) return false
        }
        return true
    }

    private fun position(p: CameraPosition, v: Viewport, centre: LatLon, zoom: Double, cx: Double, cy: Double): CameraPosition {
        val at = clamp(p.copy(target = centre, zoom = zoom))
        val c = camera(at, v)
        // The target sits at the principal point; put the box's centre at the padded region's centre instead.
        return if (abs(cx - c.principalX) < 0.5 && abs(cy - c.principalY) < 0.5) at else place(at, v, centre, cx.toFloat(), cy.toFloat())
    }

    /** Ground metres per dp at the camera's target. */
    fun metresPerDp(p: CameraPosition): Double = Mercator.metresPerPixel(p.target.lat, p.zoom)

    /** The ground in view as a box, or null for an empty view. */
    fun visibleBounds(p: CameraPosition, v: Viewport): BBox? {
        if (v.isEmpty) return null
        return BBox.of(camera(p, v).groundPolygonLatLon())
    }

    /** The camera [t] of the way from [a] to [b]: target, zoom and pitch straight, the bearing the short way round. */
    fun lerp(a: CameraPosition, b: CameraPosition, t: Double): CameraPosition {
        if (t <= 0.0) return a
        if (t >= 1.0) return b
        val x = Mercator.x(a.target.lon) + (Mercator.x(b.target.lon) - Mercator.x(a.target.lon)) * t
        val y = Mercator.y(a.target.lat) + (Mercator.y(b.target.lat) - Mercator.y(a.target.lat)) * t
        return CameraPosition(
            LatLon(Mercator.lat(y), Mercator.lon(x)), a.zoom + (b.zoom - a.zoom) * t,
            a.bearing + turn(a.bearing, b.bearing) * t, a.pitch + (b.pitch - a.pitch) * t,
        )
    }

    /** The shortest signed turn from [from] to [to] degrees, in -180..180. */
    fun turn(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private fun log2(v: Double) = ln(v) / LN2
    private val LN2 = ln(2.0)
}
