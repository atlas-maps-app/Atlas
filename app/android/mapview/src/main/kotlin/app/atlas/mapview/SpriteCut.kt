package app.atlas.mapview

import android.graphics.Bitmap

/**
 * A sprite for the vector engine cut from the bitmap the app drew it on: the pixels drawn ([width] × [height],
 * premultiplied RGBA8 with R in the low byte, as the core reads them), and where the drawing's anchor lies in them.
 */
internal class SpriteCut(val width: Int, val height: Int, val anchorX: Int, val anchorY: Int, val pixels: IntArray) {
    companion object {
        /**
         * [bitmap]'s pixels cut to those drawn, its anchor at ([ax], [ay]) in it; a clear pixel where nothing was drawn;
         * null where a drawn pixel touches its edge (the drawing needs more room). Recycles the bitmap.
         */
        fun of(bitmap: Bitmap, ax: Int, ay: Int): SpriteCut? {
            val w = bitmap.width
            val h = bitmap.height
            val all = LabelSprites.pixelsOf(bitmap)
            bitmap.recycle()
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (all[y * w + x] == 0) continue
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
            if (x1 < 0) return SpriteCut(1, 1, 0, 0, IntArray(1))
            if (x0 == 0 || y0 == 0 || x1 == w - 1 || y1 == h - 1) return null
            val sw = x1 - x0 + 1
            val sh = y1 - y0 + 1
            val out = IntArray(sw * sh)
            for (y in 0 until sh) System.arraycopy(all, (y0 + y) * w + x0, out, y * sw, sw)
            return SpriteCut(sw, sh, ax - x0, ay - y0, out)
        }
    }
}
