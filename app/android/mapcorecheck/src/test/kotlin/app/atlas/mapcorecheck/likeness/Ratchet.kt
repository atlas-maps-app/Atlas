package app.atlas.mapcorecheck.likeness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * The ratchet (D16): after a green run on `app`, CI proposes each threshold tightened to what was measured plus the
 * file's `ratchet` rule (MAD + 0.10, within 2 − 0.5 point, p99 + 2, block + 0.5, flat misses × 1.5, owner IoU share
 * − 1 point …), never looser than it is; the coordinator alone commits the proposal. A threshold looser than its fixed
 * value, or than the previous commit's, fails CI unless its history carries the owner's reason.
 */
object Ratchet {
    /** A proposal: thresholds.json with the tightened thresholds and the measured values, and what changed. */
    class Proposal(val json: JsonObject, val changes: List<String>)

    /**
     * Tightens [t] (thresholds.json) by [judged], the metrics of one green run on [platform], the worst value per limit
     * (a stage-wide limit judged by several cases takes the worst of them); [commit] is the commit measured.
     */
    fun propose(t: JsonObject, platform: String, judged: List<Judged>, commit: String?): Proposal {
        val rules = t["ratchet"]!!.jsonObject
        val worst = LinkedHashMap<List<String>, Pair<Limit, Double>>()
        for (j in judged) {
            val l = j.limit ?: continue
            if (j.value.isNaN()) continue
            val prev = worst[l.path]
            val worse = prev == null || (if (l.bound == "min") j.value < prev.second else j.value > prev.second)
            if (worse) worst[l.path] = l to j.value
        }
        val changes = ArrayList<String>()
        var out = t
        for ((path, lv) in worst) {
            val (l, v) = lv
            if (path.getOrNull(1) != platform) continue
            val rule = rules[l.metric] ?: continue
            val target = when {
                rule is JsonPrimitive && rule.isString && rule.content.startsWith("x") -> v * rule.content.substring(1).toDouble()
                else -> v + rule.jsonPrimitive.doubleOrNull!!
            }
            val tightened = if (l.bound == "min") maxOf(l.threshold, target) else minOf(l.threshold, target)
            val node = (get(out, path) as JsonObject).toMutableMap()
            node["measured"] = JsonPrimitive(round(v))
            node["commit"] = if (commit == null) node["commit"] ?: JsonPrimitive(null as String?) else JsonPrimitive(commit)
            if (tightened != l.threshold) {
                node["threshold"] = JsonPrimitive(round(tightened))
                changes.add("${path.drop(1).joinToString(".")}: ${fmt(l.threshold)} → ${fmt(round(tightened))} (measured ${fmt(v)})")
            }
            out = set(out, path, JsonObject(node)) as JsonObject
        }
        return Proposal(out, changes)
    }

    /**
     * The thresholds of [t] looser than their fixed value, or than [previous]'s (the last committed file), whose history
     * carries no entry approved by the owner (`{"approvedBy": "owner", "reason": …}`).
     */
    fun loosenings(t: JsonObject, previous: JsonObject? = null): List<String> {
        val bounds = t["metrics"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
        val out = ArrayList<String>()
        walk(t, emptyList()) { path, metric, o ->
            val bound = bounds[metric] ?: return@walk
            val th = o["threshold"]!!.jsonPrimitive.doubleOrNull ?: return@walk
            val fixed = o["fixed"]!!.jsonPrimitive.doubleOrNull ?: return@walk
            val before = previous?.let { get(it, path) as? JsonObject }?.get("threshold")?.jsonPrimitive?.doubleOrNull
            fun looser(a: Double, b: Double) = if (bound == "min") a < b - 1e-12 else a > b + 1e-12
            val approved = (o["history"] as? JsonArray)?.any { h -> (h as? JsonObject)?.get("approvedBy")?.jsonPrimitive?.content == "owner" && h["reason"] != null } == true
            if (!approved && looser(th, fixed)) out.add("${path.joinToString(".")}: threshold ${fmt(th)} is looser than its fixed ${fmt(fixed)}")
            if (!approved && before != null && looser(th, before)) out.add("${path.joinToString(".")}: threshold ${fmt(th)} is looser than the last commit's ${fmt(before)}")
        }
        return out
    }

    /** Every limit object (one with `fixed`) in [e], with its path and metric name. */
    fun walk(e: JsonElement, path: List<String>, f: (List<String>, String, JsonObject) -> Unit) {
        if (e !is JsonObject) return
        if ("fixed" in e && path.isNotEmpty()) { f(path, path.last(), e); return }
        for ((k, v) in e) walk(v, path + k, f)
    }

    fun get(e: JsonElement, path: List<String>): JsonElement? {
        var cur: JsonElement? = e
        for (k in path) cur = (cur as? JsonObject)?.get(k) ?: return null
        return cur
    }

    fun set(e: JsonElement, path: List<String>, v: JsonElement): JsonElement {
        if (path.isEmpty()) return v
        val o = e as JsonObject
        val m = LinkedHashMap(o)
        m[path[0]] = set(o[path[0]] ?: JsonObject(emptyMap()), path.drop(1), v)
        return JsonObject(m)
    }

    /** thresholds.json in its own layout: a metric's limit on one line, short flat objects on one line. */
    fun format(v: JsonElement, ind: Int = 0): String {
        val pad = "  ".repeat(ind)
        return when (v) {
            is JsonObject -> {
                if (v.isEmpty()) "{}"
                else if ("fixed" in v || (v.values.none { it is JsonObject || it is JsonArray } && v.toString().length < 110)) compact(v)
                else "{\n" + v.entries.joinToString(",\n") { (k, x) -> "$pad  ${JsonPrimitive(k)}: ${format(x, ind + 1)}" } + "\n$pad}"
            }
            is JsonArray -> if (v.none { it is JsonObject || it is JsonArray }) compact(v) else "[\n" + v.joinToString(",\n") { "$pad  ${format(it, ind + 1)}" } + "\n$pad]"
            else -> v.toString()
        }
    }

    /** One line, with Python's `json.dumps` spacing (", " and ": "). */
    private fun compact(v: JsonElement): String = when (v) {
        is JsonObject -> "{" + v.entries.joinToString(", ") { (k, x) -> "${JsonPrimitive(k)}: ${compact(x)}" } + "}"
        is JsonArray -> "[" + v.joinToString(", ") { compact(it) } + "]"
        else -> v.toString()
    }

    fun write(file: File, json: JsonObject) { file.parentFile?.mkdirs(); file.writeText(format(json) + "\n") }

    private fun round(v: Double): Double = "%.6g".format(Locale.ROOT, v).toDouble()
    private fun fmt(v: Double): String = "%.6g".format(Locale.ROOT, v)
}

class RatchetTest {
    private fun limit(fixed: Double, threshold: Double = fixed, history: String = "[]") =
        """{"fixed": $fixed, "threshold": $threshold, "measured": null, "commit": null, "history": $history}"""

    private val file = """{"metrics": {"mad": "max", "within2": "min", "flat": "max"},
        "ratchet": {"mad": 0.10, "within2": -0.005, "flat": "x1.5"},
        "platforms": {"lavapipe-x86_64": {"role": "gate", "gates": {
          "BOARD": {"stages": {"A1": {"mad": ${limit(9.0)}, "classes": {"canal": {"mad": ${limit(9.5)}}}}}},
          "WITHIN2": {"stages": {"A1": {"within2": ${limit(0.80)}}}},
          "FLAT": {"stages": {"A1": {"flat": ${limit(0.08)}}}}}}}}"""

    @Test
    fun `a green run tightens each threshold to the worst measured plus the rule, never looser`() {
        val t = Json.parseToJsonElement(file).jsonObject
        val th = Thresholds(JsonObject(t + ("stage" to JsonPrimitive("A1")) + ("stages" to JsonArray(listOf(JsonPrimitive("A1"))))))
        val judge = Judge(th, "lavapipe-x86_64", "A1")
        fun m(vararg kv: Pair<String, Double>) = Measured("x").also { mm -> for ((k, v) in kv) mm[k] = v }
        val judged = judge.case("a", "westerkerk", listOf("BOARD", "WITHIN2", "FLAT"), m("mad" to 2.0, "within2" to 0.97, "flat" to 0.001)) +
            judge.case("b", "westerkerk", listOf("BOARD", "WITHIN2", "FLAT"), m("mad" to 3.0, "within2" to 0.95, "flat" to 0.002)) +
            judge.case("c", "canal", listOf("BOARD"), m("mad" to 9.45))
        val p = Ratchet.propose(t, "lavapipe-x86_64", judged, "abc123")
        val board = Ratchet.get(p.json, listOf("platforms", "lavapipe-x86_64", "gates", "BOARD", "stages", "A1", "mad"))!!.jsonObject
        assertEquals("the worst MAD of the stage-wide limit + 0.10", 3.1, board["threshold"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals("abc123", board["commit"]!!.jsonPrimitive.content)
        assertEquals(0.945, Ratchet.get(p.json, listOf("platforms", "lavapipe-x86_64", "gates", "WITHIN2", "stages", "A1", "within2"))!!.jsonObject["threshold"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(0.003, Ratchet.get(p.json, listOf("platforms", "lavapipe-x86_64", "gates", "FLAT", "stages", "A1", "flat"))!!.jsonObject["threshold"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        // 9.45 + 0.10 is above the canal class's 9.5: it stays
        assertEquals(9.5, Ratchet.get(p.json, listOf("platforms", "lavapipe-x86_64", "gates", "BOARD", "stages", "A1", "classes", "canal", "mad"))!!.jsonObject["threshold"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals(3, p.changes.size)
        assertTrue(Ratchet.loosenings(p.json, t).isEmpty())
        // the layout round-trips
        assertEquals(p.json, Json.parseToJsonElement(Ratchet.format(p.json)).jsonObject)
    }

    @Test
    fun `a looser threshold fails unless the owner approved it`() {
        val loose = Json.parseToJsonElement(file.replace(limit(0.80), limit(0.80, 0.75))).jsonObject
        assertEquals(1, Ratchet.loosenings(loose).size)
        val approved = Json.parseToJsonElement(file.replace(limit(0.80), limit(0.80, 0.75, """[{"date": "2026-11-02", "from": 0.80, "to": 0.75, "reason": "a phone's driver", "approvedBy": "owner"}]"""))).jsonObject
        assertTrue(Ratchet.loosenings(approved).isEmpty())
        // tighter than fixed but looser than the last commit's
        val before = Json.parseToJsonElement(file.replace(limit(9.0), limit(9.0, 4.0))).jsonObject
        val after = Json.parseToJsonElement(file.replace(limit(9.0), limit(9.0, 5.0))).jsonObject
        assertEquals(1, Ratchet.loosenings(after, before).size)
    }

    @Test
    fun `a green run on the real file tightens what it measured and loosens nothing`() {
        val set = RefSetReader.open()
        val t = Thresholds.read()
        // the stub's smoke frames judged as if they were lavapipe-x86_64's at A1: all green
        val judge = Judge(t, "lavapipe-x86_64", "A1")
        val judged = set.entries.values.filter { it.tier == "smoke" && it.pathName == null && it.mode == "structural" }.flatMap { e ->
            val r = Measure.case(e, Stub().draw(e))
            judge.case(e.name, e.klass, e.gates, r.measured)
        }
        assertTrue("a green run", judged.isNotEmpty() && judged.all { it.outcome == "PASS" })
        val p = Ratchet.propose(t.json, "lavapipe-x86_64", judged, "0123abc")
        val home = Ratchet.get(p.json, listOf("platforms", "lavapipe-x86_64", "gates", "HOME_SUNNY", "stages", "A1", "mad"))!!.jsonObject
        assertEquals("measured 0 + 0.10", 0.1, home["threshold"]!!.jsonPrimitive.content.toDouble(), 1e-9)
        assertEquals("fixed stays", 8.0, home["fixed"]!!.jsonPrimitive.content.toDouble(), 0.0)
        // A2's limits were not measured: untouched
        assertEquals(t.json["platforms"]!!.jsonObject["lavapipe-x86_64"]!!.jsonObject["gates"]!!.jsonObject["HOME_SUNNY"]!!.jsonObject["stages"]!!.jsonObject["A2"],
            p.json["platforms"]!!.jsonObject["lavapipe-x86_64"]!!.jsonObject["gates"]!!.jsonObject["HOME_SUNNY"]!!.jsonObject["stages"]!!.jsonObject["A2"])
        assertTrue(Ratchet.loosenings(p.json, t.json).isEmpty())
        // a guidance platform proposes nothing
        assertTrue(Ratchet.propose(t.json, "lavapipe-macos", judged, null).changes.isEmpty())
        println("a green A1 run would tighten ${p.changes.size} limits, e.g. ${p.changes.take(3)}")
    }

    @Test
    fun `thresholds json gives every gate of every stage a value before its first run`() {
        val t = Thresholds.read()
        val missing = ArrayList<String>()
        var limits = 0
        Ratchet.walk(t.json["platforms"]!!, listOf("platforms")) { path, metric, o ->
            limits++
            if (metric !in t.bounds) missing.add("${path.joinToString(".")}: no direction for '$metric'")
            for (k in listOf("fixed", "threshold")) if ((o[k] as? JsonPrimitive)?.doubleOrNull == null) missing.add("${path.joinToString(".")}: no $k")
            for (k in listOf("measured", "commit", "history")) if (k !in o) missing.add("${path.joinToString(".")}: no $k")
        }
        // every gate the case files name is in the gating platform's table, at every stage D16 gives it a value
        val gates = t.platform("lavapipe-x86_64")["gates"]!!.jsonObject
        val named = File(Likeness.cases, "cases").listFiles()!!.flatMap { f -> Regex("\"gates\": \\[([^\\]]*)]").findAll(f.readText()).flatMap { m -> Regex("\"(\\w+)\"").findAll(m.groupValues[1]).map { it.groupValues[1] } } }.toSet()
        for (g in named) if (g !in gates) missing.add("the case files name gate $g, thresholds.json has none")
        for ((g, v) in gates) if (v.jsonObject["stages"]!!.jsonObject.isEmpty()) missing.add("gate $g has no stage")
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
        assertTrue(Ratchet.loosenings(t.json).isEmpty())
        println("thresholds.json: $limits limits, every one fixed; gates ${gates.keys}")
    }
}
