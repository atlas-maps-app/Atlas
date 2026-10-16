package app.atlas.mapview

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Sizes of the marker styles in board px (dp), scaled to pixels by [density], as the Main board draws its places: pins
 * are 30 px discs centred on the place (the Anne Frank Huis), the chosen one the 44 px reservation pin on a 10 px stem
 * over a 4 px shadow (De Belhamel); brands 34 px tiles (Bagels & Beans); chips 32 px pills (the charging point).
 */
class MarkerMetrics(val density: Float) {
    fun pinDiameter(selected: Boolean) = (if (selected) 44f else 30f) * density

    /** The chosen pin's stem and the shadow under it; a pin that is not chosen sits on its place. */
    fun pinStem(selected: Boolean) = (if (selected) 10f else 0f) * density
    fun pinFoot(selected: Boolean) = (if (selected) 2f else 0f) * density
    fun dotDiameter(selected: Boolean) = (if (selected) 26f else 20f) * density
    fun brandSide(selected: Boolean) = (if (selected) 40f else 34f) * density
    fun clusterDiameter(count: Int) = min(46f, 28f + 6f * log10(max(1, count).toFloat())) * density
    fun chipHeight(selected: Boolean) = (if (selected) 36f else 32f) * density

    /** The chip's padding: 5 px before its symbol disc, 4 px after it, 9 px after the value. */
    val chipPad = 5f * density
    val chipGap = 4f * density
    val chipEnd = 9f * density
    val chipDisc = 24f * density
    val valueChipHeight = 18f * density
    val valueChipPad = 7f * density
    val valueChipGap = 3f * density
    val ring = 3f * density
    val border = 2f * density
    val badge = 20f * density

    /** The smallest a marker's touch target is, either way. */
    val touch = 44f * density

    /** The chip's width for a value [textWidth] pixels wide: padding, symbol disc, gap, value, end padding. */
    fun chipWidth(selected: Boolean, textWidth: Float): Float =
        chipPad + chipDisc + (if (textWidth > 0f) chipGap + textWidth + chipEnd else chipPad)
}

/**
 * Where markers go this frame: each one's box around its anchor, placed by priority (selected first, then higher
 * [Marker.priority], then list order). A marker that overlaps one already placed folds into it; a placed dot or cluster
 * that folded others shows as a cluster bubble with their count, other styles simply hide the losers.
 *
 * Arrays are reused between frames; call [layout] once per frame, then read the results.
 */
class MarkerLayout {
    private var order = IntArray(0)
    private var keys = LongArray(0)

    /** Placed markers in draw order (lowest priority first), as indices into the marker list. */
    var placed = IntArray(0)
        private set
    var placedCount = 0
        private set

    /** Each placed marker's box: left, top, right, bottom, in pixels. */
    var boxes = FloatArray(0)
        private set

    /** Each placed marker's cluster count: 0 when it is drawn as itself. */
    var clusterCount = IntArray(0)
        private set

    private var folded = IntArray(0)
    private val grid = BoxGrid()
    private val box = FloatArray(4)

    /**
     * Places [markers] whose anchors are at ([xs], [ys]) where [visible]; [chipText] gives the width of a chip's text.
     * The view is [width] × [height] pixels.
     */
    fun layout(
        markers: List<Marker>, xs: FloatArray, ys: FloatArray, visible: BooleanArray, metrics: MarkerMetrics,
        width: Float, height: Float, chipText: (Marker) -> Float,
    ) {
        val n = markers.size
        if (order.size < n) {
            order = IntArray(n); keys = LongArray(n); placed = IntArray(n); boxes = FloatArray(4 * n)
            clusterCount = IntArray(n); folded = IntArray(n)
        }
        // Sort keys: selected, then priority (both descending), then the list order.
        var m = 0
        for (i in 0 until n) {
            if (!visible[i]) continue
            val mk = markers[i]
            val pr = (mk.priority.toLong() + Int.MAX_VALUE.toLong() + 1).coerceIn(0, 0xFFFFFFFFL)
            val sel = if (mk.selected) 1L else 0L
            keys[m++] = ((1 - sel) shl 62) or ((0xFFFFFFFFL - pr) shl 24) or i.toLong()
        }
        java.util.Arrays.sort(keys, 0, m)
        grid.reset(width, height, 64f * metrics.density)
        var count = 0
        val placedOrder = order
        for (k in 0 until m) {
            val i = (keys[k] and 0xFFFFFF).toInt()
            val mk = markers[i]
            boxOf(mk, xs[i], ys[i], metrics, chipText, box)
            val hit = grid.firstOverlap(box[0], box[1], box[2], box[3], boxes)
            if (hit >= 0) {
                folded[hit] += mk.count ?: 1
                continue
            }
            placedOrder[count] = i
            boxes[4 * count] = box[0]; boxes[4 * count + 1] = box[1]; boxes[4 * count + 2] = box[2]; boxes[4 * count + 3] = box[3]
            folded[count] = 0
            grid.add(count, box[0], box[1], box[2], box[3])
            count++
        }
        // Draw order: lowest priority first, so the most important ends on top; boxes and counts follow.
        placedCount = count
        for (a in 0 until count / 2) {
            val b = count - 1 - a
            val t = placedOrder[a]; placedOrder[a] = placedOrder[b]; placedOrder[b] = t
            val f = folded[a]; folded[a] = folded[b]; folded[b] = f
            for (q in 0 until 4) { val v = boxes[4 * a + q]; boxes[4 * a + q] = boxes[4 * b + q]; boxes[4 * b + q] = v }
        }
        for (j in 0 until count) {
            val mk = markers[placedOrder[j]]
            placed[j] = placedOrder[j]
            clusterCount[j] = when {
                mk.style == MarkerStyle.CLUSTER -> (mk.count ?: 1) + folded[j]
                mk.style == MarkerStyle.DOT && folded[j] > 0 -> (mk.count ?: 1) + folded[j]
                else -> 0
            }
        }
    }

    /**
     * The placed marker under ([x], [y]), top-most first, its target grown to the touch size; -1 for none. Returns the
     * index into the marker list.
     */
    fun hit(x: Float, y: Float, metrics: MarkerMetrics): Int {
        for (j in placedCount - 1 downTo 0) {
            val l = boxes[4 * j]; val t = boxes[4 * j + 1]; val r = boxes[4 * j + 2]; val b = boxes[4 * j + 3]
            val cx = (l + r) / 2; val cy = (t + b) / 2
            val hw = max((r - l) / 2, metrics.touch / 2)
            val hh = max((b - t) / 2, metrics.touch / 2)
            if (x >= cx - hw && x <= cx + hw && y >= cy - hh && y <= cy + hh) return placed[j]
        }
        return -1
    }

    companion object {
        /** The box of [m] anchored at ([x], [y]) into [out] (left, top, right, bottom). */
        fun boxOf(m: Marker, x: Float, y: Float, mm: MarkerMetrics, chipText: (Marker) -> Float, out: FloatArray) {
            when (m.style) {
                MarkerStyle.PIN -> {
                    val d = mm.pinDiameter(m.selected)
                    // centred on the place, or the chosen pin standing on its stem
                    val bottom = y + if (m.selected) mm.pinFoot(true) else d / 2
                    val top = if (m.selected) y - mm.pinFoot(true) - mm.pinStem(true) - d else y - d / 2
                    out[0] = x - d / 2; out[1] = top; out[2] = x + d / 2; out[3] = bottom
                    if (m.value != null) {
                        val w = chipText(m) / 2 + mm.valueChipPad
                        out[0] = min(out[0], x - w); out[2] = max(out[2], x + w)
                        out[3] = bottom + mm.valueChipGap + mm.valueChipHeight
                    }
                }
                MarkerStyle.DOT -> {
                    val d = mm.dotDiameter(m.selected) / 2
                    out[0] = x - d; out[1] = y - d; out[2] = x + d; out[3] = y + d
                }
                MarkerStyle.BRAND -> {
                    val s = mm.brandSide(m.selected)
                    out[0] = x - s / 2; out[1] = y - s / 2; out[2] = x + s / 2; out[3] = y + s / 2
                }
                MarkerStyle.CLUSTER -> {
                    val d = mm.clusterDiameter(m.count ?: 1) / 2
                    out[0] = x - d; out[1] = y - d; out[2] = x + d; out[3] = y + d
                }
                MarkerStyle.CHIP -> {
                    val w = mm.chipWidth(m.selected, chipText(m))
                    val h = mm.chipHeight(m.selected)
                    out[0] = x - w / 2; out[1] = y - h / 2; out[2] = x + w / 2; out[3] = y + h / 2
                }
            }
        }
    }
}

/** A uniform grid of boxes over the view, for quick overlap queries; no allocation once grown. */
internal class BoxGrid {
    private var cols = 1
    private var rows = 1
    private var cell = 64f
    private var head = IntArray(1)
    private var next = IntArray(256)
    private var item = IntArray(256)
    private var used = 0
    private var originX = 0f
    private var originY = 0f

    /** Clears the grid for a view of [width] × [height] (boxes may reach a view's width beyond it on every side). */
    fun reset(width: Float, height: Float, cellSize: Float) {
        cell = max(8f, cellSize)
        originX = -width
        originY = -height
        cols = max(1, (3 * width / cell).toInt() + 1)
        rows = max(1, (3 * height / cell).toInt() + 1)
        if (head.size < cols * rows) head = IntArray(cols * rows)
        java.util.Arrays.fill(head, 0, cols * rows, -1)
        used = 0
    }

    fun add(index: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        val c0 = col(x0); val c1 = col(x1); val r0 = row(y0); val r1 = row(y1)
        for (r in r0..r1) for (c in c0..c1) {
            if (used == next.size) { next = next.copyOf(used * 2); item = item.copyOf(used * 2) }
            val k = r * cols + c
            item[used] = index
            next[used] = head[k]
            head[k] = used
            used++
        }
    }

    /** The first stored box (by [boxes], 4 floats each) that overlaps the query box, or -1. */
    fun firstOverlap(x0: Float, y0: Float, x1: Float, y1: Float, boxes: FloatArray): Int {
        val c0 = col(x0); val c1 = col(x1); val r0 = row(y0); val r1 = row(y1)
        var best = -1
        for (r in r0..r1) for (c in c0..c1) {
            var e = head[r * cols + c]
            while (e >= 0) {
                val j = item[e]
                val b = 4 * j
                if (x1 > boxes[b] && x0 < boxes[b + 2] && y1 > boxes[b + 1] && y0 < boxes[b + 3]) {
                    if (best < 0 || j < best) best = j
                }
                e = next[e]
            }
        }
        return best
    }

    private fun col(x: Float) = ((x - originX) / cell).toInt().coerceIn(0, cols - 1)
    private fun row(y: Float) = ((y - originY) / cell).toInt().coerceIn(0, rows - 1)
}
