package app.atlas.render

import app.atlas.render.LivingTest.Companion.pyOf
import app.atlas.render.LivingTest.Tally
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Real.kt's stage 2 half against render/real.py on the New York extract (data/regions/newyork, local only: the tests
 * are skipped without it): load with the census and year joins, the street fronts, attach_shops, water_polygon,
 * Shadows, theme, the draw helpers, the whole render in eleven views and themes (and the split layers), and the
 * anchors real.py's __main__ picks.
 */
class RealRenderTest {
    companion object {
        private val dir = File(Oracle.out, "real")
        private val osm = File(dir, "osm_newyork")
        private val extract = File(Oracle.repo, "data/regions/newyork/raw.json")

        /** One oracle run (one load, about 40 s of Python) for every test here. */
        private val oracleTimes: String by lazy {
            dir.mkdirs()
            Oracle.run("real_render_oracle.py", "mkosm", Oracle.repo.path, osm.path)
            Oracle.run("real_render_oracle.py", "all", osm.path, dir.path)
        }

        private class Loaded(val S: Real.RealData, val water: List<V2>?, val ms: LongArray)

        private val loaded: Loaded by lazy {
            oracleTimes
            Oracle.configure()
            val t0 = System.nanoTime()
            val S = Real.load(osm)
            val t1 = System.nanoTime()
            Real.fronts(S)
            val t2 = System.nanoTime()
            Real.attachShops(S)
            val t3 = System.nanoTime()
            Loaded(S, Real.waterPolygon(S), longArrayOf((t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000))
        }

        @Suppress("UNCHECKED_CAST")
        private fun json(name: String): Map<String, Any?> = Oracle.py(Json.parseToJsonElement(File(dir, name).readText())) as Map<String, Any?>
    }

    @Before
    fun setUp() {
        assumeTrue("data/regions/newyork/raw.json is local only", extract.isFile)
        Oracle.configure()
    }

    private fun d(v: Any?) = CityTest.d(v)

    @Test
    fun loadJoinsFrontsAndShops() {
        val (S, water) = loaded.let { it.S to it.water }
        val want = json("load.json")
        val t = Tally("load + fronts + attach_shops + water_polygon")
        val bs = want["buildings"] as List<*>
        t.eq(bs.size, S.buildings.size) { "buildings" }
        for ((i, b0) in bs.withIndex()) {
            if (i >= S.buildings.size) break
            val w = b0 as Map<*, *>; val b = S.buildings[i]
            t.eq(w["poly"], b.poly) { "building[$i].poly" }
            t.eq(w["h"], b.h) { "building[$i].h" }
            t.eq(w["levels"], b.levels) { "building[$i].levels" }
            t.eq(w["year"], b.year) { "building[$i].year" }
            t.text(w["mat"], b.mat) { "building[$i].mat" }
            t.text(w["col"], b.col) { "building[$i].col" }
            t.text(w["tags"], b.tags.keys.toList()) { "building[$i].tags" }
            t.eq(w["guessed"], b.guessed.entries.map { listOf(it.key, it.value) }) { "building[$i].guessed" }
            t.eq(w["id"], b.id) { "building[$i].id" }
            t.eq(w["front"], b.front) { "building[$i].front" }
            t.eq(w["shops"], b.shops.map { listOf(it.edge, it.u, it.tags.keys.toList()) }) { "building[$i].shops" }
        }
        val rs = want["roads"] as List<*>
        t.eq(rs.size, S.roads.size) { "roads" }
        for ((i, r0) in rs.withIndex()) {
            if (i >= S.roads.size) break
            val w = r0 as Map<*, *>; val r = S.roads[i]
            t.eq(listOf(w["pts"], w["w"], w["hw"], w["tags"]), listOf(r.pts, r.w, r.hw, r.tags.keys.toList())) { "road[$i]" }
        }
        t.eq(want["crossings"].let { (it as List<*>).map { c -> (c as Map<*, *>).let { m -> listOf(m["pts"], m["tags"]) } } },
            S.crossings.map { listOf(it.pts, it.tags.keys.toList()) }) { "crossings" }
        t.eq((want["areas"] as List<*>).map { a -> (a as Map<*, *>).let { listOf(it["poly"], it["kind"]) } }, S.areas.map { listOf(it.poly, it.kind) }) { "areas" }
        t.eq(want["water"], S.water) { "water" }
        t.eq(want["nodes"], S.nodes.map { listOf(it.x, it.z, it.id, it.tags.keys.toList()) }) { "nodes" }
        t.eq(want["trees"], S.trees.map { listOf(it.x, it.z, it.genus, it.species, it.common, it.dbh, it.source, it.tags) }) { "trees" }
        t.eq(want["pois"], S.pois.map { listOf(it.x, it.z, it.tags.keys.toList()) }) { "pois" }
        t.eq(want["water_polygon"], water) { "water_polygon" }
        println("load: ${S.buildings.size} buildings (${S.buildings.count { it.year != null }} with a year, ${S.buildings.sumOf { it.shops.size }} shopfronts), " +
            "${S.roads.size} roads, ${S.crossings.size} crossings, ${S.areas.size} areas, ${S.nodes.size} nodes, ${S.trees.size} trees, ${S.pois.size} pois")
        println("kotlin load ${loaded.ms[0]} ms, fronts ${loaded.ms[1]} ms, attach_shops ${loaded.ms[2]} ms; python ${oracleTimes.trim().lines().last()}")
        t.done()
    }

    @Test
    fun shadowsThemesAndHelpers() {
        val S = loaded.S
        val f = json("funcs.json")
        val t = Tally("Shadows, theme, nearest_road_dir, tree_size, water_polygon, street_bearing")
        @Suppress("UNCHECKED_CAST")
        val themes = f["theme"] as Map<String, Map<String, Any?>>
        for ((n, w) in themes) {
            val got = Real.theme(n)
            t.text(w.keys.toList(), got.keys.toList()) { "theme($n) keys" }
            for (k in w.keys) t.eq(w[k], got[k]) { "theme($n)[$k]" }
        }
        val drive = S.roads.filter { it.hw != "path" }
        for (r0 in f["nearest_road_dir"] as List<*>) {
            val r = r0 as List<*>
            val p = v2(r[0])
            t.eq(r[1], Real.nearestRoadDir(if (p.x == 0.0 && p.y == 0.0) emptyList() else drive, p.x, p.y)) { "nearest_road_dir(${r[0]})" }
        }
        val trees = S.trees.take(40) + S.trees.takeLast(40)
        for ((i, r0) in (f["tree_size"] as List<*>).withIndex()) {
            val r = r0 as List<*>
            t.eq(r[1], Real.treeSize(trees[i])) { "tree_size(${r[0]})" }
        }
        for (sr0 in f["shadows"] as List<*>) {
            val sr = sr0 as Map<*, *>
            val sh = Real.Shadows(S.buildings, v3(sr["L"]))
            t.eq(sr["maxh"], sh.maxh) { "maxh" }
            val lit = sr["lit"] as List<*>
            for ((j, r0) in lit.withIndex()) {
                val r = r0 as List<*>
                val k = (r[1] as Number).toInt()
                val skip = if (k >= 0) S.buildings[k] else null
                val got = if (j % 2 == 0) sh.lit(v3(r[0]), skip) else sh.lit(v3(r[0]), skip, reach = 60.0)
                t.eq(r[2], got) { "lit(${r[0]}, skip=$k, L=${sr["L"]})" }
            }
            for (r0 in sr["shadow_height"] as List<*>) {
                val r = r0 as List<*>
                val k = (r[1] as Number).toInt()
                val p = v3(r[0])
                t.eq(r[2], sh.shadowHeight(p.x, p.z, if (k >= 0) S.buildings[k] else null)) { "shadow_height(${r[0]}, skip=$k)" }
            }
        }
        t.eq(f["bb"], S.buildings.map { it.bb!!.toList() }) { "bb" }
        val sh0 = Real.Shadows(S.buildings, V3(0.0, 1.0, 0.0))
        for (r0 in f["top"] as List<*>) {
            val r = r0 as Map<*, *>
            val b = r["b"] as Map<*, *>
            val src = S.buildings[(r["i"] as Number).toInt()]
            val prism = object : Real.Prism {
                override val poly = src.poly
                override val h = d(b["h"])
                override var bb: DoubleArray? = null
                override val roofH = (b["roof_h"] as Number?)?.toDouble()
                override val roof = b["roof"] as String?
                override val orient = b["orient"] as String?
                override var rf: DoubleArray? = null
            }
            for (q0 in r["top"] as List<*>) {
                val q = q0 as List<*>
                val p = v2(q[0])
                t.eq(q[1], sh0.top(prism, p.x, p.y)) { "top($b, ${q[0]})" }
            }
            t.eq(r["rf"], prism.rf?.toList()) { "top($b) roof frame" }
        }
        for (r0 in f["water_polygon"] as List<*>) {
            val r = r0 as List<*>
            t.eq(r[1], Real.waterPolygon((r[0] as List<*>).map { v2s(it) })) { "water_polygon(${r[0]})" }
        }
        val cfg = json("anchors.json")["cfg"] as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val cam = CityTest.camera(cfg as Map<String, Any?>)
        var docks = 0
        for (r0 in f["draws"] as List<*>) {
            val r = r0 as List<*>
            val th = Real.theme(r[1] as String)
            val L = if (r[1] == "golden") Living.sunVector(240.0, 20.0) else V3(0.0, -1.0, 0.0)
            th["_L"] = L
            val sc = Living.Scene(emptyList(), th, cam, L, emptyList())
            val a = r[2] as List<*>
            val got = if (r[0] == "car") Real.drawCar(sc, d(a[0]), d(a[1]), d(a[2]), d(a[3]), a[4] as String)
            else Real.drawDock(sc, S.nodes[(a[0] as Number).toInt()], drive, th).also { docks++ }
            t.text(r[3], got) { "draw_${r[0]}(${r[1]}, $a)" }
        }
        println("draw_car / draw_dock: ${(f["draws"] as List<*>).size} calls, $docks docks")
        for (r0 in f["street_bearing"] as List<*>) {
            val r = r0 as List<*>
            t.eq(r[1], Real.streetBearing(S, r[0] as String)) { "street_bearing(${r[0]})" }
        }
        t.done()
    }

    @Test
    fun everyViewRendersTheSameSvg() {
        val S = loaded.S; val water = loaded.water
        val doc = json("views/views.json")
        @Suppress("UNCHECKED_CAST")
        val views = doc["views"] as Map<String, Map<String, Any?>>
        val vdir = File(dir, "views")
        val lines = ArrayList<String>()
        var failures = 0
        var kt = 0.0; var py = 0.0
        fun check(name: String, want: String, got: String, ms: Double, pySec: Double, extra: String = "") {
            val diff = SvgDiff.compare(want, got)
            val mine = File(vdir, "$name.kt.svg")
            if (diff.identicalText) mine.delete() else mine.writeText(got)
            kt += ms; py += pySec * 1000
            lines.add(String.format(java.util.Locale.ROOT, "%-24s %8d chars  %s  divergences %d  similarity %.6f  kotlin %.0f ms  python %.0f ms%s",
                name, want.length, if (diff.identicalText) "byte-identical" else "DIFFERENT", diff.divergenceCount, diff.similarity, ms, pySec * 1000, extra))
            if (!diff.identicalText) {
                failures++; lines.add(diff.toString())
            }
        }
        for ((name, v) in views) {
            val tn = v["theme"] as String
            if ("split" in v) {
                val th = Real.theme(tn)
                @Suppress("UNCHECKED_CAST")
                val cfg = views["main_golden"]!!["cfg"] as Map<String, Any?>
                val b = S.buildings[(v["split"] as Number).toInt()]
                val t0 = System.nanoTime()
                val sp = Real.renderSplit(S, th, cfg, water, b)
                val v1 = sp.variant(b, false); val v2 = sp.variant(b, true)
                val ms = (System.nanoTime() - t0) / 1e6
                if (!CityTest.same(v["depth"], sp.depth)) {
                    failures++; lines.add("$name depth: python=${v["depth"]} kotlin=${sp.depth}")
                }
                for ((part, txt) in listOf("base" to sp.base, "front" to sp.front, "variant" to v1, "variant_truth" to v2)) {
                    check("${name}_$part", File(vdir, "${name}_$part.svg").readText(), txt, if (part == "base") ms else 0.0, if (part == "base") d(v["seconds"]) else 0.0)
                }
                continue
            }
            @Suppress("UNCHECKED_CAST")
            val cfg = v["cfg"] as Map<String, Any?>
            val th = Real.theme(tn)
            @Suppress("UNCHECKED_CAST")
            th.putAll(v["extra"] as Map<String, Any?>)
            if ("error" in v) {
                // real.py stops on a named building:colour ('white') in view; the port must stop there too
                val err = runCatching { Real.render(S, th, cfg, water) }.exceptionOrNull()
                val ok = err is NumberFormatException
                lines.add("%-24s python: ValueError (%s)  kotlin: %s".format(name, v["error"], err ?: "no error"))
                if (!ok) failures++
                continue
            }
            val t0 = System.nanoTime()
            val out = Real.render(S, th, cfg, water)
            val ms = (System.nanoTime() - t0) / 1e6
            val infoSame = CityTest.same((v["info"] as Map<*, *>).values.toList(), out.info.values.toList())
            if (!infoSame) {
                failures++; lines.add("$name info: python=${v["info"]} kotlin=${out.info}")
            }
            check(name, File(vdir, "$name.svg").readText(), out.svg, ms, d(v["seconds"]), if (infoSame) "  info same" else "  info DIFFERS")
        }
        lines.add(String.format(java.util.Locale.ROOT, "total render: kotlin %.0f ms, python %.0f ms", kt, py))
        println(lines.joinToString("\n"))
        assertTrue("real.render differs:\n" + lines.joinToString("\n"), failures == 0)
    }

    @Test
    fun mainAnchorsAndStats() {
        val S = loaded.S
        val want = json("anchors.json")
        @Suppress("UNCHECKED_CAST")
        val got = Real.mainAnchors(S, want["cfg"] as Map<String, Any?>)
        val t = Tally("__main__ anchors and stats")
        t.eq((want["anchors"] as Map<*, *>).keys.toList(), (got["anchors"] as Map<*, *>).keys.toList()) { "anchor kinds" }
        for ((k, w) in want["anchors"] as Map<*, *>) {
            val g = (got["anchors"] as Map<*, *>)[k] as Map<*, *>? ?: continue
            val wm = w as Map<*, *>
            t.eq(wm["at"], g["at"]) { "$k at" }
            t.eq(wm["tags"], pyOf(g["tags"])) { "$k tags" }
            t.text((wm["tags"] as Map<*, *>).keys.toList(), (g["tags"] as Map<*, *>).keys.toList()) { "$k tag order" }
            t.eq(wm["info"], pyOf(g["info"])) { "$k info" }
        }
        t.eq(want["stats"], got["stats"]) { "stats" }
        println("anchors: " + (got["anchors"] as Map<*, *>).mapValues { (it.value as Map<*, *>)["at"] })
        t.done()
    }
}
