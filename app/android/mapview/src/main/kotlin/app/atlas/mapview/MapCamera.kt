package app.atlas.mapview

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.mapview.svg.SvgCamera
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.RegionFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where the ground's local metres come from: x east and y south of a reference, for a point on Earth and back. The map
 * draws a region in the engine's own frame ([Region]), so overlays land exactly on what the engine drew; with no region
 * it is the Web Mercator square scaled to metres at the camera's latitude ([Mercator]).
 */
sealed interface GroundFrame {
    fun east(lat: Double, lon: Double): Double
    fun south(lat: Double, lon: Double): Double
    fun lat(east: Double, south: Double): Double
    fun lon(east: Double, south: Double): Double

    /** Local metres per true metre at [lat] (the frame's scale there). */
    fun scaleAt(lat: Double): Double

    /** Web Mercator, in metres of [refLat]'s scale. */
    data class Mercator(val refLat: Double) : GroundFrame {
        private val k = app.atlas.geo.Mercator.metresPerUnit(refLat)
        override fun east(lat: Double, lon: Double) = app.atlas.geo.Mercator.x(lon) * k
        override fun south(lat: Double, lon: Double) = app.atlas.geo.Mercator.y(lat) * k
        override fun lat(east: Double, south: Double) = app.atlas.geo.Mercator.lat(south / k)
        override fun lon(east: Double, south: Double) = app.atlas.geo.Mercator.lon(east / k)
        override fun scaleAt(lat: Double) = k / app.atlas.geo.Mercator.metresPerUnit(lat)
    }

    /** The engine's frame of a region (`render/amsterdam.py:22 XZ`): x east, z south of its origin. */
    data class Region(val frame: RegionFrame) : GroundFrame {
        override fun east(lat: Double, lon: Double) = frame.x(lat, lon)
        override fun south(lat: Double, lon: Double) = frame.z(lat, lon)
        override fun lat(east: Double, south: Double) = frame.lat(east, south)
        override fun lon(east: Double, south: Double) = frame.lon(east, south)
        override fun scaleAt(lat: Double) = 1.0
    }
}

/**
 * The map's perspective camera, which is the engine's (`render/city.py` Camera): a 36° vertical field of view, the eye
 * [distance] metres from the target so that, looking straight down, the ground scale at the target is the Web Mercator
 * scale of [zoom] (256-pixel tiles in dp), [bearing] clockwise from north, [pitch] from straight down. The view is
 * [width] × [height] physical pixels at [density] per dp, the target [offsetY] pixels below the centre.
 *
 * Its world is local metres around the target in [frame]: x east, y south, z up.
 */
class MapCamera(
    val target: LatLon,
    val zoom: Double,
    bearing: Double = 0.0,
    pitch: Double = 0.0,
    val width: Int,
    val height: Int,
    val density: Float = 1f,
    val offsetY: Double = 0.0,
    val frame: GroundFrame = GroundFrame.Mercator(target.lat),
    /** How far the ground reaches, in camera distances, before the haze takes it. */
    val farFactor: Double = 6.0,
) {
    val bearing: Double = ((bearing % 360) + 360) % 360
    val pitch: Double = pitch.coerceIn(0.0, MAX_PITCH)
    val fov: Double = LiveCamera.FOV

    /** The target in the frame. */
    val targetEast: Double = frame.east(target.lat, target.lon)
    val targetSouth: Double = frame.south(target.lat, target.lon)

    /** Ground metres per physical pixel at the target, looking straight down. */
    val metresPerPixel: Double = Mercator.metresPerPixel(target.lat, zoom) / density

    /** Focal length in pixels. */
    val focal: Double = height / 2.0 / tan(fov * PI / 360)

    /** From the eye to the target, in metres: the engine's `dist`. */
    val distance: Double = focal * metresPerPixel

    val principalX: Double = width / 2.0
    val principalY: Double = height / 2.0 + offsetY

    private val sinP = sin(this.pitch * PI / 180)
    private val cosP = cos(this.pitch * PI / 180)
    private val sinB = sin(this.bearing * PI / 180)
    private val cosB = cos(this.bearing * PI / 180)

    /** View direction. */
    val fx = sinP * sinB
    val fy = -sinP * cosB
    val fz = -cosP

    /** Screen right, always level. */
    val rx = cosB
    val ry = sinB

    /** Screen up. */
    val ux = cosP * sinB
    val uy = -cosP * cosB
    val uz = sinP

    /** The eye in local metres. */
    val eyeX = -fx * distance
    val eyeY = -fy * distance
    val eyeZ = -fz * distance

    /** The near clipping plane, metres of depth. */
    val near: Double = (distance * 0.02).coerceIn(0.5, 20.0)

    /** The farthest ground depth drawn, metres. */
    val far: Double = distance * farFactor

    /** The screen row of the horizon, or negative infinity looking straight down. */
    val horizonY: Double = if (sinP < 1e-9) Double.NEGATIVE_INFINITY else principalY - focal * cosP / sinP

    /** The screen row where the ground reaches [far]. */
    val farY: Double = if (sinP < 1e-9) Double.NEGATIVE_INFINITY else principalY - focal * (cosP - eyeZ / far) / sinP

    /** Local metres of a point on Earth. */
    fun localX(lat: Double, lon: Double): Double = frame.east(lat, lon) - targetEast
    fun localY(lat: Double, lon: Double): Double = frame.south(lat, lon) - targetSouth

    /** The point on Earth of local metres. */
    fun latLonOf(x: Double, y: Double): LatLon = LatLon(frame.lat(targetEast + x, targetSouth + y), frame.lon(targetEast + x, targetSouth + y))

    /** Local metres per true metre near [lat]. */
    fun scaleAt(lat: Double): Double = frame.scaleAt(lat)

    /** Depth along the view of a local point. */
    fun depth(x: Double, y: Double, z: Double): Double = (x - eyeX) * fx + (y - eyeY) * fy + (z - eyeZ) * fz

    /** Projects local metres into [out] (x, y, depth); false when nearer than the near plane. */
    fun projectLocal(x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
        val dx = x - eyeX
        val dy = y - eyeY
        val dz = z - eyeZ
        val cz = dx * fx + dy * fy + dz * fz
        if (cz < near) return false
        val cx = dx * rx + dy * ry
        val cy = dx * ux + dy * uy + dz * uz
        out[0] = principalX + focal * cx / cz
        out[1] = principalY - focal * cy / cz
        out[2] = cz
        return true
    }

    /** The screen position of a point [heightM] above the ground, or null behind the camera. */
    fun project(p: LatLon, heightM: Double = 0.0): ScreenPoint? {
        val out = DoubleArray(3)
        if (!projectLocal(localX(p.lat, p.lon), localY(p.lat, p.lon), heightM, out)) return null
        return ScreenPoint(out[0].toFloat(), out[1].toFloat(), out[2].toFloat())
    }

    /** Where the ray through pixel ([x], [y]) meets the ground, in local metres into [out] (x, y, depth); false above the horizon. */
    fun unprojectLocal(x: Double, y: Double, out: DoubleArray, groundZ: Double = 0.0): Boolean {
        val a = (x - principalX) / focal
        val b = (principalY - y) / focal
        val dx = rx * a + ux * b + fx
        val dy = ry * a + uy * b + fy
        val dz = uz * b + fz
        if (dz >= -1e-9) return false
        val t = (groundZ - eyeZ) / dz
        if (t <= 0) return false
        out[0] = eyeX + dx * t
        out[1] = eyeY + dy * t
        out[2] = t
        return true
    }

    /** The point on the ground under pixel ([x], [y]), or null above the horizon. */
    fun unproject(x: Double, y: Double): LatLon? {
        val out = DoubleArray(3)
        if (!unprojectLocal(x, y, out)) return null
        return latLonOf(out[0], out[1])
    }

    /** The ground the camera sees, out to [far]: bottom-left, bottom-right, top-right, top-left. */
    fun groundPolygonLatLon(): List<LatLon> {
        val top = maxOf(0.0, farY, horizonY + 1.0).coerceAtMost(height.toDouble())
        val ys = doubleArrayOf(height.toDouble(), height.toDouble(), top, top)
        val xs = doubleArrayOf(0.0, width.toDouble(), width.toDouble(), 0.0)
        val p = DoubleArray(3)
        return List(4) { i ->
            if (!unprojectLocal(xs[i], ys[i], p)) unprojectLocal(xs[i], (horizonY + 2.0).coerceAtMost(height.toDouble()), p)
            latLonOf(p[0], p[1])
        }
    }

    /** Ground distance from the point below the eye to the target. */
    val groundDistance: Double get() = sqrt(eyeX * eyeX + eyeY * eyeY)

    /**
     * The engine's camera for this view, in dp (CSS pixels of the engine's SVG): its world is the region frame's
     * (x east, y up, z south), so it only exists in a [GroundFrame.Region].
     */
    fun svgCamera(): SvgCamera? {
        if (frame !is GroundFrame.Region) return null
        val d = density.toDouble()
        return SvgCamera(
            targetEast, 0.0, targetSouth, bearing, LiveCamera.enginePitch(pitch), distance, fov,
            width / d, height / d, cx = principalX / d, cy = principalY / d,
        )
    }

    override fun toString() = "MapCamera($target z=${"%.2f".format(zoom)} bearing=${"%.1f".format(bearing)} pitch=${"%.1f".format(pitch)} ${width}x$height@$density)"

    companion object {
        const val MAX_PITCH = 72.0
    }
}

/** A projected point: screen pixels and depth in metres. */
data class ScreenPoint(val x: Float, val y: Float, val depth: Float)
