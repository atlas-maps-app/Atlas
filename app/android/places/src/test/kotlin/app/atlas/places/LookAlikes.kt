package app.atlas.places

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tells which symbols read alike where Atlas draws them smallest: a map pin's symbol is 14 px, a row's 22 (the owner,
 * 6 November: "Some of these icons arent distinct enough. For example waterpark could be playground too"). Each symbol
 * is drawn white at [SIDE] px, softened a little as the eye takes it in at that size, and two compared twice: how much
 * of their ink lies on each other's (the overlap over the union), and how far their outlines run the same way. Both
 * must agree: two blobs of a size overlap a lot, but a heart's outline is not a camera's. Each takes the best of a
 * pixel's slack either way. It sees shape, not meaning: two hands drawing one slide differ to it, so the sheets are
 * still for the eye.
 */
internal object LookAlikes {
    /** The side symbols are compared at, in pixels: between a pin's 14 and a row's 22. */
    const val SIDE = 20

    /**
     * The score from which two symbols read alike: a bus stop's bus and a trolleybus stop's score 0.85; the water park's
     * filled slide and the playground's drawn one 0.4, which only the eye sees; a croissant and a bed 0.3.
     */
    const val ALIKE = 0.70f

    private const val PAD = 2
    private const val N = SIDE + 2 * PAD
    private const val SS = 4

    /** What the eye takes in of a symbol at [SIDE] px: its [ink], 0 to 1 a pixel, and its [outline]s' run. */
    class Look internal constructor(internal val ink: FloatArray, internal val outline: FloatArray)

    fun look(icon: Icon): Look {
        val ink = gauss(ink(icon), 0.6f)
        return Look(ink, outline(ink))
    }

    /** How alike [a] and [b] read, from 0 to 1: the overlap of their ink and the agreement of their outlines, together. */
    fun score(a: Look, b: Look): Float = sqrt(overlap(a.ink, b.ink) * max(0f, along(a.outline, b.outline)))

    /** [icon] drawn white as the sheets draw it, without its shadow, at [SIDE] px with room round it: N × N, 0 to 1. */
    private fun ink(icon: Icon): FloatArray {
        val big = N * SS
        val img = BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        Java2DIcons.draw(g, icon.copy(shadows = emptyList()), (PAD * SS).toFloat(), (PAD * SS).toFloat(), (SIDE * SS).toFloat(), Color.WHITE)
        g.dispose()
        val out = FloatArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            var s = 0
            for (j in 0 until SS) for (i in 0 until SS) s += img.getRGB(x * SS + i, y * SS + j) ushr 24
            out[y * N + x] = s / (255f * SS * SS)
        }
        return out
    }

    /**
     * The outlines of [ink] and which way they run, light-to-dark or dark-to-light alike (a stroke's two sides and a
     * fill's edge): per pixel the steepness times (cos 2θ, sin 2θ), softened; 2 · N · N values.
     */
    private fun outline(ink: FloatArray): FloatArray {
        val c = FloatArray(N * N)
        val s = FloatArray(N * N)
        fun at(x: Int, y: Int) = if (x in 0 until N && y in 0 until N) ink[y * N + x] else 0f
        for (y in 0 until N) for (x in 0 until N) {
            val gx = (at(x + 1, y - 1) + 2 * at(x + 1, y) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x - 1, y) - at(x - 1, y + 1)) / 4f
            val gy = (at(x - 1, y + 1) + 2 * at(x, y + 1) + at(x + 1, y + 1) - at(x - 1, y - 1) - 2 * at(x, y - 1) - at(x + 1, y - 1)) / 4f
            val m = sqrt(gx * gx + gy * gy)
            if (m > 1e-6f) {
                c[y * N + x] = (gx * gx - gy * gy) / m
                s[y * N + x] = 2 * gx * gy / m
            }
        }
        return gauss(c, 0.7f) + gauss(s, 0.7f)
    }

    private fun gauss(a: FloatArray, sigma: Float): FloatArray {
        val r = (sigma * 3f).toInt().coerceAtLeast(1)
        val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2f * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        val tmp = FloatArray(N * N)
        val out = FloatArray(N * N)
        for (y in 0 until N) for (x in 0 until N) {
            var s = 0f
            for (j in -r..r) { val xx = x + j; if (xx in 0 until N) s += k[j + r] * a[y * N + xx] }
            tmp[y * N + x] = s
        }
        for (y in 0 until N) for (x in 0 until N) {
            var s = 0f
            for (j in -r..r) { val yy = y + j; if (yy in 0 until N) s += k[j + r] * tmp[yy * N + x] }
            out[y * N + x] = s
        }
        return out
    }

    /** The ink [a] and [b] share over the ink of either. */
    private fun overlap(a: FloatArray, b: FloatArray): Float {
        var best = 0f
        for (dy in -1..1) for (dx in -1..1) {
            var lo = 0f
            var hi = 0f
            for (y in 0 until N) for (x in 0 until N) {
                val u = a[y * N + x]
                val xx = x + dx
                val yy = y + dy
                val v = if (xx in 0 until N && yy in 0 until N) b[yy * N + xx] else 0f
                lo += min(u, v)
                hi += max(u, v)
            }
            if (hi > 0f) best = max(best, lo / hi)
        }
        return best
    }

    /** How far the outlines [a] and [b] run alike: their cosine, from -1 to 1. */
    private fun along(a: FloatArray, b: FloatArray): Float {
        val nn = N * N
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until 2 * nn) {
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        if (aa == 0.0 || bb == 0.0) return 0f
        var best = -1f
        for (dy in -1..1) for (dx in -1..1) {
            var ab = 0.0
            for (y in 0 until N) for (x in 0 until N) {
                val xx = x + dx
                val yy = y + dy
                if (xx !in 0 until N || yy !in 0 until N) continue
                val i = y * N + x
                val j = yy * N + xx
                ab += a[i] * b[j] + a[nn + i] * b[nn + j]
            }
            best = max(best, (ab / sqrt(aa * bb)).toFloat())
        }
        return best
    }
}
