package app.atlas.engine

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A perspective camera over Web Mercator.
 *
 * [zoom] is fractional, for 256-pixel tiles in density-independent pixels; [bearing] is degrees clockwise from north;
 * [pitch] is degrees from straight down (0) up to [MAX_PITCH]; [fov] is the vertical field of view in degrees.
 * The viewport is [width] × [height] physical pixels at [density] pixels per dp. The camera stands back from [target]
 * so that, looking straight down, the ground scale at the target equals the Web Mercator scale of [zoom].
 * [offsetY] moves the target down the screen by that many pixels (a principal point below the centre).
 *
 * The camera's world is local metres around the target: x east, y south, z up. Maths is in doubles; the per-tile
 * matrices it hands the renderer are floats.
 */
class Camera(
    val target: LatLon,
    val zoom: Double,
    bearing: Double = 0.0,
    pitch: Double = 0.0,
    val width: Int,
    val height: Int,
    val density: Float = 1f,
    val fov: Double = 36.0,
    val offsetY: Double = 0.0,
    /** How far the ground reaches, in camera distances, before the haze takes it. */
    val farFactor: Double = 6.0,
) {
    val bearing: Double = ((bearing % 360) + 360) % 360
    val pitch: Double = pitch.coerceIn(0.0, MAX_PITCH)

    /** The target in unit Mercator. */
    val targetX: Double = Mercator.x(target.lon)
    val targetY: Double = Mercator.y(target.lat)

    /** True metres per unit Mercator at the target's latitude: the scale of the camera's local frame. */
    val metresPerUnit: Double = Mercator.metresPerUnit(target.lat)

    /** Ground metres per physical pixel at the target, looking straight down. */
    val metresPerPixel: Double = Mercator.metresPerPixel(target.lat, zoom) / density

    /** Focal length in pixels. */
    val focal: Double = height / 2.0 / tan(fov * PI / 360)

    /** From the eye to the target, in metres. */
    val distance: Double = focal * metresPerPixel

    val principalX: Double = width / 2.0
    val principalY: Double = height / 2.0 + offsetY

    // Basis in the local frame (x east, y south, z up).
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
    val rz = 0.0

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

    /** The screen row where the ground reaches [far]: above it there is only haze and sky. */
    val farY: Double = if (sinP < 1e-9) Double.NEGATIVE_INFINITY else principalY - focal * (cosP - eyeZ / far) / sinP

    /** Local metres of a unit-Mercator point. */
    fun localX(unitX: Double): Double = (unitX - targetX) * metresPerUnit
    fun localY(unitY: Double): Double = (unitY - targetY) * metresPerUnit
    fun unitX(localX: Double): Double = targetX + localX / metresPerUnit
    fun unitY(localY: Double): Double = targetY + localY / metresPerUnit

    /** Depth along the view of a local point. */
    fun depth(x: Double, y: Double, z: Double): Double = (x - eyeX) * fx + (y - eyeY) * fy + (z - eyeZ) * fz

    /**
     * Projects local metres to the screen into [out] (x, y, depth). Returns false (and leaves [out] alone) when the point
     * is nearer than the near plane or behind the eye.
     */
    fun projectLocal(x: Double, y: Double, z: Double, out: DoubleArray): Boolean {
        val dx = x - eyeX
        val dy = y - eyeY
        val dz = z - eyeZ
        val cz = dx * fx + dy * fy + dz * fz
        if (cz < near) return false
        val cx = dx * rx + dy * ry + dz * rz
        val cy = dx * ux + dy * uy + dz * uz
        out[0] = principalX + focal * cx / cz
        out[1] = principalY - focal * cy / cz
        out[2] = cz
        return true
    }

    /** The screen position of a point [heightM] metres above the ground at [lat], [lon], or null behind the camera. */
    fun project(lat: Double, lon: Double, heightM: Double = 0.0): ScreenPoint? {
        val out = DoubleArray(3)
        if (!projectLocal(localX(Mercator.x(lon)), localY(Mercator.y(lat)), heightM, out)) return null
        return ScreenPoint(out[0].toFloat(), out[1].toFloat(), out[2].toFloat())
    }

    fun project(p: LatLon, heightM: Double = 0.0): ScreenPoint? = project(p.lat, p.lon, heightM)

    /**
     * Where the ray through screen pixel ([x], [y]) meets the ground at [groundZ], in local metres into [out] (x, y, depth),
     * or false above the horizon. Points beyond [far] are still returned.
     */
    fun unprojectLocal(x: Double, y: Double, out: DoubleArray, groundZ: Double = 0.0): Boolean {
        val a = (x - principalX) / focal
        val b = (principalY - y) / focal
        val dx = rx * a + ux * b + fx
        val dy = ry * a + uy * b + fy
        val dz = rz * a + uz * b + fz
        if (dz >= -1e-9) return false
        val t = (groundZ - eyeZ) / dz
        if (t <= 0) return false
        out[0] = eyeX + dx * t
        out[1] = eyeY + dy * t
        out[2] = t
        return true
    }

    /** The point on the ground under screen pixel ([x], [y]), or null above the horizon. */
    fun unproject(x: Double, y: Double): LatLon? {
        val out = DoubleArray(3)
        if (!unprojectLocal(x, y, out)) return null
        return LatLon(Mercator.lat(unitY(out[1])), Mercator.lon(unitX(out[0])))
    }

    /** Metres of ground per pixel across the screen at pixel ([x], [y]), or null above the horizon. */
    fun metresPerPixelAt(x: Double, y: Double): Double? {
        val out = DoubleArray(3)
        if (!unprojectLocal(x, y, out)) return null
        return out[2] / focal
    }

    /** Metres per pixel across the screen at a local point (depth over focal length). */
    fun metresPerPixelAtLocal(x: Double, y: Double, z: Double): Double = depth(x, y, z).coerceAtLeast(near) / focal

    /**
     * The ground the camera sees, as a convex polygon in unit Mercator (x, y pairs, screen bottom-left first, then
     * bottom-right, top-right, top-left): the frustum cut by the ground plane and capped at [far].
     */
    fun groundPolygon(): DoubleArray {
        val top = maxOf(0.0, farY, horizonY + 1.0).coerceAtMost(height.toDouble())
        val ys = doubleArrayOf(height.toDouble(), height.toDouble(), top, top)
        val xs = doubleArrayOf(0.0, width.toDouble(), width.toDouble(), 0.0)
        val out = DoubleArray(8)
        val p = DoubleArray(3)
        for (i in 0 until 4) {
            if (!unprojectLocal(xs[i], ys[i], p)) unprojectLocal(xs[i], (horizonY + 2.0).coerceAtMost(height.toDouble()), p)
            out[2 * i] = unitX(p[0])
            out[2 * i + 1] = unitY(p[1])
        }
        return out
    }

    /** The ground polygon as points on Earth. */
    fun groundPolygonLatLon(): List<LatLon> {
        val g = groundPolygon()
        return List(4) { LatLon(Mercator.lat(g[2 * it + 1]), Mercator.lon(g[2 * it])) }
    }

    /** Ground distance from the point below the eye to the target. */
    val groundDistance: Double get() = sqrt(eyeX * eyeX + eyeY * eyeY)

    /**
     * The 3×4 float matrix that takes tile-local metres of a scene (origin [originX], [originY] in unit Mercator,
     * [metresPerUnitTile] its own scale) to homogeneous screen coordinates: X/W, Y/W in pixels, W the depth.
     * Rows: X, Y, W; columns: x, y, z, 1.
     */
    fun tileMatrix(originX: Double, originY: Double, metresPerUnitTile: Double, out: FloatArray) {
        val k = metresPerUnit / metresPerUnitTile
        // Tile origin in camera-local metres, relative to the eye.
        val ox = (originX - targetX) * metresPerUnit - eyeX
        val oy = (originY - targetY) * metresPerUnit - eyeY
        val oz = -eyeZ
        // Camera rows: X = focal*r + px*f, Y = -focal*u + py*f, W = f.
        val xr0 = focal * rx + principalX * fx
        val xr1 = focal * ry + principalX * fy
        val xr2 = focal * rz + principalX * fz
        val yr0 = -focal * ux + principalY * fx
        val yr1 = -focal * uy + principalY * fy
        val yr2 = -focal * uz + principalY * fz
        out[0] = (xr0 * k).toFloat(); out[1] = (xr1 * k).toFloat(); out[2] = xr2.toFloat()
        out[3] = (xr0 * ox + xr1 * oy + xr2 * oz).toFloat()
        out[4] = (yr0 * k).toFloat(); out[5] = (yr1 * k).toFloat(); out[6] = yr2.toFloat()
        out[7] = (yr0 * ox + yr1 * oy + yr2 * oz).toFloat()
        out[8] = (fx * k).toFloat(); out[9] = (fy * k).toFloat(); out[10] = fz.toFloat()
        out[11] = (fx * ox + fy * oy + fz * oz).toFloat()
    }

    /** The eye in a scene's tile-local metres, into [out] (x, y, z). */
    fun eyeInTile(originX: Double, originY: Double, metresPerUnitTile: Double, out: FloatArray) {
        val k = metresPerUnitTile / metresPerUnit
        out[0] = ((eyeX - (originX - targetX) * metresPerUnit) * k).toFloat()
        out[1] = ((eyeY - (originY - targetY) * metresPerUnit) * k).toFloat()
        out[2] = eyeZ.toFloat()
    }

    /** A copy with some values changed. */
    fun copy(
        target: LatLon = this.target,
        zoom: Double = this.zoom,
        bearing: Double = this.bearing,
        pitch: Double = this.pitch,
        width: Int = this.width,
        height: Int = this.height,
        density: Float = this.density,
        fov: Double = this.fov,
        offsetY: Double = this.offsetY,
    ) = Camera(target, zoom, bearing, pitch, width, height, density, fov, offsetY, farFactor)

    override fun toString() =
        "Camera($target z=${"%.2f".format(zoom)} bearing=${"%.1f".format(bearing)} pitch=${"%.1f".format(pitch)} ${width}x$height@$density)"

    companion object {
        const val MAX_PITCH = 72.0
    }
}

/** A projected point: screen pixels and depth in metres. */
data class ScreenPoint(val x: Float, val y: Float, val depth: Float)
