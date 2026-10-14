package app.atlas.render

import app.atlas.render.CityTest.Companion.d
import app.atlas.render.CityTest.Companion.dict
import app.atlas.render.CloseupTest.Companion.m
import app.atlas.render.py.Dict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Generic.kt against render/generic.py: the helpers on the oracle's samples, and apply() over every building of
 * data/ams/raw.json (with Amsterdam's tradition, with the keep-homes looks, and with every other tradition), and of
 * every region whose extract is here (data/regions/<name>/raw.json: local only). Each building's dict after apply,
 * with every guessed value, tradition detail and reason, must be Python's, text for text.
 */
class GenericTest {
    companion object {
        private lateinit var funcs: JsonObject
        private val outDir = File(Oracle.out, "port-closeup-generic")
        private val trads = File(Oracle.repo, "render/traditions")

        @BeforeClass
        @JvmStatic
        fun oracle() {
            Oracle.configure()
            Generic.tradDir = trads
            funcs = Oracle.json("generic_oracle.py", "funcs").jsonObject
        }
    }

    @Before
    fun setUp() {
        Oracle.configure()
        Generic.tradDir = trads
    }

    private fun rows(k: String) = funcs[k]!!.jsonArray.map { dict(it) }

    @Test
    fun colours() {
        val t = PortTally("colour_hex, paint, jitter")
        for (r in rows("colour")) {
            val v = r["v"] as String?
            t.text(r["hex"], Generic.colourHex(v)) { "colour_hex($v)" }
            t.text(r["paint"], Generic.paint(v)) { "paint($v)" }
            t.text(r["paint3"], Generic.paint(v, 0.3)) { "paint($v, 0.3)" }
        }
        for (r in rows("jitter")) t.text(r["out"], Generic.jitter(r["h"] as String, r["seed"], d(r["k"]))) { "jitter $r" }
        t.done()
    }

    @Test
    fun pickEraAndLabels() {
        val t = PortTally("pick, era, kind_label")
        for (r in rows("pick")) t.eq(r["out"], Generic.pick(r["options"] as List<*>, r["seed"])) { "pick $r" }
        for (r in rows("era")) t.eq(r["out"], Generic.era(r["entry"], r["yr"])) { "era $r" }
        for (row in funcs["kind_label"]!!.jsonArray) {
            val r = CityTest.py(row) as List<*>
            t.text(r[1], Generic.kindLabel(r[0] as String?)) { "kind_label(${r[0]})" }
        }
        t.done()
    }

    @Test
    fun traditionEntriesAndRules() {
        val t = PortTally("trad_entry, fits")
        val P = HashMap<String, Map<String, Any?>>()
        for (r in rows("trad_entry")) {
            val p = P.getOrPut(r["tid"] as String) { Generic.tradition(r["tid"] as String)!! }
            val out = Generic.tradEntry(m(p[r["table"] as String]), r["bt"] as String, r["kind"] as String, r["modern"] as Boolean, r["yr"])
            t.eq(r["out"], out) { "trad_entry $r" }
        }
        var held = 0
        for (r in rows("fits")) {
            val b = LinkedHashMap(m(r["b"])); b["poly"] = v2s(b["poly"])
            val out = Generic.fits(m(r["when"]), b, r["kind"] as String, r["md"], d(r["short"]))
            if (out) held++
            t.eq(r["out"], out) { "fits $r" }
        }
        println("fits: $held rules held")
        t.done()
    }

    @Test
    fun kindsAndLooks() {
        val t = PortTally("kind_of, look")
        val seen = HashSet<String>()
        for (r in rows("look")) {
            val b = LinkedHashMap(m(r["b"]))
            b["poly"] = v2s(b["poly"])
            val lu = r["lu"] as String?; val region = r["region"] as String?
            val k = Generic.kindOf(m(b["tags"]), lu, region, Math.abs(Real.signedArea(v2s(b["poly"]))), b["h"])
            t.eq(r["kind_of"], k.toList()) { "kind_of $b $lu $region" }
            val lk = Generic.look(b, region, lu)
            seen.add(lk.kind)
            t.eq(r["look"], listOf(lk.wall, lk.roof, lk.kind, lk.why)) { "look $b $lu $region" }
        }
        println("look: ${seen.size} kinds seen")
        t.done()
    }

    @Test
    fun traditionsShipAsResources() {
        val res = File(Oracle.repo, "app/android/render/src/main/resources/traditions")
        val repoFiles = trads.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(repoFiles.size >= 6)
        for (f in repoFiles) assertEquals("resources/traditions/${f.name} is out of date with render/traditions", f.readText(), File(res, f.name).readText())
        Generic.tradDir = null
        for (f in repoFiles) {
            val id = f.name.removeSuffix(".json")
            val fromRes = Generic.tradition(id)
            Generic.tradDir = trads
            assertEquals(Generic.tradition(id), fromRes)
            Generic.tradDir = null
        }
        assertEquals(null, Generic.tradition(null))
        assertEquals(null, Generic.tradition(""))
        Generic.tradDir = trads
    }

    /** One case: Python's apply on the buildings it receives, against Kotlin's on the same copy. Returns (buildings, differences, Kotlin ms). */
    private fun runCase(case: String, py: JsonObject, t: PortTally): Triple<Int, Int, Double> {
        val state = m(Oracle.py(Json.parseToJsonElement(File(outDir, "$case.in.json").readText())))
        @Suppress("UNCHECKED_CAST")
        fun bs(k: String): List<Dict> = (state[k] as List<Any?>).map { e ->
            (e as Dict).also { b -> b["poly"] = v2s(b["poly"]) }
        }
        val buildings = bs("buildings"); val parts = bs("parts")
        val all = buildings + parts
        val lu = state["lu"] as List<*>?
        val byB = IdentityHashMap<Dict, String?>()
        if (lu != null) for (i in all.indices) byB[all[i]] = lu[i] as String?
        val S = mapOf("buildings" to buildings, "parts" to parts, "tradition" to state["tradition"])
        val t0 = System.nanoTime()
        Generic.apply(S, state["region"] as String?, if (lu != null) { b -> byB[b] } else null, state["keep_homes"] as Boolean)
        val ms = (System.nanoTime() - t0) / 1e6
        val expected = File(outDir, "$case.out.jsonl").readLines()
        assertEquals("$case: buildings", expected.size, all.size)
        val before = t.bad.size
        for (i in all.indices) t.text(expected[i], PyDumps.dumps(all[i])) { "$case building #$i id=${all[i]["id"]}" }
        val info = py[case]!!.jsonObject
        println(
            "apply %-22s %6d buildings, %d differ; Kotlin %.0f ms, Python %.0f ms (its loader %.1f s)".format(
                case, all.size, t.bad.size - before, ms, Oracle.seconds(info["secs"]) * 1000, Oracle.seconds(info["load_secs"]),
            ),
        )
        return Triple(all.size, t.bad.size - before, ms)
    }

    /** Runs the oracle for [cases], a few at once, then compares each. */
    private fun applyCases(what: String, cases: List<String>) {
        outDir.mkdirs()
        val pool = Executors.newFixedThreadPool(3)
        val runs = try {
            cases.map { c -> pool.submit(Callable { Json.parseToJsonElement(Oracle.run("generic_oracle.py", "apply", outDir.path, c)).jsonObject }) }.map { it.get() }
        } finally {
            pool.shutdown()
        }
        val t = PortTally(what)
        var n = 0
        for ((c, py) in cases.zip(runs)) {
            assertEquals("$c: the loader's own buildings and the copy differ in Python", py[c]!!.jsonObject["copy_same_as_live"].toString().let { it == "true" || it == "null" }, true)
            n += runCase(c, py, t).first
            File(outDir, "$c.in.json").delete(); File(outDir, "$c.out.jsonl").delete()
        }
        println("$what: $n buildings over ${cases.size} cases")
        t.done()
    }

    @Test
    fun applyAmsterdam() {
        val others = trads.listFiles { f -> f.name.endsWith(".json") && f.name != "catalogue.json" && f.name != "nl_amsterdam.json" }!!
            .map { "ams_kit@" + it.name.removeSuffix(".json") }.sorted()
        applyCases("apply over data/ams/raw.json", listOf("ams_kit", "ams_looks") + others)
    }

    @Test
    fun applyRegions() {
        val names = File(Oracle.repo, "data/regions").listFiles { f -> File(f, "raw.json").isFile }!!.map { it.name }.sorted()
        org.junit.Assume.assumeTrue("no region extracts here (they are local only)", names.isNotEmpty())
        val cases = names + (if ("paris" in names) listOf("paris@fr_paris") else emptyList())
        // the biggest first, so the pool is busy with them while the small ones run
        val order = cases.sortedByDescending { File(Oracle.repo, "data/regions/${it.substringBefore('@')}/raw.json").length() }
        applyCases("apply over the regions", order)
    }
}
