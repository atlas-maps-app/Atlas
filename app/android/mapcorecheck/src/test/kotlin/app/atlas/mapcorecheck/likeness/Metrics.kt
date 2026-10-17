package app.atlas.mapcorecheck.likeness

import app.atlas.anchor.RefMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * D16's frame metrics, through G1's `RefMetrics` (kept: the gates and the calibration floors were measured with it):
 * MAD over R, G and B; the share within 2 levels; the p99 of the largest channel difference; the worst 32 × 32 block;
 * the flat misses (structural only); the masked share. And the pictures the report shows of them.
 */
object Metrics {
    /** [cand] against [ref], both opaque ARGB [w] × [h], inside [mask] when given. */
    fun frame(cand: IntArray, ref: IntArray, w: Int, h: Int, mask: BooleanArray? = null): RefMetrics {
        require(cand.size == w * h && ref.size == w * h) { "frames of ${cand.size} and ${ref.size} pixels for $w × $h" }
        return RefMetrics.of(cand, ref, w, h, mask)
    }

    /**
     * A heat map of [cand] against [ref]: each pixel's largest channel difference, white at 0, yellow at 2 (the
     * within-2 line), orange at 8, red at 32 (a pop), black at 128 and beyond.
     */
    fun heat(cand: IntArray, ref: IntArray): IntArray = IntArray(cand.size) { i ->
        var d = 0
        for (s in 0 until 24 step 8) d = max(d, abs(((cand[i] ushr s) and 0xFF) - ((ref[i] ushr s) and 0xFF)))
        heatColour(d)
    }

    /** The heat map's colour for a difference of [d] levels. */
    fun heatColour(d: Int): Int {
        // stops: 0 white, 2 pale yellow, 8 orange, 32 red, 128 black
        val stops = intArrayOf(0, 2, 8, 32, 128)
        val cols = intArrayOf(0xFFFFFF, 0xFFF3A0, 0xFFA030, 0xE01010, 0x000000)
        if (d <= 0) return 0xFFFFFFFF.toInt()
        for (k in 1 until stops.size) {
            if (d <= stops[k]) {
                val t = (d - stops[k - 1]).toDouble() / (stops[k] - stops[k - 1])
                return (0xFF shl 24) or mix(cols[k - 1], cols[k], t)
            }
        }
        return 0xFF000000.toInt()
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** [px] ([w] × [h]) shrunk by an integer [k] with a box filter, for the report's thumbnails. */
    fun shrink(px: IntArray, w: Int, h: Int, k: Int): IntArray {
        val sw = w / k; val sh = h / k
        val out = IntArray(sw * sh)
        for (y in 0 until sh) for (x in 0 until sw) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0 until k) for (dx in 0 until k) {
                val p = px[(y * k + dy) * w + x * k + dx]
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val n = k * k
            out[y * sw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }
}

/** A case measured: its frame metrics, owners and labels, and the values its gates read. */
class CaseResult(
    val entry: RefEntry,
    val candidate: CandidateFrame,
    val frame: RefMetrics?,
    val owners: OwnerReport?,
    val labels: LabelReport?,
    val measured: Measured,
) {
    var judged: List<Judged> = emptyList()

    /** PASS, FAIL, or NONE (nothing measured); [enforced] says whether a FAIL fails the run. */
    val outcome: String get() = when {
        judged.isEmpty() -> "NONE"
        judged.any { it.outcome == "FAIL" } -> "FAIL"
        else -> "PASS"
    }
}

/** Measures a candidate frame against a reference entry. */
object Measure {
    /**
     * [cand] against [entry]'s reference: the frame metrics (flat misses only in structural mode), the owners' IoU
     * share of buildings and parts, the labels on the ground; [structuralMad] is the case's structural twin's MAD, for
     * a full-mode case's FULL gate.
     */
    fun case(entry: RefEntry, cand: CandidateFrame, structuralMad: Double? = null, refFrame: Frame = entry.ref,
             refOwners: OwnerFrame? = entry.owners, refLabels: List<Label>? = entry.labels): CaseResult {
        val m = Measured(entry.name)
        if (cand.px == null) {
            m.notes.add("the candidate drew nothing: ${cand.note}")
            return CaseResult(entry, cand, null, null, null, m)
        }
        require(cand.w == refFrame.w && cand.h == refFrame.h) { "${entry.name}: the candidate is ${cand.w} × ${cand.h}, the reference ${refFrame.w} × ${refFrame.h}" }
        val f = Metrics.frame(cand.px, refFrame.px, refFrame.w, refFrame.h)
        m["mad"] = f.mad; m["within2"] = f.within2; m["p99"] = f.p99.toDouble(); m["block"] = f.block
        // flat misses are a structural measure (D16); the FULL gate reads full mode against its structural twin
        if (entry.mode == "structural") m["flat"] = f.flatMisses else m.na.add("flat")
        if (entry.mode == "full") { if (structuralMad != null) m["madOverStructural"] = f.mad - structuralMad } else m.na.add("madOverStructural")
        var owners: OwnerReport? = null
        if (refOwners != null) {
            owners = OwnerMetrics.of(refOwners, refFrame.px, cand.owners, cand.px)
            val built = owners.results.any { it.owner.klass in OwnerMetrics.BUILT }
            when {
                !built -> { m.na.add("ownersIoU"); m.notes.add("no building or part with ${OwnerMetrics.MIN_PIXELS} reference pixels to measure") }
                owners.candidateHasOwners -> m["ownersIoU"] = owners.builtShare
                else -> m.notes.add("the candidate gave no owner map")
            }
        }
        var labels: LabelReport? = null
        if (refLabels != null) {
            labels = LabelMetrics.of(refLabels, cand.labels)
            if (cand.labels != null) {
                m["labelMissing"] = labels.groundMissing.toDouble()
                m["labelPx"] = labels.groundMaxPx
                m["labelDeg"] = labels.groundMaxDeg
            } else {
                m.notes.add("the candidate gave no label list")
            }
        }
        return CaseResult(entry, cand, f, owners, labels, m)
    }
}

class MetricsTest {
    private fun frame(w: Int, h: Int, f: (Int, Int) -> Int) = IntArray(w * h) { f(it % w, it / w) or (0xFF shl 24) }

    @Test
    fun `the frame metrics are G1's, a frame against itself is perfect`() {
        val w = 96; val h = 64
        val a = frame(w, h) { x, y -> if (x < 48) 0x405060 else ((x * 3) shl 16) or ((y * 2) shl 8) or 0x80 }
        val m = Metrics.frame(a, a, w, h)
        assertEquals(0.0, m.mad, 0.0); assertEquals(1.0, m.within2, 0.0); assertEquals(0, m.p99); assertEquals(0.0, m.block, 0.0); assertEquals(0.0, m.flatMisses, 0.0)
        // every pixel 9 levels off in red: MAD 3, none within 2, p99 9
        val b = IntArray(a.size) { a[it] + (if (((a[it] shr 16) and 0xFF) < 246) 9 shl 16 else -(9 shl 16)) }
        val m2 = Metrics.frame(b, a, w, h)
        assertEquals(3.0, m2.mad, 1e-9); assertEquals(0.0, m2.within2, 0.0); assertEquals(9, m2.p99)
    }

    @Test
    fun `the heat map's colours rise with the difference`() {
        assertEquals(0xFFFFFFFF.toInt(), Metrics.heatColour(0))
        assertEquals(0xFF000000.toInt(), Metrics.heatColour(200))
        val red = (Metrics.heatColour(32) shr 16) and 0xFF; val green = (Metrics.heatColour(32) shr 8) and 0xFF
        assertTrue(red > 200 && green < 40)
        assertEquals(0xFF102030.toInt(), Metrics.shrink(IntArray(16) { 0xFF102030.toInt() }, 4, 4, 2)[3])
    }
}
