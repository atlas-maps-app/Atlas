package app.atlas.ui.home

import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Compares a full-home screenshot with the Main board's picture (`cache/look-ref/board_2x.png`, 824 × 1830, its map
 * and UI): side by side, a 50 % blend, crop pairs of the overlay regions (the board above, ours below) and the mean
 * colour difference in each.
 */
internal object BoardShots {
    /** The board with its UI at the phone's 2×, on the owner's machine only. */
    val reference: File get() = File(System.getProperty("atlas.repo") ?: "../..", "cache/look-ref/board_2x.png")

    /** A named part of the screen, in dp on the board's 412 × 915. */
    class Region(val name: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** The overlays' parts of the screen. */
    val regions = listOf(
        Region("top_left", 0, 36, 160, 200),
        Region("rail", 330, 36, 412, 300),
        Region("bottom_row", 0, 690, 412, 760),
        Region("search", 0, 760, 412, 832),
        Region("tabs", 0, 832, 412, 915),
    )

    fun read(f: File): BufferedImage = ImageIO.read(f)

    private fun write(img: BufferedImage, f: File) {
        f.parentFile?.mkdirs()
        ImageIO.write(img, "png", f)
    }

    /** Writes the comparison images into [out] and returns one line per region: the mean |Δ| of R, G and B. */
    fun compare(board: BufferedImage, ours: BufferedImage, out: File): List<String> {
        val w = minOf(board.width, ours.width)
        val h = minOf(board.height, ours.height)
        val side = BufferedImage(w * 2, h, BufferedImage.TYPE_INT_RGB)
        val blend = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) {
            val a = board.getRGB(x, y)
            val b = ours.getRGB(x, y)
            side.setRGB(x, y, a)
            side.setRGB(w + x, y, b)
            blend.setRGB(x, y, mix(a, b))
        }
        write(side, File(out, "side_by_side.png"))
        write(blend, File(out, "blend.png"))
        val scale = w / 412.0
        return regions.map { r ->
            val x0 = (r.left * scale).toInt()
            val y0 = (r.top * scale).toInt()
            val x1 = minOf(w, (r.right * scale).toInt())
            val y1 = minOf(h, (r.bottom * scale).toInt())
            val pair = BufferedImage(x1 - x0, (y1 - y0) * 2 + 4, BufferedImage.TYPE_INT_RGB)
            var dr = 0L
            var dg = 0L
            var db = 0L
            for (y in y0 until y1) for (x in x0 until x1) {
                val a = board.getRGB(x, y)
                val b = ours.getRGB(x, y)
                pair.setRGB(x - x0, y - y0, a)
                pair.setRGB(x - x0, y - y0 + (y1 - y0) + 4, b)
                dr += abs(((a shr 16) and 255) - ((b shr 16) and 255))
                dg += abs(((a shr 8) and 255) - ((b shr 8) and 255))
                db += abs((a and 255) - (b and 255))
            }
            for (x in 0 until x1 - x0) for (y in 0 until 4) pair.setRGB(x, (y1 - y0) + y, 0xFF00FF)
            write(pair, File(out, "pair_${r.name}.png"))
            val n = ((x1 - x0) * (y1 - y0)).toDouble()
            String.format(Locale.ROOT, "%-11s mean |Δ| R %5.1f G %5.1f B %5.1f", r.name, dr / n, dg / n, db / n)
        }.also { File(out, "regions.txt").writeText(it.joinToString("\n") + "\n") }
    }

    private fun mix(a: Int, b: Int): Int {
        val r = (((a shr 16) and 255) + ((b shr 16) and 255)) / 2
        val g = (((a shr 8) and 255) + ((b shr 8) and 255)) / 2
        val bl = ((a and 255) + (b and 255)) / 2
        return (r shl 16) or (g shl 8) or bl
    }
}
