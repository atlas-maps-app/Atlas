package app.atlas.mapview

import android.graphics.Path
import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Circles on the ground as screen paths: a ring of points in metres around the centre, cut at the near plane
 * (Sutherland–Hodgman against one half-plane, which keeps the polygon convex) and projected. Scratch is reused.
 */
class AreaPaths {
    private var xs = DoubleArray(SEGMENTS * 2)
    private var ys = DoubleArray(SEGMENTS * 2)
    private var cx = DoubleArray(SEGMENTS * 2)
    private var cy = DoubleArray(SEGMENTS * 2)

    /** The circle of [radiusM] metres around [centre] as seen by [g], into [path] (reset first). Its radius on screen at
     * the centre, in pixels, is returned; 0 when nothing of it shows. */
    fun circle(g: GroundView, centre: LatLon, radiusM: Double, path: Path): Float {
        path.rewind()
        // Metres at the centre's own latitude, in the camera's frame (whose scale is the target's).
        val r = radiusM / Mercator.metresPerUnit(centre.lat) * g.metresPerUnit
        val ox = g.localX(centre)
        val oy = g.localY(centre)
        for (k in 0 until SEGMENTS) {
            xs[k] = ox + r * COS[k]
            ys[k] = oy + r * SIN[k]
        }
        val n = clip(g, SEGMENTS)
        if (n < 3) return 0f
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in 0 until n) {
            val d = g.depth(cx[k], cy[k])
            val sx = g.screenX(cx[k], cy[k], d)
            val sy = g.screenY(cx[k], cy[k], d)
            if (k == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
            if (sx < minX) minX = sx
            if (sx > maxX) maxX = sx
            if (sy < minY) minY = sy
            if (sy > maxY) maxY = sy
        }
        path.close()
        if (!g.onScreen(minX, minY, maxX, maxY)) {
            path.rewind()
            return 0f
        }
        val dc = g.depth(ox, oy)
        return if (dc > g.nearClip) (r * g.pixelsPerMetre(dc)).toFloat() else (maxX - minX) / 2
    }

    /** Keeps the part of polygon (xs, ys) in front of the near clip, into (cx, cy); returns its size. */
    private fun clip(g: GroundView, n: Int): Int {
        var m = 0
        val lim = g.nearClip
        for (i in 0 until n) {
            val j = (i + 1) % n
            val di = g.depth(xs[i], ys[i])
            val dj = g.depth(xs[j], ys[j])
            val inI = di >= lim
            val inJ = dj >= lim
            if (inI) { cx[m] = xs[i]; cy[m] = ys[i]; m++ }
            if (inI != inJ) {
                val t = (lim - di) / (dj - di)
                cx[m] = xs[i] + (xs[j] - xs[i]) * t
                cy[m] = ys[i] + (ys[j] - ys[i]) * t
                m++
            }
        }
        return m
    }

    private companion object {
        const val SEGMENTS = 72
        val COS = DoubleArray(SEGMENTS) { cos(2 * PI * it / SEGMENTS) }
        val SIN = DoubleArray(SEGMENTS) { sin(2 * PI * it / SEGMENTS) }
    }
}
