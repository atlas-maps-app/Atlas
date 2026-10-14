package app.atlas.render

import app.atlas.render.City.Bldg
import app.atlas.render.City.Camera
import app.atlas.render.py.PyFmt
import app.atlas.render.py.PyMath
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * City.kt against render/city.py: every helper on the oracle's sample inputs, build_city line by line, and the
 * whole render of every view render/views.py defines, diffed as SVG.
 */
class CityTest {
    companion object {
        private lateinit var funcs: JsonObject

        @BeforeClass
        @JvmStatic
        fun oracle() {
            Oracle.configure()
            funcs = Oracle.json("city_oracle.py", "funcs").jsonObject
        }

        /** A Python value read from JSON (ints stay ints, floats are Doubles). */
        fun py(e: JsonElement?): Any? = if (e == null) null else Oracle.py(e)

        @Suppress("UNCHECKED_CAST")
        fun dict(e: JsonElement?): Map<String, Any?> = py(e) as Map<String, Any?>

        fun d(v: Any?): Double = (v as Number).toDouble()

        fun bldg(e: JsonElement): Bldg {
            val m = dict(e)
            @Suppress("UNCHECKED_CAST")
            return Bldg(v2s(m["foot"]), d(m["h"]), m["kind"] as String, (m["col"] as Number).toInt(), m["id"] as List<Any?>,
                d(m["y0"]), m["named"] as String?)
        }

        /** Python and Kotlin values equal: numbers bit for bit (ints and floats alike), tuples and lists item by item. */
        fun same(a: Any?, b: Any?, num: (Double, Double) -> Boolean = { x, y -> java.lang.Double.doubleToRawLongBits(x) == java.lang.Double.doubleToRawLongBits(y) }): Boolean {
            if (a == null || b == null) return a == null && b == null
            if (a is Number && b is Number) return num(a.toDouble(), b.toDouble())
            val la = seq(a)
            val lb = seq(b)
            if (la != null && lb != null) return la.size == lb.size && la.indices.all { same(la[it], lb[it], num) }
            return a == b
        }

        fun seq(v: Any?): List<Any?>? = when (v) {
            is List<*> -> v
            is V2 -> listOf(v.x, v.y)
            is V3 -> listOf(v.x, v.y, v.z)
            is Pair<*, *> -> listOf(v.first, v.second)
            else -> null
        }

        fun camera(cfg: Map<String, Any?>): Camera = Camera(
            v3(cfg["target"]), d(cfg["yaw"]), d(cfg["pitch"]), d(cfg["dist"]), d(cfg["fov"]), d(cfg["W"]), d(cfg["H"]),
            cx = (cfg["cx"] as Number?)?.toDouble(), cy = (cfg["cy"] as Number?)?.toDouble(),
        )
    }

    @Before
    fun setUp() = Oracle.configure()

    /** Collects mismatches; fails with the first few. */
    private class Tally(val what: String) {
        var checked = 0
        var noise = 0
        val bad = ArrayList<String>()

        /** Equal; numbers apart by libm noise are counted as such, anything else is a difference. */
        fun eq(expected: Any?, actual: Any?, ctx: () -> String) {
            checked++
            if (same(expected, actual)) return
            if (same(expected, actual, Close::close)) noise++
            else bad.add("${ctx()}:\n    python=$expected\n    kotlin=$actual")
        }

        /** Equal text, exactly: emitted SVG. */
        fun text(expected: Any?, actual: Any?, ctx: () -> String) {
            checked++
            if (expected != actual) bad.add("${ctx()}:\n    python=$expected\n    kotlin=$actual")
        }
        fun done() {
            println("$what: $checked checked, ${bad.size} differ" + if (noise > 0) ", $noise apart by libm noise (last-bit sin/cos/pow)" else "")
            assertEquals("$what differs from city.py:\n" + bad.take(15).joinToString("\n"), 0, bad.size)
        }
    }

    private fun arr(k: String) = funcs[k]!!.jsonArray

    @Test
    fun vectorsAndColours() {
        val t = Tally("vectors and colours")
        for (row0 in arr("vec")) {
            val r = dict(row0)
            val a = v3(r["a"]); val b = v3(r["b"]); val tt = d(r["t"])
            t.eq(r["vsub"], City.vsub(a, b)) { "vsub" }
            t.eq(r["vdot"], City.vdot(a, b)) { "vdot" }
            t.eq(r["vcross"], City.vcross(a, b)) { "vcross" }
            t.eq(r["vnorm"], City.vnorm(a)) { "vnorm" }
            t.eq(r["mix"], City.mix(a, b, tt)) { "mix" }
            t.eq(r["mulc"], City.mulc(a, tt)) { "mulc" }
            t.eq(r["L_"], City.L_(a.x, a.y, a.z)) { "L_" }
        }
        for (row in arr("hx")) {
            val r = py(row) as List<*>
            t.eq(r[1], City.hx(r[0] as String)) { "hx(${r[0]})" }
        }
        for (row in arr("tohex")) {
            val r = py(row) as List<*>
            t.text(r[1], City.tohex(v3(r[0]))) { "tohex(${r[0]})" }
        }
        for (row in arr("clamp")) {
            val r = py(row) as List<*>
            t.eq(r[3], City.clamp(d(r[0]), d(r[1]), d(r[2]))) { "clamp$r" }
        }
        for (row in arr("clamp1")) {
            val r = py(row) as List<*>
            t.eq(r[1], City.clamp(d(r[0]))) { "clamp(${r[0]})" }
        }
        for (row in arr("P")) {
            val r = py(row) as List<*>
            t.text(r[1], City.P(v2s(r[0]))) { "P(${r[0]})" }
        }
        for (row in arr("area")) {
            val r = py(row) as List<*>
            t.eq(r[1], City.area(v2s(r[0]))) { "area(${r[0]})" }
        }
        t.done()
    }

    @Test
    fun cameras() {
        val t = Tally("Camera")
        for (c0 in arr("cams")) {
            val c = c0.jsonObject
            val a = py(c["args"]) as List<*>
            val cam = Camera(v3(a[0]), d(a[1]), d(a[2]), d(a[3]), d(a[4]), d(a[5]), d(a[6]), (a[7] as Number?)?.toDouble(),
                (a[8] as Number?)?.toDouble(), d(a[9]))
            val want = dict(c["cam"])
            val got = mapOf("C" to cam.C, "f" to cam.f, "r" to cam.r, "u" to cam.u, "W" to cam.W, "H" to cam.H, "focal" to cam.focal,
                "cx" to cam.cx, "cy" to cam.cy, "near" to cam.near)
            for ((k, v) in want) t.eq(v, got[k]) { "Camera$a.$k" }
            for (p0 in c["pts"]!!.jsonArray) {
                val p = dict(p0)
                val pt = v3(p["p"])
                t.eq(p["cam"], cam.cam(pt)) { "cam($pt)" }
                t.eq(p["proj"], cam.proj(pt)) { "proj($pt)" }
                t.eq(p["depth"], cam.depth(pt)) { "depth($pt)" }
            }
            for (p0 in c["polys"]!!.jsonArray) {
                val p = dict(p0)
                val pts = (p["pts"] as List<*>).map { v3(it) }
                t.eq(p["poly"], cam.poly(pts, d(p["margin"]))) { "poly($pts, ${p["margin"]})" }
            }
            for (g0 in c["ground_depth"]!!.jsonArray) {
                val g = py(g0) as List<*>
                t.eq(g[2], cam.groundDepth(d(g[0]), d(g[1]))) { "ground_depth$g" }
            }
            t.eq(py(c["horizon_y"]), cam.horizonY()) { "horizon_y" }
        }
        t.done()
    }

    @Test
    fun geometry() {
        val t = Tally("geometry")
        for (row in arr("hull2")) {
            val r = py(row) as List<*>
            t.eq(r[1], City.hull2(v2s(r[0]))) { "hull2(${r[0]})" }
        }
        for (row0 in arr("lines")) {
            val r = dict(row0)
            val pts = v2s(r["pts"])
            if (r["smooth"] != "ZeroDivisionError") t.eq(r["smooth"], City.smooth(pts, d(r["r"]), (r["seg"] as Number).toInt())) { "smooth($pts, ${r["r"]}, ${r["seg"]})" }
            t.eq(r["ribbon"], City.ribbon(pts, d(r["w"]))) { "ribbon($pts, ${r["w"]})" }
            t.eq(r["densify"], City.densify(pts, d(r["step"]))) { "densify($pts, ${r["step"]})" }
        }
        for (row in arr("shore")) {
            val r = py(row) as List<*>
            t.eq(r[1], City.shoreE(d(r[0]))) { "shore_e(${r[0]})" }
            t.eq(r[2], City.shoreW(d(r[0]))) { "shore_w(${r[0]})" }
        }
        for (row in arr("rng_for")) {
            val r = py(row) as List<*>
            val g = City.rngFor(*(r[0] as List<*>).toTypedArray())
            t.eq(r[1], List(3) { g.random() }) { "rng_for(${r[0]})" }
            t.eq(r[2], g.uniform(-0.025, 0.02)) { "rng_for(${r[0]}).uniform" }
            t.eq(r[3], g.randrange(1000)) { "rng_for(${r[0]}).randrange" }
        }
        for (row in arr("rect")) {
            val r = py(row) as List<*>
            val a = (r[0] as List<*>).map { d(it) }
            t.eq(r[1], City.rect(a[0], a[1], a[2], a[3])) { "rect$a" }
        }
        for (row in arr("overlaps")) {
            val r = py(row) as List<*>
            val a = (r[0] as List<*>).map { d(it) }.toDoubleArray(); val b = (r[1] as List<*>).map { d(it) }.toDoubleArray()
            t.eq(r[2], City.overlaps(a, b)) { "overlaps(${r[0]}, ${r[1]})" }
        }
        t.done()
    }

    private val themes: Map<String, Map<String, Any?>> by lazy {
        @Suppress("UNCHECKED_CAST")
        dict(funcs["themes"]).mapValues { it.value as Map<String, Any?> }
    }

    @Test
    fun themesAndShading() {
        val t = Tally("themes and shading")
        // the port's own DAY and NIGHT equal city.py's
        for ((name, th) in listOf("day" to City.DAY, "night" to City.NIGHT)) {
            val want = themes[name]!!
            t.eq(want.keys.toList(), th.keys.toList()) { "$name keys" }
            for ((k, v) in want) t.eq(v, th[k]) { "$name.$k" }
        }
        for (row0 in arr("fog")) {
            val r = dict(row0)
            val th = themes[r["theme"]]!!
            val c = v3(r["c"]); val dd = d(r["d"])
            t.eq(r["fogmix"], City.fogmix(th, c, dd)) { "fogmix(${r["theme"]}, $c, $dd)" }
            t.eq(r["face_color"], City.faceColor(th, c, v3(r["n"]), dd, r["top"] as Boolean)) { "face_color(${r["theme"]}, $c, ${r["n"]}, $dd, ${r["top"]})" }
        }
        for (row0 in arr("base_color")) {
            val r = row0.jsonObject
            val th = themes[py(r["theme"])]!!
            val b = bldg(r["b"]!!)
            t.eq(py(r["base_color"]), City.baseColor(th, b, py(r["hl"]) as String?)) { "base_color(${py(r["theme"])}, ${b.id}, ${py(r["hl"])})" }
        }
        for (row0 in arr("face_svg")) {
            val r = dict(row0)
            t.text(r["svg"], City.faceSvg(themes[r["theme"]]!!, v2s(r["sp"]), r["hc"] as String, r["top"] as Boolean)) { "face_svg(${r["theme"]}, ${r["top"]})" }
        }
        t.done()
    }

    @Test
    fun emitters() {
        val t = Tally("emitters")
        @Suppress("UNCHECKED_CAST")
        val cfgs = dict(funcs["view_cfgs"]).mapValues { it.value as Map<String, Any?> }
        for (row0 in arr("emit")) {
            val r = row0.jsonObject
            val view = py(r["view"]) as String
            val cfg = cfgs[view]!!
            val th = themes[cfg["theme"]]!!
            val cam = camera(cfg)
            val b = bldg(r["b"]!!)
            val dd = d(py(r["d"]))
            val base = V3(200.0, 190.0, 180.0)
            t.text(py(r["emit_bldg"]), City.emitBldg(cam, th, b, dd, cfg["highlight"] as String?, cfg)) { "emit_bldg($view, ${b.id})" }
            t.text(py(r["emit_simple"]), City.emitSimple(cam, th, b, base, dd)) { "emit_simple($view, ${b.id})" }
            val cxz = V2(PyMath.sumOf(b.foot) { it.x } / b.foot.size, PyMath.sumOf(b.foot) { it.y } / b.foot.size)
            t.text(py(r["emit_simple_apex"]), City.emitSimple(cam, th, b, base, dd, apex = V3(cxz.x, b.y0 + b.h + 5, cxz.y))) { "emit_simple apex($view, ${b.id})" }
            if (r["roof_bits"] !is JsonNull) t.text(py(r["roof_bits"]), City.roofBits(cam, th, b, base, dd)) { "roof_bits($view, ${b.id})" }
        }
        for (row0 in arr("faces")) {
            val r = row0.jsonObject
            val view = py(r["view"]) as String
            val cfg = cfgs[view]!!
            val th = themes[cfg["theme"]]!!
            val cam = camera(cfg)
            val b = bldg(r["b"]!!)
            val i = (py(r["i"]) as Number).toInt()
            val a = b.foot[i]; val bb = b.foot[(i + 1) % b.foot.size]
            val dd = d(py(r["d"])); val fg = d(py(r["fg"])); val elen = d(py(r["elen"]))
            t.text(py(r["day_lines"]), City.dayLines(cam, th, b, a, bb, b.y0, b.y0 + b.h, elen, v3(py(r["col"])), fg)) { "day_lines($view, ${b.id}, $i)" }
            t.text(py(r["windows"]), City.windows(cam, th, b, a, bb, b.y0, b.y0 + b.h, elen, dd, fg, d(py(r["ar"])))) { "windows($view, ${b.id}, $i)" }
        }
        val parts = City.bridgeParts()
        val want = arr("bridge_parts")
        t.eq(want.size, parts.size) { "bridge_parts count" }
        for (i in want.indices) {
            val w = dict(want[i])
            val p = parts[i]
            val got = LinkedHashMap<String, Any?>()
            for (k in w.keys) got[k] = when (k) {
                "kind" -> p.kind
                "c" -> p.c
                "foot" -> p.foot
                "y0" -> p.y0
                "h" -> p.h
                "a" -> p.a
                "b" -> p.b
                else -> "unknown key $k"
            }
            t.eq(w.keys.toList(), got.keys.toList()) { "bridge_parts[$i] keys" }
            for (k in w.keys) t.eq(w[k], got[k]) { "bridge_parts[$i].$k" }
        }
        for (row0 in arr("emit_bridge")) {
            val r = dict(row0)
            val view = r["view"] as String
            val cfg = cfgs[view]!!
            val th = themes[cfg["theme"]]!!
            val k = (r["k"] as Number).toInt()
            t.text(r["svg"], City.emitBridge(camera(cfg), th, parts[k], d(r["d"]))) { "emit_bridge($view, $k)" }
        }
        t.done()
    }

    // ---------------------------------------------------------------- canonical text, as city_oracle.py writes it

    private fun r(x: Double): String = PyFmt.repr(x)
    private fun pt(p: V2): String = "(${r(p.x)}, ${r(p.y)})"
    private fun pt(p: V3): String = "(${r(p.x)}, ${r(p.y)}, ${r(p.z)})"
    private fun pts(ps: List<V2>): String = ps.joinToString(", ", "[", "]") { pt(it) }

    private fun canonCity(c: City.CityData): List<String> {
        val out = ArrayList<String>()
        for (p in c.pads) out.add("pad " + pts(p))
        for ((k, p) in c.parks) out.add("park '$k' " + pts(p))
        for (b in c.bldgs) {
            out.add("B ${pts(b.foot)} ${r(b.h)} '${b.kind}' ${b.col} ${PyFmt.reprTuple(b.id)} ${r(b.y0)} ${b.named?.let { "'$it'" } ?: "None"}")
        }
        for (tr in c.trees) out.add("tree (${r(tr.x)}, ${r(tr.z)}, ${r(tr.r)})")
        out.add("water " + pts(c.water))
        out.add("esp " + pts(c.esp))
        return out
    }

    @Test
    fun buildCityLineByLine() {
        val regions = listOf(
            intArrayOf(-3600, 2200, -6500, 1500), intArrayOf(-900, 1400, -2400, 400), intArrayOf(-4200, 2200, -7000, 900),
            intArrayOf(0, 500, -1000, 0),
        )
        for (reg in regions) {
            val file = File(Oracle.out, "city/build_${reg.joinToString("_")}.txt")
            file.parentFile.mkdirs()
            val info = Oracle.run("city_oracle.py", "build", *reg.map { it.toString() }.toTypedArray(), file.path)
            val want = file.readLines()
            val t0 = System.nanoTime()
            val city = City.buildCity(reg.map { it.toDouble() }.toDoubleArray())
            val ms = (System.nanoTime() - t0) / 1e6
            val got = canonCity(city)
            var noise = 0
            val firstBad = want.indices.firstOrNull {
                if (it >= got.size) return@firstOrNull true
                if (want[it] == got[it]) return@firstOrNull false
                if (Close.closeText(want[it], got[it])) {
                    noise++; false
                } else true
            }
            println("build_city(${reg.joinToString()}): ${want.size} lines, kotlin ${"%.0f".format(ms)} ms, python ${info.trim()}" +
                (firstBad?.let { ", first difference at line ${it + 1}" } ?: ", identical") +
                if (noise > 0) " ($noise lines with a number apart by libm noise)" else "")
            if (firstBad != null) {
                assertEquals("build_city(${reg.joinToString()}) line ${firstBad + 1}", want[firstBad], got.getOrNull(firstBad))
            }
            assertEquals(want.size, got.size)
        }
    }

    // ---------------------------------------------------------------- the whole render

    private var viewsDoc: Map<String, Any?>? = null

    private fun views(): Map<String, Any?> {
        viewsDoc?.let { return it }
        val dir = File(Oracle.out, "city")
        Oracle.run("city_oracle.py", "views", dir.path)
        @Suppress("UNCHECKED_CAST")
        val m = Oracle.py(Json.parseToJsonElement(File(dir, "views.json").readText())) as Map<String, Any?>
        viewsDoc = m
        return m
    }

    @Test
    fun everyViewRendersTheSameSvg() {
        val all = views()
        val dir = File(Oracle.out, "city")
        val cities = HashMap<List<Double>, City.CityData>()
        val lines = ArrayList<String>()
        var failures = 0
        for ((name, v0) in all) {
            @Suppress("UNCHECKED_CAST")
            val v = v0 as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val cfg = v["cfg"] as Map<String, Any?>
            val reg = (cfg["region"] as List<*>).map { d(it) }
            val t0 = System.nanoTime()
            val city = cities.getOrPut(reg) { City.buildCity(reg.toDoubleArray()) }
            val t1 = System.nanoTime()
            val out = City.render(cfg, city, name)
            val t2 = System.nanoTime()
            val want = File(dir, "$name.svg").readText()
            val diff = SvgDiff.compare(want, out.svg)
            val mine = File(dir, "$name.kt.svg")
            if (diff.identicalText) mine.delete() else mine.writeText(out.svg)
            val anchorsSame = same(v["anchors"].let { a -> (a as Map<*, *>).entries.map { listOf(it.key, it.value) } },
                out.anc.entries.map { listOf(it.key, it.value) })
            val line = String.format(
                java.util.Locale.ROOT, "%-9s %8d chars  %s  divergences %d  similarity %.6f  anchors %s  kotlin build %.0f ms render %.0f ms  python build %.0f ms render %.0f ms",
                name, want.length, if (diff.identicalText) "byte-identical" else "DIFFERENT", diff.divergenceCount, diff.similarity,
                if (anchorsSame) "same" else "DIFFER", (t1 - t0) / 1e6, (t2 - t1) / 1e6, d(v["build_s"]) * 1000, d(v["render_s"]) * 1000,
            )
            lines.add(line)
            if (!diff.identicalText || !anchorsSame) {
                failures++
                lines.add(diff.toString())
                if (!anchorsSame) lines.add("anchors: python=${v["anchors"]} kotlin=${out.anc}")
            }
        }
        println(lines.joinToString("\n"))
        assertTrue("city.render differs:\n" + lines.joinToString("\n"), failures == 0)
    }
}
