package app.atlas.render

import app.atlas.render.py.Dict
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regions.kt, Terrain.kt, Relief.kt, Woods.kt, Coasters.kt and FetchDem.kt against render/regions.py and the modules it imports:
 * the helpers on samples, and every view of every region in data/regions/places.py that has its extract locally (the extracts are
 * not in git: on CI these tests skip), at every moment, with and without ATLAS_AO, compared layer by layer (g, o, o2 and the raster t)
 * as whole text or bytes; and the board audit's calls (cache/oracle/audit/calls.json) by md5.
 *
 * The Python runs in the test (regions_oracle.py, one process per job, three at a time) into cache/oracle/regions. A job's outputs
 * are reused while they are newer than every input (the .py files of render/, fetch/dem.py, the driver, the region's data). `-Datlas.regions=a,b`
 * limits the jobs to tags or views starting with those names.
 */
class RegionsTest {
    companion object {
        val dir = File(Oracle.out, "regions").also { it.mkdirs() }
        private val dataRoot = File(Oracle.repo, "data/regions")

        init {
            Regions.root = dataRoot
            Regions.parkRoot = File(Oracle.repo, "data/park")
            Regions.streetCfgDir = File(Oracle.repo, "cache/render") // read only: regions.py's cfg_for reads its street cameras there
        }

        /** One oracle job: a view, its moments, with or without ATLAS_AO. */
        class Job(val tag: String, val view: String, val moments: List<String>, val ao: Boolean, val cfg: Map<String, Any?>? = null) {
            val region: String get() = Regions.baseOf(view)
            fun spec(): String = PyDumps.dumps(linkedMapOf<String, Any?>("tag" to tag, "view" to view, "moments" to moments, "ao" to ao).also { if (cfg != null) it["cfg"] = cfg })
        }

        @Suppress("UNCHECKED_CAST")
        val JOBS: List<Job> by lazy {
            val out = ArrayList<Job>()
            for ((k, p) in Regions.PLACES) {
                val own = ((p["moments"] as Map<String, Any?>?) ?: emptyMap()).keys.toList()
                for (v in (p["views"] as Map<String, Any?>).keys) {
                    val view = k + (if (v.isNotEmpty()) "_$v" else "")
                    for (ao in listOf(false, true)) out.add(Job(view + (if (ao) "_ao" else ""), view, listOf("sunny", "grey", "wet") + own, ao))
                }
            }
            // the street-level view on the boards (its camera only in cache/render, as the street renderer wrote it), whole and unsplit
            if (File(Regions.streetCfgDir, "paris_walk_detail_cfg.json").exists()) {
                for (ao in listOf(false, true)) {
                    out.add(Job("paris_walk_detail" + (if (ao) "_ao" else ""), "paris_walk_detail", listOf("sunny", "grey", "wet"), ao))
                    out.add(Job("paris_walk_detail_ns" + (if (ao) "_ao" else ""), "paris_walk_detail", listOf("sunny", "grey", "wet"), ao, mapOf("obj_split" to 0)))
                }
            }
            val only = System.getProperty("atlas.regions")?.split(',')?.filter { it.isNotBlank() }
            out.filter { j -> File(File(dataRoot, j.region), "raw.json").exists() && (only == null || only.any { j.tag == it || j.view.startsWith(it) }) }
        }

        private fun inputsTime(region: String): Long {
            val files = (File(Oracle.repo, "render").listFiles { f -> f.name.endsWith(".py") }!!.toList() + File(Oracle.repo, "fetch/dem.py") + File(Oracle.drivers, "regions_oracle.py") +
                File(dataRoot, region).listFiles()!!.toList() + File(dataRoot, "places.py"))
            return files.maxOf { it.lastModified() }
        }

        /** Runs the Python for every job whose outputs are missing or older than its inputs. */
        fun runOracle(jobs: List<Job>) {
            val todo = jobs.filter { j -> val f = File(dir, "${j.tag}.json"); !f.exists() || f.lastModified() < inputsTime(j.region) }
            if (todo.isEmpty()) return
            val pool = Executors.newFixedThreadPool(3)
            val fs = todo.map { j -> pool.submit<Unit> { Oracle.run("regions_oracle.py", "render", j.spec(), dir.path, timeoutSec = 3600) } }
            pool.shutdown(); pool.awaitTermination(3, TimeUnit.HOURS)
            fs.forEach { it.get() }
        }

        @Suppress("UNCHECKED_CAST")
        fun readJson(f: File): Map<String, Any?> = Oracle.py(Json.parseToJsonElement(f.readText())) as Map<String, Any?>

        fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
        fun md5(s: String): String = md5(s.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `places resource is data regions places py`() {
        val py = Oracle.py(Json.parseToJsonElement(Oracle.run("places_json.py", Oracle.repo.path)))
        assertEquals(PyDumps.dumps(py), PyDumps.dumps(Regions.PLACES))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `helpers match regions py, fetch_dem, coasters and the surveys`() {
        Oracle.configure()
        val f = File(dir, "funcs.json")
        Oracle.run("regions_oracle.py", "funcs", "{}", f.path)
        val o = readJson(f).also { f.delete() }
        var n = 0
        for (e in o["rd"] as List<List<Any?>>) {
            val p = FetchDem.rd(pyNum(e[0]), pyNum(e[1])); val q = e[2] as List<Any?>
            assertEquals("rd ${e[0]} ${e[1]}", PyDumps.dumps(q), PyDumps.dumps(listOf(p.x, p.y))); n++
        }
        Regions.resetPlace(); Regions.root = dataRoot; Regions.configure("port")
        for (e in o["rd_to_local"] as List<List<Any?>>) {
            val p = Relief.rdToLocal(pyNum(e[0]), pyNum(e[1]))
            assertEquals("rd_to_local", PyDumps.dumps(e[2]), PyDumps.dumps(listOf(p.x, p.y))); n++
        }
        for (e in o["cfg_for"] as List<List<Any?>>) { assertEquals("cfg_for ${e[0]}", PyDumps.dumps(e[1]), PyDumps.dumps(Regions.cfgFor(e[0] as String))); n++ }
        for (e in o["simplify"] as List<List<Any?>>) {
            val r = Regions.simplify(v2s(e[0]), pyNum(e[1]))
            assertEquals("simplify", PyDumps.dumps(e[2]), PyDumps.dumps(r)); n++
        }
        for (e in o["offset_line"] as List<List<Any?>>) { assertEquals("offset_line", PyDumps.dumps(e[2]), PyDumps.dumps(Regions.offsetLine(v2s(e[0]), pyNum(e[1])))); n++ }
        for (e in o["smooth"] as List<List<Any?>>) { assertEquals("smooth", PyDumps.dumps(e[1]), PyDumps.dumps(Coasters.smooth(pyNum(e[0])))); n++ }
        for (e in o["subdivide"] as List<List<Any?>>) {
            val (P, Y) = Coasters.subdivide(v2s(e[0]), (e[1] as List<Any?>).map { pyNum(it) }, pyNum(e[2]))
            assertEquals("subdivide", PyDumps.dumps(e[3]), PyDumps.dumps(listOf(P, Y))); n++
        }
        for (e in o["land_kind"] as List<List<Any?>>) { assertEquals("land_kind ${e[0]}", e[1], Regions.landKind(e[0] as Map<String, Any?>)); n++ }
        for (dm in o["dem"] as List<Map<String, Any?>>) {
            Regions.resetPlace(); Regions.configure(dm["region"] as String)
            // math.cos is the platform's libm (macOS: faithfully, not correctly, rounded); StrictMath may differ in the last bit,
            // as it does for San Gimignano's latitude. The survey's own arithmetic is compared from the same KX.
            Amsterdam.KX = pyNum(dm["KX"])
            val D = Terrain.DEM(File(File(dataRoot, dm["region"] as String), dm["file"] as String))
            val pts = v2s(dm["pts"])
            val h = pts.map { D.h(it.x, it.y) }; val hn = pts.map { D.hn(it.x, it.y).let { v -> if (v.isNaN()) null else v } }
            assertEquals("DEM.h ${dm["region"]} ${dm["file"]}", PyDumps.dumps(dm["h"]), PyDumps.dumps(h))
            assertEquals("DEM.hn ${dm["region"]} ${dm["file"]}", PyDumps.dumps(dm["hn"]), PyDumps.dumps(hn)); n += 2 * pts.size
        }
        println("regions helpers: $n checks equal")
    }

    private fun pyNum(v: Any?): Double = (v as Number).toDouble()

    /** What one layer comparison found. */
    class Row(val tag: String, val moment: String, val layer: String, val identical: Boolean, val note: String)

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `every region view and moment renders as regions py does`() {
        assumeTrue("no local region extracts", JOBS.isNotEmpty())
        Oracle.configure()
        runOracle(JOBS)
        val rows = ArrayList<Row>(); val failures = ArrayList<String>(); val times = ArrayList<String>()
        val sips = File("/usr/bin/sips").exists()
        for (job in JOBS) {
            val res = readJson(File(dir, "${job.tag}.json"))
            val files = res["files"] as Map<String, Map<String, Any?>>
            val pt = res["t"] as Map<String, Any?>
            val opts = RenderOptions(ao = job.ao)
            val t0 = System.nanoTime()
            val S: Dict = Regions.load(job.view, opts)
            val cfg = Regions.cfgFor(job.view).also { c -> job.cfg?.let { c.putAll(it) } }
            val tLoad = (System.nanoTime() - t0) / 1e9
            val tk = LinkedHashMap<String, Double>()
            for (m in job.moments) {
                val t1 = System.nanoTime()
                val r = Regions.render(S, m, cfg, opts)
                tk[m] = (System.nanoTime() - t1) / 1e9
                val mine = linkedMapOf("g" to r.g, "o" to r.o)
                if (r.o2 != null) mine["o2"] = r.o2
                val theirs = files.values.filter { it["moment"] == m }.associateBy { it["layer"] as String }
                for (layer in (theirs.keys + mine.keys).distinct().filter { it == "g" || it == "o" || it == "o2" }) {
                    val fn = "${job.tag}__${m}__$layer.svg"
                    val kt = mine[layer]; val meta = theirs[layer]
                    if (kt == null || meta == null) {
                        rows.add(Row(job.tag, m, layer, false, if (kt == null) "missing in Kotlin" else "not written by Python")); failures.add("$fn: ${if (kt == null) "missing in Kotlin" else "extra in Kotlin"}"); continue
                    }
                    val same = md5(kt) == meta["md5"]
                    rows.add(Row(job.tag, m, layer, same, ""))
                    if (!same) {
                        File(dir, "kt_$fn").writeText(kt)
                        val pyFile = File(dir, fn) // the outputs may have been cleared to save disk: the md5s stay in <tag>.json
                        failures.add("$fn: " + if (pyFile.exists()) SvgDiff.compare(pyFile.readText(), kt, keep = 6).toString() else "md5 ${md5(kt)} vs ${meta["md5"]} (rerun the oracle for the diff)")
                    } else File(dir, "kt_$fn").delete()
                }
                val tMeta = theirs["t"]; val raster = r.t
                if (tMeta != null || raster != null) {
                    if (tMeta == null || raster == null) {
                        rows.add(Row(job.tag, m, "t", false, "raster on one side only")); failures.add("${job.tag} $m t: raster on one side only"); continue
                    }
                    var same = raster.ext == tMeta["ext"] && raster.clip == tMeta["clip"]
                    var note = ""
                    if (raster.ext == "jpg") {
                        val rgb = theirs["t_rgb"]
                        val pix = rgb != null && md5(raster.source!!) == rgb["md5"]
                        note = if (pix) "pixels identical" else "pixels differ"
                        same = same && pix && (!sips || md5(raster.data) == tMeta["md5"])
                        if (!sips) note += ", JPEG not checked (no sips)"
                    } else {
                        same = same && md5(raster.data) == tMeta["md5"]
                    }
                    if (!same) {
                        File(dir, "kt_${job.tag}__${m}__t.${raster.ext}").writeBytes(raster.data)
                        failures.add("${job.tag} $m t: ${raster.ext} md5 ${md5(raster.data)} vs ${tMeta["md5"]}, clip ${if (raster.clip == tMeta["clip"]) "same" else "differs"}; $note")
                    }
                    rows.add(Row(job.tag, m, "t", same, note))
                }
            }
            times.add("%-28s load %6.1f s (Python %6.1f s); %s".format(job.tag, tLoad, (pt["load"] as Number).toDouble(),
                job.moments.joinToString("; ") { m -> "%s %5.1f s (Python %5.1f s)".format(m, tk[m], (pt[m] as Number).toDouble()) }))
            println(times.last())
        }
        val table = StringBuilder("region view × moment × layer (identical = byte for byte):\n")
        for (r in rows) table.append("%-28s %-6s %-3s %s %s\n".format(r.tag, r.moment, r.layer, if (r.identical) "identical" else "DIFFERS  ", r.note))
        File(dir, "report.txt").writeText(table.toString() + "\n" + times.joinToString("\n") + "\n\n" + failures.joinToString("\n\n"))
        println(table)
        assertTrue("${failures.size} layers differ:\n" + failures.take(8).joinToString("\n\n"), failures.isEmpty())
    }

    /**
     * The Main board: grachten, view home, at sunny, loaded as `regions.load('grachten_home')` (its 0.7 m generalisation), split as
     * regions.py splits it. The port must equal a fresh run of today's Python byte for byte. Today's Python wrote the 9 October render on
     * the board (cache/render/grachten_home_sunny_*.svg) byte for byte until regions.py's power-line fade (14 October): how many layers
     * still equal the board's files is reported, not asserted.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `Main board grachten_home sunny is byte-identical to today's Python`() {
        val job = JOBS.firstOrNull { it.tag == "grachten_home" }
        assumeTrue("the grachten extract is local only", job != null)
        Oracle.configure()
        runOracle(listOf(job!!))
        val fresh = (readJson(File(dir, "grachten_home.json"))["files"] as Map<String, Map<String, Any?>>).values.filter { it["moment"] == "sunny" }
        val t0 = System.nanoTime()
        val S = Regions.load("grachten_home")
        val r = Regions.render(S, "sunny", Regions.cfgFor("grachten_home"))
        val t = (System.nanoTime() - t0) / 1e9
        val mine = mapOf("g" to r.g, "o" to r.o, "o2" to r.o2)
        val board = ArrayList<String>()
        for (layer in listOf("g", "o", "o2")) {
            val kt = mine[layer] ?: ""
            val py = fresh.first { it["layer"] == layer }["md5"]
            assertEquals("grachten_home sunny $layer against today's Python", py, md5(kt))
            val f = File(Oracle.repo, "cache/render/grachten_home_sunny_$layer.svg")
            if (f.exists()) board.add("$layer " + (if (md5(f.readBytes()) == md5(kt)) "equals the board render" else "differs from the board render"))
        }
        println("Main board: g, o, o2 equal today's Python (Kotlin %.1f s load and render); %s".format(t, board))
    }

    /** terrain.py's own drawing (regions.py draws terrain with relief.py): hillshade traced and flat, draw, Cover and drape on the dunes. */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `terrain py hillshade, draw, cover and drape on the dunes`() {
        assumeTrue("data/regions/dunes is local only", File(dataRoot, "dunes/dem.json").exists())
        Oracle.configure()
        val f = File(dir, "terrain_dunes.json")
        if (!f.exists() || f.lastModified() < inputsTime("dunes")) Oracle.run("regions_oracle.py", "terrain", "{\"view\": \"dunes\", \"moments\": [\"sunny\", \"wet\"]}", f.path, timeoutSec = 1800)
        val py = readJson(f)
        val S = Regions.load("dunes"); val cfg = Regions.cfgFor("dunes")
        val cam = Amsterdam.cameraOf(cfg)
        for ((k, e) in py["moments"] as Map<String, Map<String, Any?>>) {
            val th: Dict = LinkedHashMap(Amsterdam.THEMES.getValue(k))
            val hm = (th["hhmm"] as List<Any?>).map { (it as Number).toInt() }; val dt = (th["date"] as List<Any?>).map { (it as Number).toInt() }
            val (az, el) = Amsterdam.sunPosition(java.time.LocalDateTime.of(dt[0], dt[1], dt[2], hm[0], hm[1]).minusHours(Amsterdam.TZ_H.toLong()))
            th["_L"] = if (el > 0) Living.sunVector(az, el) else V3(0.0, -1.0, 0.0)
            for (trace in listOf(true, false)) {
                S.remove("_local")
                val png = Terrain.hillshade(S, cam, th, cfg, trace = trace, raw = true) as ByteArray?
                assertEquals("hillshade $k trace=$trace", e["hillshade_${if (trace) "True" else "False"}"], png?.let { md5(it) })
            }
            S.remove("_local")
            val lines = (S["_lines"] as List<Terrain.Line>).map { Terrain.Line(it.pts, it.width, it.colour, it.dash) }
            val svg = Terrain.draw(S, cam, th, { _, _ -> false }, ArrayList(), cfg, Regions.LAND, lines)
            assertEquals("terrain.draw $k length", e["draw_len"], svg.length)
            assertEquals("terrain.draw $k", e["draw"], md5(svg))
            assertEquals("wet cells $k", e["wet"], (S["_local"] as Terrain.Local?)?.wet?.size)
        }
        val cov = Terrain.Cover(S["land"] as List<Dict>, S["_lakes"] as List<List<V2>>, S["_seas"] as List<List<V2>>, Regions.LAND)
        val pts = v2s(py["cover_pts"])
        assertEquals("Cover.at", PyDumps.dumps(py["cover"]), PyDumps.dumps(pts.map { cov.at(it.x, it.y) }))
        val lakes = S["_lakes"] as List<List<V2>>
        val ring = if (lakes.isNotEmpty()) lakes[0] else listOf(V2(0.0, 0.0), V2(100.0, 0.0), V2(100.0, 80.0))
        assertEquals("drape", PyDumps.dumps(py["drape"]), PyDumps.dumps(Terrain.drape(S["dem"] as Terrain.DEM, ring.take(30), lift = 0.4)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun sunRounded(m: Map<String, Any?>): Map<String, Any?> = LinkedHashMap(m).also { o ->
        for ((k, v) in m) if (v is Map<*, *> && "az" in v) o[k] = LinkedHashMap(v as Map<String, Any?>).also {
            it["az"] = round9(it["az"]); it["el"] = round9(it["el"])
        }
    }

    private fun round9(v: Any?): Double = Math.round((v as Number).toDouble() * 1e9) / 1e9

    /** view_moments for every view (regions.py's `<name>_moments.json`): the counts of what is in view, each moment's sun and weather. */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `view moments match regions py`() {
        val views = JOBS.filter { !it.ao && it.cfg == null }.map { it.view }.distinct()
        assumeTrue("no local region extracts", views.isNotEmpty())
        Oracle.configure()
        val todo = views.filter { v -> val f = File(dir, "moments_$v.json"); !f.exists() || f.lastModified() < inputsTime(Regions.baseOf(v)) }
        val pool = Executors.newFixedThreadPool(3)
        todo.map { v -> pool.submit<Unit> { Oracle.run("regions_oracle.py", "moments", PyDumps.dumps(mapOf("view" to v)), File(dir, "moments_$v.json").path, timeoutSec = 1800) } }.forEach { it.get() }
        pool.shutdown()
        var lastBit = 0
        for (v in views) {
            val py = readJson(File(dir, "moments_$v.json"))
            val S = Regions.load(v)
            val kt = Regions.viewMoments(S, v, Regions.cfgFor(v))
            if (PyDumps.dumps(py) != PyDumps.dumps(kt)) lastBit++
            // the sun's azimuth and elevation run through libm's trig (macOS: faithful, not correctly rounded): compared to 1e-9 degrees
            assertEquals("view_moments $v", PyDumps.dumps(sunRounded(py)), PyDumps.dumps(sunRounded(kt)))
        }
        println("view_moments: ${views.size} views equal ($lastBit with a sun position off in its last bit)")
    }

    /**
     * The board audit's region calls (cache/oracle/audit/calls.json). Each IDENTICAL call is an acceptance test: the Kotlin output must
     * equal a fresh run of today's Python for that call (the job with the same view, flags and camera above). calls.json's recorded md5s
     * predate regions.py's power-line fade (14 October); how many layers still equal them is reported, not asserted.
     */
    @Suppress("UNCHECKED_CAST")
    @Test
    fun `board audit region calls match a fresh Python run`() {
        val calls = File(Oracle.repo, "cache/oracle/audit/calls.json")
        assumeTrue("no board audit", calls.exists())
        Oracle.configure()
        val all = Oracle.py(Json.parseToJsonElement(calls.readText())) as List<Map<String, Any?>>
        val mine = all.filter { (it["driver_spec"] as Map<String, Any?>)["kind"] == "regions" }
        val sips = File("/usr/bin/sips").exists()
        val failures = ArrayList<String>(); val skipped = ArrayList<String>(); var n = 0; var recorded = 0; var recordedSame = 0
        val used = ArrayList<Job>()
        for (c in mine) {
            val spec = c["driver_spec"] as Map<String, Any?>; val view = spec["view"] as String
            val ao = (c["env"] as Map<String, Any?>)["ATLAS_AO"] == "1"
            if (spec["no_dem5"] == true) { skipped.add("${c["tag"]} (an audit probe that hides dem5.json)"); continue }
            val job = JOBS.firstOrNull { it.view == view && it.ao == ao && it.cfg == spec["cfg"] }
            if (job == null) { skipped.add("${c["tag"]} (no local data or filtered out)"); continue }
            used.add(job)
        }
        assumeTrue("no audit calls with local data", used.isNotEmpty())
        runOracle(used.distinct())
        for (c in mine) {
            val spec = c["driver_spec"] as Map<String, Any?>; val view = spec["view"] as String
            val ao = (c["env"] as Map<String, Any?>)["ATLAS_AO"] == "1"
            if (spec["no_dem5"] == true) continue
            val job = JOBS.firstOrNull { it.view == view && it.ao == ao && it.cfg == spec["cfg"] } ?: continue
            val fresh = (readJson(File(dir, "${job.tag}.json"))["files"] as Map<String, Map<String, Any?>>).values
            val opts = RenderOptions(ao = ao)
            val S = Regions.load(view, opts); val cfg = Regions.cfgFor(view)
            (spec["cfg"] as Map<String, Any?>?)?.let { cfg.putAll(it) }
            val layers = c["layers"] as Map<String, Map<String, Any?>>
            for (m in spec["moments"] as List<String>) {
                val r = Regions.render(S, m, cfg, opts)
                val got = linkedMapOf("g" to md5(r.g), "o" to md5(r.o))
                r.o2?.let { got["o2"] = md5(it) }
                r.t?.let { if (it.ext == "png" || sips) got["t"] = md5(it.data) }
                for ((k, v) in layers) {
                    val (mm, layer) = k.split('/')
                    if (mm != m || layer !in got) continue
                    val py = fresh.firstOrNull { it["moment"] == m && it["layer"] == layer }?.get("md5")
                    n++
                    if (got[layer] != py) failures.add("${c["tag"]} $k: Kotlin ${got[layer]}, fresh Python $py")
                    recorded++
                    if (got[layer] == v["raw_md5"]) recordedSame++
                }
            }
        }
        println("board audit: $n region layers equal a fresh Python run: ${n - failures.size}; equal to the md5 recorded on 14 October: $recordedSame of $recorded; skipped: $skipped")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
