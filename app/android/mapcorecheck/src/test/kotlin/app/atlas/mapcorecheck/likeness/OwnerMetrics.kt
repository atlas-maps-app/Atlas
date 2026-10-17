package app.atlas.mapcorecheck.likeness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** One owner measured: its reference and candidate pixels, their IoU, how far their centroids lie apart, the colour off. */
class OwnerResult(val owner: Owner, val refPixels: Int, val candPixels: Int, val iou: Double, val centroidPx: Double, val colourDiff: Double)

/** The per-owner metrics of one case (D16): every owner with enough reference pixels, the shares per class, the worst. */
class OwnerReport(
    val results: List<OwnerResult>,
    /** Per class, the share of its owners' reference pixels whose owner has IoU ≥ [OwnerMetrics.IOU]. */
    val shareByClass: Map<String, Double>,
    /** The same share over buildings and parts together: the OWNERS gate. */
    val builtShare: Double,
    /** The [OwnerMetrics.WORST] worst owners: lowest IoU, then largest colour difference. */
    val worst: List<OwnerResult>,
    /** False when the candidate gave no owner map: nothing but colour could be measured. */
    val candidateHasOwners: Boolean,
)

/**
 * D16's per-owner metrics, through the world tape's owner maps: for each OSM element with at least [MIN_PIXELS]
 * reference pixels, the IoU of its pixels in the reference's and the candidate's owner maps, the offset of their
 * centroids (device px) and the mean colour difference over its reference pixels (levels, over R, G and B). Owners are
 * matched by OSM type and id, not by index: each map numbers its own owners.
 */
object OwnerMetrics {
    const val MIN_PIXELS = 50
    const val IOU = 0.85
    const val WORST = 20

    /** The classes the OWNERS gate counts. */
    val BUILT = setOf("building", "part")

    fun of(ref: OwnerFrame, refPx: IntArray, cand: OwnerFrame?, candPx: IntArray): OwnerReport {
        val n = ref.owners.size
        val w = ref.frame.w
        val refCount = IntArray(n + 1); val candCount = IntArray(n + 1); val inter = IntArray(n + 1)
        val rx = DoubleArray(n + 1); val ry = DoubleArray(n + 1); val cx = DoubleArray(n + 1); val cy = DoubleArray(n + 1)
        val colour = LongArray(n + 1)
        // the candidate's owner indices as the reference's (by OSM type and id); unknown owners as 0
        val map: IntArray? = cand?.let { c ->
            val byKey = HashMap<String, Int>(n * 2)
            for ((i, o) in ref.owners.withIndex()) byKey[o.tag] = i + 1
            IntArray(c.owners.size + 1) { k -> if (k == 0) 0 else byKey[c.owners[k - 1].tag] ?: 0 }
        }
        val ri = ref.frame.px
        val ci = cand?.frame?.px
        for (i in ri.indices) {
            val r = ri[i]
            val x = i % w; val y = i / w
            if (r != 0) {
                refCount[r]++; rx[r] += x.toDouble(); ry[r] += y.toDouble()
                val a = refPx[i]; val b = candPx[i]
                colour[r] += (abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))).toLong()
            }
            if (ci != null && map != null) {
                val c = map[ci[i].coerceIn(0, map.size - 1)]
                if (c != 0) {
                    candCount[c]++; cx[c] += x.toDouble(); cy[c] += y.toDouble()
                    if (c == r) inter[c]++
                }
            }
        }
        val results = ArrayList<OwnerResult>()
        for (k in 1..n) {
            if (refCount[k] < MIN_PIXELS) continue
            val union = refCount[k] + candCount[k] - inter[k]
            val iou = if (cand == null) Double.NaN else if (union == 0) 0.0 else inter[k].toDouble() / union
            val off = if (cand == null || candCount[k] == 0) Double.NaN else hypot(cx[k] / candCount[k] - rx[k] / refCount[k], cy[k] / candCount[k] - ry[k] / refCount[k])
            results.add(OwnerResult(ref.owners[k - 1], refCount[k], candCount[k], iou, off, colour[k] / (3.0 * refCount[k])))
        }
        fun share(keep: (String) -> Boolean): Double {
            var tot = 0L; var good = 0L
            for (r in results) if (keep(r.owner.klass)) { tot += r.refPixels; if (r.iou >= IOU) good += r.refPixels }
            return if (tot == 0L) Double.NaN else good.toDouble() / tot
        }
        val classes = results.map { it.owner.klass }.toSortedSet()
        val byClass = classes.associateWith { k -> share { it == k } }
        val worst = results.sortedWith(compareBy<OwnerResult> { if (it.iou.isNaN()) -1.0 else it.iou }.thenByDescending { it.colourDiff }).take(WORST)
        return OwnerReport(results, byClass, if (cand == null) Double.NaN else share { it in BUILT }, worst, cand != null)
    }
}

class OwnerMetricsTest {
    private fun owners(vararg tags: Pair<String, String>) = tags.map { (t, k) -> Owner(t.substring(0, 1), t.substring(1).toLong(), k, k, 0) }

    @Test
    fun `IoU, centroid and colour per owner, matched by OSM id across the two maps' own numbering`() {
        val w = 40; val h = 20
        // the reference: building w1 at x 0..19, part w2 at x 20..39; the candidate numbers them the other way round
        // and draws the part 4 px too far left
        val ref = OwnerFrame(Frame(w, h, IntArray(w * h) { if (it % w < 20) 1 else 2 }), owners("w1" to "building", "w2" to "part"))
        val cand = OwnerFrame(Frame(w, h, IntArray(w * h) { if (it % w < 16) 2 else 1 }), owners("w2" to "part", "w1" to "building"))
        val px = IntArray(w * h) { 0xFF808080.toInt() }
        val r = OwnerMetrics.of(ref, px, cand, px)
        val b = r.results.first { it.owner.tag == "w1" }; val p = r.results.first { it.owner.tag == "w2" }
        assertEquals(16.0 / 20.0, b.iou, 1e-9)
        assertEquals(20.0 / 24.0, p.iou, 1e-9)
        assertEquals(2.0, b.centroidPx, 1e-9)
        assertEquals(0.0, b.colourDiff, 0.0)
        assertTrue(r.candidateHasOwners)
        // neither reaches 0.85: no built pixel counts
        assertEquals(0.0, r.builtShare, 0.0)
        assertEquals("w1", r.worst.first().owner.tag)
    }

    @Test
    fun `owners under 50 pixels are not measured, and no candidate map measures nothing`() {
        val w = 10; val h = 10
        val ref = OwnerFrame(Frame(w, h, IntArray(w * h) { if (it < 49) 1 else 2 }), owners("w1" to "building", "n2" to "tree"))
        val px = IntArray(w * h) { 0xFF000000.toInt() }
        val r = OwnerMetrics.of(ref, px, ref, px)
        assertEquals(listOf("n2"), r.results.map { it.owner.tag })
        assertTrue(r.builtShare.isNaN())
        val none = OwnerMetrics.of(ref, px, null, px)
        assertFalse(none.candidateHasOwners)
        assertTrue(none.results.all { it.iou.isNaN() })
    }
}
