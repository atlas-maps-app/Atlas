package app.atlas.mapview

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Coloured triangles in screen pixels, gathered and drawn with `drawVertices` in chunks of at most 65,535 vertices.
 * The arrays grow once and are reused from frame to frame.
 */
class TriangleBuffer(initialVertices: Int = 4096) {
    private var pos = FloatArray(initialVertices * 2)
    private var col = IntArray(initialVertices)
    private var idx = ShortArray(initialVertices * 3)
    private var vertices = 0
    private var indices = 0
    private val paint = Paint().apply { color = Color.WHITE; isAntiAlias = false }
    private var canvas: Canvas? = null

    /** Vertices drawn since [begin]. */
    var drawn = 0
        private set

    fun begin(canvas: Canvas) {
        this.canvas = canvas
        vertices = 0
        indices = 0
        drawn = 0
    }

    /** Room for [v] more vertices and [i] more indices; flushes first when a chunk would overflow. */
    fun reserve(v: Int, i: Int) {
        if (vertices + v > MAX_VERTICES) flush()
        if ((vertices + v) * 2 > pos.size) {
            val n = maxOf((vertices + v) * 2, pos.size * 2).coerceAtMost(MAX_VERTICES * 2)
            pos = pos.copyOf(n)
            col = col.copyOf(n / 2)
        }
        if (indices + i > idx.size) idx = idx.copyOf(maxOf(indices + i, idx.size * 2))
    }

    fun vertex(x: Float, y: Float, colour: Int): Int {
        pos[2 * vertices] = x
        pos[2 * vertices + 1] = y
        col[vertices] = colour
        return vertices++
    }

    fun triangle(a: Int, b: Int, c: Int) {
        idx[indices++] = a.toShort()
        idx[indices++] = b.toShort()
        idx[indices++] = c.toShort()
    }

    /** Draws what is gathered. */
    fun flush() {
        val c = canvas ?: return
        if (indices >= 3) {
            c.drawVertices(Canvas.VertexMode.TRIANGLES, vertices * 2, pos, 0, null, 0, col, 0, idx, 0, indices, paint)
            drawn += vertices
        }
        vertices = 0
        indices = 0
    }

    fun end() {
        flush()
        canvas = null
    }

    companion object {
        const val MAX_VERTICES = 65_535
    }
}
