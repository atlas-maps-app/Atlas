package app.atlas.mapcorecheck.likeness

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * D16's planted failures that need no engine (K4's acceptance: "paint, shift, drops on the stub's frames"), judged on
 * the stub: the oracle's own frame of each base case with one fault planted (one paint 8 levels off, the frame 0.25 px
 * to the right, 20 small owners dropped, two neighbours in painter's order swapped, the sun shadows off, a canal name
 * moved, a generator-only element drawn without its filter). Each must FAIL the stub's gates (D16's final rule with the
 * stub's floor 0 and allowance 0), and the unplanted stub must PASS them on every case, or the gates could not tell.
 */
class PlantedTest {
    @Test
    fun `every planted failure that needs no engine fails on the stub's frames, and the unplanted stub passes`() {
        val t0 = System.nanoTime()
        val set = RefSetReader.open()
        val t = Thresholds.read()
        val judge = Judge(t, "stub")
        val tier = Likeness.tier
        // the control: the stub's own frames pass every case of the tier (structural first: FULL reads their MAD)
        val mads = HashMap<String, Double>()
        val controls = set.entries.values.filter { (tier == "full" || it.tier == "smoke") && !it.isProbe && it.pathName == null }.sortedBy { if (it.mode == "structural") 0 else 1 }.map { e ->
            Measure.case(e, Stub().draw(e), e.structural?.let { mads[it] }).also { r ->
                r.frame?.let { mads[e.name] = it.mad }
                r.judged = judge.case(e.name, e.klass, e.gates, r.measured)
            }
        }
        val badControls = controls.filter { it.outcome != "PASS" }.map { "${it.entry.name}: ${it.judged.filter { j -> j.outcome != "PASS" }}" }
        val plants = set.planted.values.filter { tier == "full" || it.tier == "smoke" }.map { p ->
            val e = set.entries[p.base] ?: error("plant ${p.name} stands on ${p.base}, which the set lacks")
            PlantResult(p, Measure.case(e, Stub(p).draw(e)).also { it.judged = judge.case(e.name, e.klass, e.gates, it.measured) })
        }
        println("stub controls: ${controls.size} cases, ${controls.count { it.outcome == "PASS" }} pass")
        for (p in plants) {
            val m = p.result.measured
            println("  %-24s must %-4s gave %-4s  MAD %.3f within2 %.4f p99 %.0f block %.2f flat %.6f owners %s labels %s px  caught by %s".format(Locale.ROOT,
                p.plant.name, p.plant.expect, p.result.outcome, m["mad"], m["within2"], m["p99"], m["block"], m["flat"],
                if (m["ownersIoU"].isNaN()) "n/a" else "%.4f".format(Locale.ROOT, m["ownersIoU"]), if (m["labelPx"].isNaN()) "n/a" else "%.2f".format(Locale.ROOT, m["labelPx"]),
                p.caughtBy.ifEmpty { listOf("nothing") }))
        }
        val page = Report.write(File(Likeness.report, "stub"), Run(tier, "stub", "A4", true, "stub (the oracle's own frames)", set, controls.take(4), emptyList(), plants,
            listOf("The stub stands in for an engine: each plant is one fault the oracle draws on purpose."), emptyList(), (System.nanoTime() - t0) / 1_000_000))
        println("report ${page.path}")
        assertTrue("the unplanted stub fails: $badControls", badControls.isEmpty())
        val wrong = plants.filter { !it.ok }.map { "${it.plant.name} gave ${it.result.outcome}, must ${it.plant.expect}" }
        assertTrue("planted failures not judged as they must be: $wrong", wrong.isEmpty())
        // every kind is judged where the set is the whole tier; a shard holds the plants whose base falls in it (the
        // nightly's second, the board's shift and drops alone), and the smoke tier on every push judges all three
        val kinds = plants.filter { it.plant.expect == "FAIL" }.map { it.plant.kind }.toSet()
        if (set.shard == null) for (k in listOf("paint", "shift", "drops")) assertTrue("the plant '$k' was judged", k in kinds)
    }

    /** The plants that need an engine wait for the package that brings it; each names one, so none is forgotten. */
    @Test
    fun `the plants that wait for an engine name the package they wait for`() {
        val files = File(Likeness.cases, "cases").listFiles { f -> f.name.endsWith(".json") }!!
        val waiting = files.flatMap { f -> Likeness.json(f)["planted"]?.jsonArray.orEmpty().map { it.jsonObject } }.filter { it["needs"] != null }
        for (p in waiting) {
            val needs = p["needs"]!!.jsonPrimitive.content
            assertTrue("${p["name"]} waits for '$needs'", Regex("K\\d+[a-z]?").matches(needs))
        }
        println("plants waiting for the engine: " + waiting.joinToString { "${it["name"]!!.jsonPrimitive.content} (${it["needs"]!!.jsonPrimitive.content})" })
    }
}
