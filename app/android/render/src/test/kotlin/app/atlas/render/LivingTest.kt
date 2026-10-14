package app.atlas.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/**
 * Living.kt against render/living.py: the helpers on sample inputs, build_scene feature by feature, every draw
 * function on every feature in three views and three themes, and the whole render of seven views in every theme,
 * diffed as SVG.
 */
class LivingTest {
    companion object {
        private val funcs: JsonObject by lazy { Oracle.json("living_oracle.py", "funcs").jsonObject }
        private val scene: Living.SceneData by lazy { Living.buildScene() }
        val THEMES = linkedMapOf("noon" to Living.NOON, "golden" to Living.GOLDEN, "night" to Living.NIGHT)

        /** A Kotlin value as the Python one living_oracle.py dumps: points and tuples as lists. */
        fun pyOf(v: Any?): Any? = when (v) {
            is V2 -> listOf(v.x, v.y)
            is V3 -> listOf(v.x, v.y, v.z)
            is Pair<*, *> -> listOf(pyOf(v.first), pyOf(v.second))
            is DoubleArray -> v.toList()
            is List<*> -> v.map { pyOf(it) }
            is Map<*, *> -> v.entries.associate { it.key to pyOf(it.value) }
            is Feature -> mapOf("kind" to v.kind, "geom" to pyOf(v.geom), "tags" to pyOf(v.tags))
            else -> v
        }

        /** Dicts as lists of [key, value] pairs, recursively: compared in order, numbers by value whatever their type. */
        fun norm(v: Any?): Any? = when (v) {
            is Map<*, *> -> v.entries.map { listOf(it.key, norm(it.value)) }
            is List<*> -> v.map { norm(it) }
            else -> v
        }

        /** render()'s preamble: the camera, the sun and the scene, as living_oracle.py's `prepared`. */
        fun prepared(th0: Map<String, Any?>, cfg: Map<String, Any?>, F: List<Feature>): Living.Scene {
            val cam = CityTest.camera(cfg)
            @Suppress("UNCHECKED_CAST")
            val hhmm = th0["hhmm"] as List<Int>
            val t = if (hhmm[0] + 4 < 24) LocalDateTime.of(2026, 9, 30, hhmm[0] + 4, hhmm[1]) else LocalDateTime.of(2026, 10, 1, hhmm[0] + 4 - 24, hhmm[1])
            val (az, el) = Living.sunPosition(t)
            val L = if (el > 0) Living.sunVector(az, el) else V3(0.0, -1.0, 0.0)
            val th = LinkedHashMap(th0); th["_L"] = L
            return Living.Scene(F, th, cam, L)
        }
    }

    @Before
    fun setUp() = Oracle.configure()

    /** Collects mismatches; fails with the first few. */
    class Tally(val what: String) {
        var checked = 0
        var noise = 0
        val bad = ArrayList<String>()

        fun eq(expected: Any?, actual: Any?, ctx: () -> String) {
            checked++
            val a = pyOf(actual)
            val e = norm(expected); val n = norm(a)
            if (CityTest.same(e, n)) return
            if (CityTest.same(e, n, Close::close)) noise++
            else bad.add("${ctx()}:\n    python=$expected\n    kotlin=$a")
        }

        fun text(expected: Any?, actual: Any?, ctx: () -> String) {
            checked++
            if (expected != actual) bad.add("${ctx()}:\n    python=$expected\n    kotlin=$actual")
        }

        fun done() {
            println("$what: $checked checked, ${bad.size} differ" + if (noise > 0) ", $noise apart by libm noise" else "")
            assertEquals("$what differs from Python:\n" + bad.take(12).joinToString("\n"), 0, bad.size)
        }
    }

    private fun rows(k: String): List<*> = Oracle.py(funcs[k]!!) as List<*>

    @Suppress("UNCHECKED_CAST")
    private fun views(): Map<String, Map<String, Any?>> = Oracle.py(funcs["views"]!!) as Map<String, Map<String, Any?>>

    private fun d(v: Any?) = CityTest.d(v)

    @Test
    fun sunAndHours() {
        val t = Tally("sun_position, sun_vector, open_at, height_of, quad_at")
        for (r0 in rows("sun_position")) {
            val r = r0 as List<*>
            val dt = (r[0] as List<*>).map { (it as Number).toInt() }
            t.eq(r[1], Living.sunPosition(LocalDateTime.of(dt[0], dt[1], dt[2], dt[3], dt[4]))) { "sun_position($dt)" }
        }
        for (r0 in rows("sun_vector")) {
            val r = r0 as List<*>
            t.eq(r[2], Living.sunVector(d(r[0]), d(r[1]))) { "sun_vector(${r[0]}, ${r[1]})" }
        }
        for (r0 in rows("open_at")) {
            val r = r0 as List<*>
            @Suppress("UNCHECKED_CAST")
            t.eq(r[2], Living.openAt(r[0] as String, (r[1] as List<Number>).map { it.toInt() })) { "open_at(${r[0]}, ${r[1]})" }
        }
        for (r0 in rows("height_of")) {
            val r = r0 as List<*>
            @Suppress("UNCHECKED_CAST")
            t.eq(r[1], Living.heightOf(r[0] as Map<String, Any?>)) { "height_of(${r[0]})" }
        }
        for (r0 in rows("quad_at")) {
            val r = r0 as List<*>
            val a = r[0] as List<*>
            val q = if (a.size > 6) Living.quadAt(v2(a[0]), v2(a[1]), d(a[2]), d(a[3]), d(a[4]), d(a[5]), d(a[6]), v3(a[7]))
            else Living.quadAt(v2(a[0]), v2(a[1]), d(a[2]), d(a[3]), d(a[4]), d(a[5]))
            t.eq(r[1], q) { "quad_at($a)" }
        }
        t.done()
    }

    @Test
    fun buildSceneFeatureByFeature() {
        val t = Tally("build_scene")
        @Suppress("UNCHECKED_CAST")
        val want = Oracle.py(funcs["scene"]!!) as Map<String, Any?>
        val F = want["F"] as List<*>
        val got = scene
        t.eq(F.size, got.F.size) { "number of features" }
        for (i in 0 until minOf(F.size, got.F.size)) {
            @Suppress("UNCHECKED_CAST")
            val w = F[i] as Map<String, Any?>
            val g = got.F[i]
            t.text(w["kind"], g.kind) { "F[$i].kind" }
            t.eq(w["geom"], g.geom) { "F[$i].geom (${g.kind})" }
            t.text((w["tags"] as Map<*, *>).entries.map { listOf(it.key, it.value) }, g.tags.entries.map { listOf(it.key, it.value) }) { "F[$i].tags (${g.kind})" }
        }
        t.eq(want["blocks"], got.blocks) { "blocks" }
        t.eq(want["park"], got.park) { "park" }
        println("build_scene: ${got.F.size} features, " + got.F.groupingBy { it.kind }.eachCount())
        t.done()
    }

    @Test
    fun lightSunlitTextAndFaces() {
        val t = Tally("light, sunlit, ground_text, face_path")
        @Suppress("UNCHECKED_CAST")
        val Ls = Oracle.py(funcs["L"]!!) as Map<String, Any?>
        val main = views()["main"]!!
        val scenes = THEMES.mapValues { (_, th) -> prepared(th, main, scene.F) }
        for ((nm, sc) in scenes) t.eq(Ls[nm], sc.L) { "L($nm)" }
        for (r0 in rows("light")) {
            val r = r0 as List<*>
            t.eq(r[4], Living.light(scenes[r[0]]!!.th, v3(r[1]), v3(r[2]), r[3] as Boolean)) { "light(${r[0]}, ${r[1]}, ${r[2]}, ${r[3]})" }
        }
        val golden = scenes["golden"]!!
        val bl = scene.F.filter { it.kind == "building" }
        for (r0 in rows("sunlit")) {
            val r = r0 as List<*>
            val k = (r[1] as Number).toInt()
            t.eq(r[2], Living.sunlit(golden, v3(r[0]), if (k >= 0) bl[k] else null)) { "sunlit(${r[0]}, skip=$k)" }
        }
        for (r0 in rows("ground_text")) {
            val r = r0 as List<*>
            val cam = CityTest.camera(views()[r[0] as String]!!)
            val a = r[1] as List<*>
            if (a[0] == "face") {
                val (s, sp) = Living.facePath(cam, (a[1] as List<*>).map { v3(it) }, "#123456", " opacity=\".5\"")
                val w = r[2] as List<*>
                t.text(w[0], s) { "face_path(${a[1]})" }
                t.eq(w[1], sp) { "face_path(${a[1]}) points" }
            } else {
                t.text(r[2], Living.groundText(cam, "N 7TH ST", v3(a[0]), v3(a[1]), v3(a[2]), d(a[3]), a[4] as String, d(a[5]))) { "ground_text(${r[0]}, $a)" }
            }
        }
        t.done()
    }

    @Test
    fun everyDrawFunctionOnEveryFeature() {
        val file = File(Oracle.out, "living/draws.json")
        file.parentFile.mkdirs()
        Oracle.run("living_oracle.py", "draws", file.path)
        @Suppress("UNCHECKED_CAST")
        val res = Oracle.py(Json.parseToJsonElement(file.readText())) as List<Map<String, Any?>>
        val t = Tally("draw_* per feature")
        val views = views()
        val F = scene.F
        var key: String? = null
        var sc: Living.Scene? = null
        var glow = ArrayList<String>()
        var anchors = LinkedHashMap<String, Any?>()
        var rg = app.atlas.render.py.PyRandom(9)
        var lines = 0
        for (row in res) {
            val vn = row["view"] as String; val tn = row["theme"] as String
            if ("$vn/$tn" != key) {
                key = "$vn/$tn"
                sc = prepared(THEMES[tn]!!, views[vn]!!, F)
                glow = ArrayList(); anchors = LinkedHashMap(); rg = app.atlas.render.py.PyRandom(9)
            }
            val s = sc!!
            val night = tn == "night"
            if ("extra" in row) {
                for (e0 in row["extra"] as List<*>) {
                    val e = e0 as List<*>
                    val a = e[1] as List<*>
                    val got = when (e[0]) {
                        "car" -> Living.drawCar(s, Feature("car", listOf(d(a[0]), d(a[1]), a[2]), linkedMapOf("_col" to "#B8322A")))
                        "person" -> Living.drawPerson(s, Feature("person", listOf(d(a[0]), d(a[1])), linkedMapOf("_col" to "#1E3A5F")))
                        "tank" -> Living.drawTank(s, d(a[0]), d(a[1]), d(a[2]), d(a[3]), null)
                        "box" -> Living.box(s, -2.0, 2.0, 0.0, 3.0, -60.0, -56.0, V3(180.0, 50.0, 40.0))
                        "box_unlit" -> Living.box(s, 20.0, 24.0, 0.0, 6.0, 20.0, 26.0, V3(90.0, 120.0, 200.0), litOk = false)
                        else -> error("unknown extra ${e[0]}")
                    }
                    t.text(e[2], got) { "$key ${e[0]}$a" }
                }
                t.eq(row["anchors"], anchors) { "$key anchors" }
                t.text(row["glow"], glow) { "$key glow" }
                continue
            }
            val i = (row["i"] as Number).toInt(); val dd = d(row["d"]); val f = F[i]
            val got = when (f.kind) {
                "building" -> Living.drawBuilding(s, f, dd, glow, anchors)
                "tree" -> Living.drawTree(s, f, dd, rg)
                "lamp" -> Living.drawLamp(s, f, dd, glow)
                "hydrant" -> Living.drawPost(s, f, 0.85, 0.28, if (!night) "#C0392B" else "#4A1F1C", "#E8C04A")
                "bench" -> Living.drawBench(s, f)
                "subway" -> Living.drawSubway(s, f, glow, anchors)
                "bus_stop" -> Living.drawShelter(s, f, glow)
                "bike_rack" -> Living.drawRack(s, f)
                "fountain" -> Living.drawPost(s, f, 0.95, 0.35, if (!night) "#5A6B7A" else "#1A2230", if (!night) "#7FC3FF" else "#2A4A6A")
                "person_park" -> Living.drawPerson(s, Feature("person_park", f.geom, linkedMapOf("_col" to rg.choice(listOf("#B8322A", "#1E3A5F", "#E8E8E4", "#7B4DFF", "#C9A36A")))))
                "playground" -> Living.drawPlayground(s, f)
                else -> error("unexpected ${f.kind}")
            }
            lines += got.size
            t.text(row["out"], got) { "$key ${f.kind} F[$i] d=${row["d"]}" }
        }
        println("draws: ${res.size} calls, $lines SVG elements")
        t.done()
    }

    @Test
    fun everyViewAndThemeRendersTheSameSvg() {
        val dir = File(Oracle.out, "living")
        Oracle.run("living_oracle.py", "views", dir.path)
        @Suppress("UNCHECKED_CAST")
        val all = Oracle.py(Json.parseToJsonElement(File(dir, "views.json").readText())) as Map<String, Map<String, Any?>>
        val lines = ArrayList<String>()
        var failures = 0
        var ktTotal = 0.0; var pyTotal = 0.0
        for ((name, v) in all) {
            @Suppress("UNCHECKED_CAST")
            val cfg = v["cfg"] as Map<String, Any?>
            val th = THEMES[v["theme"] as String]!!
            val t0 = System.nanoTime()
            val out = Living.render(th, cfg, scene.F, scene.blocks, scene.park)
            val ms = (System.nanoTime() - t0) / 1e6
            val want = File(dir, "$name.svg").readText()
            val diff = SvgDiff.compare(want, out.svg)
            val mine = File(dir, "$name.kt.svg")
            if (diff.identicalText) mine.delete() else mine.writeText(out.svg)
            val anchorsSame = CityTest.same(v["anchors"], pyOf(out.anchors)) && (v["anchors"] as Map<*, *>).keys.toList() == out.anchors.keys.toList()
            ktTotal += ms; pyTotal += d(v["seconds"]) * 1000
            lines.add(String.format(java.util.Locale.ROOT, "%-14s %8d chars  %s  divergences %d  similarity %.6f  anchors %s  kotlin %.0f ms  python %.0f ms",
                name, want.length, if (diff.identicalText) "byte-identical" else "DIFFERENT", diff.divergenceCount, diff.similarity,
                if (anchorsSame) "same" else "DIFFER", ms, d(v["seconds"]) * 1000))
            if (!diff.identicalText || !anchorsSame) {
                failures++
                lines.add(diff.toString())
                if (!anchorsSame) lines.add("anchors: python=${v["anchors"]}\n  kotlin=${pyOf(out.anchors)}")
            }
        }
        lines.add(String.format(java.util.Locale.ROOT, "total: kotlin %.0f ms, python %.0f ms", ktTotal, pyTotal))
        println(lines.joinToString("\n"))
        assertTrue("living.render differs:\n" + lines.joinToString("\n"), failures == 0)
    }
}
