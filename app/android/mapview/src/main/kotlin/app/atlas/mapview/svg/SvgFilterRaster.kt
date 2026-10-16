package app.atlas.mapview.svg

import app.atlas.num.ExactMath
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Runs a [SvgFilter] on the CPU the way WebKit's software filters do on macOS (the browser the renders are checked in),
 * on any Android version and on the JVM.
 *
 * Coordinates are "filter pixels": user units times the filter scale, anchored at the user-space origin, so the noise
 * lies on the same grid however the element is placed. As in WebKit, every primitive's result is an 8-bit buffer over
 * its part of the filter region: premultiplied or not as the primitive writes it, in its own colour space, with
 * conversions between formats and colour spaces done on bytes. The source graphic is held in linear RGB (WebKit draws
 * it into a linear-RGB buffer), turbulence is computed in single precision ([Turbulence.colorWebKit], kept in [noise]
 * when there is one), the arithmetic composite truncates like WebKit's NEON code, colour matrices round like
 * `Uint8ClampedArray`, displacement offsets are truncated, and equal blur sizes run as vImage's three edge-extended box
 * blurs.
 *
 * Each primitive is computed only over the rectangle later primitives read, which [prepare] works out backwards from
 * the visible output. The same working-out makes bands exact: [run] can split the output into horizontal bands, each a
 * chain of its own prepared for its rows (so it gets its blur and displacement margins), run in parallel; every pixel
 * of a band is the pixel the whole chain makes.
 */
class SvgFilterRaster(private val filter: SvgFilter, private val noise: TurbulenceCache? = TurbulenceCache.shared) {
    /** Integer rectangle in filter pixels: left, top, right, bottom (exclusive). */
    class Rect(var left: Int, var top: Int, var right: Int, var bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val isEmpty get() = right <= left || bottom <= top

        fun copy() = Rect(left, top, right, bottom)

        fun intersect(o: Rect) = Rect(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom))

        fun union(o: Rect): Rect = if (isEmpty) o.copy() else if (o.isEmpty) copy() else
            Rect(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))

        fun inset(dx: Int, dy: Int) = Rect(left - dx, top - dy, right + dx, bottom + dy)

        fun contains(x: Int, y: Int) = x >= left && x < right && y >= top && y < bottom

        override fun toString() = "[$left,$top,$right,$bottom]"

        companion object {
            /** The integer rectangle enclosing x, y, w, h. */
            fun enclosing(x: Double, y: Double, w: Double, h: Double) =
                Rect(floor(x).toInt(), floor(y).toInt(), ceil(x + w).toInt(), ceil(y + h).toInt())

            val EMPTY get() = Rect(0, 0, 0, 0)
        }
    }

    /** An 8-bit image over [rect]: packed `a r g b`, premultiplied or not, in linear RGB or sRGB. */
    private class Buf(val rect: Rect, val px: IntArray, val premul: Boolean, val linear: Boolean) {
        /** False for the arithmetic composite, whose colour may exceed its alpha until a consumer corrects it. */
        var valid = true

        fun at(x: Int, y: Int): Int = if (rect.contains(x, y)) px[(y - rect.top) * rect.width + (x - rect.left)] else 0
    }

    /**
     * A pixel converted from one format to another (premultiplied or not, linear RGB or sRGB), corrected to valid
     * premultiplied colour first when [correct]: exactly what [Chain.convert] after [Chain.corrected] does to a buffer.
     */
    private class Conversion(fromPremul: Boolean, fromLinear: Boolean, private val premul: Boolean, private val linear: Boolean, private val correct: Boolean) {
        private val mode = when {
            fromPremul == premul && fromLinear == linear -> SAME
            fromLinear == linear -> if (premul) PREMULTIPLY else UNPREMULTIPLY
            fromPremul -> FROM_PREMULTIPLIED
            else -> FROM_UNPREMULTIPLIED
        }

        fun map(raw: Int): Int {
            var p = raw
            if (correct) {
                val a = p ushr 24
                p = (a shl 24) or (min((p shr 16) and 0xFF, a) shl 16) or (min((p shr 8) and 0xFF, a) shl 8) or min(p and 0xFF, a)
            }
            return when (mode) {
                SAME -> p
                PREMULTIPLY -> Bytes.premultiply(p)
                UNPREMULTIPLY -> Bytes.unpremultiply(p)
                else -> {
                    val u = if (mode == FROM_PREMULTIPLIED) Bytes.unpremultiplyExact(p, linear) else Bytes.mapUnpremultiplied(p, linear)
                    val q = Bytes.premultiply(u)
                    if (premul) q else Bytes.unpremultiply(q)
                }
            }
        }

        private companion object {
            const val SAME = 0
            const val PREMULTIPLY = 1
            const val UNPREMULTIPLY = 2
            const val FROM_PREMULTIPLIED = 3
            const val FROM_UNPREMULTIPLIED = 4
        }
    }

    /** A [Buf] read through a [Conversion]: each pixel converted as it is read, without making a converted buffer. */
    private class View(buf: Buf, premul: Boolean, linear: Boolean, correct: Boolean) {
        private val rect = buf.rect
        private val px = buf.px
        private val width = rect.width
        val conversion = Conversion(buf.premul, buf.linear, premul, linear, correct && !buf.valid)

        fun at(x: Int, y: Int): Int = if (rect.contains(x, y)) conversion.map(px[(y - rect.top) * width + (x - rect.left)]) else 0
    }

    /** What a node computes from noise alone: its field in the cache, and the map from the noise to its pixels. */
    private class Noise(val field: NoiseField, val map: NoiseMap?)

    private var sx = 1.0
    private var sy = 1.0
    private var main: Chain? = null

    /** The filter region in filter pixels, set by [prepare]. */
    lateinit var regionRect: Rect
        private set

    /** The source pixels the filter reads, set by [prepare]: the element must be drawn over this rectangle. */
    lateinit var sourceRect: Rect
        private set

    private lateinit var outRect: Rect

    /** The output rectangle: the region's pixels that can show. */
    val outputRect: Rect get() = outRect

    /**
     * Builds the chain for an element whose filter region is [region] (user units, already resolved against the bounding
     * box) under the filter scale [scaleX], [scaleY], for output pixels [visible]. Returns false when nothing shows.
     */
    fun prepare(region: DoubleArray, scaleX: Double, scaleY: Double, visible: Rect): Boolean {
        sx = scaleX
        sy = scaleY
        regionRect = Rect.enclosing(region[0] * sx, region[1] * sy, region[2] * sx, region[3] * sy)
        outRect = regionRect.intersect(visible)
        main = null
        if (outRect.isEmpty) return false
        val c = Chain(outRect)
        main = c
        sourceRect = c.sourceRect
        return true
    }

    /**
     * Runs the filter. [pixels] are the element drawn in sRGB over [sourceRect] (premultiplied ARGB, row-major); the
     * result over [outputRect], premultiplied sRGB ARGB, goes into [out]. With [bands] above 1 a large output is made in
     * up to that many horizontal bands at once, on the filter workers; the pixels are the same.
     */
    fun run(pixels: IntArray, out: IntArray, bands: Int = 1) {
        val whole = main ?: throw IllegalStateException("run before a successful prepare")
        val o = outRect
        val tops = bandTops(o, bands)
        if (tops.size <= 2) {
            whole.run(pixels, sourceRect, out, 0)
            return
        }
        val n = tops.size - 1
        val chains = Array(n) { k -> Chain(Rect(o.left, tops[k], o.right, tops[k + 1])) }
        val area = sourceRect
        parallel(n) { k -> chains[k].run(pixels, area, out, (tops[k] - o.top) * o.width) }
    }

    /**
     * The chain of primitives prepared for the output pixels [visible]: each node computed over what its consumers read
     * of it, within the region.
     */
    private inner class Chain(visible: Rect) {
        private val nodes = ArrayList<Node>()
        private val output: Node
        private val src = SourceNode()
        private val srcAlpha = SourceAlphaNode()
        val outRect: Rect = regionRect.intersect(visible)

        /** The source pixels this chain reads. */
        val sourceRect: Rect

        private var source = IntArray(0)
        private var sourceArea = Rect.EMPTY

        init {
            val named = HashMap<String, Node>()
            var previous: Node = src
            for (fe in filter.primitives) {
                fun input(name: String?): Node = when (name) {
                    null -> previous
                    SvgFe.SOURCE_GRAPHIC -> src
                    SvgFe.SOURCE_ALPHA -> srcAlpha
                    else -> named[name] ?: if (name in STANDARD_INPUTS) {
                        throw SvgFormatException("filter input '$name' is not written by the engine")
                    } else {
                        previous
                    }
                }
                val node: Node = when (fe) {
                    is SvgFe.Turbulence -> TurbulenceNode(fe)
                    is SvgFe.DisplacementMap -> DisplacementNode(fe, input(fe.input), input(fe.input2))
                    is SvgFe.ColorMatrix -> MatrixNode(fe, input(fe.input))
                    is SvgFe.Composite -> CompositeNode(fe, input(fe.input), input(fe.input2))
                    is SvgFe.GaussianBlur -> BlurNode(fe, input(fe.input))
                    is SvgFe.Offset -> OffsetNode(fe, input(fe.input))
                    is SvgFe.Flood -> FloodNode(fe)
                    is SvgFe.Merge -> MergeNode(fe, fe.inputs.map { input(it) })
                }
                nodes.add(node)
                for (i in node.inputs) i.uses++
                fe.result?.let { named[it] = node }
                previous = node
            }
            output = previous
            output.needRect = outRect.copy()
            output.need = 15
            for (i in nodes.indices.reversed()) nodes[i].propagate()
            sourceRect = src.needRect.union(srcAlpha.needRect).intersect(regionRect)
        }

        /**
         * Runs the chain on [pixels], the element drawn over [area] (which holds this chain's [sourceRect]), writing the
         * rows of [outRect] into [out] from [offset], [outRect]'s width apart.
         */
        fun run(pixels: IntArray, area: Rect, out: IntArray, offset: Int) {
            source = pixels
            sourceArea = area
            for (n in nodes) n.reset()
            src.reset()
            srcAlpha.reset()
            try {
                val o = outRect
                val r = result(output, o)
                // the result as premultiplied sRGB, converted as it is written (never corrected: WebKit writes it as is)
                val fin = View(r, premul = true, linear = false, correct = false)
                var i = offset
                for (y in o.top until o.bottom) for (x in o.left until o.right) out[i++] = fin.at(x, y)
            } finally {
                for (n in nodes) n.reset()
                src.reset()
                srcAlpha.reset()
                source = IntArray(0)
            }
        }

        /** [n]'s result, computing it (over its needed rectangle) on first use; inputs no one else reads are let go. */
        private fun result(n: Node, fallback: Rect = n.needRect): Buf {
            n.result?.let { return it }
            val rect = (if (n.needRect.isEmpty) fallback else n.needRect).intersect(regionRect)
            val b = if (rect.isEmpty) Buf(Rect.EMPTY, IntArray(0), true, n.linear) else n.compute(rect)
            n.result = b
            for (i in n.inputs) if (--i.pending == 0) i.result = null
            return b
        }

        /** [n]'s result read as premultiplied or not, in linear RGB or sRGB, corrected to valid premultiplied colour. */
        private fun view(n: Node, premul: Boolean, linear: Boolean, correct: Boolean = true) = View(result(n), premul, linear, correct)

        /** [n]'s result made into a buffer that is premultiplied or not, in linear RGB or sRGB, corrected. */
        private fun input(n: Node, premul: Boolean, linear: Boolean, correct: Boolean = true): Buf {
            var b = result(n)
            if (correct && !b.valid) b = corrected(b)
            return convert(b, premul, linear)
        }

        private fun corrected(b: Buf): Buf {
            val px = IntArray(b.px.size)
            for (i in px.indices) {
                val p = b.px[i]
                val a = p ushr 24
                px[i] = (a shl 24) or (min((p shr 16) and 0xFF, a) shl 16) or (min((p shr 8) and 0xFF, a) shl 8) or min(p and 0xFF, a)
            }
            return Buf(b.rect, px, b.premul, b.linear)
        }

        private fun convert(b: Buf, premul: Boolean, linear: Boolean): Buf {
            if (b.premul == premul && b.linear == linear) return b
            val c = Conversion(b.premul, b.linear, premul, linear, correct = false)
            val px = IntArray(b.px.size)
            for (i in px.indices) px[i] = c.map(b.px[i])
            return Buf(b.rect, px, premul, linear)
        }

        private abstract inner class Node {
            /** The rectangle consumers read; the node is computed over it (within the region). */
            var needRect = Rect.EMPTY

            /** Channels (bit 0 = red … bit 3 = alpha) consumers read, before any premultiplication. */
            var need = 0
            var result: Buf? = null

            /** How many times other nodes read this one, and how many of those reads are still to come in a run. */
            var uses = 0
            var pending = 0

            /** The colour space the result is in. */
            abstract val linear: Boolean

            /** The nodes this one reads, once per read. */
            open val inputs: List<Node> get() = emptyList()

            abstract fun compute(rect: Rect): Buf

            /** What this node computes from noise alone, for the cache, or null when it reads anything else. */
            open fun noise(): Noise? = null

            /** Records what this node needs of its inputs, given [needRect] and [need]. */
            open fun propagate() = Unit

            fun reset() {
                result = null
                pending = uses
            }

            fun require(n: Node, r: Rect, channels: Int = 15) {
                n.needRect = n.needRect.union(r.intersect(regionRect))
                n.need = n.need or channels
            }
        }

        private inner class SourceNode : Node() {
            override val linear = true

            override fun compute(rect: Rect): Buf {
                val sr = sourceArea
                val w = sr.width
                val px = IntArray(rect.width * rect.height)
                var i = 0
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    px[i++] = if (sr.contains(x, y)) Bytes.srgbToLinearPremultiplied(source[(y - sr.top) * w + (x - sr.left)]) else 0
                }
                return Buf(rect, px, true, true)
            }
        }

        private inner class SourceAlphaNode : Node() {
            override val linear = true

            override fun compute(rect: Rect): Buf {
                val sr = sourceArea
                val w = sr.width
                val px = IntArray(rect.width * rect.height)
                var i = 0
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    px[i++] = if (sr.contains(x, y)) source[(y - sr.top) * w + (x - sr.left)] and (0xFF shl 24) else 0
                }
                return Buf(rect, px, true, true)
            }
        }

        private inner class TurbulenceNode(val fe: SvgFe.Turbulence) : Node() {
            override val linear = fe.linearRgb

            override fun noise(): Noise? =
                if (need == 0 || stitched) null else Noise(NoiseField(fe.seed, fe.baseX, fe.baseY, fe.octaves, fe.fractal, sx.toFloat(), sy.toFloat(), need), null)

            private val stitched get() = fe.stitch && fe.baseX > 0f && fe.baseY > 0f

            override fun compute(rect: Rect): Buf {
                if (stitched) throw SvgFormatException("stitchTiles is not written by the engine")
                val w = rect.width
                val px = IntArray(w * rect.height)
                val fx = sx.toFloat()
                val fy = sy.toFloat()
                val cache = noise
                val n = if (cache != null) noise() else null
                if (cache != null && n != null) {
                    cache.fill(n.field, rect.left, rect.top, w, rect.height, px)
                } else {
                    val t = Turbulence.of(fe.seed)
                    val need = need
                    // WebKit splits turbulence over threads too; rows are independent.
                    rows(rect.height, w) { y0, y1 ->
                        t.fillWebKit(rect.left, rect.top + y0, w, y1 - y0, 1f / fx, 1f / fy, fe.baseX, fe.baseY, fe.octaves, fe.fractal, need, px, y0 * w, w)
                    }
                }
                return Buf(rect, px, false, linear)
            }
        }

        private inner class DisplacementNode(val fe: SvgFe.DisplacementMap, val input: Node, val map: Node) : Node() {
            override val linear get() = input.linear
            override val inputs = listOf(input, map)
            private val absX = (fe.scale * sx).toFloat()
            private val absY = (fe.scale * sy).toFloat()
            private val reachX = ceil(abs(absX) / 2.0 + 1).toInt()
            private val reachY = ceil(abs(absY) / 2.0 + 1).toInt()

            override fun compute(rect: Rect): Buf {
                // WebKit reads the input premultiplied in its own colour space and the map unpremultiplied, unconverted.
                val inp = view(input, premul = true, linear = input.linear)
                val m = view(map, premul = false, linear = map.linear, correct = false)
                val scaleForColorX = (absX / 255.0).toFloat()
                val scaleForColorY = (absY / 255.0).toFloat()
                val offsetX = (0.5 - absX * 0.5).toFloat()
                val offsetY = (0.5 - absY * 0.5).toFloat()
                val shiftX = 16 - 8 * fe.xChannel
                val shiftY = 16 - 8 * fe.yChannel
                val region = regionRect
                val px = IntArray(rect.width * rect.height)
                var i = 0
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    val mp = m.at(x, y)
                    val cx = channel(mp, fe.xChannel, shiftX)
                    val cy = channel(mp, fe.yChannel, shiftY)
                    val srcX = x + ExactMath.fma(scaleForColorX, cx.toFloat(), offsetX).toInt()
                    val srcY = y + ExactMath.fma(scaleForColorY, cy.toFloat(), offsetY).toInt()
                    px[i++] = if (region.contains(srcX, srcY)) inp.at(srcX, srcY) else 0
                }
                return Buf(rect, px, true, input.linear)
            }

            private fun channel(p: Int, c: Int, shift: Int) = if (c == 3) p ushr 24 else (p shr shift) and 0xFF

            override fun propagate() {
                require(map, needRect, (1 shl fe.xChannel) or (1 shl fe.yChannel))
                require(input, needRect.inset(reachX, reachY))
            }
        }

        private inner class MatrixNode(val fe: SvgFe.ColorMatrix, val input: Node) : Node() {
            override val linear = fe.linearRgb
            override val inputs = listOf(input)
            private val m = fe.matrix

            /** WebKit hands matrices without offsets (and saturate) to vImage's integer matrix multiply. */
            private val integer = m[4] == 0f && m[9] == 0f && m[14] == 0f && m[19] == 0f
            private val q = IntArray(16) { k -> Math.round(m[(k % 4) * 5 + k / 4] * 256f) }

            override fun compute(rect: Rect): Buf {
                val px = IntArray(rect.width * rect.height)
                val cache = noise
                val n = if (cache != null) noise() else null
                if (cache != null && n != null) {
                    cache.fill(n.field, rect.left, rect.top, rect.width, rect.height, px, map = n.map)
                    return Buf(rect, px, true, linear)
                }
                // WebKit draws the input into a premultiplied buffer, then reads that back unpremultiplied.
                val inp = view(input, premul = true, linear = linear)
                var i = 0
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) px[i++] = apply(Bytes.unpremultiply(inp.at(x, y)))
                return Buf(rect, px, true, linear)
            }

            /** The matrix on one unpremultiplied pixel of the input, premultiplied. */
            private fun apply(p: Int): Int {
                val r = ((p shr 16) and 0xFF)
                val g = ((p shr 8) and 0xFF)
                val b = (p and 0xFF)
                val a = p ushr 24
                val u = if (integer) {
                    // q is column-major: q[col * 4 + row].
                    val or = vimage(q[0] * r + q[4] * g + q[8] * b + q[12] * a)
                    val og = vimage(q[1] * r + q[5] * g + q[9] * b + q[13] * a)
                    val ob = vimage(q[2] * r + q[6] * g + q[10] * b + q[14] * a)
                    val oa = vimage(q[3] * r + q[7] * g + q[11] * b + q[15] * a)
                    (oa shl 24) or (or shl 16) or (og shl 8) or ob
                } else {
                    val rf = r.toFloat()
                    val gf = g.toFloat()
                    val bf = b.toFloat()
                    val af = a.toFloat()
                    (row(15, rf, gf, bf, af) shl 24) or (row(0, rf, gf, bf, af) shl 16) or (row(5, rf, gf, bf, af) shl 8) or row(10, rf, gf, bf, af)
                }
                return Bytes.premultiply(u)
            }

            /**
             * On noise (or on a matrix on noise) the result is noise alone, pixel by pixel: kept under a name that says
             * every input of the map, the input's format and this matrix.
             */
            override fun noise(): Noise? {
                val n = input.noise() ?: return null
                val inPremul = input is MatrixNode
                val conv = Conversion(inPremul, input.linear, true, linear, correct = false)
                val before = n.map
                val name = StringBuilder(n.field.steps).append('m')
                    .append(if (input.linear) 'L' else 'S').append(if (inPremul) 'P' else 'U').append(if (linear) 'L' else 'S')
                for (v in m) name.append(':').append(Integer.toHexString(java.lang.Float.floatToRawIntBits(v)))
                name.append(';')
                val map = if (before == null) {
                    NoiseMap { raw -> apply(Bytes.unpremultiply(conv.map(raw))) }
                } else {
                    NoiseMap { raw -> apply(Bytes.unpremultiply(conv.map(before.map(raw)))) }
                }
                return Noise(n.field.copy(steps = name.toString()), map)
            }

            /** One row of the matrix as WebKit's float code computes it, stored like `Uint8ClampedArray`. */
            private fun row(k: Int, r: Float, g: Float, b: Float, a: Float): Int {
                var t = ExactMath.fma(m[k], r, m[k + 1] * g)
                t = ExactMath.fma(m[k + 2], b, t)
                t = ExactMath.fma(m[k + 3], a, t)
                t = ExactMath.fma(m[k + 4], 255f, t)
                return Bytes.clamped(t.toDouble())
            }

            private fun vimage(sum: Int): Int = ((sum + 128) shr 8).coerceIn(0, 255)

            override fun propagate() = require(input, needRect)
        }

        private inner class CompositeNode(val fe: SvgFe.Composite, val a: Node, val b: Node) : Node() {
            override val linear = fe.linearRgb
            override val inputs = listOf(a, b)

            override fun compute(rect: Rect): Buf {
                val arithmetic = fe.operator == SvgFe.Composite.Operator.ARITHMETIC
                val ia = view(a, premul = true, linear = linear, correct = !arithmetic)
                val ib = view(b, premul = true, linear = linear, correct = !arithmetic)
                val px = IntArray(rect.width * rect.height)
                var i = 0
                if (arithmetic) {
                    val k1 = fe.k1 / 255f
                    val k2 = fe.k2
                    val k3 = fe.k3
                    val k4 = fe.k4 * 255f
                    val b1 = fe.k1 != 0f
                    val b4 = fe.k4 != 0f
                    for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                        val s = ia.at(x, y)
                        val d = ib.at(x, y)
                        var o = 0
                        var sh = 24
                        while (sh >= 0) {
                            val i1 = ((s ushr sh) and 0xFF).toFloat()
                            val i2 = ((d ushr sh) and 0xFF).toFloat()
                            var r = i1 * k2
                            r += i2 * k3
                            if (b1) r += (i1 * i2) * k1
                            if (b4) r += k4
                            val v = if (r <= 0f) 0 else min(r.toInt(), 255)
                            o = o or (v shl sh)
                            sh -= 8
                        }
                        px[i++] = o
                    }
                    return Buf(rect, px, true, linear).also { it.valid = false }
                }
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    val s = ia.at(x, y)
                    val d = ib.at(x, y)
                    px[i++] = when (fe.operator) {
                        SvgFe.Composite.Operator.IN -> Bytes.scale(s, d ushr 24)
                        SvgFe.Composite.Operator.OUT -> Bytes.scale(s, 255 - (d ushr 24))
                        SvgFe.Composite.Operator.ATOP -> Bytes.add(Bytes.scale(s, d ushr 24), Bytes.scale(d, 255 - (s ushr 24)))
                        SvgFe.Composite.Operator.XOR -> Bytes.add(Bytes.scale(s, 255 - (d ushr 24)), Bytes.scale(d, 255 - (s ushr 24)))
                        else -> Bytes.over(s, d)
                    }
                }
                return Buf(rect, px, true, linear)
            }

            override fun propagate() {
                require(a, needRect)
                require(b, needRect)
            }
        }

        private inner class OffsetNode(val fe: SvgFe.Offset, val input: Node) : Node() {
            override val linear = fe.linearRgb
            override val inputs = listOf(input)
            private val dx = fe.dx * sx
            private val dy = fe.dy * sy
            private val ix = floor(dx).toInt()
            private val iy = floor(dy).toInt()
            private val fx = dx - ix
            private val fy = dy - iy

            override fun compute(rect: Rect): Buf {
                val px = IntArray(rect.width * rect.height)
                var i = 0
                if (fx == 0.0 && fy == 0.0) {
                    val inp = view(input, premul = true, linear = linear)
                    for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) px[i++] = inp.at(x - ix, y - iy)
                    return Buf(rect, px, true, linear)
                }
                // The input drawn at a fractional offset: bilinear on premultiplied bytes.
                val inp = input(input, premul = true, linear = linear)
                val w00 = (1 - fx) * (1 - fy)
                val w10 = fx * (1 - fy)
                val w01 = (1 - fx) * fy
                val w11 = fx * fy
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    val p00 = inp.at(x - ix, y - iy)
                    val p10 = inp.at(x - ix - 1, y - iy)
                    val p01 = inp.at(x - ix, y - iy - 1)
                    val p11 = inp.at(x - ix - 1, y - iy - 1)
                    var o = 0
                    var sh = 24
                    while (sh >= 0) {
                        var acc = 0.0
                        if (w00 != 0.0) acc += w00 * ((p00 ushr sh) and 0xFF)
                        if (w10 != 0.0) acc += w10 * ((p10 ushr sh) and 0xFF)
                        if (w01 != 0.0) acc += w01 * ((p01 ushr sh) and 0xFF)
                        if (w11 != 0.0) acc += w11 * ((p11 ushr sh) and 0xFF)
                        o = o or (acc.roundToInt().coerceIn(0, 255) shl sh)
                        sh -= 8
                    }
                    px[i++] = o
                }
                return Buf(rect, px, true, linear)
            }

            override fun propagate() {
                val r = needRect
                require(input, Rect(r.left - ix - 1, r.top - iy - 1, r.right - ix + 1, r.bottom - iy + 1))
            }
        }

        private inner class FloodNode(val fe: SvgFe.Flood) : Node() {
            override val linear = fe.linearRgb

            override fun compute(rect: Rect): Buf {
                val c = fe.argb
                val u = if (linear) Bytes.mapUnpremultiplied(c, true) else c
                val p = Bytes.premultiply(u)
                return Buf(rect, IntArray(rect.width * rect.height) { p }, true, linear)
            }
        }

        private inner class MergeNode(val fe: SvgFe.Merge, override val inputs: List<Node>) : Node() {
            override val linear = fe.linearRgb

            override fun compute(rect: Rect): Buf {
                val views = inputs.map { view(it, premul = true, linear = linear) }
                val px = IntArray(rect.width * rect.height)
                var i = 0
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) {
                    var d = 0
                    for (v in views) d = Bytes.over(v.at(x, y), d)
                    px[i++] = d
                }
                return Buf(rect, px, true, linear)
            }

            override fun propagate() {
                for (n in inputs) require(n, needRect)
            }
        }

        /** WebKit's Gaussian blur: three box blurs on premultiplied bytes. */
        private inner class BlurNode(val fe: SvgFe.GaussianBlur, val input: Node) : Node() {
            override val linear = fe.linearRgb
            override val inputs = listOf(input)
            private val kx = kernel(fe.deviationX * sx)
            private val ky = kernel(fe.deviationY * sy)
            private val reachX = 3 * (kx / 2 + 1)
            private val reachY = 3 * (ky / 2 + 1)

            override fun compute(rect: Rect): Buf {
                val inp = input(input, premul = true, linear = linear)
                // Blur over the rectangle the input was computed on; its edges are the region's or lie beyond the reach.
                val r = inp.rect
                if (r.isEmpty) return Buf(rect, IntArray(rect.width * rect.height), true, linear)
                val buf = if (inp.px === result(input).px) inp.px.copyOf() else inp.px
                boxBlurs(buf, r.width, r.height, kx, ky)
                val blurred = Buf(r, buf, true, linear)
                val px = IntArray(rect.width * rect.height)
                var i = 0
                for (y in rect.top until rect.bottom) for (x in rect.left until rect.right) px[i++] = blurred.at(x, y)
                return Buf(rect, px, true, linear)
            }

            override fun propagate() = require(input, needRect.inset(reachX, reachY))
        }
    }

    /** Byte arithmetic as WebKit, vImage and Core Graphics do it. */
    internal object Bytes {
        fun toLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

        fun toSrgb(c: Double) = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055

        /** vImage's premultiply: (c · a + 127) / 255. */
        fun premultiply(p: Int): Int {
            val a = p ushr 24
            if (a == 255) return p
            if (a == 0) return 0
            return (a shl 24) or (((((p shr 16) and 0xFF) * a + 127) / 255) shl 16) or
                (((((p shr 8) and 0xFF) * a + 127) / 255) shl 8) or (((p and 0xFF) * a + 127) / 255)
        }

        /** vImage's unpremultiply: (c · 255 + a / 2) / a, at most 255. */
        fun unpremultiply(p: Int): Int {
            val a = p ushr 24
            if (a == 255) return p
            if (a == 0) return 0
            fun u(c: Int) = min(255, (c * 255 + a / 2) / a)
            return (a shl 24) or (u((p shr 16) and 0xFF) shl 16) or (u((p shr 8) and 0xFF) shl 8) or u(p and 0xFF)
        }

        /**
         * Per alpha a and premultiplied channel c, the rounded byte of the transfer function at c / a: the colour
         * Core Graphics stores when it converts a pixel to the other colour space. Indexed `a · 256 + c`.
         */
        private val PREMUL_TO_LINEAR by lazy { premulTable(true) }
        private val PREMUL_TO_SRGB by lazy { premulTable(false) }

        private fun premulTable(linear: Boolean) = ByteArray(256 * 256) { k ->
            val a = k shr 8
            val c = k and 0xFF
            if (a == 0) {
                0
            } else if (a == 255 && !linear && c in SRGB_OF_LINEAR_BYTE) {
                SRGB_OF_LINEAR_BYTE.getValue(c).toByte()
            } else {
                val v = min(1.0, c / a.toDouble())
                ((if (linear) toLinear(v) else toSrgb(v)) * 255 + 0.5).toInt().coerceIn(0, 255).toByte()
            }
        }

        /**
         * Where Core Graphics' linear-to-sRGB conversion of an opaque byte differs from rounding the exact curve (all
         * within 0.03 of a half), as measured from Safari's rasterisation of the 256 linear greys.
         */
        private val SRGB_OF_LINEAR_BYTE = mapOf(24 to 87, 120 to 183, 160 to 207, 167 to 211, 193 to 225, 219 to 239)

        /**
         * A premultiplied pixel converted to linear RGB ([toLinear]) or to sRGB by its exact unpremultiplied colour,
         * returned unpremultiplied and rounded.
         */
        fun unpremultiplyExact(p: Int, toLinear: Boolean): Int {
            val a = p ushr 24
            if (a == 0) return 0
            val t = if (toLinear) PREMUL_TO_LINEAR else PREMUL_TO_SRGB
            val row = a shl 8
            return (a shl 24) or ((t[row + ((p shr 16) and 0xFF)].toInt() and 0xFF) shl 16) or
                ((t[row + ((p shr 8) and 0xFF)].toInt() and 0xFF) shl 8) or (t[row + (p and 0xFF)].toInt() and 0xFF)
        }

        /** An unpremultiplied pixel's colour converted to linear RGB ([toLinear]) or to sRGB, rounded. */
        fun mapUnpremultiplied(p: Int, toLinear: Boolean): Int {
            val t = if (toLinear) PREMUL_TO_LINEAR else PREMUL_TO_SRGB
            fun m(c: Int) = t[(255 shl 8) + c].toInt() and 0xFF
            return (p and (0xFF shl 24)) or (m((p shr 16) and 0xFF) shl 16) or (m((p shr 8) and 0xFF) shl 8) or m(p and 0xFF)
        }

        /** A premultiplied sRGB pixel as Core Graphics draws it into a linear-RGB buffer. */
        fun srgbToLinearPremultiplied(p: Int): Int {
            val a = p ushr 24
            if (a == 0) return 0
            return premultiply(unpremultiplyExact(p, true))
        }

        /** `Uint8ClampedArray`'s conversion: round half to even, clamped to 0 … 255. */
        fun clamped(v: Double): Int = if (v.isNaN() || v <= 0) 0 else if (v >= 255) 255 else Math.rint(v).toInt()

        /** Every channel of premultiplied [p] times [f] / 255, rounded. */
        fun scale(p: Int, f: Int): Int {
            if (f == 255) return p
            if (f == 0) return 0
            fun s(c: Int) = (c * f + 127) / 255
            return (s(p ushr 24) shl 24) or (s((p shr 16) and 0xFF) shl 16) or (s((p shr 8) and 0xFF) shl 8) or s(p and 0xFF)
        }

        fun add(p: Int, q: Int): Int {
            fun c(sh: Int) = min(255, ((p ushr sh) and 0xFF) + ((q ushr sh) and 0xFF))
            return (c(24) shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }

        /** Premultiplied source-over. */
        fun over(s: Int, d: Int): Int {
            val sa = s ushr 24
            if (sa == 255 || d == 0) return s
            if (sa == 0) return d
            return add(s, scale(d, 255 - sa))
        }
    }

    companion object {
        /** Output pixels below which a filter is run as one band: splitting it would cost more than it saves. */
        private const val MIN_BAND_PIXELS = 65_536

        /** The fewest rows a band has. */
        private const val MIN_BAND_ROWS = 32

        /** The filter workers: one per core, at most eight. */
        val WORKERS = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

        /**
         * The rows where the bands of [o] start, and its bottom: [bands] bands at most, none under [MIN_BAND_ROWS] rows,
         * their edges on the turbulence tiles' rows where they can be, so bands rarely need the same tile. One band
         * (two entries) when [o] is small or [bands] is 1.
         */
        internal fun bandTops(o: Rect, bands: Int): IntArray {
            if (bands <= 1 || o.width.toLong() * o.height < MIN_BAND_PIXELS) return intArrayOf(o.top, o.bottom)
            val n = min(bands, o.height / MIN_BAND_ROWS)
            if (n <= 1) return intArrayOf(o.top, o.bottom)
            val tops = ArrayList<Int>(n + 1)
            tops.add(o.top)
            val t = TurbulenceCache.T
            for (k in 1 until n) {
                val even = o.top + (o.height.toLong() * k / n).toInt()
                // the nearest tile row, when that leaves both neighbours at least MIN_BAND_ROWS rows
                val snapped = Math.floorDiv(even + t / 2, t) * t
                val at = if (o.height / n >= 2 * t) snapped else even
                if (at - tops.last() >= MIN_BAND_ROWS && o.bottom - at >= MIN_BAND_ROWS) tops.add(at)
            }
            tops.add(o.bottom)
            return tops.toIntArray()
        }

        /** True on a filter worker: work started there runs inline (a pool waiting on itself would deadlock). */
        private fun onWorker() = Thread.currentThread().name == WORKER_NAME

        /**
         * Runs [body] for 0 until [count], on the filter workers when there is more than one and this is not a worker,
         * and returns when all are done. An exception in any is thrown here.
         */
        internal fun parallel(count: Int, body: (Int) -> Unit) {
            if (count <= 0) return
            if (count == 1 || WORKERS == 1 || onWorker()) {
                for (i in 0 until count) body(i)
                return
            }
            val tasks = (0 until count).map { i -> java.util.concurrent.Callable { body(i) } }
            for (f in pool.invokeAll(tasks)) {
                try {
                    f.get()
                } catch (e: java.util.concurrent.ExecutionException) {
                    throw e.cause ?: e
                }
            }
        }

        /** Runs [body] over row ranges of a [height] × [width] image, in parallel when the image is large. */
        internal fun rows(height: Int, width: Int, body: (Int, Int) -> Unit) {
            val threads = WORKERS
            // small work, one core, or already on a worker: inline
            if (threads == 1 || height.toLong() * width < 32_768 || height < 2 * threads || onWorker()) {
                body(0, height)
                return
            }
            val chunk = (height + threads * 4 - 1) / (threads * 4)
            val n = (height + chunk - 1) / chunk
            parallel(n) { k -> body(k * chunk, min(height, (k + 1) * chunk)) }
        }

        private const val WORKER_NAME = "atlas-filter"

        /**
         * The filters' workers, at Android's background priority: a frame being made shares the cores with the screen and
         * must never starve it (on a 4-core phone a full-priority raster froze the app at launch, 16 October).
         */
        private val pool: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newFixedThreadPool(WORKERS) { r ->
                Thread({
                    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
                    r.run()
                }, WORKER_NAME).apply { isDaemon = true }
            }
        }

        private val STANDARD_INPUTS = setOf("BackgroundImage", "BackgroundAlpha", "FillPaint", "StrokePaint")

        /** WebKit's box size for a deviation in filter pixels: ⌊σ · 3√(2π)/4 + ½⌋, at least 2; 0 for no blur. */
        fun kernel(deviation: Double): Int {
            if (deviation <= 0.0) return 0
            val f = 3f / 4f * sqrt(2 * Math.PI.toFloat())
            return max(2, floor(deviation.toFloat() * f + 0.5f).toInt()).coerceAtMost(500)
        }

        /**
         * Three box blurs of [kx] × [ky] over premultiplied ARGB [buf]: when both sizes are equal, three square boxes of
         * the next odd size with the edge extended (vImage's `vImageBoxConvolve_ARGB8888`, one rounding per pass);
         * otherwise WebKit's separable passes with its offsets for even sizes, a transparent edge and truncation.
         */
        fun boxBlurs(buf: IntArray, w: Int, h: Int, kx: Int, ky: Int) {
            if (kx == ky && kx > 0) {
                val k = if (kx % 2 == 0) kx + 1 else kx
                val n = w * h
                val sums = Array(4) { IntArray(n) }
                val tmp = IntArray(n)
                repeat(3) { boxSquare(buf, tmp, w, h, k, sums) }
                return
            }
            val tmp = IntArray(buf.size)
            var from = buf
            var to = tmp
            var dxl = 0
            var dxr = 0
            var dyl = 0
            var dyr = 0
            var sizeX = kx
            var sizeY = ky
            for (i in 0 until 3) {
                if (sizeX > 0) {
                    val p = position(i, sizeX, dxl, dxr)
                    dxl = p[0]
                    dxr = p[1]
                    sizeX = p[2]
                    boxNone(from, to, w, h, sizeX, dxl, dxr, true)
                    val s = from
                    from = to
                    to = s
                }
                if (sizeY > 0) {
                    val p = position(i, sizeY, dyl, dyr)
                    dyl = p[0]
                    dyr = p[1]
                    sizeY = p[2]
                    boxNone(from, to, w, h, sizeY, dyl, dyr, false)
                    val s = from
                    from = to
                    to = s
                }
            }
            if (from !== buf) from.copyInto(buf)
        }

        /** WebKit's `kernelPosition`: the left and right reach of box [iteration] and its (possibly grown) size. */
        private fun position(iteration: Int, size: Int, left: Int, right: Int): IntArray {
            var l = left
            var r = right
            var s = size
            when (iteration) {
                0 -> if (s % 2 == 0) {
                    l = s / 2 - 1
                    r = s - l
                } else {
                    l = s / 2
                    r = s - l
                }
                1 -> if (s % 2 == 0) {
                    l++
                    r--
                }
                2 -> if (s % 2 == 0) {
                    r++
                    s++
                }
            }
            return intArrayOf(l, r, s)
        }

        /** One box pass with a transparent edge, along rows ([horizontal]) or columns, truncating like WebKit's NEON code. */
        private fun boxNone(src: IntArray, dst: IntArray, w: Int, h: Int, d: Int, left: Int, right: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val step = if (horizontal) 1 else w
            val maxKernel = min(right, len)
            val inv = 1f / d
            for (line in 0 until lines) {
                val base = if (horizontal) line * w else line
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in 0 until maxKernel) {
                    val p = src[base + i * step]
                    sa += p ushr 24
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                }
                for (x in 0 until len) {
                    val at = base + x * step
                    dst[at] = ((sa * inv).toInt() shl 24) or ((sr * inv).toInt() shl 16) or ((sg * inv).toInt() shl 8) or (sb * inv).toInt()
                    if (x >= left) {
                        val p = src[at - left * step]
                        sa -= p ushr 24
                        sr -= (p shr 16) and 0xFF
                        sg -= (p shr 8) and 0xFF
                        sb -= p and 0xFF
                    }
                    if (x + right < len) {
                        val p = src[at + right * step]
                        sa += p ushr 24
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                    }
                }
            }
        }

        /**
         * One k × k box (k odd), edge pixels repeated, the sum divided once with rounding; [buf] is replaced. [sums] is
         * scratch: four arrays of w · h.
         */
        private fun boxSquare(buf: IntArray, tmp: IntArray, w: Int, h: Int, k: Int, sums: Array<IntArray>) {
            val half = k / 2
            val area = k * k
            // Column sums per channel would overflow nothing: 255 · 1001 fits easily. First pass: horizontal sums.
            val ha = sums[0]
            val hr = sums[1]
            val hg = sums[2]
            val hb = sums[3]
            for (y in 0 until h) {
                val row = y * w
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -half..half) {
                    val p = buf[row + i.coerceIn(0, w - 1)]
                    sa += p ushr 24
                    sr += (p shr 16) and 0xFF
                    sg += (p shr 8) and 0xFF
                    sb += p and 0xFF
                }
                for (x in 0 until w) {
                    ha[row + x] = sa
                    hr[row + x] = sr
                    hg[row + x] = sg
                    hb[row + x] = sb
                    val out = buf[row + (x - half).coerceIn(0, w - 1)]
                    val inn = buf[row + (x + half + 1).coerceIn(0, w - 1)]
                    sa += (inn ushr 24) - (out ushr 24)
                    sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                    sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                    sb += (inn and 0xFF) - (out and 0xFF)
                }
            }
            val round = area / 2
            for (x in 0 until w) {
                var sa = 0
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -half..half) {
                    val at = i.coerceIn(0, h - 1) * w + x
                    sa += ha[at]
                    sr += hr[at]
                    sg += hg[at]
                    sb += hb[at]
                }
                for (y in 0 until h) {
                    tmp[y * w + x] = (((sa + round) / area) shl 24) or (((sr + round) / area) shl 16) or
                        (((sg + round) / area) shl 8) or ((sb + round) / area)
                    val out = (y - half).coerceIn(0, h - 1) * w + x
                    val inn = (y + half + 1).coerceIn(0, h - 1) * w + x
                    sa += ha[inn] - ha[out]
                    sr += hr[inn] - hr[out]
                    sg += hg[inn] - hg[out]
                    sb += hb[inn] - hb[out]
                }
            }
            tmp.copyInto(buf)
        }
    }
}
