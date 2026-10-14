package app.atlas.mapview.svg

import kotlin.math.max
import kotlin.math.min

/**
 * Object bounding boxes, as SVG defines them: the fill geometry of a node in its own user space (inside its
 * `transform`), without strokes, filters or clips. They resolve `objectBoundingBox` units of gradients and filter
 * regions. Text has no measured box here; [textWidth] estimates its advance when a caller has no font at hand.
 */
class SvgBounds(private val doc: SvgDocument, private val textWidth: (SvgText) -> Float = { it.font.size * 0.55f * it.text.length }) {
    private val box = FloatArray(4)

    /** The bounding box of [node] as left, top, right, bottom in [out], or false when it has no geometry. */
    fun of(node: SvgNode, out: FloatArray): Boolean {
        box[0] = Float.POSITIVE_INFINITY
        box[1] = Float.POSITIVE_INFINITY
        box[2] = Float.NEGATIVE_INFINITY
        box[3] = Float.NEGATIVE_INFINITY
        add(node, null)
        if (box[0] > box[2] || box[1] > box[3]) return false
        box.copyInto(out)
        return true
    }

    /** Adds [node]'s geometry, mapped by [m] (its parent's transforms down to the box's space), to [box]. */
    private fun add(node: SvgNode, m: FloatArray?) {
        when (node) {
            is SvgGroup -> for (c in node.children) add(c, compose(m, c.transform))
            is SvgShape -> shape(node, m)
            is SvgText -> {
                val w = textWidth(node)
                val left = when (node.font.anchor) {
                    SvgFont.Anchor.START -> node.x
                    SvgFont.Anchor.MIDDLE -> node.x - w / 2
                    SvgFont.Anchor.END -> node.x - w
                }
                rect(left, node.y - node.font.size * 0.8f, left + w, node.y + node.font.size * 0.2f, m)
            }
        }
    }

    private fun shape(s: SvgShape, m: FloatArray?) {
        val g = s.geometry
        when (s.kind) {
            SvgShape.Kind.PATH -> {
                val c = doc.path.coords
                var k = s.coordStart
                val verbs = doc.path.verbs
                for (v in s.verbStart until s.verbEnd) {
                    if (verbs[v] == SvgPathData.CLOSE) continue
                    point(c[k], c[k + 1], m)
                    k += 2
                }
            }
            SvgShape.Kind.RECT -> rect(g[0], g[1], g[0] + g[2], g[1] + g[3], m)
            SvgShape.Kind.CIRCLE -> rect(g[0] - g[2], g[1] - g[2], g[0] + g[2], g[1] + g[2], m)
            SvgShape.Kind.ELLIPSE -> rect(g[0] - g[2], g[1] - g[3], g[0] + g[2], g[1] + g[3], m)
        }
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float, m: FloatArray?) {
        if (m == null) {
            point(l, t, null)
            point(r, b, null)
        } else {
            point(l, t, m)
            point(r, t, m)
            point(l, b, m)
            point(r, b, m)
        }
    }

    private fun point(x: Float, y: Float, m: FloatArray?) {
        val px = if (m == null) x else m[0] * x + m[2] * y + m[4]
        val py = if (m == null) y else m[1] * x + m[3] * y + m[5]
        box[0] = min(box[0], px)
        box[1] = min(box[1], py)
        box[2] = max(box[2], px)
        box[3] = max(box[3], py)
    }

    companion object {
        /** [outer] after [inner] (inner applies first), either of them possibly identity (null). */
        fun compose(outer: FloatArray?, inner: FloatArray?): FloatArray? {
            if (inner == null) return outer
            if (outer == null) return inner
            val a = outer
            val b = inner
            return floatArrayOf(
                a[0] * b[0] + a[2] * b[1],
                a[1] * b[0] + a[3] * b[1],
                a[0] * b[2] + a[2] * b[3],
                a[1] * b[2] + a[3] * b[3],
                a[0] * b[4] + a[2] * b[5] + a[4],
                a[1] * b[4] + a[3] * b[5] + a[5],
            )
        }
    }
}
