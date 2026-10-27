package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Canvas
import app.atlas.geo.LatLon
import app.atlas.mapcore.state.MarkerBlock
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max

/**
 * The map's markers as sprites for the vector engine (ENGINE.md step 6; `MapCore.markers`): each marker's look drawn
 * with the device's own Skia by [MarkerPainter.draw], the same paints, shadows and symbols as the painter draws the
 * marker over the frame, into a bitmap at the density with the marker's place on a whole pixel of it, cut to its
 * pixels; once a look (markers that look alike share it), and a named pin's twice, its name right of the pin and left
 * of it (the core picks the side as the painter does, by where the pin stands). A cluster is the core's own bubble with
 * its count over it, the count drawn here by [MarkerPainter.drawCount] centred on a whole pixel, for each count the
 * markers can make (every count up to all of theirs, [MAX_COUNTS] at most) in each ink a cluster writes its count in.
 * With them goes what the core places the markers by (MarkerLayout's inputs). Looks and counts are kept for the next
 * hand-over, so only those new to the map are drawn again. One thread at a time.
 */
internal class MarkerSprites(val painter: MarkerPainter) {
    private data class Look(val marker: Marker, val left: Boolean)
    private data class Count(val key: Int, val ink: Int)

    /** The images drawn, the least recently used first, and their texels. */
    private val looks = LinkedHashMap<Look, MarkerBlock.Image>(64, 0.75f, true)
    private val counts = LinkedHashMap<Count, MarkerBlock.Image>(64, 0.75f, true)
    private var keptTexels = 0L
    private val box = FloatArray(4)

    /** [markers] as a block for the core. */
    fun block(markers: List<Marker>): ByteBuffer {
        // the symbols and brands the looks draw with, loaded first: a look is drawn once, and drawing finds only those
        // loaded already
        val names = HashSet<String>()
        val logos = HashSet<String>()
        MarkerPainter.symbolsOf(markers, names, logos)
        if (!Icons.ready(names, logos)) Icons.warm(names, logos)
        val lookList = ArrayList<MarkerBlock.Image>()
        val lookIndex = HashMap<Look, Int>()
        fun lookOf(m: Marker, left: Boolean): Int {
            val key = Look(m.copy(id = "", at = ORIGIN, priority = 0, place = null), left)
            return lookIndex.getOrPut(key) {
                lookList += looks[key] ?: image(key.marker, left).also { keep(looks, key, it) }
                lookList.size - 1
            }
        }
        val out = markers.map { m ->
            val colour = m.colour.toInt() or (0xFF shl 24)
            val look = if (m.style == MarkerStyle.CLUSTER) MarkerBlock.NO_LOOK else lookOf(m, false)
            val left = if (m.style == MarkerStyle.PIN && m.label != null) lookOf(m, true) else look
            MarkerBlock.Marker(
                m.at.lat, m.at.lon, m.priority, m.style.ordinal, m.selected, m.value != null, max(1, m.count ?: 1),
                painter.chipTextWidth(m), painter.nameWidth(m), colour, MarkerPainter.onColour(colour), look, left,
            )
        }
        // the clusters' counts: every count the markers can make, in each ink a dot or a cluster writes it in
        val inks = markers.asSequence().filter { it.style == MarkerStyle.DOT || it.style == MarkerStyle.CLUSTER }
            .map { MarkerPainter.onColour(it.colour.toInt() or (0xFF shl 24)) }.distinct().toList()
        val total = markers.sumOf { max(1, it.count ?: 1).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val keys = countKeys(total)
        val countList = ArrayList<MarkerBlock.Count>(inks.size * keys.size)
        for (ink in inks) {
            for (k in keys) {
                val key = Count(k, ink)
                countList += MarkerBlock.Count(ink, k, counts[key] ?: countImage(k, ink).also { keep(counts, key, it) })
            }
        }
        return MarkerBlock.write(painter.density, lookList, out, countList)
    }

    /** [image] kept as [key] in [map]; the least recently used go while the images kept are more than [KEPT_TEXELS]. */
    private fun <K> keep(map: LinkedHashMap<K, MarkerBlock.Image>, key: K, image: MarkerBlock.Image) {
        map[key] = image
        keptTexels += image.pixels.size
        for (m in listOf(looks, counts)) {
            val eldest = m.entries.iterator()
            while (keptTexels > KEPT_TEXELS && eldest.hasNext()) {
                val e = eldest.next()
                if (e.value === image) continue
                keptTexels -= e.value.pixels.size
                eldest.remove()
            }
        }
    }

    /** Marker [m] as itself, as [MarkerPainter.draw] draws it, its name on the left where [left]; cut to its pixels. */
    private fun image(m: Marker, left: Boolean): MarkerBlock.Image {
        MarkerLayout.boxOf(m, 0f, 0f, painter.metrics, painter.chipWidth, box)
        // room for the box, a name either side of the pin, and what reaches past them (shadows, rings, badges)
        val name = painter.nameWidth(m).let { if (it < 0f) 0f else painter.metrics.pinDiameter(m.selected) / 2 + it + 8f * painter.density }
        var margin = ceil(REACH_DP * painter.density).toInt()
        while (true) {
            val ax = margin + ceil(max(-box[0], name)).toInt()
            val ay = margin + ceil(-box[1]).toInt()
            val w = ax + ceil(max(box[2], name)).toInt() + margin
            val h = ay + ceil(box[3]).toInt() + margin
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            painter.viewWidth = if (left) 0f else Float.MAX_VALUE
            painter.draw(Canvas(bitmap), m, ax.toFloat(), ay.toFloat(), 0)
            painter.viewWidth = Float.MAX_VALUE
            cut(bitmap, ax, ay)?.let { return it }
            margin *= 2
        }
    }

    /** Count [key]'s text in [ink], as [MarkerPainter.drawCount] writes it over a cluster's bubble; cut to its pixels. */
    private fun countImage(key: Int, ink: Int): MarkerBlock.Image {
        var margin = ceil(COUNT_DP * painter.density).toInt()
        while (true) {
            val w = 2 * margin
            val bitmap = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888)
            painter.drawCount(Canvas(bitmap), key, margin.toFloat(), margin.toFloat(), ink)
            cut(bitmap, margin, margin)?.let { return it }
            margin *= 2
        }
    }

    /**
     * [bitmap]'s pixels cut to those drawn, with its anchor ([ax], [ay]) in them; null where a drawn pixel touches its
     * edge (it needs more room). Recycles the bitmap.
     */
    private fun cut(bitmap: Bitmap, ax: Int, ay: Int): MarkerBlock.Image? {
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
        if (x1 < 0) return MarkerBlock.Image(1, 1, 0, 0, IntArray(1))
        if (x0 == 0 || y0 == 0 || x1 == w - 1 || y1 == h - 1) return null
        val sw = x1 - x0 + 1
        val sh = y1 - y0 + 1
        val out = IntArray(sw * sh)
        for (y in 0 until sh) System.arraycopy(all, (y0 + y) * w + x0, out, y * sw, sw)
        return MarkerBlock.Image(sw, sh, ax - x0, ay - y0, out)
    }

    companion object {
        private val ORIGIN = LatLon(0.0, 0.0)

        /** How far a look reaches past its box (the chosen pin's shadow: three sigmas of 8 dp, 6 dp lower), dp. */
        private const val REACH_DP = 32f

        /** Half the room a count's text is drawn in first, dp (four figures at 12 dp fit). */
        private const val COUNT_DP = 24f

        /** The counts drawn for an ink at most: every count up to 2,047 of them (the k's above 999 count once each). */
        const val MAX_COUNTS = 2048

        /** The images kept for the next hand-over at most (16 MB). */
        private const val KEPT_TEXELS = 4L shl 20

        /**
         * The keys of every count from 1 to [total] ([MarkerBlock.countKey]: one for each text MarkerPainter.countLabel
         * writes), the lowest first, [MAX_COUNTS] at most.
         */
        fun countKeys(total: Int): List<Int> {
            val out = ArrayList<Int>()
            var n = 1
            while (n <= total && out.size < MAX_COUNTS) {
                val k = MarkerBlock.countKey(n)
                out += k
                // the next count whose text differs
                n = when {
                    n < 1000 -> n + 1
                    n < 10_000 -> k + 100
                    k >= 999_000 -> break
                    else -> k + 1000
                }
            }
            return out
        }
    }
}
