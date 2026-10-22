package app.atlas.mapcorecheck.load

import app.atlas.anchor.AnchorCases
import app.atlas.render.Amsterdam
import app.atlas.render.PoiStyle
import app.atlas.render.Regions
import app.atlas.render.V2
import app.atlas.render.live.LiveEngine
import app.atlas.render.py.Dict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap

/**
 * Stage P's loaders' test entry points in the core's host build (`CP/jni/TestLoad.cpp`), loaded from
 * `-Datlas.mapcore.host` (the library :mapcorecheck's coreHost task builds).
 */
internal object LoadHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's load entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The living map's load of [raw] written to [out] as canonical lines: `lines=… buildings=… … load_ms=…`, or `error=…`. */
    @JvmStatic external fun dump(raw: String, out: String): String

    /** The core's capitalize() of each UTF-8 text of [bytes] ([lengths] bytes each): each a u32 length and its bytes. */
    @JvmStatic external fun capitalize(bytes: ByteArray, lengths: IntArray): ByteArray
}

/**
 * Stage P held to the port (ENGINE.md step 3): the living map's load in the core (`CP/load/Amsterdam.hpp`) gives every
 * record and field the port's `Amsterdam.load` gives (with guess_tags, the places' styles and buildings, and `hidden`),
 * to the bit, in the same order. Both sides write the scene as TestLoad.cpp's canonical lines, compared one by one.
 *
 * The Westerkerk's scene is the port's as the packer loads the living map ([LiveEngine.loadScene]); the canal ring's
 * raw.json, and every raw.json `-Datlas.load.raws` names (a list split by the path separator: the renderer's
 * data/regions on this Mac), go through `Amsterdam.load` itself, in the Westerkerk's frame: data the living map's rules
 * never met, from Paris, New York and Bergen among them.
 */
class AmsterdamParityTest {
    /** One record's canonical line, as TestLoad.cpp writes it. */
    private class Line(list: String, index: Int) {
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
    private fun lines(S: Dict): Sequence<String> = sequence {
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
    }

    /** The fields of two lines that differ, each side's value cut to [width] characters. */
    private fun differences(a: String, b: String, width: Int = 160): String {
        val fa = a.split(' ')
        val fb = b.split(' ')
        if (fa.size != fb.size || fa.take(2) != fb.take(2)) return "core: ${a.take(width * 2)}\n      port: ${b.take(width * 2)}"
        return fa.indices.filter { fa[it] != fb[it] }.joinToString("\n      ") { "${fa[it].take(width)}  (the port: ${fb[it].take(width)})" }
    }

    /** Holds the core's load of [raw] to the port's scene [S]; the summary line. */
    private fun compare(name: String, raw: File, S: Dict, portMs: Long): String {
        val out = File.createTempFile("atlas-load-$name-", ".txt")
        try {
            val summary = LoadHost.dump(raw.path, out.path)
            assertTrue("$name: the core did not load ${raw.path}: $summary", summary.startsWith("lines="))
            val mismatches = ArrayList<String>()
            var count = 0
            var differing = 0
            // what the derivations made, so that a field both sides leave empty cannot pass unnoticed
            val made = linkedMapOf("hidden" to 0, "with places" to 0, "styled" to 0, "guessed colours" to 0, "trees" to 0, "labels" to 0)
            out.bufferedReader().use { core ->
                for (port in lines(S)) {
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

    @Test
    fun theCoreLoadsTheLivingMapAsThePortDoes() {
        val westerkerk = AnchorCases.westerkerk()
        val t0 = System.nanoTime()
        val scene = LiveEngine.loadScene(westerkerk)
        compare(westerkerk.id, File(westerkerk.dir, "raw.json"), scene.S, (System.nanoTime() - t0) / 1_000_000)
    }

    @Test
    fun theCoreLoadsOtherDataAsThePortDoes() {
        val raws = ArrayList<Pair<String, File>>()
        AnchorCases.grachten()?.let { raws += it.id to File(it.dir, "raw.json") }
        System.getProperty("atlas.load.raws")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach {
            val f = File(it)
            assertTrue("atlas.load.raws names $it, which is not a file", f.isFile)
            raws += (f.parentFile?.name ?: f.name) to f
        }
        for ((name, raw) in raws) {
            Regions.resetPlace()
            val t0 = System.nanoTime()
            val S = Amsterdam.load(raw)
            compare(name, raw, S, (System.nanoTime() - t0) / 1_000_000)
        }
        println("load: ${raws.size} other raw.json held to the port")
    }

    /**
     * The core's capitalize() (`CP/text/Case.hpp`) against the port's own (PoiStyle.capitalize, which labels a shop the
     * tables do not name) on every code point: first, between two letters, after two, and alone; and on texts that
     * end words with a capital sigma, which Java lowercases by its word breaks.
     */
    @Test
    fun theCoreCapitalizesAsThePortDoes() {
        val port = PoiStyle::class.java.getDeclaredMethod("capitalize", String::class.java).apply { isAccessible = true }
        val texts = ArrayList<String>()
        for (c in 0..0x10FFFF) {
            if (c in 0xD800..0xDFFF) continue
            val s = String(Character.toChars(c))
            texts += s + "AB"
            texts += "x" + s + "y"
            texts += "xx$s"
            texts += s
        }
        texts += listOf(
            "", "ΟΔΥΣΣΕΥΣ", "ΑΣ", "ΑΣ ΒΑ", "ΑΣ.", "Σ", "ΣΣ", "ΑΣΑ", "ΑΑΣ", "ἈΣ", "İSTANBUL", "ßtraße", "ǆemal", "ǅ",
            "3D_printing_materials".replace('_', ' '), "Plumbing & Heating", "ﬀ", "ŉ", "ᾳ", "Ⓐⓑ", "ⅰⅱ", "𐐀𐐨", "ΣΑΣ ΣΑΣ",
        )
        val bytes = java.io.ByteArrayOutputStream()
        val lengths = IntArray(texts.size)
        for ((i, s) in texts.withIndex()) {
            val b = s.toByteArray(Charsets.UTF_8)
            lengths[i] = b.size
            bytes.write(b)
        }
        val out = LoadHost.capitalize(bytes.toByteArray(), lengths)
        var at = 0
        val wrong = ArrayList<String>()
        for (s in texts) {
            var n = 0
            for (k in 0 until 4) n = n or ((out[at + k].toInt() and 0xFF) shl (8 * k))
            val core = String(out, at + 4, n, Charsets.UTF_8)
            at += 4 + n
            val want = port.invoke(PoiStyle, s) as String
            if (core != want && wrong.size < 20) wrong += "${s.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }}: core $core, the port $want"
        }
        assertEquals("every text read back", out.size, at)
        assertTrue("capitalize differs from the port's on:\n  ${wrong.joinToString("\n  ")}", wrong.isEmpty())
    }

    private companion object {
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}
