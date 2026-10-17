package app.atlas.likeness

import app.atlas.anchor.RefMetrics
import app.atlas.render.py.PyFmt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Characters [range] of layer [layer] (an index into the render's layers) left out of the text. */
data class Cut(val layer: Int, val range: IntRange)

/** Edits of a render's SVG text: whole elements found and cut out, as the feature ladder and the plants need. */
object SvgEdit {
    /** The end (exclusive) of the element that starts at [i] (its `<`): after its `/>`, or after its matching close tag. */
    fun elementEnd(s: String, i: Int): Int {
        val e = SvgScan.tagEnd(s, i)
        require(e > 0) { "no element at $i" }
        if (s[e - 1] == '/') return e + 1
        var depth = 1
        var j = s.indexOf('<', e + 1)
        while (j >= 0) {
            if (s.startsWith("<!--", j)) { j = s.indexOf('<', s.indexOf("-->", j + 4) + 3); continue }
            val te = SvgScan.tagEnd(s, j)
            if (te < 0) break
            if (s[j + 1] == '/') {
                depth--
                if (depth == 0) return te + 1
            } else if (s[te - 1] != '/') {
                depth++
            }
            j = s.indexOf('<', te + 1)
        }
        throw IllegalArgumentException("the element at $i never closes")
    }

    /** The element whose start tag begins with [prefix], searched from [from] up to [to], as its whole range; or null. */
    fun element(s: String, prefix: String, from: Int = 0, to: Int = s.length): IntRange? {
        val i = s.indexOf(prefix, from)
        if (i < 0 || i >= to) return null
        return i until elementEnd(s, i)
    }

    /** Every element named [name] between [from] and [to], whole. */
    fun elements(s: String, name: String, from: Int = 0, to: Int = s.length): List<IntRange> {
        val out = ArrayList<IntRange>()
        val open = "<$name"
        var i = s.indexOf(open, from)
        while (i in 0 until to) {
            val c = s.getOrNull(i + open.length)
            if (c == ' ' || c == '>' || c == '/') {
                val e = elementEnd(s, i)
                out.add(i until e)
                i = s.indexOf(open, e)
            } else {
                i = s.indexOf(open, i + 1)
            }
        }
        return out
    }

    /** The elements in [from] until [to] whose start tag holds [attr] (such as `fill="url(#glass)"`), whole. */
    fun elementsWith(s: String, attr: String, from: Int, to: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var k = s.indexOf(attr, from)
        while (k in 0 until to) {
            val i = s.lastIndexOf('<', k)
            val e = elementEnd(s, i)
            if (out.isEmpty() || i > out.last().last) out.add(i until e)
            k = s.indexOf(attr, e)
        }
        return out
    }

    /** [layers] with [cuts] left out (each layer's cuts sorted and merged). */
    fun apply(layers: List<String>, cuts: List<Cut>): List<String> = layers.mapIndexed { li, s ->
        val rs = cuts.filter { it.layer == li }.map { it.range }.sortedBy { it.first }
        if (rs.isEmpty()) return@mapIndexed s
        val sb = StringBuilder(s.length)
        var at = 0
        for (r in rs) {
            if (r.last < at) continue
            if (r.first > at) sb.append(s, at, r.first)
            at = maxOf(at, r.last + 1)
        }
        sb.append(s, at, s.length)
        sb.toString()
    }
}

/**
 * Where a render's features lie in its layers' text: the items the world tape logged (M1b) by kind, the windows (the
 * `glass` fills), the sun shadows (the group `overlays` writes with the theme's `shadow_op` and `shadow`), the water's
 * reflections (the `ripple` group), the texts on the ground (canal and water names).
 */
class Features(val made: Oracle.Made) {
    private val layers = made.layers
    val scans: List<SvgScan.Result> = layers.map { SvgScan.scan(it) }
    private val items = ItemTable.of(made.tape)

    /** Where each layer's objects content starts in the items' character count. */
    private val bases: IntArray = IntArray(layers.size).also { b ->
        var acc = 0
        for (i in layers.indices) { b[i] = acc; acc += scans[i].objectsLength }
    }

    /**
     * Item [k]'s characters, as ranges of the layers that hold them: one, or two when the objects were split into two
     * images in the middle of the item's fragments (`obj_split` cuts the fragment list by length, not by item).
     */
    fun itemCuts(k: Int): List<Cut> {
        var from = items.first[k]
        val end = from + items.count[k]
        val out = ArrayList<Cut>(1)
        for (li in layers.indices) {
            val sc = scans[li]
            if (sc.objectsStart < 0 || from >= end) continue
            val lo = bases[li]; val hi = lo + sc.objectsLength
            if (from < lo || from >= hi) continue
            val to = minOf(end, hi)
            out.add(Cut(li, sc.objectsStart + from - lo until sc.objectsStart + to - lo))
            from = to
        }
        return out
    }

    /** Item [k]'s characters when they lie in one layer, else null. */
    fun itemCut(k: Int): Cut? = itemCuts(k).singleOrNull()

    /** The items whose kind [keep] accepts. */
    fun items(keep: (String) -> Boolean): List<Cut> = (0 until items.size).filter { keep(items.kind[it]) }.flatMap { itemCuts(it) }

    /** Item indices by owner id on the tape. */
    fun itemsOf(owner: Int): List<Int> = (0 until items.size).filter { items.owner[it] == owner }

    /** The items' kinds and owners, for the plants. */
    val table: ItemTable get() = items

    /** The windows: elements filled with the `glass` or `glassLit` gradients, inside the objects groups. */
    val windows: List<Cut> by lazy {
        layers.indices.flatMap { li ->
            val sc = scans[li]
            if (sc.objectsStart < 0) emptyList() else
                (SvgEdit.elementsWith(layers[li], "fill=\"url(#glass)\"", sc.objectsStart, sc.objectsEnd) +
                    SvgEdit.elementsWith(layers[li], "fill=\"url(#glassLit)\"", sc.objectsStart, sc.objectsEnd)).map { Cut(li, it) }
        }
    }

    /** The sun shadows' group, as `overlays` writes it: `<g opacity="<shadow_op>" fill="<shadow>"…>`. */
    val shadows: List<Cut> by lazy {
        val op = made.theme["shadow_op"] ?: return@lazy emptyList()
        val fill = made.theme["shadow"] as? String ?: return@lazy emptyList()
        val prefix = PyFmt.pf("<g opacity=\"%.2f\" fill=\"%s\"", op, fill)
        layers.indices.mapNotNull { li -> SvgEdit.element(layers[li], prefix)?.let { Cut(li, it) } }
    }

    /** The water's reflections: the group under the `ripple` filter. */
    val reflections: List<Cut> by lazy { layers.indices.mapNotNull { li -> SvgEdit.element(layers[li], "<g filter=\"url(#ripple)\"")?.let { Cut(li, it) } } }

    /** The texts outside the objects groups: the canal and water names on the ground. */
    val groundTexts: List<Cut> by lazy {
        layers.indices.flatMap { li ->
            val sc = scans[li]
            SvgEdit.elements(layers[li], "text").filter { !(sc.objectsStart >= 0 && it.first >= sc.objectsStart && it.first < sc.objectsEnd) }.map { Cut(li, it) }
        }
    }
}

/**
 * The feature ladder of delivery's D-M1, re-measured with Skia: features cut out of a render's SVG text, each variant
 * rastered structural at the case's size, and its MAD and share of pixels off by more than 2 levels against the whole
 * frame. Each step adds a feature back: L0 the ground, flat water, areas, roads, sky, haze and vignette; L1 buildings and
 * parts without windows; L2 sun shadows; L3 windows; L4 trees; L5 the water's reflections; L6 the canal names (what is
 * left is street furniture). Each step's MAD is the weight of what is still missing: the list builders work down.
 */
object Ladder {
    /** One step: what the frame [holds], its MAD and share off by more than 2 levels against the whole frame. */
    class Step(val name: String, val holds: String, val mad: Double, val over2: Double) {
        fun json(): Map<String, Any?> = mapOf("step" to name, "holds" to holds, "mad" to mad, "over2" to over2)
    }

    /** D-M1's numbers (Java2D at S1, 16 October): MAD and share over 2, per step, for the Westerkerk home and the Main board, sunny. */
    val DM1: Map<String, List<Pair<Double, Double>>> = mapOf(
        "westerkerk" to listOf(54.35 to 0.761, 9.08 to 0.174, 5.50 to 0.080, 1.64 to 0.027, 1.02 to 0.020, 0.11 to 0.002, 0.05 to 0.001),
        "canal" to listOf(60.56 to 0.689, 5.03 to 0.137, 0.93 to 0.020, 0.93 to 0.020, 0.02 to 0.001, 0.02 to 0.0, 0.00 to 0.0),
    )

    private val HOLDS = listOf(
        "ground, flat water, areas, roads, sky, haze, vignette", "+ buildings and parts, without windows", "+ sun shadows", "+ windows",
        "+ trees", "+ the water's reflections", "+ the canal names (the rest: street furniture)",
    )

    /** The ladder of [made] at [size], against [whole] (its structural reference, drawn if null). */
    fun measure(made: Oracle.Made, size: FrameSize, whole: IntArray? = null): List<Step> {
        val f = Features(made)
        val full = whole ?: Frames.draw(made.layers, made.render.raster, size, Mode.STRUCTURAL)
        val buildings = f.items { it == "building" || it == "part" }
        val trees = f.items { it == "tree" }
        val furniture = f.items { it != "building" && it != "part" && it != "tree" }
        val missing = listOf(buildings, f.shadows, f.windows, trees, f.reflections, f.groundTexts, furniture)
        return (0..6).map { step ->
            // step k holds the first k features of the list; everything after them is cut
            val cuts = missing.drop(step).flatten()
            val px = Frames.draw(SvgEdit.apply(made.layers, cuts), made.render.raster, size, Mode.STRUCTURAL)
            val m = RefMetrics.of(px, full, size.wPx, size.hPx)
            Step("L$step", HOLDS[step], m.mad, 1 - m.within2)
        }
    }

    /** How far [steps] lie from D-M1 for [klass]: per step, measured MAD over D-M1's MAD (NaN where D-M1 is 0). */
    fun ratios(klass: String, steps: List<Step>): List<Double> {
        val dm = DM1[klass] ?: return emptyList()
        return steps.mapIndexed { i, s -> if (i < dm.size && dm[i].first > 0) s.mad / dm[i].first else Double.NaN }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class LadderTest {
    /**
     * K4's acceptance: the ladder re-measured with Skia lands within ±25 % of D-M1 at its L1 and L2 steps, for the
     * Westerkerk home and the Main board, sunny, at S1. The canal ring's pack must be here (cache/packs/regions).
     */
    @Test
    fun `the ladder re-measured with Skia lands within 25 percent of D-M1 at L1 and L2`() {
        val set = CaseSet.load(phone = null)
        val s1 = FrameSize.NAMED.getValue("S1")
        val failures = ArrayList<String>()
        for ((name, klass) in listOf("home sunny S1" to "westerkerk", "board sunny S1" to "canal")) {
            val t0 = System.nanoTime()
            val made = Oracle.render(set.case(name))
            val steps = Ladder.measure(made, s1)
            val dm = Ladder.DM1.getValue(klass)
            println("ladder $name (%.1f s):".format((System.nanoTime() - t0) / 1e9))
            for ((i, s) in steps.withIndex()) {
                println("  %s %-55s MAD %6.2f  over 2 %5.1f %%   D-M1 %6.2f / %5.1f %%".format(s.name, s.holds, s.mad, s.over2 * 100, dm[i].first, dm[i].second * 100))
            }
            for (i in 1..2) {
                val r = steps[i].mad / dm[i].first
                if (abs(r - 1) > 0.25) failures.add("$name L$i: MAD %.2f against D-M1's %.2f (×%.2f)".format(steps[i].mad, dm[i].first, r))
            }
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }
}

