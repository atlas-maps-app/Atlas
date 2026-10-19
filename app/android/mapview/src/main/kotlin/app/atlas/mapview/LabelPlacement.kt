package app.atlas.mapview

/**
 * The board's labels' placement (plan-vector D13), apart from their drawing so that the vector engine's port
 * (`CP/labels/Placement.cpp`) is held to it: each label, in priority order, at the first of its points where one of
 * [SPOTS] takes it, the first whose box (half its text's width and half its text's height, each with the pad) lies
 * within the frame and clear of the chrome's keep-out boxes, the markers and the labels placed before it. All in float,
 * as the core computes it; nothing allocates once the boxes have grown.
 */
internal class LabelPlacement {
    private var boxes = FloatArray(4 * 8)

    /** The boxes placed since [reset]. */
    var boxCount = 0
        private set

    /** Forgets the placed boxes (a new frame). */
    fun reset() {
        boxCount = 0
    }

    /** Box [i] of those placed: left, top, right, bottom. */
    fun box(i: Int, out: FloatArray) {
        System.arraycopy(boxes, 4 * i, out, 0, 4)
    }

    /**
     * Tries [SPOTS] about the point ([px], [py]) for a label of half width [half] (with the pad [pad]) and text height
     * [textH]: the first spot whose box lies within the [width] × [height] frame and clear of [keepOut] ([keepOutCount]
     * boxes), [markers] ([markerCount]) and the boxes placed so far takes it, its centre written to [out] and its box
     * placed; false when none does.
     */
    fun tryPoint(
        half: Float, pad: Float, textH: Float, px: Float, py: Float, width: Float, height: Float,
        keepOut: FloatArray, keepOutCount: Int, markers: FloatArray, markerCount: Int, out: FloatArray,
    ): Boolean {
        val halfH = textH / 2 + pad
        for (k in SPOTS.indices step 2) {
            val cx = px + SPOTS[k] * (half - pad)
            val cy = py + SPOTS[k + 1] * textH
            val l = cx - half
            val r = cx + half
            val t = cy - halfH
            val b = cy + halfH
            if (l < 0f || t < 0f || r > width || b > height) continue
            if (overlaps(keepOut, keepOutCount, l, t, r, b) || overlaps(markers, markerCount, l, t, r, b) || overlaps(boxes, boxCount, l, t, r, b)) continue
            out[0] = cx
            out[1] = cy
            add(l, t, r, b)
            return true
        }
        return false
    }

    private fun add(l: Float, t: Float, r: Float, b: Float) {
        if (4 * (boxCount + 1) > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
        boxes[4 * boxCount] = l; boxes[4 * boxCount + 1] = t; boxes[4 * boxCount + 2] = r; boxes[4 * boxCount + 3] = b
        boxCount++
    }

    private fun overlaps(list: FloatArray, n: Int, l: Float, t: Float, r: Float, b: Float): Boolean {
        for (i in 0 until n) {
            if (l < list[4 * i + 2] && r > list[4 * i] && t < list[4 * i + 3] && b > list[4 * i + 1]) return true
        }
        return false
    }

    companion object {
        /** Where a label may stand about its point: (x in half-widths less the pad, y in text heights), in the order tried. */
        val SPOTS = floatArrayOf(0f, 0f, 0f, -0.5f, -1f, 0f, 1f, 0f, -1f, -0.5f, 1f, -0.5f)
    }
}
