package app.atlas.mapview.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/** Pixel comparison of a rendering against a browser's rasterisation of the same SVG, with pictures to look at. */
object SvgCompare {
    val repo = File(System.getProperty("atlas.repo") ?: "../../..")
    val cache = File(repo, "cache")
    val fonts = File(repo, "app/android/mapview/src/main/assets/svgfonts")

    /** Where the comparison pictures go (git-ignored). */
    val out: File = File(cache, "svg-android/compare").apply { mkdirs() }

    class Result(val name: String, val mean: Double, val worst: List<Tile>, val width: Int, val height: Int) {
        override fun toString() = "%-40s MAD %6.2f  worst %s".format(name, mean, worst.take(4).joinToString(" "))
    }

    /** A square of the picture and its mean absolute difference. */
    class Tile(val x: Int, val y: Int, val mean: Double) {
        override fun toString() = "(%d,%d)=%.1f".format(x, y, mean)
    }

    /** Renders [svg] with [SvgCanvas] at [scale] on white. */
    fun render(svg: File, scale: Float = 2f, mode: SvgCanvas.FilterMode = SvgCanvas.FilterMode.CPU): Pair<Bitmap, SvgCanvas> {
        val doc = SvgParser.parse(svg)
        val w = Math.round(doc.width * scale)
        val h = Math.round(doc.height * scale)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val sc = SvgCanvas(doc, SvgFonts.fromDirectory(fonts), mode)
        sc.draw(c, w.toFloat(), h.toFloat())
        return bmp to sc
    }

    /** The mean absolute difference over red, green and blue (0 … 255) and the worst [tile]-pixel squares. */
    fun compare(name: String, ours: Bitmap, ref: Bitmap, tile: Int = 64): Result {
        val w = minOf(ours.width, ref.width)
        val h = minOf(ours.height, ref.height)
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        ours.getPixels(a, 0, w, 0, 0, w, h)
        ref.getPixels(b, 0, w, 0, 0, w, h)
        val tw = (w + tile - 1) / tile
        val th = (h + tile - 1) / tile
        val sums = DoubleArray(tw * th)
        val counts = IntArray(tw * th)
        var total = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val p = a[y * w + x]
            val q = b[y * w + x]
            val d = (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))) / 3.0
            total += d
            val t = (y / tile) * tw + x / tile
            sums[t] += d
            counts[t]++
        }
        val tiles = (0 until tw * th).map { Tile((it % tw) * tile, (it / tw) * tile, sums[it] / counts[it]) }.sortedByDescending { it.mean }
        return Result(name, total / (w * h), tiles.take(8), w, h)
    }

    /** Writes reference | ours | difference (×4) side by side as `<name>.png` in [out]. */
    fun sideBySide(name: String, ours: Bitmap, ref: Bitmap): File {
        val w = minOf(ours.width, ref.width)
        val h = minOf(ours.height, ref.height)
        val all = Bitmap.createBitmap(w * 3, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(all)
        val p = Paint()
        c.drawBitmap(ref, 0f, 0f, p)
        c.drawBitmap(ours, w.toFloat(), 0f, p)
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        ours.getPixels(a, 0, w, 0, 0, w, h)
        ref.getPixels(b, 0, w, 0, 0, w, h)
        for (i in a.indices) {
            val d = minOf(255, 4 * (abs(Color.red(a[i]) - Color.red(b[i])) + abs(Color.green(a[i]) - Color.green(b[i])) + abs(Color.blue(a[i]) - Color.blue(b[i]))) / 3)
            a[i] = Color.rgb(d, d, d)
        }
        c.drawBitmap(Bitmap.createBitmap(a, w, h, Bitmap.Config.ARGB_8888), (2 * w).toFloat(), 0f, p)
        val f = File(out, "$name.png")
        FileOutputStream(f).use { all.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun load(f: File): Bitmap = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        ?: error("cannot read $f")

    fun save(b: Bitmap, f: File) {
        f.parentFile?.mkdirs()
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
