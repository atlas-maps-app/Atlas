package app.atlas.render

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Real.kt's stage 1 helpers against render/real.py: coordinates, num, the opening-hours parser over every
 * opening_hours value in data/ams/raw.json (and the local region extracts), and the polygon helpers.
 */
class RealTest {
    @Before
    fun setUp() = Oracle.configure()

    companion object {
        /** One oracle run for all the tests (it reads every local extract). */
        private val doc: JsonObject by lazy { Oracle.json("real_oracle.py", "helpers", Oracle.repo.path).jsonObject }
    }

    private class Tally(val what: String) {
        var checked = 0
        var noise = 0
        val bad = ArrayList<String>()
        fun eq(expected: Any?, actual: Any?, ctx: () -> String) {
            checked++
            if (CityTest.same(expected, actual)) return
            if (CityTest.same(expected, actual, Close::close)) noise++
            else bad.add("${ctx()}:\n    python=$expected\n    kotlin=$actual")
        }
        fun done() {
            println("$what: $checked checked, ${bad.size} differ" + if (noise > 0) ", $noise apart by libm noise" else "")
            assertEquals("$what differs from real.py:\n" + bad.take(15).joinToString("\n"), 0, bad.size)
        }
    }

    private fun py(k: String): Any? = Oracle.py(doc[k]!!)

    @Test
    fun constantsAndCoordinates() {
        val t = Tally("constants and XZ")
        @Suppress("UNCHECKED_CAST")
        val c = py("consts") as Map<String, Any?>
        t.eq(c["LAT0"], Real.LAT0) { "LAT0" }
        t.eq(c["LON0"], Real.LON0) { "LON0" }
        t.eq(c["KX"], Real.KX) { "KX" }
        t.eq(c["KZ"], Real.KZ) { "KZ" }
        t.eq(c["DAYS"], Real.DAYS) { "DAYS" }
        t.eq(c["MONTHS"], Real.MONTHS) { "MONTHS" }
        for (row in py("XZ") as List<*>) {
            val r = row as List<*>
            t.eq(r[2], Real.XZ(CityTest.d(r[0]), CityTest.d(r[1]))) { "XZ(${r[0]}, ${r[1]})" }
        }
        t.done()
    }

    @Test
    fun numReadsTheLeadingNumber() {
        val t = Tally("num")
        for (row in py("num") as List<*>) {
            val r = row as List<*>
            t.eq(r[2], Real.num(r[0], (r[1] as Number?)?.toDouble())) { "num(${r[0]}, ${r[1]})" }
        }
        t.done()
    }

    @Test
    fun openingHours() {
        val t = Tally("opening hours")
        for (row in py("_days") as List<*>) {
            val r = row as List<*>
            val got: Any = try {
                Real._days(r[0] as String).sortedBy { Real.DAYS.indexOf(it) }
            } catch (e: IllegalArgumentException) {
                "ValueError"
            }
            t.eq(r[1], got) { "_days('${r[0]}')" }
        }
        val oa = doc["open_at"]!!.jsonObject
        val times = (Oracle.py(oa["times"]!!) as List<*>).map { (it as List<*>).map { v -> (v as Number).toInt() }.toIntArray() }
        val dates = (Oracle.py(oa["dates"]!!) as List<*>).map { (it as List<*>).map { v -> (v as Number).toInt() }.toIntArray() }
        var strings = 0
        val t0 = System.nanoTime()
        for (row0 in oa["rows"]!!.jsonArray) {
            strings++
            @Suppress("UNCHECKED_CAST")
            val row = Oracle.py(row0) as Map<String, Any?>
            val oh = row["oh"] as String
            val want = row["open_at"] as List<*>
            var k = 0
            for (day in Real.DAYS) for (hm in times) for (dt in dates) {
                val got: Any? = try {
                    Real.openAt(oh, day, hm, dt)
                } catch (e: RuntimeException) {
                    "error"
                }
                val i = k++
                t.eq(want[i], got) { "open_at('$oh', $day, ${hm.toList()}, ${dt.toList()})" }
            }
            val states = row["state"] as List<*>
            k = 0
            for (day in Real.DAYS) for ((tm, spill) in listOf(600 to false, 1500 to true, 0 to false)) {
                val got: Any? = try {
                    Real._dayState(PyRe.strip(oh), day, tm, intArrayOf(2026, 9, 30), spill).let { listOf(it.first, it.second) }
                } catch (e: RuntimeException) {
                    "error"
                }
                val i = k++
                t.eq(states[i], got) { "_day_state('$oh', $day, $tm, $spill)" }
            }
        }
        println("opening hours: $strings strings, kotlin ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms")
        t.done()
    }

    @Test
    fun polygonHelpers() {
        val t = Tally("polygon helpers")
        for (p0 in py("polys") as List<*>) {
            @Suppress("UNCHECKED_CAST")
            val p = p0 as Map<String, Any?>
            val poly = v2s(p["poly"])
            t.eq(p["signed_area"], Real.signedArea(poly)) { "signed_area($poly)" }
            t.eq(p["centroid"], Real.centroid(poly)) { "centroid($poly)" }
            for (q0 in p["pip"] as List<*>) {
                val q = q0 as List<*>
                val pt = v2(q[0])
                t.eq(q[1], Real.pip(pt.x, pt.y, poly)) { "pip($pt, $poly)" }
            }
            for (s0 in p["seg"] as List<*>) {
                val s = s0 as List<*>
                val q = v2(s[0]); val a = v2(s[1]); val b = v2(s[2])
                t.eq(s[3], Real.segDist(q.x, q.y, a, b)) { "seg_dist($q, $a, $b)" }
                t.eq(s[4], Real.segDist(q.x, q.y, a, a)) { "seg_dist($q, $a, $a)" }
            }
        }
        t.done()
    }
}
