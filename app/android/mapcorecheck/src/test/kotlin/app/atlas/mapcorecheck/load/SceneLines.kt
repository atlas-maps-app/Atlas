package app.atlas.mapcorecheck.load

import app.atlas.render.PoiStyle
import app.atlas.render.V2
import app.atlas.render.py.Dict
import org.junit.Assert.assertTrue
import java.io.File
import java.util.IdentityHashMap

/**
 * The scene's canonical lines, as `CP/jni/TestLoad.cpp` writes them, from the port's dicts: what AmsterdamParityTest
 * and RegionsParityTest hold the core's loads to, line for line (see TestLoad.cpp for the layout).
 */
internal object SceneLines {
    /** One record's canonical line, as TestLoad.cpp writes it. */
    internal class Line(list: String, index: Int) {
        val sb = StringBuilder(256).append(list).append(' ').append(index)

        fun field(name: String): Line = raw(" ").raw(name).raw("=")
        fun real(v: Double): Line = raw(java.lang.Long.toHexString(java.lang.Double.doubleToRawLongBits(v)).padStart(16, '0'))
        fun real(v: Any?): Line = if (v == null) raw("-") else real((v as Number).toDouble())
        fun integer(v: Any?): Line = if (v == null) raw("-") else raw((v as Number).toLong().toString())
        fun flag(v: Any?): Line = raw(if (v as Boolean) "1" else "0")
        fun text(v: Any?): Line {
            if (v == null) return raw("-")
            sb.append('\'')
            for (b in (v as String).toByteArray(Charsets.UTF_8)) {
                val u = b.toInt() and 0xFF
                val plain = u in '0'.code..'9'.code || u in 'A'.code..'Z'.code || u in 'a'.code..'z'.code || u == '_'.code ||
                    u == '.'.code || u == ':'.code || u == '#'.code || u == '-'.code
                if (plain) sb.append(u.toChar()) else sb.append('%').append(HEX[u shr 4]).append(HEX[u and 15])
            }
            return this
        }
        fun points(v: Any?): Line {
            @Suppress("UNCHECKED_CAST")
            val p = v as List<V2>
            sb.append(p.size)
            for (q in p) {
                sb.append(',')
                real(q.x)
                sb.append(',')
                real(q.y)
            }
            return this
        }
        fun tags(v: Any?): Line {
            @Suppress("UNCHECKED_CAST")
            val t = (v as Map<String, Any?>?) ?: emptyMap()
            sb.append(t.size)
            for ((k, value) in t) {
                sb.append('|')
                text(k)
                sb.append('=')
                text(value)
            }
            return this
        }
        fun raw(s: String): Line {
            sb.append(s)
            return this
        }
        override fun toString(): String = sb.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun dicts(v: Any?): List<Dict> = (v as List<Dict>?) ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun rings(v: Any?): List<List<V2>> = (v as List<List<V2>>?) ?: emptyList()

    /** The port's scene [S] as canonical lines, in TestLoad.cpp's order. */
    fun lines(S: Dict, region: Boolean = false): Sequence<String> = sequence {
        val buildings = dicts(S["buildings"])
        val parts = dicts(S["parts"])
        val nodes = dicts(S["nodes"])
        val nodeIndex = IdentityHashMap<Any, Int>().also { m -> nodes.forEachIndexed { i, n -> m[n] = i } }
        val buildingIndex = IdentityHashMap<Any, Int>().also { m -> buildings.forEachIndexed { i, b -> m[b] = i } }
        for ((list, records) in listOf("building" to buildings, "part" to parts)) {
            for ((i, b) in records.withIndex()) {
                val l = Line(list, i)
                l.field("id").integer(b["id"])
                l.field("src").raw((b["src"] as String?) ?: "-")
                if (region) {
                    // without what generic.py's looks set or change: they join when they are ported
                    l.field("min").real(b["min"])
                    l.field("year").integer(b["year"])
                    l.field("otype").raw(if (b["otype"] == "relation") "relation" else "~")
                    l.field("parent_id")
                    if (b["parent_id"] != null) l.integer(b["parent_id"]) else l.raw("~")
                    l.field("in_church").raw(if (b["in_church"] == true) "1" else "~")
                    val bb = b["_bb"] as DoubleArray
                    l.field("bb").real(bb[0]).raw(",").real(bb[1]).raw(",").real(bb[2]).raw(",").real(bb[3])
                    l.field("places")
                    if ("places" in b) l.raw(dicts(b["places"]).joinToString(",") { nodeIndex.getValue(it).toString() }) else l.raw("~")
                    l.field("hidden")
                    if ("hidden" in b) l.flag(b["hidden"]) else l.raw("~")
                    // generic.py's looks, where the region has no tradition (with a tradition: not yet, and left out)
                    l.field("looks")
                    if (S["tradition"] == null) {
                        l.raw("1")
                        l.field("h").real(b["h"])
                        l.field("levels").integer(b["levels"])
                        l.field("roof").text(b["roof"])
                        l.field("roof_h").real(b["roof_h"])
                        l.field("orient").text(b["orient"])
                        l.field("mat").text(b["mat"])
                        l.field("rmat").text(b["rmat"])
                        l.field("col").text(b["col"])
                        l.field("rcol").text(b["rcol"])
                        l.field("mat_draw")
                        if ("mat_draw" in b) l.text(b["mat_draw"]) else l.raw("~")
                        l.field("rmat_draw").text(b["rmat_draw"])
                        l.field("kind").text(b["kind"])
                        l.field("col_draw").text(b["col_draw"])
                        l.field("rcol_draw").text(b["rcol_draw"])
                        l.field("no_win").flag(b["no_win"])
                        l.field("look_why").text(b["look_why"])
                        @Suppress("UNCHECKED_CAST")
                        l.field("guessed").raw((b["guessed"] as Map<String, Any?>).keys.joinToString(","))
                    } else {
                        l.raw("~")
                    }
                    l.field("poly").points(b["poly"])
                    l.field("tags").tags(b["tags"])
                    yield(l.toString())
                    continue
                }
                l.field("h").real(b["h"])
                l.field("min").real(b["min"])
                l.field("year").integer(b["year"])
                l.field("roof").text(b["roof"])
                l.field("roof_h").real(b["roof_h"])
                l.field("orient").text(b["orient"])
                l.field("mat").text(b["mat"])
                l.field("rmat").text(b["rmat"])
                l.field("col").text(b["col"])
                l.field("rcol").text(b["rcol"])
                l.field("mat_draw")
                if ("mat_draw" in b) l.text(b["mat_draw"]) else l.raw("~")
                l.field("levels").integer(b["levels"])
                @Suppress("UNCHECKED_CAST")
                l.field("guessed").raw((b["guessed"] as Map<String, Any?>).keys.joinToString(","))
                val bb = b["_bb"] as DoubleArray
                l.field("bb").real(bb[0]).raw(",").real(bb[1]).raw(",").real(bb[2]).raw(",").real(bb[3])
                l.field("places")
                if ("places" in b) l.raw(dicts(b["places"]).joinToString(",") { nodeIndex.getValue(it).toString() }) else l.raw("~")
                l.field("hidden")
                if ("hidden" in b) l.flag(b["hidden"]) else l.raw("~")
                l.field("poly").points(b["poly"])
                l.field("tags").tags(b["tags"])
                yield(l.toString())
            }
        }
        for ((i, r) in rings(S["water"]).withIndex()) yield(Line("water", i).field("ring").points(r).toString())
        for ((i, r) in dicts(S["roads"]).withIndex()) {
            val l = Line("road", i)
            l.field("id").integer(r["id"])
            l.field("hw").text(r["hw"])
            l.field("w").real(r["w"])
            l.field("layer").real(r["layer"])
            l.field("bridge").flag(r["bridge"])
            l.field("wguess").flag(r["wguess"])
            l.field("pts").points(r["pts"])
            l.field("tags").tags(r["tags"])
            yield(l.toString())
        }
        for ((i, r) in rings(S["rails"]).withIndex()) yield(Line("rail", i).field("pts").points(r).toString())
        for ((i, a) in dicts(S["areas"]).withIndex()) {
            yield(Line("area", i).field("kind").text(a["kind"]).field("poly").points(a["poly"]).field("tags").tags(a["tags"]).toString())
        }
        for ((i, t) in dicts(S["trees"]).withIndex()) {
            val l = Line("tree", i)
            l.field("node").integer(nodeIndex.getValue(t))
            l.field("h").real(t["h"])
            l.field("crown").real(t["crown"])
            @Suppress("UNCHECKED_CAST")
            l.field("guessed").raw((t["guessed"] as Map<String, Any?>).keys.joinToString(","))
            yield(l.toString())
        }
        for ((i, n) in nodes.withIndex()) {
            val l = Line("node", i)
            l.field("id").integer(n["id"])
            l.field("x").real(n["x"])
            l.field("z").real(n["z"])
            l.field("style")
            val st = n["style"] as PoiStyle.Style?
            if (st != null) {
                l.text(st.cat).raw(",").text(st.col).raw(",").text(st.glyph).raw(",").text(st.kind).raw(",")
                l.text(st.brand).raw(",").text(st.bg).raw(",").text(st.fg)
            } else {
                l.raw("~")
            }
            l.field("building")
            val b = n["_b"]
            if (b != null) l.integer(buildingIndex.getValue(b)) else l.raw("~")
            l.field("tags").tags(n["tags"])
            yield(l.toString())
        }
        for ((i, p) in dicts(S["pois"]).withIndex()) yield(Line("poi", i).field("node").integer(nodeIndex.getValue(p)).toString())
        for ((i, lb) in dicts(S["labels"]).withIndex()) {
            yield(Line("label", i).field("kind").text(lb["kind"]).field("name").text(lb["name"]).field("pts").points(lb["pts"]).toString())
        }
        if (!region) return@sequence
        for ((i, a) in dicts(S["land"]).withIndex()) {
            yield(Line("land", i).field("kind").text(a["kind"]).field("id").integer(a["id"]).field("poly").points(a["poly"]).field("tags").tags(a["tags"]).toString())
        }
        for ((i, a) in dicts(S["aero"]).withIndex()) {
            yield(Line("aero", i).field("kind").text(a["kind"]).field("id").integer(a["id"]).field("pts").points(a["pts"]).field("tags").tags(a["tags"]).toString())
        }
        for ((i, r) in dicts(S["big"]).withIndex()) {
            yield(Line("big", i).field("hw").text(r["hw"]).field("layer").real(r["layer"]).field("id").integer(r["id"]).field("pts").points(r["pts"]).field("tags").tags(r["tags"]).toString())
        }
        for ((i, r) in dicts(S["rail"]).withIndex()) {
            yield(Line("railway", i).field("crane").flag(r["crane"]).field("pts").points(r["pts"]).field("tags").tags(r["tags"]).toString())
        }
        for ((i, r) in dicts(S["power"]).withIndex()) yield(Line("power", i).field("pts").points(r["pts"]).field("tags").tags(r["tags"]).toString())
        for ((i, m) in dicts(S["marine"]).withIndex()) {
            yield(Line("marine", i).field("kind").text(m["kind"]).field("closed").flag(m["closed"]).field("pts").points(m["pts"]).field("tags").tags(m["tags"]).toString())
        }
        fun item(list: String, i: Int, d: Dict): String {
            val l = Line(list, i).field("id").integer(d["id"]).field("x").real(d["x"]).field("z").real(d["z"]).field("h").real(d["h"]).field("rotor").real(d["rotor"])
            l.field("poly")
            if (d["poly"] != null) l.points(d["poly"]) else l.raw("~")
            return l.field("tags").tags(d["tags"]).toString()
        }
        for ((i, d) in dicts(S["cranes"]).withIndex()) yield(item("crane", i, d))
        for ((i, d) in dicts(S["masts"]).withIndex()) yield(item("mast", i, d))
        for ((list, key) in listOf("gantry" to "gantries", "conveyor" to "conveyors")) {
            for ((i, g) in dicts(S[key]).withIndex()) {
                yield(Line(list, i).field("id").integer(g["id"]).field("h").real(g["h"]).field("pts").points(g["pts"]).field("tags").tags(g["tags"]).toString())
            }
        }
        for ((i, d) in dicts(S["turbines"]).withIndex()) yield(item("turbine", i, d))
        for ((i, d) in dicts(S["mtowers"]).withIndex()) yield(item("mtower", i, d))
        @Suppress("UNCHECKED_CAST")
        val towers = (S["towers"] as Map<Long, Pair<V2, Double?>>?) ?: emptyMap()
        for ((i, e) in towers.entries.withIndex()) {
            yield(Line("tower", i).field("id").integer(e.key).field("x").real(e.value.first.x).field("z").real(e.value.first.y).field("h").real(e.value.second).toString())
        }
        for ((i, p) in dicts(S["park"]).withIndex()) yield(Line("park", i).field("ring").points(p["ring"]).field("tags").tags(p["tags"]).toString())
        for ((i, r) in rings(S["water_holes"]).withIndex()) yield(Line("hole", i).field("ring").points(r).toString())
        @Suppress("UNCHECKED_CAST")
        val shore = (S["natural_shore"] as Collection<Any?>?) ?: emptyList()
        for ((i, r) in rings(S["water"]).withIndex()) if (shore.any { it === r }) yield(Line("shore", i).toString())
    }

    /** The fields of two lines that differ, each side's value cut to [width] characters. */
    private fun differences(a: String, b: String, width: Int = 160): String {
        val fa = a.split(' ')
        val fb = b.split(' ')
        if (fa.size != fb.size || fa.take(2) != fb.take(2)) return "core: ${a.take(width * 2)}\n      port: ${b.take(width * 2)}"
        return fa.indices.filter { fa[it] != fb[it] }.joinToString("\n      ") { "${fa[it].take(width)}  (the port: ${fb[it].take(width)})" }
    }

    /** Holds the core's load of [raw] to the port's scene [S]; the summary line. */
    fun compare(name: String, raw: File, S: Dict, portMs: Long, region: Boolean = false, core: (String) -> String): String {
        val out = File.createTempFile("atlas-load-$name-", ".txt")
        try {
            val summary = core(out.path)
            assertTrue("$name: the core did not load ${raw.path}: $summary", summary.startsWith("lines="))
            val mismatches = ArrayList<String>()
            var count = 0
            var differing = 0
            // what the derivations made, so that a field both sides leave empty cannot pass unnoticed
            val made = linkedMapOf("hidden" to 0, "with places" to 0, "styled" to 0, "guessed colours" to 0, "trees" to 0, "labels" to 0)
            out.bufferedReader().use { core ->
                for (port in lines(S, region)) {
                    if (" hidden=1 " in port) made["hidden"] = made.getValue("hidden") + 1
                    if (port.startsWith("building ") && " places=~ " !in port) made["with places"] = made.getValue("with places") + 1
                    if (port.startsWith("node ") && " style=~ " !in port) made["styled"] = made.getValue("styled") + 1
                    if ("building:colour" in port.substringAfter(" guessed=").substringBefore(' ')) made["guessed colours"] = made.getValue("guessed colours") + 1
                    if (port.startsWith("tree ")) made["trees"] = made.getValue("trees") + 1
                    if (port.startsWith("label ")) made["labels"] = made.getValue("labels") + 1
                    val mine = core.readLine()
                    if (mine == null) {
                        mismatches += "the core's scene ends before the port's line $count: ${port.take(200)}"
                        differing++
                        break
                    }
                    if (mine != port) {
                        differing++
                        if (mismatches.size < 8) mismatches += "line $count (${port.substringBefore('=').substringBeforeLast(' ')}):\n      ${differences(mine, port)}"
                    }
                    count++
                }
                val extra = core.readLine()
                if (extra != null) {
                    differing++
                    mismatches += "the core has more lines than the port's $count, from: ${extra.take(200)}"
                }
            }
            println("load $name: ${raw.length()} bytes; core $summary; the port $portMs ms; $count lines, $differing differ; $made")
            assertTrue("$name: $differing of $count records differ from the port's:\n  ${mismatches.joinToString("\n  ")}", differing == 0)
            return summary
        } finally {
            out.delete()
        }
    }


    private val HEX = "0123456789ABCDEF".toCharArray()
}
