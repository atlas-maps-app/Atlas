package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import app.atlas.mapcore.state.LabelBlock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * The board's labels as sprites for the vector engine (plan-vector D13; `MapCore.labels`, Appendix D): each label drawn
 * with the device's own Skia by [LabelPainter.drawAt], the same paints, halos and whole-pixel anchor as the painter sets
 * it over the frame, into a bitmap at the density, cut to its pixels, with what the core's placement reads
 * (`CP/labels/Sprites.hpp`'s LabelSprite): the label's measures from the same paint and its points. The core puts a
 * sprite down so that its anchor lands where LabelPainter would draw the text, rounded as LabelPainter rounds it, so the
 * two are the same pixels (`LabelSpritesTest`). Each label is drawn a second time at the band's top
 * ([LabelBlock.BAND_TOP] times its size, by a painter at that many times the density), which the core draws the label
 * from while it is grown, so it stays sharp. A label's two images are kept for the next hand-over (by its kind, text and
 * face), so only labels new to the map are drawn again.
 */
internal class LabelSprites(val painter: LabelPainter) {
    /**
     * One label's sprite: its id, priority and points; its measures (half its text's width plus the pad, the pad, its
     * text's height, and (ascent + descent) / 2); its [own] image (LabelPainter's pixels) and its [large] one, each
     * premultiplied RGBA8 with R in the low byte as the core reads them, rows top to bottom, with where its text's centre
     * and baseline lie in it.
     */
    class Sprite(
        val id: Int,
        val priority: Int,
        val points: List<app.atlas.geo.LatLon>,
        val half: Float,
        val pad: Float,
        val textHeight: Float,
        val baseline: Float,
        val own: LabelBlock.Image,
        val large: LabelBlock.Image,
    )

    /** The painter of the large images: at [LabelBlock.BAND_TOP] times the density, in the painter's face. */
    private val largePainter = LabelPainter(painter.density * LabelBlock.BAND_TOP)

    private data class Key(val kind: LabelKind, val text: String, val face: Typeface)

    /** The images drawn, the least recently used first, and their texels. */
    private val images = LinkedHashMap<Key, Pair<LabelBlock.Image, LabelBlock.Image>>(64, 0.75f, true)
    private var keptTexels = 0L

    private val measures = FloatArray(4)
    private val extent = FloatArray(2)

    /** The sprite of [label], known to the core as [id] (one thread at a time). */
    fun sprite(label: MapLabel, id: Int): Sprite {
        painter.measures(label.kind, label.text, measures)
        val key = Key(label.kind, label.text, painter.typeface)
        val (own, large) = images[key] ?: run {
            largePainter.typeface = painter.typeface
            val made = image(painter, label.kind, label.text) to image(largePainter, label.kind, label.text)
            images[key] = made
            keptTexels += texels(made)
            // the least recently used go while the images kept are more than KEPT_TEXELS, never the one just drawn
            val eldest = images.entries.iterator()
            while (keptTexels > KEPT_TEXELS && images.size > 1) {
                val e = eldest.next()
                keptTexels -= texels(e.value)
                eldest.remove()
            }
            made
        }
        return Sprite(id, label.priority, label.points, measures[0], measures[1], measures[2], measures[3], own, large)
    }

    private fun texels(images: Pair<LabelBlock.Image, LabelBlock.Image>): Long =
        images.first.pixels.size.toLong() + images.second.pixels.size

    /** [text] in [kind]'s look as [p] draws it, cut to the pixels drawn, with where its text's centre and baseline lie. */
    private fun image(p: LabelPainter, kind: LabelKind, text: String): LabelBlock.Image {
        p.extent(kind, extent)
        // A canvas with room for the text, an italic's overhang (an em each side) and the halos' reach; drawn again with
        // more room should anything touch its edge, then cut to the pixels drawn.
        var margin = p.haloReach(kind) + ceil(extent[1] - extent[0]).toInt()
        while (true) {
            val w = ceil(p.textWidth(kind, text)).toInt() + 2 * margin
            val h = ceil(extent[1] - extent[0]).toInt() + 2 * margin
            val ax = w / 2
            val ay = margin + ceil(-extent[0]).toInt()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            p.drawAt(Canvas(bitmap), kind, text, ax, ay)
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
            if (x1 < 0) return LabelBlock.Image(1, 1, 0, 0, IntArray(1))
            if (x0 == 0 || y0 == 0 || x1 == w - 1 || y1 == h - 1) {
                margin *= 2
                continue
            }
            val sw = x1 - x0 + 1
            val sh = y1 - y0 + 1
            val cut = IntArray(sw * sh)
            for (y in 0 until sh) System.arraycopy(all, (y0 + y) * w + x0, cut, y * sw, sw)
            return LabelBlock.Image(sw, sh, ax - x0, ay - y0, cut)
        }
    }

    companion object {
        /**
         * The texels of the images kept for the next hand-over (16 MB): two or three sets of a screen's labels (a label
         * at density 2.625 takes about 22k at its own size and 50k at the band's top, `LabelSpritesTest`).
         */
        private const val KEPT_TEXELS = 4L shl 20

        /** A bitmap's pixels as Skia keeps them: premultiplied RGBA8, R in the low byte of each int. */
        fun pixelsOf(bitmap: Bitmap): IntArray {
            val bytes = ByteBuffer.allocate(bitmap.byteCount).order(ByteOrder.LITTLE_ENDIAN)
            bitmap.copyPixelsToBuffer(bytes)
            bytes.rewind()
            val out = IntArray(bitmap.width * bitmap.height)
            bytes.asIntBuffer().get(out)
            return out
        }

        /** A bitmap of [image]'s pixels (ARGB_8888, premultiplied as they are). */
        fun bitmapOf(image: LabelBlock.Image): Bitmap {
            val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val bytes = ByteBuffer.allocate(image.pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asIntBuffer().put(image.pixels)
            bitmap.copyPixelsFromBuffer(bytes)
            return bitmap
        }
    }
}
