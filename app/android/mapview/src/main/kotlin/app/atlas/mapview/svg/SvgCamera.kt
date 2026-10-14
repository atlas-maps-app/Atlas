package app.atlas.mapview.svg

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The engine's perspective camera, as `render/city.py`'s `Camera` defines it: world x east, y up, z south (metres),
 * a target point, yaw and pitch in degrees, a distance from the target, a vertical field of view, and a screen of
 * [width] × [height] CSS pixels whose principal point is [cx], [cy]. Plain Kotlin and double precision, so a frame's
 * camera here projects exactly as the render that made it.
 */
class SvgCamera(
    val targetX: Double,
    val targetY: Double,
    val targetZ: Double,
    val yaw: Double,
    val pitch: Double,
    val dist: Double,
    val fov: Double,
    val width: Double,
    val height: Double,
    cx: Double? = null,
    cy: Double? = null,
    val near: Double = 0.6,
) {
    /** The camera's position. */
    val c = DoubleArray(3)

    /** Forward, right and up unit vectors. */
    val f = DoubleArray(3)
    val r = DoubleArray(3)
    val u = DoubleArray(3)

    /** Pixels per unit of tangent: (H/2) / tan(fov/2). */
    val focal: Double = (height / 2) / tan(Math.toRadians(fov) / 2)
    val cx: Double = cx ?: (width / 2)
    val cy: Double = cy ?: (height / 2)

    init {
        // render/city.py:21 Camera.__init__
        val yr = Math.toRadians(yaw)
        val pr = Math.toRadians(pitch)
        val d = doubleArrayOf(sin(yr) * cos(pr), -sin(pr), -cos(yr) * cos(pr))
        c[0] = targetX - d[0] * dist
        c[1] = targetY - d[1] * dist
        c[2] = targetZ - d[2] * dist
        norm(d).copyInto(f)
        norm(doubleArrayOf(-f[2], 0.0, f[0])).copyInto(r)
        cross(r, f).copyInto(u)
    }

    /** Camera coordinates (right, up, forward) of world point [x], [y], [z]. render/city.py:33 cam */
    fun cam(x: Double, y: Double, z: Double, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val dx = x - c[0]
        val dy = y - c[1]
        val dz = z - c[2]
        out[0] = dx * r[0] + dy * r[1] + dz * r[2]
        out[1] = dx * u[0] + dy * u[1] + dz * u[2]
        out[2] = dx * f[0] + dy * f[1] + dz * f[2]
        return out
    }

    /** The screen point of camera coordinates [k]. render/city.py:36 pc */
    fun pc(k: DoubleArray, out: DoubleArray = DoubleArray(2)): DoubleArray {
        out[0] = cx + focal * k[0] / k[2]
        out[1] = cy - focal * k[1] / k[2]
        return out
    }

    /** The screen point of a world point, or null when it is nearer than [near]. render/city.py:38 proj */
    fun proj(x: Double, y: Double, z: Double): DoubleArray? {
        val k = cam(x, y, z)
        if (k[2] < near) return null
        return pc(k)
    }

    /** The depth of a world point along the view. render/city.py:43 depth */
    fun depth(x: Double, y: Double, z: Double): Double = cam(x, y, z)[2]

    /** The depth at which screen row [sy] meets the plane y = [gy], or null above the horizon. render/city.py:61 ground_depth */
    fun groundDepth(sy: Double, gy: Double = 0.0): Double? {
        val b = (cy - sy) / focal
        val den = f[1] + b * u[1]
        if (den >= -1e-6) return null
        return -(c[1] - gy) / den
    }

    /** The screen row of the horizon. render/city.py:67 horizon_y */
    fun horizonY(): Double {
        val b = -f[1] / u[1]
        return cy - b * focal
    }

    /** The world direction (not normalised) seen at screen point [sx], [sy]. */
    fun ray(sx: Double, sy: Double, out: DoubleArray = DoubleArray(3)): DoubleArray {
        val a = (sx - cx) / focal
        val b = (cy - sy) / focal
        for (i in 0 until 3) out[i] = f[i] + a * r[i] + b * u[i]
        return out
    }

    /** The point of the plane y = [gy] seen at screen point [sx], [sy], or null when the ray does not meet it ahead. */
    fun groundAt(sx: Double, sy: Double, gy: Double = 0.0): DoubleArray? {
        val d = ray(sx, sy)
        if (d[1] >= -1e-9) return null
        val t = (gy - c[1]) / d[1]
        if (t <= 0) return null
        return doubleArrayOf(c[0] + d[0] * t, gy, c[2] + d[2] * t)
    }

    /** The screen point of the world direction [d] (a point at infinity), or null when it points behind the camera. */
    fun projDirection(d: DoubleArray): DoubleArray? {
        val k2 = d[0] * f[0] + d[1] * f[1] + d[2] * f[2]
        if (k2 <= 1e-9) return null
        val k0 = d[0] * r[0] + d[1] * r[1] + d[2] * r[2]
        val k1 = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
        return doubleArrayOf(cx + focal * k0 / k2, cy - focal * k1 / k2)
    }

    /** A copy with some parameters changed. */
    fun copy(
        targetX: Double = this.targetX,
        targetY: Double = this.targetY,
        targetZ: Double = this.targetZ,
        yaw: Double = this.yaw,
        pitch: Double = this.pitch,
        dist: Double = this.dist,
        fov: Double = this.fov,
        width: Double = this.width,
        height: Double = this.height,
        cx: Double = this.cx,
        cy: Double = this.cy,
        near: Double = this.near,
    ) = SvgCamera(targetX, targetY, targetZ, yaw, pitch, dist, fov, width, height, cx, cy, near)

    private companion object {
        fun norm(a: DoubleArray): DoubleArray {
            val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
            return doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
        }

        fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        )
    }
}
