package app.atlas.render

import app.atlas.render.CityTest.Companion.camera
import app.atlas.render.CityTest.Companion.d
import app.atlas.render.CityTest.Companion.dict
import app.atlas.render.CityTest.Companion.py
import app.atlas.render.CityTest.Companion.same
import app.atlas.render.py.PyMath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** Collects differences between Python and Kotlin; fails with the first few. Text that differs only by libm noise is counted apart. */
class PortTally(private val what: String) {
    var checked = 0
    var noise = 0
    val bad = ArrayList<String>()

    /** Equal values: numbers bit for bit, or apart by libm noise. */
    fun eq(expected: Any?, actual: Any?, ctx: () -> String) {
        checked++
        if (same(expected, actual)) return
        if (same(expected, actual, Close::close)) noise++ else bad.add("${ctx()}:\n    python=$expected\n    kotlin=$actual")
    }

    /** Equal text. Text apart only by numbers that differ by libm noise is noted as such, and still fails if [strict]. */
    fun text(expected: Any?, actual: Any?, strict: Boolean = true, ctx: () -> String) {
        checked++
        if (expected == actual) return
        if (expected is String && actual is String && Close.closeText(expected, actual) && !strict) {
            noise++; return
        }
        bad.add("${ctx()}:\n    python=${clip(expected)}\n    kotlin=${clip(actual)}" + firstDiff(expected, actual))
    }

    private fun clip(v: Any?): String = v.toString().let { if (it.length > 400) it.take(400) + "… (${it.length} chars)" else it }

    private fun firstDiff(a: Any?, b: Any?): String {
        if (a !is String || b !is String) return ""
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return "\n    first difference at $i: python=…${a.substring(maxOf(0, i - 60), minOf(a.length, i + 60))}…\n" +
            "                          kotlin=…${b.substring(maxOf(0, i - 60), minOf(b.length, i + 60))}…"
    }

    fun done() {
        println("$what: $checked checked, ${bad.size} differ" + if (noise > 0) ", $noise apart by libm noise" else "")
        assertEquals("$what differs from Python:\n" + bad.take(8).joinToString("\n"), 0, bad.size)
    }
}

/** Closeup.kt against render/closeup.py: every function on the oracle's samples, and paving() along the Westerkerk's streets. */
class CloseupTest {
    companion object {
        private lateinit var funcs: JsonObject

        @BeforeClass
        @JvmStatic
        fun oracle() {
            Oracle.configure()
            funcs = Oracle.json("closeup_oracle.py", "funcs").jsonObject
        }

        /** The oracle's stand-in for amsterdam.light. */
        val lightStub: LightFn = { th, base, n, lit ->
            val L = v3(th["_L"])
            val k = 0.55 + 0.35 * PyMath.max(0.0, n[0] * L[0] + n[1] * L[1] + n[2] * L[2]) + (if (lit) 0.12 else 0.0)
            V3(base[0] * k, base[1] * k, base[2] * k)
        }

        /** The oracle's stand-in for Shadows.lit. */
        val litStub: (V3) -> Boolean = { p -> Math.floorMod(PyMath.floorInt(p[0] * 0.37 + p[2] * 0.11), 2L) == 0L }

        /** A Python dict held in another value. */
        @Suppress("UNCHECKED_CAST")
        fun m(v: Any?): Map<String, Any?> = v as Map<String, Any?>

        val sideCol: (String?) -> String = { sf -> mapOf("paving_stones" to "#C9C4BA", "sett" to "#B8B0A4", "asphalt" to "#9A9A9A")[sf] ?: "#C4C0B8" }
    }

    @Before
    fun setUp() = Oracle.configure()

    private fun rows(k: String) = funcs[k]!!.jsonArray.map { dict(it) }

    @Test
    fun patternsAndFilter() {
        val t = PortTally("pattern_defs, crown_filter")
        val p = funcs["pattern_defs"]!!.jsonArray
        t.text(p[0].jsonPrimitive.content, Closeup.patternDefs(false)) { "pattern_defs(day)" }
        t.text(p[1].jsonPrimitive.content, Closeup.patternDefs(true)) { "pattern_defs(night)" }
        t.text(funcs["crown_filter"]!!.jsonPrimitive.content, Closeup.crownFilter()) { "crown_filter" }
        t.done()
    }

    @Test
    fun sidewalkSides() {
        val t = PortTally("sidewalk_sides")
        for (r in rows("sidewalk_sides")) {
            @Suppress("UNCHECKED_CAST")
            val (sides, surf) = Closeup.sidewalkSides(r["t"] as Map<String, Any?>)
            t.eq(r["sides"], sides) { "sides ${r["t"]}" }
            t.eq(r["surf"], surf.entries.map { listOf(it.key, it.value) }) { "surf ${r["t"]}" }
        }
        t.done()
    }

    @Test
    fun clipping() {
        val t = PortTally("_clip_x, _clip_z, _near_carriageway, _seg")
        for (r in rows("clip")) {
            val cam = camera(m(r["cam"]))
            val cs = (r["cs"] as List<*>).map { v3(it) }
            t.eq(r["x"], Closeup._clipX(cam, cs, d(r["sx0"]), d(r["sx1"]))) { "_clip_x $r" }
            t.eq(r["z"], Closeup._clipZ(cs, d(r["z0"]), d(r["z1"]))) { "_clip_z $r" }
        }
        for (r in rows("near_carriageway")) {
            val ids = HashMap<Int, Any>()
            fun rid(k: Any?): Any? = if (k == null) null else ids.getOrPut((k as Number).toInt()) { Any() }
            val segs = (r["segs"] as List<*>).map { s0 ->
                val s = s0 as List<*>
                Closeup.Seg(rid(s[0])!!, d(s[1]), d(s[2]), d(s[3]), d(s[4]), d(s[5]))
            }
            t.eq(r["out"], Closeup._nearCarriageway(segs, d(r["x"]), d(r["z"]), rid(r["skip"]))) { "_near_carriageway $r" }
        }
        for (r in rows("seg")) {
            val cam = camera(m(r["cam"]))
            t.eq(r["out"], Closeup._seg(cam, v3(r["p"]), v3(r["q"]))) { "_seg $r" }
        }
        t.done()
    }

    @Test
    fun wallsChurchWindowsAndCoursing() {
        val t = PortTally("church_windows, coursing")
        for ((i, r) in rows("walls").withIndex()) {
            val cam = camera(m(r["cam"]))
            val th = m(r["th"])
            val a = v2(r["a"]); val b = v2(r["b"]); val nrm = v3(r["nrm"])
            val night = r["night"] as Boolean
            t.eq(r["church"], Closeup.churchWindows(cam, th, a, b, d(r["ex"]), d(r["ez"]), d(r["elen"]), nrm, d(r["y0"]), d(r["top"]), d(r["d"]), "#8A8478", night)) { "church_windows #$i" }
            t.eq(r["coursing"], Closeup.coursing(cam, a, b, d(r["ex"]), d(r["ez"]), d(r["elen"]), nrm, d(r["y0"]), d(r["top"]), d(r["course"]))) { "coursing #$i" }
        }
        t.done()
    }

    @Test
    fun trees() {
        val t = PortTally("tree_form, autumn, tree_close")
        var drawn = 0
        for ((i, r) in rows("trees").withIndex()) {
            val n = m(r["n"])
            @Suppress("UNCHECKED_CAST")
            val tags = n["tags"] as Map<String, Any?>
            val th = m(r["th"])
            t.eq(r["form"], Closeup.treeForm(tags).toList()) { "tree_form $tags" }
            t.eq(r["autumn"], Closeup.autumn(th["date"] as List<*>, tags)) { "autumn $tags" }
            val out = Closeup.treeClose(camera(m(r["cam"])), th, litStub, n, d(r["d"]), lightStub, null)
            if (out.isNotEmpty()) drawn++
            t.text(r["close"], out) { "tree_close #$i ${r["n"]} d=${r["d"]}" }
        }
        println("tree_close: $drawn trees drawn")
        t.done()
    }

    @Test
    fun skyClouds() {
        val t = PortTally("sky_clouds")
        for ((i, r) in rows("skies").withIndex()) {
            val defs = ArrayList<String>()
            val out = Closeup.skyClouds(camera(m(r["cam"])), m(r["th"]), 412.0, d(r["hy"]), defs, d(r["eye"]), r["tile"] as Boolean)
            t.text(r["out"], out) { "sky_clouds #$i" }
            t.text(r["defs"], defs) { "sky_clouds defs #$i" }
        }
        t.done()
    }

    @Test
    fun pavingAtTheWesterkerk() {
        val res = Oracle.json("closeup_oracle.py", "paving").jsonObject
        val roads = res["roads"]!!.jsonArray.map { e ->
            val m = LinkedHashMap(dict(e))
            m["pts"] = v2s(m["pts"]); m
        }
        val S = mapOf("roads" to roads)
        val t = PortTally("paving")
        var ms = 0.0
        var patterns = 0
        for ((i, e) in res["cases"]!!.jsonArray.withIndex()) {
            val r = dict(e)
            val cam = camera(m(r["cam"]))
            val t0 = System.nanoTime()
            val (under, over) = Closeup.paving(S, cam, m(r["th"]), null, sideCol)
            ms += (System.nanoTime() - t0) / 1e6
            patterns += Regex("<pattern ").findAll(under + over).count()
            t.text(r["under"], under) { "paving under #$i ${r["cam"]}" }
            t.text(r["over"], over) { "paving over #$i ${r["cam"]}" }
        }
        println("paving: ${roads.size} streets, ${t.checked / 2} cameras, $patterns pattern pieces; Kotlin %.0f ms, Python %.0f ms".format(ms, Oracle.seconds(res["secs"]) * 1000))
        t.done()
    }
}
