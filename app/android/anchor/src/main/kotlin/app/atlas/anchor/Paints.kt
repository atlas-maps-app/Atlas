package app.atlas.anchor

import app.atlas.mapview.svg.SvgBounds
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgGradient
import app.atlas.mapview.svg.SvgNode
import app.atlas.mapview.svg.SvgPaint
import app.atlas.mapview.svg.SvgPattern
import app.atlas.mapview.svg.SvgUnits

/**
 * The anchor's paints (plan-gpu D3, "Paints"): flat colours, and gradients and patterns evaluated per fragment through
 * a paint transform that maps anchor screen CSS px to the gradient's or pattern's own space. For bounding-box units the
 * box is the element's at the anchor's camera (as `SvgCanvas` measures it), and the element's and gradient's own
 * transforms are folded in, so at the anchor's camera a fragment's paint is exact, and in motion it is read at the
 * fragment's anchor-screen position.
 *
 * Paints are shared: equal colours and equal paint transforms give one entry.
 */
internal class PaintTable {
    private val kind = ByteList()
    private val argb = IntList()
    private val ref = IntList()
    private val opacity = FloatList()
    private val transform = FloatList()
    private val index = HashMap<PaintKey, Int>()

    /** Gradients by (document, id), in first use. */
    val gradients = ArrayList<SvgGradient>()
    private val gradientIndex = HashMap<Pair<Int, String>, Int>()

    /** Patterns by (document, id, resolved tile), in first use. */
    val patterns = ArrayList<AnchorPattern>()
    private val patternIndex = HashMap<String, Int>()

    private data class PaintKey(val kind: Byte, val argb: Int, val ref: Int, val opacity: Float, val t: List<Float>)

    val size: Int get() = kind.size

    private fun add(k: Byte, c: Int, r: Int, op: Float, t: FloatArray): Int {
        val key = PaintKey(k, c, r, op, t.toList())
        return index.getOrPut(key) {
            kind.add(k); argb.add(c); ref.add(r); opacity.add(op)
            for (v in t) transform.add(v)
            kind.size - 1
        }
    }

    private val colors = LongIntMap(256)

    /** A flat colour (ARGB as the SVG writes it) at [op]acity. */
    fun color(c: Int, op: Float): Int {
        val k = (c.toLong() shl 32) or (java.lang.Float.floatToRawIntBits(op).toLong() and 0xFFFFFFFFL)
        val have = colors.get(k)
        if (have >= 0) return have
        return add(PaintKind.COLOR, c, -1, op, IDENTITY).also { colors.put(k, it) }
    }

    fun isOpaque(p: Int): Boolean = when (kind[p]) {
        PaintKind.COLOR -> (argb[p] ushr 24) == 0xFF && opacity[p] >= 1f
        PaintKind.GRADIENT -> opacity[p] >= 1f && gradients[ref[p]].colors.all { (it ushr 24) == 0xFF }
        else -> false
    }

    /**
     * The paint of [paint] at [op]acity on [node] of document [docIndex] ([doc]), whose full transform to the anchor's
     * screen is [ctm]; −1 when nothing is drawn (`none`, a missing reference without fallback, or a box-relative paint on
     * an element without area), as `SvgCanvas` decides.
     */
    fun resolve(paint: SvgPaint, op: Float, node: SvgNode, ctm: FloatArray?, docIndex: Int, doc: SvgDocument, bounds: SvgBounds): Int {
        when (paint) {
            SvgPaint.None -> return -1
            is SvgPaint.Color -> return if (op > 0f) color(paint.argb, op) else -1
            is SvgPaint.Ref -> {
                val g = doc.gradients[paint.id]
                if (g != null) return gradient(g, op, node, ctm, docIndex, bounds)
                val pt = doc.patterns[paint.id]
                if (pt != null) return pattern(pt, op, node, ctm, docIndex, doc, bounds)
                return resolve(paint.fallback, op, node, ctm, docIndex, doc, bounds)
            }
        }
    }

    private fun gradient(g: SvgGradient, op: Float, node: SvgNode, ctm: FloatArray?, docIndex: Int, bounds: SvgBounds): Int {
        if (g.offsets.isEmpty()) return -1
        if (g.offsets.size == 1 || degenerate(g)) return if (op > 0f) color(g.colors[g.colors.size - 1], op) else -1
        if (op <= 0f) return -1
        var m: FloatArray? = ctm
        if (g.units == SvgUnits.BOUNDING_BOX) {
            val box = FloatArray(4)
            if (!bounds.of(node, box)) return -1
            val w = box[2] - box[0]; val h = box[3] - box[1]
            if (w <= 0f || h <= 0f) return -1
            m = SvgBounds.compose(m, floatArrayOf(w, 0f, 0f, h, box[0], box[1]))
        }
        m = SvgBounds.compose(m, g.transform)
        val inv = invert(m) ?: return -1
        val gi = gradientIndex.getOrPut(docIndex to g.id) { gradients.add(g); gradients.size - 1 }
        return add(PaintKind.GRADIENT, 0, gi, op, inv)
    }

    private fun degenerate(g: SvgGradient): Boolean {
        val k = g.coords
        return if (g.radial) k[2] <= 0f else k[0] == k[2] && k[1] == k[3]
    }

    private fun pattern(pt: SvgPattern, op: Float, node: SvgNode, ctm: FloatArray?, docIndex: Int, doc: SvgDocument, bounds: SvgBounds): Int {
        var x = pt.x; var y = pt.y; var w = pt.width; var h = pt.height
        if (pt.units == SvgUnits.BOUNDING_BOX) {
            val box = FloatArray(4)
            if (!bounds.of(node, box)) return -1
            val bw = box[2] - box[0]; val bh = box[3] - box[1]
            x = box[0] + x * bw; y = box[1] + y * bh; w *= bw; h *= bh
        }
        if (w <= 0f || h <= 0f || op <= 0f) return -1
        // anchor screen to the tile's own space: the tile spans 0 … w, 0 … h and repeats
        var m = SvgBounds.compose(ctm, pt.transform)
        m = SvgBounds.compose(m, floatArrayOf(1f, 0f, 0f, 1f, x, y))
        val inv = invert(m) ?: return -1
        val key = "$docIndex:${pt.id}:$w:$h"
        val pi = patternIndex.getOrPut(key) {
            patterns.add(AnchorPattern(pt.id, SvgWriter.patternTile(pt, w, h, doc)))
            patterns.size - 1
        }
        return add(PaintKind.PATTERN, 0, pi, op, inv)
    }

    fun build(): AnchorPaints = AnchorPaints(kind.toArray(), argb.toArray(), ref.toArray(), opacity.toArray(), transform.toArray())

    companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

        /** The inverse of affine [m] (`a b c d e f`; null is identity), or null when it has none. */
        fun invert(m: FloatArray?): FloatArray? {
            if (m == null) return IDENTITY.copyOf()
            val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble(); val d = m[3].toDouble()
            val e = m[4].toDouble(); val f = m[5].toDouble()
            val det = a * d - b * c
            if (det == 0.0 || det.isNaN()) return null
            val ia = d / det; val ib = -b / det; val ic = -c / det; val id = a / det
            return floatArrayOf(ia.toFloat(), ib.toFloat(), ic.toFloat(), id.toFloat(), (-(ia * e + ic * f)).toFloat(), (-(ib * e + id * f)).toFloat())
        }
    }
}

/** A growable list of bytes. */
internal class ByteList(capacity: Int = 64) {
    private var a = ByteArray(capacity)
    var size = 0
        private set

    fun add(v: Byte) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Byte = a[i]
    fun toArray(): ByteArray = a.copyOf(size)
}

/** A growable list of floats. */
internal class FloatList(capacity: Int = 64) {
    private var a = FloatArray(capacity)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int): Float = a[i]
    fun toArray(): FloatArray = a.copyOf(size)
}
