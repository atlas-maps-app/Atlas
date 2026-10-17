package app.atlas.mapcorecheck.likeness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/** A reference text and the candidate's text matched to it (null: missing), with the centre error, angle error and scale. */
class LabelMatch(val ref: Label, val cand: Label?, val centrePx: Double, val angleDeg: Double, val scale: Double)

/** D16's label metrics of one case. */
class LabelReport(val matches: List<LabelMatch>, val missing: List<Label>, val extra: List<Label>) {
    private val ground: List<LabelMatch> get() = matches.filter { it.ref.layer == "ground" }

    /** Names on the ground (canal and water names) the reference shows in the frame and the candidate lacks. */
    val groundMissing: Int get() = missing.count { it.layer == "ground" }

    /** The largest centre error of a matched name on the ground, device px (0 when none matched). */
    val groundMaxPx: Double get() = ground.filter { it.cand != null }.maxOfOrNull { it.centrePx } ?: 0.0

    /** The largest angle error of a matched name on the ground, degrees. */
    val groundMaxDeg: Double get() = ground.filter { it.cand != null }.maxOfOrNull { it.angleDeg } ?: 0.0
}

/**
 * Matches the candidate's texts to the reference's (D16): by string, then nearest centre first. Only texts whose ink
 * shows in the reference frame count. Angles are compared modulo 180°: a canal name the core turns to read upright
 * (the owner's Q3) lies on the same line as the render's. The centre is the ink's centre in device px.
 */
object LabelMetrics {
    fun of(ref: List<Label>, cand: List<Label>?): LabelReport {
        val shown = ref.filter { it.visible }
        if (cand == null) return LabelReport(shown.map { LabelMatch(it, null, Double.NaN, Double.NaN, Double.NaN) }, shown, emptyList())
        val matches = ArrayList<LabelMatch>()
        val missing = ArrayList<Label>()
        val extra = ArrayList<Label>()
        val byText = cand.groupBy { it.text }
        for ((text, rs) in shown.groupBy { it.text }) {
            val cs = byText[text].orEmpty().toMutableList()
            // nearest pairs first
            val pairs = ArrayList<Triple<Int, Int, Double>>()
            for ((i, r) in rs.withIndex()) for ((j, c) in cs.withIndex()) pairs.add(Triple(i, j, hypot(r.cx - c.cx, r.cy - c.cy)))
            pairs.sortBy { it.third }
            val usedR = BooleanArray(rs.size); val usedC = BooleanArray(cs.size)
            for ((i, j, d) in pairs) {
                if (usedR[i] || usedC[j]) continue
                usedR[i] = true; usedC[j] = true
                val r = rs[i]; val c = cs[j]
                matches.add(LabelMatch(r, c, d, angle(r.angle, c.angle), if (r.size > 0) c.size / r.size else Double.NaN))
            }
            for ((i, r) in rs.withIndex()) if (!usedR[i]) { missing.add(r); matches.add(LabelMatch(r, null, Double.NaN, Double.NaN, Double.NaN)) }
            for ((j, c) in cs.withIndex()) if (!usedC[j]) extra.add(c)
        }
        for ((text, cs) in byText) if (shown.none { it.text == text }) extra.addAll(cs)
        return LabelReport(matches, missing, extra)
    }

    /** The difference of two baseline angles modulo 180°, in 0 … 90. */
    fun angle(a: Double, b: Double): Double {
        var d = abs(a - b) % 180.0
        if (d > 90.0) d = 180.0 - d
        return d
    }
}

class LabelMetricsTest {
    private fun l(t: String, x: Double, y: Double, a: Double, layer: String = "ground", visible: Boolean = true) = Label(t, x, y, a, 26.0, layer, visible)

    @Test
    fun `names match by string and nearest centre, upright turns count as the same line, missing and extra are listed`() {
        val ref = listOf(l("SINGEL", 100.0, 200.0, 30.0), l("SINGEL", 900.0, 1200.0, -150.0), l("HERENGRACHT", 500.0, 500.0, 10.0), l("OFF", 5000.0, 5000.0, 0.0, visible = false), l("12", 10.0, 10.0, 0.0, "object"))
        val cand = listOf(l("SINGEL", 901.0, 1200.0, 30.5), l("SINGEL", 100.0, 200.5, 30.0), l("KEIZERSGRACHT", 1.0, 1.0, 0.0), l("12", 10.0, 10.0, 0.0, "object"))
        val r = LabelMetrics.of(ref, cand)
        assertEquals(1, r.groundMissing)
        assertEquals("HERENGRACHT", r.missing.single().text)
        assertEquals(listOf("KEIZERSGRACHT"), r.extra.map { it.text })
        assertEquals(1.0, r.groundMaxPx, 1e-9)
        assertEquals("−150° and 30.5° lie on one line, 0.5° apart", 0.5, r.groundMaxDeg, 1e-9)
        assertEquals(0.0, LabelMetrics.angle(-179.0, 1.0), 1e-9)
        assertEquals(90.0, LabelMetrics.angle(0.0, 90.0), 1e-9)
    }

    @Test
    fun `a candidate with no label list misses every name it shows`() {
        val r = LabelMetrics.of(listOf(l("SINGEL", 1.0, 1.0, 0.0), l("X", 0.0, 0.0, 0.0, visible = false)), null)
        assertEquals(1, r.groundMissing)
        assertTrue(r.extra.isEmpty())
    }
}
