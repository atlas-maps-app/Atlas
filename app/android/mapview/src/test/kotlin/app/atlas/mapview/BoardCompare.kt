package app.atlas.mapview

import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Compares a render of the map with the board picture, pixel for pixel at 824 × 1830: side by side, a 50 % blend, the
 * screen cut into 8 × 16 crops each paired with the board's, and the mean colour of each kind of surface. The reference
 * is the board's map alone (`cache/look-ref/map_2x.png`, its engine layers without the UI).
 */
object BoardCompare {
    const val W = 824
    const val H = 1830
    const val COLS = 8
    const val ROWS = 16

    /** An image as packed RGB pixels. */
    class Img(val w: Int, val h: Int, val px: IntArray) {
        operator fun get(x: Int, y: Int) = px[y * w + x]
    }

    fun read(f: File): Img {
        val b = ImageIO.read(f)
        val out = IntArray(b.width * b.height)
        b.getRGB(0, 0, b.width, b.height, out, 0, b.width)
        for (i in out.indices) out[i] = out[i] and 0xFFFFFF
        return Img(b.width, b.height, out)
    }

    fun write(img: Img, f: File) {
        val b = BufferedImage(img.w, img.h, BufferedImage.TYPE_INT_RGB)
        b.setRGB(0, 0, img.w, img.h, img.px, 0, img.w)
        f.parentFile?.mkdirs()
        ImageIO.write(b, "png", f)
    }

    /**
     * Parts of the screen left out of every number (left, top, right, bottom, in pixels): none for the map-alone
     * reference, which has no overlays.
     */
    var excluded: List<IntArray> = emptyList()
        set(v) { field = v; maskCache = null }

    private var maskCache: BooleanArray? = null

    private val mask: BooleanArray
        get() = maskCache ?: BooleanArray(W * H).also { m ->
            for (r in excluded) for (y in max(0, r[1]) until min(H, r[3])) for (x in max(0, r[0]) until min(W, r[2])) m[y * W + x] = true
            maskCache = m
        }

    /** True where the reference shows the map itself. */
    fun isMap(x: Int, y: Int) = !mask[y * W + x]

    fun sideBySide(board: Img, ours: Img, f: File) {
        val out = IntArray(2 * W * H)
        for (y in 0 until H) for (x in 0 until W) {
            out[y * 2 * W + x] = board[x, y]
            out[y * 2 * W + W + x] = ours[x, y]
        }
        write(Img(2 * W, H, out), f)
    }

    fun blend(board: Img, ours: Img, f: File) {
        val out = IntArray(W * H) { i -> mix(board.px[i], ours.px[i], 0.5f) }
        write(Img(W, H, out), f)
    }

    /** The 8 × 16 grid of crops, board left and ours right in each pair, written as four bands of four rows. */
    fun pairs(board: Img, ours: Img, dir: File) {
        val cw = W / COLS
        val ch = H / ROWS
        val rowsPerBand = 4
        for (band in 0 until ROWS / rowsPerBand) {
            val bw = COLS * (2 * cw + 6)
            val bh = rowsPerBand * (ch + 6)
            val out = IntArray(bw * bh) { 0xFFFFFF }
            for (r in 0 until rowsPerBand) for (c in 0 until COLS) {
                val row = band * rowsPerBand + r
                val ox = c * (2 * cw + 6)
                val oy = r * (ch + 6)
                for (y in 0 until ch) for (x in 0 until cw) {
                    val sx = c * cw + x
                    val sy = row * ch + y
                    out[(oy + y) * bw + ox + x] = board[sx, sy]
                    out[(oy + y) * bw + ox + cw + 2 + x] = ours[sx, sy]
                }
            }
            write(Img(bw, bh, out), File(dir, "pairs_$band.png"))
        }
    }

    /** Kinds of surface, told apart by colour the same way in both pictures. */
    enum class Kind(val test: (Int, Int, Int) -> Boolean) {
        WATER({ r, g, b -> b > r + 18 && b >= g - 4 && b > 70 }),
        ROOF_TILE({ r, g, b -> r > 150 && r - g > 45 && r - b > 55 }),
        WALL({ r, g, b -> r - b > 18 && r < 165 && luma(r, g, b) < 120 && !(r > 150 && r - g > 45) }),
        FLAT_ROOF({ r, g, b -> max3(r, g, b) - min3(r, g, b) < 22 && luma(r, g, b) in 55f..150f }),
        STREET({ r, g, b -> luma(r, g, b) > 190f && max3(r, g, b) - min3(r, g, b) < 34 }),
        TREE({ r, g, b -> g > r + 10 && g > b + 4 }),
    }

    private fun max3(a: Int, b: Int, c: Int) = max(a, max(b, c))
    private fun min3(a: Int, b: Int, c: Int) = min(a, min(b, c))
    private fun luma(r: Int, g: Int, b: Int) = 0.299f * r + 0.587f * g + 0.114f * b

    fun kindOf(c: Int): Kind? {
        val r = (c shr 16) and 255
        val g = (c shr 8) and 255
        val b = c and 255
        for (k in Kind.entries) if (k.test(r, g, b)) return k
        return null
    }

    /** A mean colour and how many pixels it is of. */
    class Mean(var r: Long = 0, var g: Long = 0, var b: Long = 0, var n: Long = 0) {
        fun add(c: Int) { r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ }
        val colour: Int get() = if (n == 0L) 0 else ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        override fun toString() = if (n == 0L) "   -   " else "#%06X".format(colour)
    }

    /** Screen regions (board pixels) whose mean colour is compared whole. */
    val areas: Map<String, IntArray> = linkedMapOf(
        "top band (rows 0-120)" to intArrayOf(0, 0, W, 120),
        "far third (rows 0-610)" to intArrayOf(0, 0, W, 610),
        "middle third (610-1220)" to intArrayOf(0, 610, W, 1220),
        "near third (1220-1830)" to intArrayOf(0, 1220, W, H),
        "whole map" to intArrayOf(0, 0, W, H),
    )

    /**
     * Mean colours: of each [Kind] of surface (and its share of the map) in both pictures, of the quay edges (pixels
     * beside water that are not water), and of whole [areas]; with the distance between board and ours in RGB.
     */
    fun regions(board: Img, ours: Img): String {
        val sb = StringBuilder()
        sb.append("region                      board    share   ours     share   dRGB\n")
        val kb = HashMap<Kind, Mean>(); val ko = HashMap<Kind, Mean>()
        var total = 0L
        for (y in 0 until H) for (x in 0 until W) {
            if (!isMap(x, y)) continue
            total++
            kindOf(board[x, y])?.let { kb.getOrPut(it) { Mean() }.add(board[x, y]) }
            kindOf(ours[x, y])?.let { ko.getOrPut(it) { Mean() }.add(ours[x, y]) }
        }
        for (k in Kind.entries) {
            val a = kb[k] ?: Mean(); val b = ko[k] ?: Mean()
            sb.append(line(k.name.lowercase(), a, b, total))
        }
        sb.append(line("quay edge (beside water)", quay(board), quay(ours), 0))
        for ((name, r) in areas) {
            val a = Mean(); val b = Mean()
            for (y in r[1] until r[3]) for (x in r[0] until r[2]) {
                if (!isMap(x, y)) continue
                a.add(board[x, y]); b.add(ours[x, y])
            }
            sb.append(line(name, a, b, 0))
        }
        return sb.toString()
    }

    private fun line(name: String, a: Mean, b: Mean, total: Long): String {
        val sa = if (total > 0) "%5.1f%%".format(Locale.ROOT, 100.0 * a.n / total) else "      "
        val so = if (total > 0) "%5.1f%%".format(Locale.ROOT, 100.0 * b.n / total) else "      "
        val d = if (a.n > 0 && b.n > 0) "%5.1f".format(Locale.ROOT, distance(a.colour, b.colour)) else "  -"
        return "%-26s  %s  %s  %s  %s  %s\n".format(Locale.ROOT, name, a, sa, b, so, d)
    }

    /** Pixels within 3 px of water that are not water themselves: the quay edges and banks. */
    private fun quay(img: Img): Mean {
        val water = BooleanArray(W * H)
        for (y in 0 until H) for (x in 0 until W) water[y * W + x] = kindOf(img[x, y]) == Kind.WATER
        val m = Mean()
        for (y in 3 until H - 3) for (x in 3 until W - 3) {
            if (water[y * W + x] || !isMap(x, y)) continue
            var near = false
            loop@ for (dy in -3..3) for (dx in -3..3) if (water[(y + dy) * W + x + dx]) { near = true; break@loop }
            if (near) m.add(img[x, y])
        }
        return m
    }

    /** The mean colour of each of the 8 × 16 cells in both pictures and their distance, worst first. */
    fun cells(board: Img, ours: Img): String {
        val cw = W / COLS
        val ch = H / ROWS
        val rows = ArrayList<Pair<Double, String>>()
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val a = Mean(); val b = Mean()
            for (y in r * ch until (r + 1) * ch) for (x in c * cw until (c + 1) * cw) {
                if (!isMap(x, y)) continue
                a.add(board[x, y]); b.add(ours[x, y])
            }
            if (a.n < cw * ch / 4) continue
            val d = distance(a.colour, b.colour)
            rows += d to "cell r%02d c%d  board %s  ours %s  d %5.1f\n".format(Locale.ROOT, r, c, a, b, d)
        }
        val mean = rows.map { it.first }.average()
        return "mean cell distance %.1f\n".format(Locale.ROOT, mean) + rows.sortedByDescending { it.first }.joinToString("") { it.second }
    }

    fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 255) - ((b shr 16) and 255)
        val dg = ((a shr 8) and 255) - ((b shr 8) and 255)
        val db = (a and 255) - (b and 255)
        return sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val r = (((a shr 16) and 255) * (1 - t) + ((b shr 16) and 255) * t).toInt()
        val g = (((a shr 8) and 255) * (1 - t) + ((b shr 8) and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    /** The sum of absolute differences of two pictures' greys over the map, per pixel: how well edges line up. */
    fun meanAbsDiff(board: Img, ours: Img): Double {
        var s = 0L; var n = 0L
        for (y in 0 until H) for (x in 0 until W) {
            if (!isMap(x, y)) continue
            val a = board[x, y]; val b = ours[x, y]
            s += abs(luma((a shr 16) and 255, (a shr 8) and 255, a and 255) - luma((b shr 16) and 255, (b shr 8) and 255, b and 255)).toLong()
            n++
        }
        return s.toDouble() / n
    }
}
