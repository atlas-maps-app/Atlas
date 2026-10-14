package app.atlas.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ribbons: a polyline widened into triangles, in metres, with round joins and caps. One quad per segment; a fan fills
 * the outside of each bend and each capped end. Overlaps inside bends are harmless for opaque fills.
 */
object Ribbon {
    /** Fan segments per half turn. */
    private const val ARC_STEPS = 6

    /**
     * Adds a ribbon of half-width [hw] along the [n] points in [xy] (x, y pairs) to the open face of [mb]. Heights come
     * from [z] (one per point) or are all [z0]. [capStart] and [capEnd] round the ends.
     */
    fun add(
        mb: MeshBuilder, xy: FloatArray, n: Int, hw: Float, colour: Int,
        z: FloatArray? = null, z0: Float = 0f, capStart: Boolean = true, capEnd: Boolean = true, roundJoins: Boolean = true,
    ) {
        if (n < 2 || hw <= 0f) return
        var prevDx = 0f
        var prevDy = 0f
        var havePrev = false
        var firstDx = 0f
        var firstDy = 0f
        var lastDx = 0f
        var lastDy = 0f
        var firstI = -1
        var lastI = -1
        for (i in 0 until n - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]
            val bx = xy[2 * i + 2]; val by = xy[2 * i + 3]
            val dx = bx - ax
            val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-4f) continue
            val ux = dx / len
            val uy = dy / len
            val nx = -uy * hw
            val ny = ux * hw
            val za = z?.get(i) ?: z0
            val zb = z?.get(i + 1) ?: z0
            val v0 = mb.v(ax + nx, ay + ny, za, colour)
            val v1 = mb.v(ax - nx, ay - ny, za, colour)
            val v2 = mb.v(bx - nx, by - ny, zb, colour)
            val v3 = mb.v(bx + nx, by + ny, zb, colour)
            mb.quad(v0, v1, v2, v3)
            if (havePrev) join(mb, ax, ay, za, prevDx, prevDy, ux, uy, hw, colour, roundJoins)
            if (firstI < 0) { firstI = i; firstDx = ux; firstDy = uy }
            lastI = i + 1
            lastDx = ux; lastDy = uy
            prevDx = ux; prevDy = uy
            havePrev = true
        }
        if (firstI < 0) return
        if (capStart) cap(mb, xy[2 * firstI], xy[2 * firstI + 1], z?.get(firstI) ?: z0, -firstDx, -firstDy, hw, colour)
        if (capEnd) cap(mb, xy[2 * lastI], xy[2 * lastI + 1], z?.get(lastI) ?: z0, lastDx, lastDy, hw, colour)
    }

    /** Fills the outside of the bend at (x, y) between directions (ax, ay) and (bx, by). */
    private fun join(mb: MeshBuilder, x: Float, y: Float, z: Float, ax: Float, ay: Float, bx: Float, by: Float, hw: Float, colour: Int, round: Boolean) {
        val cross = ax * by - ay * bx
        val dot = ax * bx + ay * by
        if (abs(cross) < 1e-3f && dot > 0f) return
        // Turning toward the left normal leaves the gap on the right, and the other way round.
        val side = if (cross > 0f) -1f else 1f
        val n0x = -ay * hw * side; val n0y = ax * hw * side
        val n1x = -by * hw * side; val n1y = bx * hw * side
        val c = mb.v(x, y, z, colour)
        if (!round) {
            mb.tri(c, mb.v(x + n0x, y + n0y, z, colour), mb.v(x + n1x, y + n1y, z, colour))
            return
        }
        val a0 = atan2(n0y, n0x)
        var sweep = atan2(n1y, n1x) - a0
        while (sweep > PI) sweep -= (2 * PI).toFloat()
        while (sweep < -PI) sweep += (2 * PI).toFloat()
        val steps = maxOf(1, ceil(abs(sweep) / (PI / ARC_STEPS)).toInt())
        var prev = mb.v(x + n0x, y + n0y, z, colour)
        for (k in 1..steps) {
            val a = a0 + sweep * k / steps
            val p = mb.v(x + hw * cos(a), y + hw * sin(a), z, colour)
            mb.tri(c, prev, p)
            prev = p
        }
    }

    /** A half disc at (x, y) bulging toward (dx, dy). */
    private fun cap(mb: MeshBuilder, x: Float, y: Float, z: Float, dx: Float, dy: Float, hw: Float, colour: Int) {
        val c = mb.v(x, y, z, colour)
        val a0 = atan2(dy, dx) - (PI / 2).toFloat()
        var prev = mb.v(x + hw * cos(a0), y + hw * sin(a0), z, colour)
        for (k in 1..ARC_STEPS) {
            val a = a0 + (PI * k / ARC_STEPS).toFloat()
            val p = mb.v(x + hw * cos(a), y + hw * sin(a), z, colour)
            mb.tri(c, prev, p)
            prev = p
        }
    }

    /**
     * Small arrowheads along a line every [spacing] metres pointing the way of travel ([reverse] for `oneway=-1`),
     * [size] metres long, added to the open face at height [z].
     */
    fun arrows(mb: MeshBuilder, xy: FloatArray, n: Int, spacing: Float, size: Float, colour: Int, z: Float, reverse: Boolean) {
        var carry = spacing * 0.5f
        for (i in 0 until n - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]
            val bx = xy[2 * i + 2]; val by = xy[2 * i + 3]
            val dx = bx - ax; val dy = by - ay
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-4f) continue
            var ux = dx / len; var uy = dy / len
            if (reverse) { ux = -ux; uy = -uy }
            var s = carry
            while (s <= len) {
                val px = ax + dx / len * s
                val py = ay + dy / len * s
                val tipX = px + ux * size * 0.5f; val tipY = py + uy * size * 0.5f
                val bX = px - ux * size * 0.5f; val bY = py - uy * size * 0.5f
                val w = size * 0.32f
                val t = mb.v(tipX, tipY, z, colour)
                val l = mb.v(bX - uy * w, bY + ux * w, z, colour)
                val m = mb.v(px - ux * size * 0.18f, py - uy * size * 0.18f, z, colour)
                val r = mb.v(bX + uy * w, bY - ux * w, z, colour)
                mb.tri(t, l, m)
                mb.tri(t, m, r)
                s += spacing
            }
            carry = s - len
        }
    }

    /** Length of a polyline. */
    fun length(xy: FloatArray, n: Int): Float {
        var s = 0f
        for (i in 0 until n - 1) {
            val dx = xy[2 * i + 2] - xy[2 * i]; val dy = xy[2 * i + 3] - xy[2 * i + 1]
            s += sqrt(dx * dx + dy * dy)
        }
        return s
    }
}
