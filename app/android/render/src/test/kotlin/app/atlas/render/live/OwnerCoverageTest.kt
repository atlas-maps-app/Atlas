package app.atlas.render.live

import app.atlas.render.Amsterdam
import app.atlas.render.AmsterdamTest
import app.atlas.render.City
import app.atlas.render.Feature
import app.atlas.render.Living
import app.atlas.render.LivingTest
import app.atlas.render.Oracle
import app.atlas.render.PyDumps
import app.atlas.render.Real
import app.atlas.render.Regions
import app.atlas.render.RegionsTest
import app.atlas.render.RenderOptions
import app.atlas.render.V3
import app.atlas.render.py.pyNum
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.IdentityHashMap
import java.util.TreeMap

/**
 * The owner calls (M1b). With a world tape recording, the Westerkerk home and the Main board grachten_home render as
 * today's Python does (md5 of g, o and o2) at every moment; at least 95 % of their drawn elements resolve to an owner or
 * to the declared non-interactive set ([SCENERY]); and every item the render sorts and draws is logged in painter's
 * order with its owner, the point its radius cut tested (equal to the one the engine's `far` test used: recomputed from
 * the owner, and predicting exactly which items a smaller radius keeps) and the point its depth was sorted by
 * (reproducing the depth bit for bit).
 */
class OwnerCoverageTest {

    // ---------------------------------------------------------------- the SVG, element by element

    /** One drawn element of a layer: its tag, its opening tag's text, its enclosing groups' opening tags, and its points as the SVG prints them. */
    class Drawn(val layer: String, val tag: String, val head: String, val groups: List<String>, val objectIndex: Int, val xs: DoubleArray, val ys: DoubleArray) {
        val n: Int get() = xs.size
        fun has(s: String): Boolean = head.contains(s)
        fun within(s: String): Boolean = groups.any { it.contains(s) }
    }

    companion object {
        private val DRAWN_TAGS = setOf("path", "circle", "ellipse", "rect", "polygon", "polyline", "line", "text", "image", "use")
        private val HIDDEN = setOf("defs", "clipPath", "pattern", "filter", "linearGradient", "radialGradient", "mask", "symbol", "marker")
        private val ATTR = Regex("\\s([\\w:-]+)=\"([^\"]*)\"")
        private val MATRIX = Regex("matrix\\(([^)]*)\\)")
        private val GLOWS = listOf("url(#pool)", "url(#open)", "url(#openpool)", "url(#halo)", "url(#streak)", "url(#mastglow)", "url(#lampglow)", "url(#shopglow)")

        /**
         * The declared non-interactive set: what the engine draws with owner 0 by design (plan-anywhere D10: sky, haze,
         * fin, shadows, rain and glow; and the water's surface, which mirrors what stands around it). Each class is the
         * markup the engine gives it, which the Python oracle pins.
         */
        val SCENERY: List<Pair<String, (Drawn) -> Boolean>> = listOf(
            "sky" to { e -> e.has("fill=\"url(#sky)\"") || e.has("url(#sunglow)") || e.has("url(#cityglow)") || e.has("url(#cloudf)") || e.has("url(#cloudsh2)") ||
                (e.tag == "circle" && e.has("r=\"9\" fill=\"#FFFDF4\"")) },
            "haze" to { e -> e.has("fill=\"url(#haze)\"") || e.has("fill=\"url(#wash)\"") },
            "fin" to { e -> e.has("filter=\"url(#grain)\"") || e.has("fill=\"url(#vig)\"") },
            "weather" to { e -> e.has("filter=\"url(#cloudsh)\"") || e.has("fill=\"url(#rain)\"") },
            "water" to { e -> e.within("clip-path=\"url(#wclip)\"") },
            // the shadow group: bare paths in a group that gives them their fill and opacity
            "shadows" to { e -> e.tag == "path" && Regex("<path d=\"[^\"]*\"/>").matches(e.head) && e.groups.lastOrNull()?.let { g -> g.contains(" fill=\"") && g.contains(" opacity=\"") } == true },
            "seams" to { e -> e.has("filter=\"url(#aob)\"") || (e.has("stroke=\"#14161E\"") && e.has("stroke-width=\"1.6\"")) },
            "glow" to { e -> GLOWS.any { e.has(it) } || e.has("filter=\"url(#litglow)\"") || e.within("filter=\"url(#litglow)\"") || e.within("mix-blend-mode:screen") },
        )

        private fun attrs(head: String): Map<String, String> = ATTR.findAll(head).associate { it.groupValues[1] to it.groupValues[2] }

        /** The absolute M and L vertices of path data [d], as printed. */
        private fun vertices(d: String, xs: MutableList<Double>, ys: MutableList<Double>) {
            var i = 0; var cmd = 'M'; val nums = ArrayList<Double>(2)
            while (i < d.length) {
                val ch = d[i]
                if (ch.isLetter()) { cmd = ch; i++; nums.clear(); continue }
                if (ch == ' ' || ch == ',') { i++; continue }
                var j = i + 1
                while (j < d.length && (d[j].isDigit() || d[j] == '.')) j++
                nums.add(d.substring(i, j).toDouble()); i = j
                if ((cmd == 'M' || cmd == 'L') && nums.size == 2) { xs.add(nums[0]); ys.add(nums[1]); nums.clear() } else if (cmd != 'M' && cmd != 'L') nums.clear()
            }
        }

        private fun points(tag: String, head: String): Pair<DoubleArray, DoubleArray> {
            val a = attrs(head); val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            fun two(x: String, y: String) { val vx = a[x]?.toDoubleOrNull(); val vy = a[y]?.toDoubleOrNull(); if (vx != null && vy != null) { xs.add(vx); ys.add(vy) } }
            when (tag) {
                "path" -> a["d"]?.let { vertices(it, xs, ys) }
                "circle", "ellipse" -> two("cx", "cy")
                "rect", "image", "use" -> two("x", "y")
                "line" -> { two("x1", "y1"); two("x2", "y2") }
                "polygon", "polyline" -> a["points"]?.trim()?.split(Regex("[\\s,]+"))?.mapNotNull { it.toDoubleOrNull() }?.chunked(2)?.forEach { if (it.size == 2) { xs.add(it[0]); ys.add(it[1]) } }
                "text" -> {
                    val m = a["transform"]?.let { MATRIX.find(it) }?.groupValues?.get(1)?.split(' ')
                    if (m != null && m.size == 6) { xs.add(m[4].toDouble()); ys.add(m[5].toDouble()) } else two("x", "y")
                }
            }
            return xs.toDoubleArray() to ys.toDoubleArray()
        }

        /**
         * The drawn elements of [svg] (layer [layer]) into [out], outside definitions; those inside the objects group
         * (`<g stroke-linejoin="round" …>` in the frame's clip group) get the index of their top-level ancestor there,
         * counting from [objectBase]. Returns how many top-level elements the objects group holds.
         */
        fun walk(svg: String, layer: String, objectBase: Int, out: MutableList<Drawn>): Int {
            val stack = ArrayList<String>(); val heads = ArrayList<String>()
            var objectsAt = -1; var tops = 0; var skip = 0
            var i = svg.indexOf('<')
            while (i >= 0 && i < svg.length - 1) {
                val c = svg[i + 1]
                if (c == '/') {
                    val e = svg.indexOf('>', i)
                    val name = stack.removeAt(stack.size - 1); heads.removeAt(heads.size - 1)
                    if (name in HIDDEN) skip--
                    if (stack.size < objectsAt) objectsAt = -1
                    i = svg.indexOf('<', e + 1); continue
                }
                if (c == '!' || c == '?') { i = svg.indexOf('<', svg.indexOf('>', i) + 1); continue }
                var j = i + 1; var q = 0.toChar()
                while (j < svg.length) { val ch = svg[j]; if (q.code != 0) { if (ch == q) q = 0.toChar() } else if (ch == '"') q = ch else if (ch == '>') break; j++ }
                val head = svg.substring(i, j + 1)
                val selfClosing = svg[j - 1] == '/'
                var k = i + 1
                while (k < j && !svg[k].isWhitespace() && svg[k] != '/' && svg[k] != '>') k++
                val tag = svg.substring(i + 1, k)
                if (objectsAt >= 0 && stack.size == objectsAt) tops++
                if (skip == 0 && tag in DRAWN_TAGS) {
                    val (xs, ys) = points(tag, head)
                    out.add(Drawn(layer, tag, head, heads.toList(), if (objectsAt >= 0) objectBase + tops - 1 else -1, xs, ys))
                }
                if (!selfClosing) {
                    if (tag in HIDDEN) skip++
                    if (tag == "g" && stack.size == 2 && objectsAt < 0 && head.startsWith("<g stroke-linejoin=\"round\"")) { objectsAt = 3; tops = 0 }
                    stack.add(tag); heads.add(head)
                }
                i = svg.indexOf('<', j + 1)
            }
            return tops
        }
    }

    // ---------------------------------------------------------------- owners

    /**
     * The owners [e] most likely is: every point of [e] on the tape votes for each owner among its key's alternatives (a
     * point drawn where another owner's, or a clip path's, prints the same screen point holds both), and the owners at
     * the most points win. Empty when none of its points is on the tape.
     */
    private fun owners(tape: WorldTape, e: Drawn): Set<Int> {
        val counts = HashMap<Int, Int>(); val seen = HashSet<Int>()
        for (k in 0 until e.n) {
            var x = tape.find(e.xs[k], e.ys[k])
            seen.clear()
            while (x >= 0) { val o = tape.owner(x); if (seen.add(o)) counts.merge(o, 1, Int::plus); x = tape.next(x) }
        }
        val top = counts.values.maxOrNull() ?: return emptySet()
        return counts.filterValues { it == top }.keys
    }

    /** The item whose run of objects-group elements ([runs], from [TapeItems.elementRuns]) holds element [index], or -1. */
    private fun itemAt(runs: IntArray, index: Int): Int {
        var lo = 0; var hi = runs.size / 2 - 1; var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid * 2] <= index) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        while (found >= 0 && runs[found * 2 + 1] == 0) found--
        return if (found >= 0 && index < runs[found * 2] + runs[found * 2 + 1]) found else -1
    }

    /** How a render's drawn elements resolve: by an item's owner, by the tape's owner, by a [SCENERY] class, or not at all. */
    class Coverage(val total: Int, val classes: Map<String, Int>, val unattributed: Map<String, Int>, val itemAgree: Int, val itemChecked: Int) {
        val resolved: Int get() = total - (classes["unattributed"] ?: 0)
        val share: Double get() = resolved.toDouble() / total
        override fun toString() = "%d drawn elements, %.2f %% resolve (%s); an item's elements with taped points have its owner on the tape: %d of %d".format(
            total, share * 100, classes.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }, itemAgree, itemChecked)
    }

    private fun signature(e: Drawn): String {
        val a = attrs(e.head)
        val keys = listOf("fill", "stroke", "filter", "style", "opacity", "stroke-width").filter { it in a }.joinToString(" ") { "$it=${a[it]}" }
        return "${e.layer} ${e.tag} [$keys] in ${e.groups.drop(2).joinToString(" > ") { it.take(70) }}"
    }

    private fun coverage(tape: WorldTape, layers: List<Pair<String, String?>>): Coverage {
        tape.seal()
        val els = ArrayList<Drawn>(); var base = 0
        for ((name, svg) in layers) if (svg != null) base += walk(svg, name, base, els)
        val runs = tape.items.elementRuns(layers.map { it.second })
        assertEquals("the items' runs are the objects group's top-level elements", base, (0 until tape.items.size).sumOf { runs[it * 2 + 1] })
        val classes = TreeMap<String, Int>(); val un = HashMap<String, Int>()
        var agree = 0; var checked = 0
        for (e in els) {
            val maj = owners(tape, e)
            val named = maj.any { it != 0 }
            var cls = when {
                e.objectIndex >= 0 -> {
                    val it = itemAt(runs, e.objectIndex)
                    val owner = if (it >= 0) tape.items.owner(it) else 0
                    if (maj.isNotEmpty() && it >= 0) { checked++; if (owner in maj) agree++ }
                    if (owner != 0) "item" else if (named) "owned" else null
                }
                named -> "owned"
                else -> null
            }
            if (cls == null) cls = SCENERY.firstOrNull { it.second(e) }?.first ?: "unattributed"
            classes.merge(cls, 1, Int::plus)
            if (cls == "unattributed") un.merge(signature(e) + (if (maj.isEmpty()) " (off the tape)" else ""), 1, Int::plus)
        }
        return Coverage(els.size, classes, un, agree, checked)
    }

    private fun assertCoverage(name: String, tape: WorldTape, layers: List<Pair<String, String?>>): Coverage {
        val c = coverage(tape, layers)
        println("$name: $c")
        for ((sig, k) in c.unattributed.entries.sortedByDescending { it.value }.take(8)) println("    unattributed: $k × $sig")
        assertTrue("$name: at least 95 % of drawn elements resolve to an owner or to the declared non-interactive set: $c", c.share >= 0.95)
        assertTrue("$name: an item's elements are its owner's on the tape: $c", c.itemAgree >= c.itemChecked * 0.99)
        return c
    }

    // ---------------------------------------------------------------- items

    private fun bits(v: Double): Long = java.lang.Double.doubleToRawLongBits(v)

    @Suppress("UNCHECKED_CAST")
    private fun ownerPoints(o: Any?): List<app.atlas.render.V2> = Amsterdam.ptsOf(o as Map<String, Any?>)

    /**
     * Every item of [tape]'s render (camera [cam], view [cfg]): an owner; a sort point and offset that give its depth bit
     * for bit, far to near; the points its radius cut tested, equal to those the engine's `far` test reads from the
     * owner (a building's or part's centroid, a node's or a tower's position, a segment's two ends; a railing tests
     * none), and within the view's radius.
     */
    private fun checkItems(name: String, tape: WorldTape, cam: City.Camera, cfg: Map<String, Any?>): Map<String, Int> {
        val items = tape.items
        val rad = cfg["radius"]?.let { pyNum(it) } ?: 1e9
        val target = cfg["target"] as List<*>; val tx = pyNum(target[0]); val tz = pyNum(target[2])
        assertTrue("$name: items logged", items.size > 0)
        val kinds = TreeMap<String, Int>()
        var prev = Double.POSITIVE_INFINITY
        for (i in 0 until items.size) {
            val kind = items.kind(i)
            kinds.merge(kind, 1, Int::plus)
            val what = "$name item $i ($kind)"
            val owner = tape.ownerObject(items.owner(i))
            assertNotNull("$what has an owner", owner)
            assertTrue("$what: painter's order, far to near", items.depth(i) <= prev); prev = items.depth(i)
            val c = cam.cam(V3(items.sortX(i), items.sortY(i), items.sortZ(i)))
            assertEquals("$what: its sort point gives its depth", bits(items.depth(i)), bits(c.z + items.sortOffset(i)))
            owner as Map<String, Any?>
            when (kind) {
                "railing" -> assertEquals("$what tests no radius", 0, items.farPoints(i))
                "building", "part" -> {
                    val ce = Real.centroid(Amsterdam.polyOf(owner))
                    assertEquals("$what far x", bits(ce.x), bits(items.farX(i))); assertEquals("$what far z", bits(ce.y), bits(items.farZ(i)))
                    assertEquals(1, items.farPoints(i))
                }
                "gantry", "conveyor", "power" -> {
                    val pts = ownerPoints(owner)
                    val k = (0 until pts.size - 1).firstOrNull { k ->
                        bits(pts[k].x) == bits(items.farX(i, 0)) && bits(pts[k].y) == bits(items.farZ(i, 0)) &&
                            bits(pts[k + 1].x) == bits(items.farX(i, 1)) && bits(pts[k + 1].y) == bits(items.farZ(i, 1))
                    }
                    assertNotNull("$what: its far points are one of its line's segments", k)
                }
                else -> { // nodes, a node's door, shopfront, blade sign and terrace, towers, turbines, cranes and masts: the owner's own point
                    assertEquals("$what far x", bits(pyNum(owner["x"])), bits(items.farX(i))); assertEquals("$what far z", bits(pyNum(owner["z"])), bits(items.farZ(i)))
                    assertEquals(1, items.farPoints(i))
                }
            }
            assertFalse("$what lies within the view's radius", items.cut(i, tx, tz, rad))
        }
        println("$name: ${items.size} items, ${items.chars} characters, log %.2f MB: $kinds".format(items.bytes() / 1e6))
        return kinds
    }

    /** An item as two renders of one scene can both log it: its owner, kind and sort point. */
    private class Key(val owner: Any?, val kind: String, val sx: Long, val sy: Long, val sz: Long) {
        override fun equals(other: Any?): Boolean = other is Key && other.owner === owner && other.kind == kind && other.sx == sx && other.sy == sy && other.sz == sz
        override fun hashCode(): Int = (System.identityHashCode(owner) * 31 + kind.hashCode()) * 31 + (sx xor sy xor sz).hashCode()
        override fun toString() = "$kind of ${(owner as? Map<*, *>)?.get("id") ?: owner?.javaClass?.simpleName}"
    }

    private fun keys(tape: WorldTape): List<Key> = (0 until tape.items.size).map { i ->
        val it = tape.items
        Key(tape.ownerObject(it.owner(i)), it.kind(i), bits(it.sortX(i)), bits(it.sortY(i)), bits(it.sortZ(i)))
    }

    /**
     * The radius cut, predicted: the items a render at radius [smaller] draws are exactly those of the render at the
     * view's radius whose logged far points are not [TapeItems.cut] by [smaller] (both renders' items, keyed by owner,
     * kind and sort point; a door is its first address's, so one whose first address falls outside is drawn by the next).
     */
    private fun assertRadiusCut(name: String, smaller: Double, render: (Map<String, Any?>) -> Unit, cfg: Map<String, Any?>): String {
        val full = WorldTape(); WorldTape.recording(full) { render(cfg) }
        val cut = LinkedHashMap(cfg); cut["radius"] = smaller
        val part = WorldTape(); WorldTape.recording(part) { render(cut) }
        val target = cfg["target"] as List<*>; val tx = pyNum(target[0]); val tz = pyNum(target[2])
        val expected = HashMap<Key, Int>(); val actual = HashMap<Key, Int>()
        var kept = 0
        for ((i, k) in keys(full).withIndex()) if (!full.items.cut(i, tx, tz, smaller)) { expected.merge(k, 1, Int::plus); kept++ }
        for (k in keys(part)) actual.merge(k, 1, Int::plus)
        val missing = expected.filter { (k, v) -> (actual[k] ?: 0) < v }.keys
        val extra = actual.filter { (k, v) -> (expected[k] ?: 0) < v }.keys
        // a door whose first address lies beyond the smaller radius is made from its next address instead
        val doorSwaps = extra.count { it.kind == "door" }
        val missingDoors = missing.count { it.kind == "door" }
        val text = "$name: radius ${cfg["radius"]} → $smaller: ${full.items.size} items, $kept predicted to stay, ${part.items.size} drawn; " +
            "${missing.size} missing, ${extra.size} extra (doors $missingDoors and $doorSwaps)"
        println(text)
        assertTrue("$text; missing ${missing.take(5)}, extra ${extra.take(5)}", missing.all { it.kind == "door" } && extra.all { it.kind == "door" } && doorSwaps <= missingDoors)
        assertTrue("$name: the smaller radius drops items", kept < full.items.size)
        return text
    }

    // ---------------------------------------------------------------- the Westerkerk

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the Westerkerk home renders as Python with the owner calls in, its elements resolve to owners, its items to their far tests`() {
        Oracle.configure()
        val kit = AmsterdamTest.GROUPS.first { it.name == "kit" }
        val home = kit.jobs.first { it["tag"] == "M_ams_kit" }
        val jobs = listOf(
            linkedMapOf<String, Any?>("tag" to "owners_home", "kind" to "ams", "moments" to listOf("sunny", "grey", "wet", "night")),
            kit.jobs.first { it["tag"] == "kit_objsplit" },
            // at eye level in the Westermarkt: doors with their numbers, shopfronts, blade signs and terraces up close
            LinkedHashMap(kit.jobs.first { it["tag"] == "E_wmarkt_kit_noao" }).also { it["tag"] = "owners_wmarkt"; it["moments"] = listOf("sunny", "night") },
        )
        val dir = File(Oracle.out, "owners").also { it.mkdirs() }
        Oracle.run("amsterdam_oracle.py", "render", PyDumps.dumps(linkedMapOf("name" to "owners_kit", "env" to kit.env, "jobs" to jobs)), dir.path, timeoutSec = 1800)
        val res = Oracle.py(Json.parseToJsonElement(File(dir, "owners_kit.json").readText())) as Map<String, Any?>
        dir.listFiles { f -> f.name.endsWith(".svg") }!!.forEach { it.delete() }
        val opts = AmsterdamTest.optsOf(kit.env)
        Regions.resetPlace(opts.daylight)
        Amsterdam.amsRaw = File(Oracle.repo, "data/ams/raw.json")
        try {
            val S = Amsterdam.load(opts = opts)
            var layers = 0
            for ((ji, job) in jobs.withIndex()) {
                val files = ((res["jobs"] as List<Map<String, Any?>>)[ji]["files"] as Map<String, Map<String, Any?>>)
                val cfg = AmsterdamTest.cfgOf(job)
                for (m in job["moments"] as List<String>) {
                    val tape = WorldTape()
                    val on = WorldTape.recording(tape) { Amsterdam.render(S, m, cfg, null, "split", opts) }
                    val mine = listOf("g" to on.svg) + on.objs!!.mapIndexed { i, s -> (if (i == 0) "o" else "o2") to s }
                    for ((layer, text) in mine) {
                        assertEquals("${job["tag"]} $m $layer with the tape on", files.getValue("${job["tag"]}__${m}__$layer.svg")["md5"], RegionsTest.md5(text)); layers++
                    }
                    assertEquals("${job["tag"]} $m: Python's layers", files.values.count { it["moment"] == m }, mine.size)
                    assertCoverage("Westerkerk ${job["tag"]} $m", tape, mine)
                    val kinds = checkItems("Westerkerk ${job["tag"]} $m", tape, on.cam, cfg)
                    val want = if (ji < 2) listOf("building", "part", "tree", "street_lamp") else listOf("building", "door", "shopfront", "blade")
                    if (m == "sunny") assertTrue("${job["tag"]} draws ${want.joinToString()}: $kinds", want.all { (kinds[it] ?: 0) > 0 })
                }
            }
            println("Westerkerk: $layers layers equal Python with the owner calls in and the tape on")
            val render = { c: Map<String, Any?> -> Amsterdam.render(S, "sunny", c, null, "split", opts); Unit }
            for (r in listOf(250.0, 120.0)) assertRadiusCut("Westerkerk sunny", r, render, AmsterdamTest.cfgOf(home))
            for (r in listOf(120.0, 60.0)) assertRadiusCut("Westermarkt sunny", r, render, AmsterdamTest.cfgOf(jobs[2]))
        } finally {
            Amsterdam.resetThemes()
        }
    }

    // ---------------------------------------------------------------- the Main board

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the Main board grachten_home renders as Python with the owner calls in, its elements resolve to owners, its items to their far tests`() {
        val job = RegionsTest.JOBS.firstOrNull { it.tag == "grachten_home" }
        assumeTrue("the grachten extract is local only", job != null)
        Oracle.configure()
        Regions.root = File(Oracle.repo, "data/regions")
        Regions.parkRoot = File(Oracle.repo, "data/park")
        Regions.streetCfgDir = File(Oracle.repo, "cache/render")
        Regions.resetPlace()
        RegionsTest.runOracle(listOf(job!!))
        val files = (RegionsTest.readJson(File(RegionsTest.dir, "grachten_home.json"))["files"] as Map<String, Map<String, Any?>>).values
        val opts = RenderOptions(ao = job.ao)
        val S = Regions.load(job.view, opts)
        val cfg = Regions.cfgFor(job.view)
        val cam = Amsterdam.cameraOf(cfg)
        for (m in job.moments) {
            val tape = WorldTape()
            val on = WorldTape.recording(tape) { Regions.render(S, m, cfg, opts) }
            val mine = listOf("g" to on.g, "o" to on.o, "o2" to on.o2)
            for ((layer, text) in mine) {
                val py = files.firstOrNull { it["moment"] == m && it["layer"] == layer }?.get("md5")
                assertEquals("grachten_home $m $layer with the tape on", py, text?.let { RegionsTest.md5(it) })
            }
            assertCoverage("grachten_home $m", tape, mine)
            val kinds = checkItems("grachten_home $m", tape, cam, cfg)
            if (m == "sunny") assertTrue("the canal ring draws buildings, trees and power lines: $kinds", listOf("building", "tree").all { (kinds[it] ?: 0) > 0 })
        }
        assertRadiusCut("grachten_home sunny", 0.5 * pyNum(cfg["radius"]), { c -> Regions.render(S, "sunny", c, opts); Unit }, cfg)
    }

    // ---------------------------------------------------------------- the living map

    @Test
    fun `the living map's objects and POI symbols are their features', and the tape changes nothing`() {
        val scene = Living.buildScene()
        val cfg: Map<String, Any?> = linkedMapOf("W" to 412, "H" to 915, "target" to listOf(4, 0, -8), "yaw" to 12, "pitch" to 42, "dist" to 200, "fov" to 36, "cy" to 500)
        for ((thn, th) in LivingTest.THEMES) {
            val off = Living.render(th, cfg, scene.F, scene.blocks, scene.park)
            val tape = WorldTape()
            val on = WorldTape.recording(tape) { Living.render(th, cfg, scene.F, scene.blocks, scene.park) }
            assertEquals("living $thn with the tape on", RegionsTest.md5(off.svg), RegionsTest.md5(on.svg))
            val c = coverage(tape, listOf("svg" to on.svg))
            println("living $thn: $c; ${tape.items.size} items")
            val items = tape.items
            assertTrue(items.size > 100)
            val features = IdentityHashMap<Any, Boolean>().also { m -> scene.F.forEach { m[it] = true } }
            var mapped = 0
            for (i in 0 until items.size) {
                val f = tape.ownerObject(items.owner(i))
                assertTrue("living item $i is a feature's", f is Feature)
                if (features.containsKey(f)) mapped++ // parked cars and passers-by are features made for the frame
                assertEquals("living item $i tests no radius", 0, items.farPoints(i))
            }
            assertTrue("living $thn: most items are the scene's own features ($mapped of ${items.size})", mapped > items.size / 2)
            assertTrue("living $thn: an item's elements are its feature's on the tape: $c", c.itemAgree >= c.itemChecked * 0.99)
        }
    }
}
