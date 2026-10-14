package app.atlas.render

import app.atlas.render.LivingTest.Tally
import app.atlas.render.py.Dict
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Amsterdam.kt (and PoiStyle.kt) against render/amsterdam.py on the committed Westerkerk extract, data/ams/raw.json:
 * the helpers on samples and on every loaded object, load() with and without the kit and the looks, and every render the
 * Python makes (each theme, lens, layer, split, quest variant, the kit cameras of the boards, the sky, the env flags),
 * compared as whole SVG text; locally also against the md5 of each call in the board audit (cache/oracle/audit/calls.json).
 */
class AmsterdamTest {
    companion object {
        private val dir = File(Oracle.out, "amsterdam").also { it.mkdirs() }
        private val raw = File(Oracle.repo, "data/ams/raw.json")
        private val cfgDir = File(Oracle.repo, "app/android/render/src/test/resources/amsterdam")
        private fun cfgFile(name: String) = File(cfgDir, "${name}_kit_cfg.json").path
        private val DEFAULT_CFG: Map<String, Any?> = linkedMapOf("W" to 412, "H" to 915, "target" to listOf(-10, 0, -30), "yaw" to 52, "pitch" to 38, "dist" to 430,
            "fov" to 36, "cy" to 440, "radius" to 380)

        private fun job(tag: String, kind: String, moments: List<String>, vararg extra: Pair<String, Any?>): Map<String, Any?> =
            linkedMapOf<String, Any?>("tag" to tag, "kind" to kind, "moments" to moments).also { it.putAll(extra) }

        private val WGW = listOf("wet", "grey", "sunny")
        private val SGW = listOf("sunny", "grey", "wet")
        private val TRUTH = listOf("truth", "truthgrey", "year", "golden")

        /** A group of jobs that share the env flags (amsterdam.py reads them at import: one Python process per group). */
        class Group(val name: String, val env: Map<String, String>, val jobs: List<Map<String, Any?>>)

        val GROUPS: List<Group> = listOf(
            Group("plain", emptyMap(), listOf(
                job("L_plain_noao", "amsfull", WGW),
                job("fixed", "amsfull", listOf("golden", "noon", "night")),
                job("T_truth_plain_noao", "amsfull", TRUTH.take(3)),
            ) + (Amsterdam.GAP_KEYS + listOf("none")).map { k -> job("T_lens_" + k.replace(':', '_'), "amsfull", listOf("grey"), "lens" to "tag:$k") } + listOf(
                job("quest", "quest", listOf("sunny", "golden", "night")),
                job("front", "amsfull", listOf("sunny", "wet"), "layer" to "front"),
                job("canal_plain", "amsfull", SGW, "cfgfile" to cfgFile("canal")),
                job("sky", "sky", SGW, "cfgfile" to cfgFile("canal")),
                job("high", "amsfull", listOf("sunny", "night"), "cfg" to mapOf("target" to listOf(60, 0, 40), "yaw" to -120, "pitch" to 62, "dist" to 900, "fov" to 40, "radius" to 700)),
            )),
            Group("ao", mapOf("ATLAS_AO" to "1"), listOf(
                job("L_plain", "amsfull", WGW),
                job("T_truth_plain", "amsfull", TRUTH),
                job("T_lens_leaf_ao", "amsfull", listOf("grey"), "lens" to "tag:leaf_type"),
            )),
            Group("kit", mapOf("ATLAS_KIT" to "1"), listOf(
                job("M_ams_kit", "ams", listOf("sunny")),
                job("E_canal_kit_noao", "ams", SGW, "cfgfile" to cfgFile("canal")),
                job("E_harten_kit_noao", "ams", SGW, "cfgfile" to cfgFile("harten")),
                job("E_wmarkt_kit_noao", "ams", SGW, "cfgfile" to cfgFile("wmarkt")),
                job("kit_objsplit", "ams", listOf("sunny", "night"), "cfg" to mapOf("obj_split" to 400000)),
                job("kit_quest", "quest", listOf("sunny"), "cfgfile" to cfgFile("wmarkt")),
            )),
            Group("kitao", mapOf("ATLAS_KIT" to "1", "ATLAS_AO" to "1"), listOf(
                job("M_ams_kit_ao", "ams", listOf("sunny")),
                job("E_canal_kit", "ams", SGW, "cfgfile" to cfgFile("canal")),
                job("E_harten_kit", "ams", SGW, "cfgfile" to cfgFile("harten")),
                job("E_wmarkt_kit", "ams", SGW, "cfgfile" to cfgFile("wmarkt")),
                job("T_truth_kit", "amsfull", TRUTH),
            )),
            Group("looks", mapOf("ATLAS_LOOKS" to "1"), listOf(
                job("L_looks_noao", "amsfull", WGW + listOf("truth")),
                job("T_truth_looks_noao", "amsfull", TRUTH),
            )),
            Group("looksao", mapOf("ATLAS_LOOKS" to "1", "ATLAS_AO" to "1"), listOf(
                job("L_looks", "amsfull", WGW + listOf("truth")),
                job("T_truth_looks", "amsfull", TRUTH),
            )),
            Group("daylight", mapOf("ATLAS_DAYLIGHT" to "1"), listOf(
                job("dusk", "amsfull", listOf("sunny", "d1830", "d1900", "d1915"), "extra_themes" to linkedMapOf(
                    "d1830" to listOf(listOf(2026, 10, 1), listOf(18, 30)), "d1900" to listOf(listOf(2026, 10, 1), listOf(19, 0)), "d1915" to listOf(listOf(2026, 10, 1), listOf(19, 15)))),
                job("dusk_canal", "amsfull", listOf("d1830", "d1900"), "cfgfile" to cfgFile("canal")),
            )),
        )

        fun optsOf(env: Map<String, String>) = RenderOptions(kit = env["ATLAS_KIT"] == "1", looks = env["ATLAS_LOOKS"] == "1", ao = env["ATLAS_AO"] == "1", daylight = env["ATLAS_DAYLIGHT"] == "1")

        private fun spec(name: String, env: Map<String, String>, jobs: List<Map<String, Any?>> = emptyList()): String =
            PyDumps.dumps(linkedMapOf("name" to name, "env" to env, "jobs" to jobs))

        @Suppress("UNCHECKED_CAST")
        private fun readJson(f: File): Map<String, Any?> = Oracle.py(Json.parseToJsonElement(f.readText())) as Map<String, Any?>

        /** Python's seconds for every group, after all the oracle runs (three at a time). */
        val pythonRuns: Map<String, Map<String, Any?>> by lazy {
            val pool = Executors.newFixedThreadPool(3)
            val futures = GROUPS.associate { g -> g.name to pool.submit<Unit> { Oracle.run("amsterdam_oracle.py", "render", spec(g.name, g.env, g.jobs), dir.path, timeoutSec = 1800) } }
            pool.shutdown(); pool.awaitTermination(40, TimeUnit.MINUTES)
            futures.mapValues { (name, f) -> f.get(); readJson(File(dir, "$name.json")) }
        }

        val funcs: Map<String, Any?> by lazy {
            val f = File(dir, "funcs.json")
            Oracle.run("amsterdam_oracle.py", "funcs", spec("funcs", emptyMap()), f.path)
            readJson(f).also { f.delete() }
        }

        fun loadDump(name: String, env: Map<String, String>): Map<String, Any?> {
            val f = File(dir, "load_$name.json")
            Oracle.run("amsterdam_oracle.py", "load", spec(name, env), f.path)
            return readJson(f).also { f.delete() }
        }

        fun md5(s: String): String = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        @Suppress("UNCHECKED_CAST")
        fun cfgOf(job: Map<String, Any?>): Map<String, Any?> {
            val cfg = LinkedHashMap(DEFAULT_CFG)
            (job["cfgfile"] as String?)?.let { cfg.putAll(Oracle.py(Json.parseToJsonElement(File(it).readText())) as Map<String, Any?>) }
            (job["cfg"] as Map<String, Any?>?)?.let { cfg.putAll(it) }
            return cfg
        }

        /** A Kotlin value as amsterdam_oracle.py's `js` dumps the Python one. */
        fun py(v: Any?): Any? = when (v) {
            is PoiStyle.Style -> linkedMapOf("cat" to v.cat, "col" to v.col, "glyph" to v.glyph, "kind" to v.kind, "brand" to v.brand, "bg" to v.bg, "fg" to v.fg)
            is Amsterdam.Edge -> listOf(py(v.a), py(v.b), v.nx, v.nz)
            is V2 -> listOf(v.x, v.y)
            is V3 -> listOf(v.x, v.y, v.z)
            is Pair<*, *> -> listOf(py(v.first), py(v.second))
            is DoubleArray -> v.toList()
            is IntArray -> v.toList()
            is Set<*> -> v.map { py(it) }
            is List<*> -> v.map { py(it) }
            is Map<*, *> -> LinkedHashMap<Any?, Any?>().also { m -> for ((k, x) in v) m[k] = py(x) }
            else -> v
        }
    }

    @Before
    fun setUp() {
        Oracle.configure()
        Amsterdam.amsRaw = raw
        Amsterdam.TONE = false
        Amsterdam.resetThemes()
    }

    @After
    fun tearDown() {
        Amsterdam.TONE = false
        Amsterdam.resetThemes()
    }

    private fun rows(k: String): List<*> = funcs[k] as List<*>
    private fun d(v: Any?) = (v as Number).toDouble()
    private fun eq(t: Tally, expected: Any?, actual: Any?, ctx: () -> String) = t.eq(expected, py(actual), ctx)

    @Test
    fun sunWeatherAndThemes() {
        val t = Tally("sun_position, year_colour, wx_at, weather_theme, THEMES")
        for (r in rows("sun_position")) {
            val (tt, want) = r as List<*>; val a = (tt as List<*>).map { (it as Number).toInt() }
            eq(t, want, Amsterdam.sunPosition(java.time.LocalDateTime.of(a[0], a[1], a[2], a[3], a[4]))) { "sun_position $a" }
        }
        for (r in rows("sun_position_at")) {
            val (tt, la, lo, want) = r as List<*>; val a = (tt as List<*>).map { (it as Number).toInt() }
            eq(t, want, Amsterdam.sunPosition(java.time.LocalDateTime.of(a[0], a[1], a[2], a[3], a[4]), d(la), d(lo))) { "sun_position $a $la $lo" }
        }
        for (r in rows("year_colour")) {
            val (y, want) = r as List<*>
            t.text(want, Amsterdam.yearColour((y as Number?)?.toInt())) { "year_colour $y" }
        }
        for (r in rows("wx_at")) {
            val (dt, hh, mm, want) = r as List<*>
            eq(t, want, Amsterdam.wxAt((dt as List<*>).map { (it as Number).toInt() }, (hh as Number).toInt(), (mm as Number).toInt())) { "wx_at $dt $hh:$mm" }
        }
        for (r in rows("weather_theme")) {
            val (dt, hm, want) = r as List<*>
            eq(t, want, Amsterdam.weatherTheme("x", (dt as List<*>).map { (it as Number).toInt() }, (hm as List<*>).map { (it as Number).toInt() })) { "weather_theme $dt $hm" }
        }
        eq(t, funcs["themes"], Amsterdam.THEMES) { "THEMES" }
        t.done()
    }

    @Test
    fun lightAndHaze() {
        val t = Tally("light, haze_at, hzc")
        for (r in rows("light")) {
            val (thn, tone, base, n, lit, hz, want) = r as List<*>
            Amsterdam.TONE = tone as Boolean
            val th: Dict = LinkedHashMap(Amsterdam.THEMES.getValue(thn as String)); th["_L"] = V3(0.3, 0.5, -0.81); th["_hz"] = d(hz)
            eq(t, want, Amsterdam.light(th, v3(base), v3(n), lit as Boolean)) { "light $thn tone=$tone $base $n $lit $hz" }
        }
        Amsterdam.TONE = false
        for (r in rows("haze_at")) {
            val (thn, eye, dd, want, col) = r as List<*>
            val th: Dict = LinkedHashMap(Amsterdam.THEMES.getValue(thn as String)); th["_eye"] = eye
            th["_hz"] = Amsterdam.hazeAt(th, d(dd))
            eq(t, want, th["_hz"]) { "haze_at $thn $eye $dd" }
            t.text(col, Amsterdam.hzc(th, "#2A2D30")) { "hzc $thn $eye $dd" }
        }
        t.done()
    }

    private operator fun <T> List<T>.component6(): T = this[5]
    private operator fun <T> List<T>.component7(): T = this[6]

    @Suppress("UNCHECKED_CAST")
    @Test
    fun ringsRoofsColoursAndLensesOnEveryObject() {
        val S = Amsterdam.load()
        val t = Tally("offset_ring, inset_ring, roof_dir, trad_ridge, surface_colour, tag_state, poi_style, road_dist, street_wall, water_edges")
        val bs = Amsterdam.run { (S["buildings"] as List<Dict>) + (S["parts"] as List<Dict>) }
        val want = rows("buildings")
        t.eq(want.size, bs.size) { "buildings" }
        val lenses = listOf(null, "truth", "year") + Amsterdam.GAP_KEYS.map { "tag:$it" }
        for ((i, w0) in want.withIndex()) {
            if (i >= bs.size) break
            val w = w0 as Map<String, Any?>; val b = bs[i]; val poly = Amsterdam.polyOf(b)
            t.eq(w["id"], b["id"]) { "building[$i].id" }
            val offs = listOf(0.25, 0.4, 0.6).map { Amsterdam.offsetRing(poly, it) }
            eq(t, w["offset"], offs) { "offset_ring ${b["id"]}" }
            eq(t, w["inset"], listOf(0.5, 0.8, 1.4).map { Amsterdam.insetRing(poly, it) }) { "inset_ring ${b["id"]}" }
            eq(t, w["roof_dir"], Amsterdam.roofDir(b)) { "roof_dir ${b["id"]}" }
            eq(t, w["ridge"], Amsterdam.tradRidge(b, poly)) { "trad_ridge ${b["id"]}" }
            eq(t, w["surface"], lenses.map { lz -> listOf(lz, Amsterdam.surfaceColour(b, lz), Amsterdam.surfaceColour(b, lz, "roof")) }) { "surface_colour ${b["id"]}" }
            eq(t, w["tag_state"], Amsterdam.GAP_KEYS.map { Amsterdam.tagState(b, it, "building") }) { "tag_state ${b["id"]}" }
        }
        val trees = S["trees"] as List<Dict>; val roads = S["roads"] as List<Dict>; val pois = S["pois"] as List<Dict>; val nodes = S["nodes"] as List<Dict>
        eq(t, rows("trees"), trees.map { n -> listOf(n["id"], Amsterdam.GAP_KEYS.map { Amsterdam.tagState(n, it, "tree") }) }) { "tag_state trees" }
        eq(t, rows("roads"), roads.map { r -> listOf(r["id"], Amsterdam.GAP_KEYS.map { Amsterdam.tagState(r, it, "road") }) }) { "tag_state roads" }
        eq(t, rows("pois"), pois.map { n -> listOf(n["id"], Amsterdam.GAP_KEYS.map { Amsterdam.tagState(n, it, "poi") }) }) { "tag_state pois" }
        eq(t, rows("style"), nodes.map { n -> listOf(n["id"], PoiStyle.style(n["tags"] as Map<String, Any?>)) }) { "poi_style.style" }
        for (r in rows("style_samples")) {
            val (tags, w) = r as List<*>
            eq(t, w, PoiStyle.style(tags as Map<String, Any?>)) { "poi_style.style $tags" }
        }
        for (r in rows("road_dist")) {
            val (x, z, w) = r as List<*>
            eq(t, w, Amsterdam.roadDist(S, d(x), d(z))) { "road_dist $x $z" }
        }
        val byId = nodes.associateBy { (it["id"] as Number).toLong() }
        val sw = rows("street_wall")
        t.eq(sw.size, nodes.count { it["_b"] != null }) { "places in buildings" }
        for (r in sw) {
            val (nid, bid, w) = r as List<*>
            val n = byId.getValue((nid as Number).toLong()); val b = n["_b"] as Dict
            t.eq(bid, b["id"]) { "place $nid in building" }
            eq(t, w, Amsterdam.streetWall(S, b, d(n["x"]), d(n["z"]))) { "street_wall $nid" }
        }
        val edges = Amsterdam.waterEdges(S); val water = S["water"] as List<List<V2>>
        eq(t, rows("water_edges"), edges.map { e -> listOf(py(e.a), py(e.b), e.nx, e.nz, water.indexOfFirst { it === e.r }) }) { "water_edges" }
        for (r in rows("near_water")) {
            val (x, z, dd, near, on) = r as List<*>
            t.eq(near, Amsterdam.nearWater(edges, listOf(V2(d(x), d(z))), d(dd))) { "near_water $x $z $dd" }
            t.eq(on, Amsterdam.onWater(S, d(x), d(z))) { "on_water $x $z" }
        }
        t.done()
    }

    private val BKEYS = listOf("poly", "h", "min", "src", "year", "id", "roof", "roof_h", "orient", "mat", "rmat", "col", "rcol", "levels", "guessed", "mat_draw",
        "hidden", "kind", "col_draw", "rmat_draw", "rcol_draw", "no_win", "look_why", "lv_why", "roof_why", "trad", "party", "in_church")

    @Suppress("UNCHECKED_CAST")
    private fun checkLoad(t: Tally, want: Map<String, Any?>, S: Dict) {
        for (k in listOf("buildings", "parts")) {
            val ws = want[k] as List<Map<String, Any?>>; val bs = S[k] as List<Dict>
            t.eq(ws.size, bs.size) { "$k count" }
            for ((i, w) in ws.withIndex()) {
                if (i >= bs.size) break
                val b = bs[i]
                for (key in BKEYS) if (key in w || key in b) eq(t, w[key], b[key]) { "$k[$i] (${b["id"]}).$key" }
                t.text(w["keys"], b.keys.toList()) { "$k[$i] (${b["id"]}) keys" }
                t.text(w["guessed_order"], (b["guessed"] as Map<String, Any?>?)?.keys?.toList() ?: emptyList<String>()) { "$k[$i] guessed order" }
                t.text(w["tags"], (b["tags"] as Map<String, Any?>).keys.toList()) { "$k[$i] tags" }
                eq(t, w["places"], ((b["places"] as List<Dict>?) ?: emptyList()).map { it["id"] }) { "$k[$i] places" }
            }
        }
        eq(t, want["water"], S["water"]) { "water" }
        val roads = S["roads"] as List<Dict>
        eq(t, want["roads"], roads.map { r -> linkedMapOf("pts" to r["pts"], "w" to r["w"], "hw" to r["hw"], "bridge" to r["bridge"], "layer" to r["layer"], "id" to r["id"],
            "wguess" to r["wguess"], "tags" to (r["tags"] as Map<String, Any?>).keys.toList()) }) { "roads" }
        eq(t, want["rails"], S["rails"]) { "rails" }
        eq(t, want["areas"], (S["areas"] as List<Dict>).map { a -> linkedMapOf("poly" to a["poly"], "kind" to a["kind"], "tags" to (a["tags"] as Map<String, Any?>).keys.toList()) }) { "areas" }
        eq(t, want["labels"], (S["labels"] as List<Dict>).map { l -> linkedMapOf("pts" to l["pts"], "name" to l["name"], "kind" to l["kind"]) }) { "labels" }
        val trees = (S["trees"] as List<Dict>).toSet(); val pois = (S["pois"] as List<Dict>)
        eq(t, want["nodes"], (S["nodes"] as List<Dict>).map { n ->
            linkedMapOf("id" to n["id"], "x" to n["x"], "z" to n["z"], "tree" to trees.any { it === n }, "poi" to pois.any { it === n }, "style" to n["style"],
                "b" to (n["_b"] as Dict?)?.get("id"), "h" to n["h"], "crown" to n["crown"], "guessed" to n["guessed"])
        }) { "nodes" }
        eq(t, want["tradition"], S["tradition"]) { "tradition" }
    }

    @Test
    fun loadPlainKitAndLooks() {
        for ((name, env) in listOf("plain" to emptyMap(), "kit" to mapOf("ATLAS_KIT" to "1"), "looks" to mapOf("ATLAS_LOOKS" to "1"))) {
            val want = loadDump(name, env)
            val t0 = System.nanoTime()
            val S = Amsterdam.load(opts = optsOf(env))
            val ms = (System.nanoTime() - t0) / 1_000_000
            println("load $name: Kotlin $ms ms, Python ${"%.0f".format(d(want["t_load"]) * 1000)} ms")
            val t = Tally("load ($name)")
            checkLoad(t, want, S)
            t.done()
        }
    }

    /** One Kotlin job, as amsterdam_oracle.py's render runs it: file name to SVG text. */
    @Suppress("UNCHECKED_CAST")
    private fun runJob(S: Dict, job: Map<String, Any?>, opts: RenderOptions, times: MutableMap<String, Double>): LinkedHashMap<String, String> {
        val cfg = cfgOf(job); val out = LinkedHashMap<String, String>(); val tag = job["tag"] as String
        (job["extra_themes"] as Map<String, Any?>?)?.forEach { (k, v) ->
            val (dt, hm) = v as List<*>
            Amsterdam.THEMES[k] = Amsterdam.weatherTheme(k, (dt as List<*>).map { (it as Number).toInt() }, (hm as List<*>).map { (it as Number).toInt() }, opts.daylight)
        }
        for (thn in job["moments"] as List<String>) {
            val t0 = System.nanoTime()
            fun put(layer: String, svg: String) { out["${tag}__${thn}__$layer.svg"] = svg }
            var lens = job["lens"] as String?
            if (lens == null && thn in setOf("truth", "truthgrey")) lens = "truth"
            if (lens == null && thn == "year") lens = "year"
            val th2 = if (thn == "truthgrey") "grey" else if (thn in setOf("year", "truth")) "golden" else thn
            when (job["kind"]) {
                "ams" -> if (lens == null) {
                    val r = Amsterdam.render(S, thn, cfg, lens, "split", opts)
                    put("g", r.svg); put("o", r.objs!![0]); if (r.objs.size > 1) put("o2", r.objs[1])
                } else error("kind ams with a lens")
                "quest" -> {
                    val target = v3(cfg["target"])
                    val cand = (S["buildings"] as List<Dict>).filter { !app.atlas.render.py.truthy(it["hidden"]) && app.atlas.render.py.truthy(it["h"]) && (it["tags"] as Map<*, *>)["building"] != "houseboat" }
                    var best: Dict? = null; var bd = 0.0
                    for (b in cand) {
                        val c = Real.centroid(Amsterdam.polyOf(b)); val dd = app.atlas.render.py.PyMath.hypot(c.x - target.x, c.y - target.z)
                        if (best == null || dd < bd) { best = b; bd = dd }
                    }
                    val q = Amsterdam.renderSplit(S, th2, cfg, lens, best!!, opts)
                    put("base", q.base); put("front", q.front)
                    put("variant", q.variant(best, null)); put("vtruth", q.variant(best, "truth"))
                    put("vhatch", q.variant(LinkedHashMap(best).also { it["_hatch"] = true }, "truth"))
                }
                "sky" -> put("sky", Amsterdam.renderSky(thn, cfg))
                else -> put("full", Amsterdam.render(S, th2, cfg, lens, job["layer"] as String?, opts).svg)
            }
            times["$tag/$thn"] = (System.nanoTime() - t0) / 1e9
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun everyRenderIsByteIdentical() {
        val runs = pythonRuns
        val audit = File(Oracle.out, "audit/calls.json").takeIf { it.isFile }?.let { f ->
            (Oracle.py(Json.parseToJsonElement(f.readText())) as List<Map<String, Any?>>).associateBy { it["tag"] as String }
        }
        val failures = ArrayList<String>(); val table = ArrayList<String>()
        var files = 0; var identical = 0; var auditChecked = 0; var auditSame = 0
        var ktTotal = 0.0; var pyTotal = 0.0
        for (g in GROUPS) {
            val opts = optsOf(g.env)
            Amsterdam.resetThemes(opts.daylight)
            val S = Amsterdam.load(opts = opts)
            val res = runs.getValue(g.name)
            for ((ji, job) in g.jobs.withIndex()) {
                val jr = (res["jobs"] as List<Map<String, Any?>>)[ji]
                val tag = job["tag"] as String
                assertEquals(tag, jr["tag"])
                val times = LinkedHashMap<String, Double>()
                val got = runJob(S, job, opts, times)
                val pyFiles = jr["files"] as Map<String, Map<String, Any?>>
                assertEquals("files of $tag", pyFiles.keys.toList(), got.keys.toList())
                for ((fn, meta) in pyFiles) {
                    files++
                    val kt = got.getValue(fn); val pyFile = File(dir, fn)
                    val py = pyFile.readText(Charsets.UTF_8)
                    val ktFile = File(dir, "$fn.kt.svg")
                    if (kt == py) {
                        identical++; pyFile.delete(); ktFile.delete()
                    } else {
                        ktFile.writeText(kt)
                        val r = SvgDiff.compare(py, kt)
                        failures.add("$fn: $r")
                    }
                    table.add("%-48s %s %8d B".format(fn, if (kt == py) "identical" else "DIFFERS  ", (meta["size"] as Number).toLong()))
                    val a = audit?.get(tag)
                    if (a != null) {
                        val m = fn.removeSuffix(".svg").split("__")
                        val layer = (a["layers"] as Map<String, Map<String, Any?>>)["${m[1]}/${m[2]}"]
                        if (layer != null) {
                            auditChecked++
                            if (md5(kt) == layer["raw_md5"]) auditSame++ else failures.add("$fn: md5 ${md5(kt)} != audit ${layer["raw_md5"]}")
                        }
                    }
                }
                val pyT = (jr["t"] as Map<String, Any?>).values.sumOf { d(it) }; val ktT = times.values.sum()
                pyTotal += pyT; ktTotal += ktT
                table.add("  %s: Kotlin %.2f s, Python %.2f s".format(tag, ktT, pyT))
            }
        }
        Amsterdam.resetThemes()
        println("Amsterdam renders: $files layers, $identical byte-identical; audit md5 $auditSame/$auditChecked; Kotlin %.1f s, Python %.1f s".format(ktTotal, pyTotal))
        table.forEach { println(it) }
        assertTrue("renders differ from Python:\n" + failures.take(8).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun liftCamRaisesEveryPoint() {
        val cam = Amsterdam.cameraOf(DEFAULT_CFG)
        assertTrue(Amsterdam.lift(cam, mapOf("x" to 1.0)) === cam)
        assertTrue(Amsterdam.lift(cam, mapOf("gy" to 0.0)) === cam)
        val lc = Amsterdam.lift(cam, mapOf("gy" to 3.25))
        val p = V3(12.0, 1.5, -40.0); val q = V3(12.0, 1.5 + 3.25, -40.0)
        assertEquals(cam.cam(q), lc.cam(p)); assertEquals(cam.proj(q), lc.proj(p)); assertEquals(cam.depth(q), lc.depth(p), 0.0)
        val pts = listOf(p, V3(20.0, 0.0, -30.0), V3(5.0, 2.0, -25.0))
        assertEquals(cam.poly(pts.map { V3(it.x, it.y + 3.25, it.z) }, 300.0), lc.poly(pts, 300.0))
        assertEquals(cam.C, lc.C); assertEquals(cam.focal, lc.focal, 0.0)
    }

    @Test
    fun weatherResourceIsTheRepoWeather() {
        val res = AmsterdamTest::class.java.getResourceAsStream("/wx/ams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        assertEquals(File(Oracle.repo, "data/wx/ams.json").readText(Charsets.UTF_8), res)
    }
}
