package app.atlas.mapview.svg

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The best 2D stand-in for re-rendering a frame under another camera: the frame's ground (the plane y = 0) moved by
 * the homography that the ground undergoes between the two cameras, and its sky (everything above the horizon, seen
 * as directions at infinity) moved by the rotation-only homography. Both are exact for what they stand for; buildings
 * and trees lean a little until the next exact frame arrives.
 *
 * Matrices are 3 × 3, row-major, mapping the frame's screen (CSS pixels of [from]) to the screen of [to]. The clip
 * polygons are in the frame's screen coordinates and keep each part where it is in front of the new camera.
 */
class SvgWarp private constructor(
    /** The ground homography, or null when the ground cannot be mapped (it is behind the new camera). */
    val ground: DoubleArray?,
    /** Where the ground part of the frame is, as x, y pairs. */
    val groundClip: DoubleArray,
    /** The sky homography, or null when the view turned too far for it. */
    val sky: DoubleArray?,
    /** Where the sky part of the frame is, as x, y pairs. */
    val skyClip: DoubleArray,
) {
    companion object {
        /** The warp that shows a frame made for [from] as [to] would see it. */
        fun between(from: SvgCamera, to: SvgCamera): SvgWarp {
            val w = from.width
            val h = from.height
            val horizon = from.horizonY()
            val groundTop = horizon.coerceIn(0.0, h)
            val ground = groundHomography(from, to, groundTop)
            val sky = skyHomography(from, to)
            val groundRect = doubleArrayOf(0.0, groundTop, w, groundTop, w, h, 0.0, h)
            val skyRect = doubleArrayOf(0.0, 0.0, w, 0.0, w, groundTop, 0.0, groundTop)
            return SvgWarp(
                ground,
                if (ground == null || groundTop >= h) DoubleArray(0) else inFront(groundRect, ground),
                sky,
                if (sky == null || groundTop <= 0.0) DoubleArray(0) else inFront(skyRect, sky),
            )
        }

        private fun groundHomography(from: SvgCamera, to: SvgCamera, groundTop: Double): DoubleArray? {
            val w = from.width
            val h = from.height
            // Four ground points spread over the ground both cameras see: the lowest row of the frame whose ends are
            // in front of the new camera, and a row a little below the horizon.
            fun ahead(y: Double): Boolean = listOf(0.0, w).all { x ->
                val g = from.groundAt(x, y) ?: return@all false
                to.cam(g[0], g[1], g[2])[2] > to.near
            }
            var bottom = h
            val step = max(1.0, (h - groundTop) / 64)
            while (bottom > groundTop && !ahead(bottom)) bottom -= step
            var top = groundTop + max(8.0, 0.15 * (h - groundTop))
            if (top >= bottom - 4) top = (groundTop + bottom) / 2
            if (bottom - top < 2 || !ahead(top)) return null
            val src = doubleArrayOf(0.0, bottom, w, bottom, w, top, 0.0, top)
            val dst = DoubleArray(8)
            for (k in 0 until 4) {
                val g = from.groundAt(src[2 * k], src[2 * k + 1]) ?: return null
                val s = to.pc(to.cam(g[0], g[1], g[2]))
                dst[2 * k] = s[0]
                dst[2 * k + 1] = s[1]
            }
            return Homography.fromPoints(src, dst)
        }

        private fun skyHomography(from: SvgCamera, to: SvgCamera): DoubleArray? {
            val w = from.width
            val h = from.height
            val src = doubleArrayOf(0.0, 0.0, w, 0.0, w, h, 0.0, h)
            val dst = DoubleArray(8)
            for (k in 0 until 4) {
                val s = to.projDirection(from.ray(src[2 * k], src[2 * k + 1])) ?: return null
                dst[2 * k] = s[0]
                dst[2 * k + 1] = s[1]
            }
            return Homography.fromPoints(src, dst)
        }

        /** The part of polygon [poly] where [m]'s w is positive (in front of the new camera), by Sutherland–Hodgman. */
        fun inFront(poly: DoubleArray, m: DoubleArray, eps: Double = 1e-6): DoubleArray {
            val n = poly.size / 2
            val out = ArrayList<Double>()
            fun wOf(i: Int) = m[6] * poly[2 * i] + m[7] * poly[2 * i + 1] + m[8]
            for (i in 0 until n) {
                val j = (i + 1) % n
                val wi = wOf(i) - eps
                val wj = wOf(j) - eps
                if (wi > 0) {
                    out.add(poly[2 * i])
                    out.add(poly[2 * i + 1])
                }
                if ((wi > 0) != (wj > 0)) {
                    val t = wi / (wi - wj)
                    out.add(poly[2 * i] + (poly[2 * j] - poly[2 * i]) * t)
                    out.add(poly[2 * i + 1] + (poly[2 * j + 1] - poly[2 * i + 1]) * t)
                }
            }
            return out.toDoubleArray()
        }
    }
}

/** 3 × 3 projective transforms, row-major, in plain Kotlin. */
object Homography {
    /**
     * The homography taking the four points [src] (x, y pairs) to [dst], or null when they are degenerate. It is scaled
     * so that w is positive at the first point.
     */
    fun fromPoints(src: DoubleArray, dst: DoubleArray): DoubleArray? {
        val a = Array(8) { DoubleArray(9) }
        for (k in 0 until 4) {
            val x = src[2 * k]
            val y = src[2 * k + 1]
            val u = dst[2 * k]
            val v = dst[2 * k + 1]
            a[2 * k] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            a[2 * k + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        val h = solve(a) ?: return null
        // Scale so that w is positive at the given points: points in front map with w > 0.
        val sign = if (h[6] * src[0] + h[7] * src[1] + 1.0 < 0) -1.0 else 1.0
        return doubleArrayOf(h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], 1.0).also { m -> for (i in m.indices) m[i] *= sign }
    }

    /** Solves the 8 × 8 system in the first eight columns of [a] against its ninth, by Gaussian elimination with pivoting. */
    private fun solve(a: Array<DoubleArray>): DoubleArray? {
        val n = 8
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            if (abs(a[pivot][col]) < 1e-12) return null
            val t = a[pivot]
            a[pivot] = a[col]
            a[col] = t
            for (row in 0 until n) {
                if (row == col) continue
                val k = a[row][col] / a[col][col]
                if (k == 0.0) continue
                for (c in col until n + 1) a[row][c] -= k * a[col][c]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    /** [m] applied to [x], [y], or null when the point goes to infinity or behind. */
    fun map(m: DoubleArray, x: Double, y: Double): DoubleArray? {
        val w = m[6] * x + m[7] * y + m[8]
        if (w <= 1e-12) return null
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** a · b (b applies first). */
    fun multiply(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
    }

    /** The inverse of [m], or null when it is singular. */
    fun invert(m: DoubleArray): DoubleArray? {
        val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
        if (abs(det) < 1e-15) return null
        val d = 1 / det
        return doubleArrayOf(
            (m[4] * m[8] - m[5] * m[7]) * d, (m[2] * m[7] - m[1] * m[8]) * d, (m[1] * m[5] - m[2] * m[4]) * d,
            (m[5] * m[6] - m[3] * m[8]) * d, (m[0] * m[8] - m[2] * m[6]) * d, (m[2] * m[3] - m[0] * m[5]) * d,
            (m[3] * m[7] - m[4] * m[6]) * d, (m[1] * m[6] - m[0] * m[7]) * d, (m[0] * m[4] - m[1] * m[3]) * d,
        )
    }

    /** The bounding box of polygon [poly] (x, y pairs) as left, top, right, bottom. */
    fun bounds(poly: DoubleArray): DoubleArray {
        var l = Double.POSITIVE_INFINITY
        var t = Double.POSITIVE_INFINITY
        var r = Double.NEGATIVE_INFINITY
        var b = Double.NEGATIVE_INFINITY
        for (i in 0 until poly.size / 2) {
            l = min(l, poly[2 * i])
            t = min(t, poly[2 * i + 1])
            r = max(r, poly[2 * i])
            b = max(b, poly[2 * i + 1])
        }
        return doubleArrayOf(l, t, r, b)
    }
}
