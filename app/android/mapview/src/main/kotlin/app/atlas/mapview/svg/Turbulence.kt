package app.atlas.mapview.svg

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * `feTurbulence`: the reference Perlin noise of the Filter Effects spec (section "feTurbulence", its C code), ported
 * exactly, in double precision, with the spec's seed handling (the seed is truncated towards zero, then clamped into
 * 1 … 2³¹ − 2). Like WebKit, Blink and Skia, a gradient that comes out as (0, 0) is drawn again, where the spec's code
 * would divide by zero.
 */
class Turbulence(seed: Float) {
    private val latticeSelector = IntArray(B + B + 2)

    /** Per channel, per lattice entry: the gradient's x and y. */
    private val gradient = DoubleArray(4 * (B + B + 2) * 2)

    /**
     * The gradients as the generator drew them, before normalising: per channel and lattice entry, the numerators
     * `gx · 256` and `gy · 256` (−256 … 255), x and y interleaved. Shaders rebuild [gradient] exactly from them.
     */
    val rawGradients = IntArray(4 * B * 2)

    /** [gradient] as WebKit keeps it: single precision, normalised with a single-precision `hypot`. */
    private val gradientF = FloatArray(4 * B * 2)

    /** The lattice selector's first 2B + 2 entries (each 0 … 255). */
    val lattice: IntArray get() = latticeSelector.copyOf()

    init {
        var s = seed.toLong()
        if (s <= 0) s = -(s % (RAND_M - 1)) + 1
        if (s > RAND_M - 1) s = RAND_M - 1
        for (k in 0 until 4) {
            for (i in 0 until B) {
                latticeSelector[i] = i
                var gx: Double
                var gy: Double
                do {
                    s = random(s)
                    gx = ((s % (B + B)) - B).toDouble() / B
                    s = random(s)
                    gy = ((s % (B + B)) - B).toDouble() / B
                } while (gx == 0.0 && gy == 0.0)
                rawGradients[(k * B + i) * 2] = (gx * B).toInt()
                rawGradients[(k * B + i) * 2 + 1] = (gy * B).toInt()
                val fx = gx.toFloat()
                val fy = gy.toFloat()
                val fn = Math.hypot(fx.toDouble(), fy.toDouble()).toFloat()
                gradientF[(k * B + i) * 2] = fx / fn
                gradientF[(k * B + i) * 2 + 1] = fy / fn
                val n = Math.sqrt(gx * gx + gy * gy)
                val g = (k * (B + B + 2) + i) * 2
                gradient[g] = gx / n
                gradient[g + 1] = gy / n
            }
        }
        var i = B
        while (--i > 0) {
            val k = latticeSelector[i]
            s = random(s)
            val j = (s % B).toInt()
            latticeSelector[i] = latticeSelector[j]
            latticeSelector[j] = k
        }
        for (n in 0 until B + 2) {
            latticeSelector[B + n] = latticeSelector[n]
            for (k in 0 until 4) {
                val from = (k * (B + B + 2) + n) * 2
                val to = (k * (B + B + 2) + B + n) * 2
                gradient[to] = gradient[from]
                gradient[to + 1] = gradient[from + 1]
            }
        }
    }

    /** Stitching state for `stitchTiles="stitch"`: width, height, wrapX, wrapY of the current octave. */
    class Stitch(var width: Int, var height: Int, var wrapX: Int, var wrapY: Int)

    /** The spec's `noise2` for one colour channel. */
    fun noise2(channel: Int, vx: Double, vy: Double, stitch: Stitch? = null): Double {
        var t = vx + PERLIN_N
        var bx0 = t.toInt()
        var bx1 = bx0 + 1
        val rx0 = t - t.toInt()
        val rx1 = rx0 - 1.0
        t = vy + PERLIN_N
        var by0 = t.toInt()
        var by1 = by0 + 1
        val ry0 = t - t.toInt()
        val ry1 = ry0 - 1.0
        if (stitch != null) {
            if (bx0 >= stitch.wrapX) bx0 -= stitch.width
            if (bx1 >= stitch.wrapX) bx1 -= stitch.width
            if (by0 >= stitch.wrapY) by0 -= stitch.height
            if (by1 >= stitch.wrapY) by1 -= stitch.height
        }
        bx0 = bx0 and BM
        bx1 = bx1 and BM
        by0 = by0 and BM
        by1 = by1 and BM
        val i = latticeSelector[bx0]
        val j = latticeSelector[bx1]
        val b00 = latticeSelector[i + by0]
        val b10 = latticeSelector[j + by0]
        val b01 = latticeSelector[i + by1]
        val b11 = latticeSelector[j + by1]
        val sx = rx0 * rx0 * (3.0 - 2.0 * rx0)
        val sy = ry0 * ry0 * (3.0 - 2.0 * ry0)
        val base = channel * (B + B + 2)
        var q = (base + b00) * 2
        var u = rx0 * gradient[q] + ry0 * gradient[q + 1]
        q = (base + b10) * 2
        var v = rx1 * gradient[q] + ry0 * gradient[q + 1]
        val a = u + sx * (v - u)
        q = (base + b01) * 2
        u = rx0 * gradient[q] + ry1 * gradient[q + 1]
        q = (base + b11) * 2
        v = rx1 * gradient[q] + ry1 * gradient[q + 1]
        val b = u + sx * (v - u)
        return a + sy * (b - a)
    }

    /**
     * The spec's `turbulence` for one channel at [x], [y] (user units): the sum over octaves, before it is mapped to a
     * colour. [tile] is x, y, width, height of the primitive subregion, used only when stitching.
     */
    fun turbulence(
        channel: Int,
        x: Double,
        y: Double,
        baseX: Double,
        baseY: Double,
        octaves: Int,
        fractal: Boolean,
        tile: DoubleArray? = null,
    ): Double {
        var fx = baseX
        var fy = baseY
        val stitch = tile?.let { stitchFor(it, fx, fy).also { r -> fx = r.second; fy = r.third }.first }
        var sum = 0.0
        var vx = x * fx
        var vy = y * fy
        var ratio = 1.0
        for (o in 0 until octaves) {
            val n = noise2(channel, vx, vy, stitch)
            sum += if (fractal) n / ratio else abs(n) / ratio
            vx *= 2
            vy *= 2
            ratio *= 2
            if (stitch != null) {
                stitch.width *= 2
                stitch.wrapX = 2 * stitch.wrapX - PERLIN_N
                stitch.height *= 2
                stitch.wrapY = 2 * stitch.wrapY - PERLIN_N
            }
        }
        return sum
    }

    /**
     * The colour of the noise at [x], [y] for all four channels, unpremultiplied, each 0 … 255 as browsers store it
     * (`(sum + 1) / 2` for fractal noise, truncated to an integer), written into [out] at [at].
     */
    fun color(x: Double, y: Double, baseX: Double, baseY: Double, octaves: Int, fractal: Boolean, out: IntArray, at: Int) {
        for (c in 0 until 4) {
            var sum = 0.0
            var vx = x * baseX
            var vy = y * baseY
            var ratio = 1.0
            for (o in 0 until octaves) {
                val n = noise2(c, vx, vy)
                sum += if (fractal) n / ratio else abs(n) / ratio
                vx *= 2
                vy *= 2
                ratio *= 2
            }
            val v = if (fractal) sum * 0.5 + 0.5 else sum
            out[at + c] = (v * 255).toInt().coerceIn(0, 255)
        }
    }

    /** Like [color] for one channel only. */
    fun channel(c: Int, x: Double, y: Double, baseX: Double, baseY: Double, octaves: Int, fractal: Boolean): Int {
        var sum = 0.0
        var vx = x * baseX
        var vy = y * baseY
        var ratio = 1.0
        for (o in 0 until octaves) {
            val n = noise2(c, vx, vy)
            sum += if (fractal) n / ratio else abs(n) / ratio
            vx *= 2
            vy *= 2
            ratio *= 2
        }
        val v = if (fractal) sum * 0.5 + 0.5 else sum
        return (v * 255).toInt().coerceIn(0, 255)
    }

    /**
     * The noise as WebKit computes it (`FETurbulenceSoftwareApplier`): single precision throughout, the lattice position
     * found as `x + 4096` in float, `std::lerp` for the interpolation, and products fused the way Apple's clang
     * contracts them on arm64. Browsers on macOS draw with this; [noise2] is the spec's double-precision original.
     */
    fun noise2WebKit(channel: Int, vx: Float, vy: Float): Float {
        val px = vx + PERLIN_N
        val ix = px.toInt()
        val fx = px - ix
        val py = vy + PERLIN_N
        val iy = py.toInt()
        val fy = py - iy
        val bx0 = ix and BM
        val bx1 = (ix + 1) and BM
        val by0 = iy and BM
        val by1 = (iy + 1) and BM
        val i = latticeSelector[bx0]
        val j = latticeSelector[bx1]
        val sx = smooth(fx)
        val sy = smooth(fy)
        val g = gradientF
        val base = channel * B
        var q = (base + latticeSelector[i + by0]) * 2
        var u = Math.fma(fx, g[q], fy * g[q + 1])
        q = (base + latticeSelector[j + by0]) * 2
        var v = Math.fma(fx - 1, g[q], fy * g[q + 1])
        val a = lerp(u, v, sx)
        q = (base + latticeSelector[i + by1]) * 2
        u = Math.fma(fx, g[q], (fy - 1) * g[q + 1])
        q = (base + latticeSelector[j + by1]) * 2
        v = Math.fma(fx - 1, g[q], (fy - 1) * g[q + 1])
        val b = lerp(u, v, sx)
        return lerp(a, b, sy)
    }

    /**
     * All channels of WebKit's turbulence at once, packed as `a r g b` bytes (unpremultiplied), with the lattice work
     * shared between channels. Channels outside [need] (bit 0 = red … bit 3 = alpha) come out as 0 (alpha as 255).
     * Equal, byte for byte, to [channelWebKit] per channel. [sums] is scratch space of four floats; threads may share
     * one [Turbulence] as long as each passes its own.
     */
    fun colorWebKit(
        x: Float,
        y: Float,
        baseX: Float,
        baseY: Float,
        octaves: Int,
        fractal: Boolean,
        need: Int = 15,
        sums: FloatArray = FloatArray(4),
    ): Int {
        var vx = x * baseX
        var vy = y * baseY
        sums.fill(0f)
        var ratio = 1f
        val g = gradientF
        for (o in 0 until octaves) {
            val px = vx + PERLIN_N
            val ix = px.toInt()
            val fx = px - ix
            val py = vy + PERLIN_N
            val iy = py.toInt()
            val fy = py - iy
            val i = latticeSelector[ix and BM]
            val j = latticeSelector[(ix + 1) and BM]
            val by0 = iy and BM
            val by1 = (iy + 1) and BM
            val b00 = latticeSelector[i + by0]
            val b10 = latticeSelector[j + by0]
            val b01 = latticeSelector[i + by1]
            val b11 = latticeSelector[j + by1]
            val sx = smooth(fx)
            val sy = smooth(fy)
            val fx1 = fx - 1
            val fy1 = fy - 1
            for (c in 0 until 4) {
                if (need and (1 shl c) == 0) continue
                val base = c * B
                var q = (base + b00) * 2
                var u = Math.fma(fx, g[q], fy * g[q + 1])
                q = (base + b10) * 2
                var v = Math.fma(fx1, g[q], fy * g[q + 1])
                val a = lerp(u, v, sx)
                q = (base + b01) * 2
                u = Math.fma(fx, g[q], fy1 * g[q + 1])
                q = (base + b11) * 2
                v = Math.fma(fx1, g[q], fy1 * g[q + 1])
                val b = lerp(u, v, sx)
                val n = lerp(a, b, sy)
                sums[c] += if (fractal) n / ratio else abs(n) / ratio
            }
            vx *= 2
            vy *= 2
            ratio *= 2
        }
        var out = 0
        for (c in 0 until 4) {
            val byte = if (need and (1 shl c) == 0) {
                if (c == 3) 255 else 0
            } else {
                var sum = sums[c]
                if (fractal) sum = sum * 0.5f + 0.5f
                (sum * 255f).toInt().coerceIn(0, 255)
            }
            out = out or (byte shl (if (c == 3) 24 else 16 - 8 * c))
        }
        return out
    }

    /**
     * One channel of WebKit's turbulence at the filter pixel whose far corner is [x], [y] (already divided by the filter
     * scale), as the byte it stores: `(int)(value · 255)` clamped to 0 … 255.
     */
    fun channelWebKit(c: Int, x: Float, y: Float, baseX: Float, baseY: Float, octaves: Int, fractal: Boolean): Int {
        var vx = x * baseX
        var vy = y * baseY
        var sum = 0f
        var ratio = 1f
        for (o in 0 until octaves) {
            val n = noise2WebKit(c, vx, vy)
            sum += if (fractal) n / ratio else abs(n) / ratio
            vx *= 2
            vy *= 2
            ratio *= 2
        }
        if (fractal) sum = sum * 0.5f + 0.5f
        return (sum * 255f).toInt().coerceIn(0, 255)
    }

    companion object {
        private const val B = 0x100
        private const val BM = 0xff
        const val PERLIN_N = 0x1000
        private const val RAND_M = 2147483647L
        private const val RAND_A = 16807L
        private const val RAND_Q = 127773L
        private const val RAND_R = 2836L

        private fun smooth(t: Float) = t * t * Math.fma(-2f, t, 3f)

        /** libc++'s `std::lerp(a, b, t)` for floats, with its products fused as clang emits them. */
        fun lerp(a: Float, b: Float, t: Float): Float {
            if ((a <= 0f && b >= 0f) || (a >= 0f && b <= 0f)) return Math.fma(t, b, (1 - t) * a)
            if (t == 1f) return b
            val x = Math.fma(t, b - a, a)
            return if ((t > 1f) == (b > a)) (if (b < x) x else b) else (if (x < b) x else b)
        }

        /** The spec's Park–Miller generator. */
        fun random(seed: Long): Long {
            var r = RAND_A * (seed % RAND_Q) - RAND_R * (seed / RAND_Q)
            if (r <= 0) r += RAND_M
            return r
        }

        /** The spec's adjustment of the base frequencies for stitching, and the first octave's stitch state. */
        fun stitchFor(tile: DoubleArray, baseX: Double, baseY: Double): Triple<Stitch, Double, Double> {
            var fx = baseX
            var fy = baseY
            val tw = tile[2]
            val th = tile[3]
            if (fx != 0.0) {
                val lo = floor(tw * fx) / tw
                val hi = ceil(tw * fx) / tw
                fx = if (fx / lo < hi / fx) lo else hi
            }
            if (fy != 0.0) {
                val lo = floor(th * fy) / th
                val hi = ceil(th * fy) / th
                fy = if (fy / lo < hi / fy) lo else hi
            }
            val w = (tw * fx + 0.5).toInt()
            val h = (th * fy + 0.5).toInt()
            val s = Stitch(w, h, (tile[0] * fx + PERLIN_N + w).toInt(), (tile[1] * fy + PERLIN_N + h).toInt())
            return Triple(s, fx, fy)
        }
    }
}
