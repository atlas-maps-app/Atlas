package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * The board's labels as sprites for the vector engine (plan-vector D13; `MapCore.labels`, Appendix D): each label drawn
 * once with the device's own Skia by [LabelPainter.drawAt], the same paints, halos and whole-pixel anchor as the
 * painter sets it over the frame, into a bitmap at the density, cut to its pixels, with what the core's placement
 * reads (`CP/labels/Sprites.hpp`'s LabelSprite): the label's measures from the same paint and its points. The core
 * puts a sprite down so that its anchor lands where LabelPainter would draw the text, rounded as LabelPainter rounds
 * it, so the two are the same pixels (`LabelSpritesTest`).
 */
internal class LabelSprites(private val painter: LabelPainter) {
    /**
     * One label's sprite: its id, priority and points; its measures (half its text's width plus the pad, the pad, its
     * text's height, and (ascent + descent) / 2); its [width] × [height] pixels, premultiplied RGBA8 with R in the low
     * byte as the core reads them, rows top to bottom; where its text's centre and baseline lie in it.
     */
    class Sprite(
        val id: Int,
        val priority: Int,
        val points: List<app.atlas.geo.LatLon>,
        val half: Float,
        val pad: Float,
        val textHeight: Float,
        val baseline: Float,
        val width: Int,
        val height: Int,
        val anchorX: Int,
        val anchorY: Int,
        val pixels: IntArray,
    )

    private val measures = FloatArray(4)
    private val extent = FloatArray(2)

    /** The sprite of [label], known to the core as [id]. */
    fun sprite(label: MapLabel, id: Int): Sprite {
        painter.measures(label.kind, label.text, measures)
        painter.extent(label.kind, extent)
        // A canvas with room for the text, an italic's overhang (an em each side) and the halos' reach; drawn again with
        // more room should anything touch its edge, then cut to the pixels drawn.
        var margin = painter.haloReach(label.kind) + ceil(extent[1] - extent[0]).toInt()
        while (true) {
            val w = ceil(painter.textWidth(label.kind, label.text)).toInt() + 2 * margin
            val h = ceil(extent[1] - extent[0]).toInt() + 2 * margin
            val ax = w / 2
            val ay = margin + ceil(-extent[0]).toInt()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            painter.drawAt(Canvas(bitmap), label.kind, label.text, ax, ay)
            val all = pixelsOf(bitmap)
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
            if (x1 < 0) return Sprite(id, label.priority, label.points, measures[0], measures[1], measures[2], measures[3], 1, 1, 0, 0, IntArray(1))
            if (x0 == 0 || y0 == 0 || x1 == w - 1 || y1 == h - 1) {
                margin *= 2
                continue
            }
            val sw = x1 - x0 + 1
            val sh = y1 - y0 + 1
            val cut = IntArray(sw * sh)
            for (y in 0 until sh) System.arraycopy(all, (y0 + y) * w + x0, cut, y * sw, sw)
            return Sprite(id, label.priority, label.points, measures[0], measures[1], measures[2], measures[3], sw, sh, ax - x0, ay - y0, cut)
        }
    }

    companion object {
        /** A bitmap's pixels as Skia keeps them: premultiplied RGBA8, R in the low byte of each int. */
        fun pixelsOf(bitmap: Bitmap): IntArray {
            val bytes = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.LITTLE_ENDIAN)
            bitmap.copyPixelsToBuffer(bytes)
            bytes.rewind()
            val out = IntArray(bitmap.width * bitmap.height)
            bytes.asIntBuffer().get(out)
            return out
        }

        /** A bitmap of [sprite]'s pixels (ARGB_8888, premultiplied as they are). */
        fun bitmapOf(sprite: Sprite): Bitmap {
            val bitmap = Bitmap.createBitmap(sprite.width, sprite.height, Bitmap.Config.ARGB_8888)
            val bytes = ByteBuffer.allocate(sprite.pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asIntBuffer().put(sprite.pixels)
            bitmap.copyPixelsFromBuffer(bytes)
            return bitmap
        }
    }
}
