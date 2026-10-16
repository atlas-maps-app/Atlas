package app.atlas.render.live

import app.atlas.render.Amsterdam
import app.atlas.render.AmsterdamTest
import app.atlas.render.City
import app.atlas.render.Oracle
import app.atlas.render.PyDumps
import app.atlas.render.Regions
import app.atlas.render.RegionsTest
import app.atlas.render.RenderOptions
import app.atlas.render.V3
import app.atlas.render.py.Dict
import app.atlas.render.py.PyFmt
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The world tape: its keys print as the SVG does, every taped point lies on its printed screen point's ray at the depth
 * the engine projected, and a render with the tape on is byte-identical to one with it off and to the Python oracle (the
 * Westerkerk, the Main board grachten_home, a terrain region), with ≥ 99 % of its path vertices found on the tape, within
 * 20 MB and 10 % of render time.
 */
class WorldTapeTest {
    private val cam = City.Camera(V3(-10.0, 0.0, -30.0), 52.0, 38.0, 430.0, 36.0, 412.0, 915.0, cy = 440.0)

    private fun digits(v: Double): Long = PyFmt.fixed(v, 1).replace(".", "").toLong()

    @Test
    fun `key10 is the %_1f text times ten`() {
        val rnd = Random(7)
        var n = 0
        fun check(v: Double) {
            assertEquals("key of ${v.toBits()} ($v)", digits(v), WorldTape.key10(v)); n++
        }
        for (i in 0 until 1_000_000) {
            val v = when (i % 4) {
                0 -> (rnd.nextDouble() - 0.5) * 3000
                1 -> (rnd.nextDouble() - 0.5) * Math.pow(10.0, rnd.nextInt(8).toDouble())
                2 -> (rnd.nextInt(2_000_001) - 1_000_000) / 4.0 // m/4: the exact ties of %.1f
                else -> { // the doubles next to a decimal tie k.k5
                    val t = ((rnd.nextInt(200_001) - 100_000) * 10 + 5) / 100.0
                    when (rnd.nextInt(3)) { 0 -> Math.nextUp(t); 1 -> Math.nextDown(t); else -> t }
                }
            }
            check(v)
        }
        for (v in doubleArrayOf(0.0, -0.0, 0.05, -0.05, 0.25, -0.25, 0.75, 1.25, 2.5e-320, -1e-300, 0.04999999999999999, 2.68e7, -2.68e7)) check(v)
        assertEquals(Long.MIN_VALUE, WorldTape.key10(Double.NaN))
        assertEquals(Long.MIN_VALUE, WorldTape.key10(Double.POSITIVE_INFINITY))
        assertEquals(Long.MIN_VALUE, WorldTape.key10(3e7))
        println("key10: $n values equal PyFmt.fixed(v, 1)")
    }

    /** How far, metres, a taped point may lie from the point projected: half a tenth of a pixel on each axis, at [depth]. */
    private fun slack(depth: Double, c: City.Camera): Double = 0.0708 * depth / c.focal + 1e-3

    @Test
    fun `pc records the depth it projected, and its entry re-projects onto the printed point`() {
        val rnd = Random(3)
        val pts = List(20_000) { V3(rnd.nextDouble() * 1600 - 800, rnd.nextDouble() * 90, rnd.nextDouble() * 1600 - 800) }
        val plain = pts.map { cam.proj(it) }
        val tape = WorldTape()
        val taped = WorldTape.recording(tape) {
            val c = City.Camera(V3(-10.0, 0.0, -30.0), 52.0, 38.0, 430.0, 36.0, 412.0, 915.0, cy = 440.0)
            assertSame(tape, c.tape)
            pts.map { c.proj(it) }
        }
        assertEquals(plain, taped)
        assertTrue("the tape is closed when the render returns", tape.closed && !tape.sealed)
        var checked = 0; var worstPx = 0.0
        val w = DoubleArray(3)
        for ((i, p) in pts.withIndex()) {
            val s = taped[i] ?: continue
            val depth = cam.depth(p)
            var e = tape.findKey(WorldTape.key10(s.x), WorldTape.key10(s.y))
            assertTrue("vertex $i on the tape", e >= 0)
            var best = -1; var bestD = Double.MAX_VALUE
            while (e >= 0) { val d = Math.abs(tape.depth(e) - depth); if (d < bestD) { best = e; bestD = d }; e = tape.next(e) }
            assertTrue("depth of $i as a float: ${tape.depth(best)} against $depth", bestD <= Math.ulp(depth.toFloat()).toDouble())
            tape.world(best, w)
            assertEquals(tape.x(best), w[0], 0.0); assertEquals(tape.y(best), w[1], 0.0); assertEquals(tape.z(best), w[2], 0.0)
            val far = Math.max(Math.abs(w[0] - p.x), Math.max(Math.abs(w[1] - p.y), Math.abs(w[2] - p.z)))
            assertTrue("world point of $i within half a tenth of a pixel: $far m at $depth m", far <= slack(depth, cam))
            val q = cam.proj(V3(w[0], w[1], w[2]))!!
            worstPx = maxOf(worstPx, Math.abs(q.x - tape.screenX10(best) / 10.0), Math.abs(q.y - tape.screenY10(best) / 10.0))
            checked++
        }
        assertTrue(checked > 10_000)
        assertTrue("re-projected onto the printed point: $worstPx px", worstPx < 1e-4)
        assertEquals(0, tape.foreignCameras)
        assertEquals(checked.toLong(), tape.calls)
        assertTrue("repeats add nothing: ${tape.size} entries", tape.size <= checked)
        assertNull("no tape outside a recording", City.Camera(V3(0.0, 0.0, 0.0), 0.0, 30.0, 100.0, 36.0, 412.0, 915.0).tape)
    }

    @Test
    fun `a lifted camera tapes the lifted point, a foreign camera nothing`() {
        val tape = WorldTape()
        WorldTape.recording(tape) {
            val c = Amsterdam.cameraOf(linkedMapOf("W" to 412, "H" to 915, "target" to listOf(-10, 0, -30), "yaw" to 52, "pitch" to 38, "dist" to 430, "fov" to 36, "cy" to 440))
            val lc = Amsterdam.lift(c, mapOf("gy" to 3.25))
            assertSame(tape, lc.tape)
            val s = lc.proj(V3(12.0, 1.5, -40.0))!!
            val other = City.Camera(V3(0.0, 0.0, 0.0), 10.0, 30.0, 100.0, 36.0, 412.0, 915.0)
            assertNull("a camera of another frame is not taped", other.tape)
            val before = tape.calls
            other.proj(V3(1.0, 0.0, 1.0))
            assertEquals(before, tape.calls)
            assertEquals(1, tape.foreignCameras)
            val e = tape.find(PyFmt.fixed(s.x, 1).toDouble(), PyFmt.fixed(s.y, 1).toDouble())
            assertTrue(e >= 0)
            val slack = slack(tape.depth(e), c)
            assertEquals(4.75, tape.y(e), slack)
            assertEquals(12.0, tape.x(e), slack)
            assertEquals(-40.0, tape.z(e), slack)
            assertNull("a query seals the tape: no camera is taped after it", City.Camera(V3(-10.0, 0.0, -30.0), 52.0, 38.0, 430.0, 36.0, 412.0, 915.0, cy = 440.0).tape)
        }
    }

    @Test
    fun `recordings nest and restore, and a closed tape records nothing`() {
        val a = WorldTape(); val b = WorldTape()
        var kept: City.Camera? = null
        WorldTape.recording(a) {
            assertSame(a, WorldTape.active())
            WorldTape.recording(b) { assertSame(b, WorldTape.active()) }
            assertSame(a, WorldTape.active())
            kept = City.Camera(V3(0.0, 0.0, 0.0), 0.0, 30.0, 100.0, 36.0, 412.0, 915.0)
            WorldTape.owner("a tree")
            kept!!.proj(V3(1.0, 0.0, 1.0))
            assertEquals("a tree", a.ownerObject(a.owner(0)))
        }
        assertNull(WorldTape.active())
        WorldTape.owner("nothing happens without a tape")
        assertEquals(1, a.ownerCount)
        kept!!.proj(V3(2.0, 0.0, 1.0)) // a camera kept past its render
        assertEquals(1, a.size); assertEquals(1L, a.late)
        a.reset()
        WorldTape.recording(a) { City.Camera(V3(0.0, 0.0, 0.0), 0.0, 30.0, 100.0, 36.0, 412.0, 915.0).proj(V3(2.0, 0.0, 1.0)) }
        assertEquals(1, a.size); assertEquals(0L, a.late); assertEquals(0, a.ownerCount)
    }

    @Test
    fun `alternatives, owners and resolving a path`() {
        val t = WorldTape(frame = cam)
        // one screen key (50.0, 60.0), three entries: a near roof corner (twice), a far ground point, the corner by another owner
        t.setOwner("roof")
        t.record(100.0, 50.04, 60.01)
        t.record(100.003, 50.04, 60.01) // within SAME: the same point
        t.setOwner("ground")
        t.record(1000.0, 49.96, 59.98)
        t.setOwner("roof")
        t.record(100.0, 70.0, 60.0)
        t.record(100.0, 70.0, 70.0)
        t.setOwner("ground")
        t.record(1000.0, 90.0, 60.0)
        t.setOwner("other")
        t.record(100.0, 50.0, 60.0)
        assertEquals(7L, t.calls); assertEquals(7, t.size)
        t.seal()
        assertEquals(6, t.size); assertEquals(4, t.keys); assertEquals(1, t.ambiguousKeys)
        val e0 = t.find(50.0, 60.0); val e1 = t.next(e0); val e2 = t.next(e1)
        assertTrue(e0 >= 0 && e1 >= 0 && e2 >= 0); assertEquals(-1, t.next(e2))
        assertEquals(listOf("roof", "ground", "other"), listOf(e0, e1, e2).map { t.ownerObject(t.owner(it)) })
        assertEquals(listOf(100.0, 1000.0, 100.0), listOf(e0, e1, e2).map { t.depth(it) })
        assertEquals(500L, t.screenX10(e0)); assertEquals(600L, t.screenY10(e1))
        // the roof's path: its unambiguous vertices are the roof's
        val out = IntArray(4)
        assertEquals(3, t.resolve(doubleArrayOf(50.0, 70.0, 70.0, 300.0), doubleArrayOf(60.0, 60.0, 70.0, 300.0), 4, out))
        assertEquals(e0, out[0]); assertEquals(-1, out[3])
        // the ground's path
        val out2 = IntArray(2)
        t.resolve(doubleArrayOf(50.0, 90.0), doubleArrayOf(60.0, 60.0), 2, out2)
        assertEquals(e1, out2[0])
        // with no owners, the alternative nearest the path's other vertices
        val u = WorldTape(frame = cam)
        u.record(100.0, 50.0, 60.0); u.record(1000.0, 50.0, 60.0)
        u.record(100.0, 70.0, 60.0); u.record(1000.0, 90.0, 60.0)
        u.resolve(doubleArrayOf(50.0, 70.0), doubleArrayOf(60.0, 60.0), 2, out2)
        assertEquals(u.find(50.0, 60.0), out2[0])
        u.resolve(doubleArrayOf(50.0, 90.0), doubleArrayOf(60.0, 60.0), 2, out2)
        assertEquals(u.next(u.find(50.0, 60.0)), out2[0])
        assertTrue(t.bytes() > 0)
        // a sealed tape records nothing more
        t.seal()
        t.record(1.0, 1.0, 1.0)
        assertEquals(1L, t.late); assertEquals(-1, t.find(1.0, 1.0))
        t.reset()
        t.record(1.0, 1.0, 1.0)
        assertEquals(1, t.size)
    }

    @Test
    fun `pages and the index grow and keep every entry, and a key's alternatives are capped`() {
        val t = WorldTape(frame = cam)
        val rnd = Random(11)
        val xs = IntArray(300_000) { rnd.nextInt(8000) - 2000 }; val ys = IntArray(xs.size) { rnd.nextInt(8000) - 2000 }
        for (i in xs.indices) t.record(i + 1.0, xs[i] / 10.0, ys[i] / 10.0)
        for (i in xs.indices) {
            var e = t.findKey(xs[i].toLong(), ys[i].toLong()); var hit = false
            while (e >= 0) { if (t.depth(e) == i + 1.0) hit = true; e = t.next(e) }
            assertTrue("entry $i", hit)
        }
        assertEquals(xs.size, t.size)
        assertTrue("${t.keys} keys", t.keys in 280_000 until xs.size)
        assertTrue("peak ${t.peakBytes} B ≥ now ${t.bytes()} B", t.peakBytes >= t.bytes())
        assertTrue("20 bytes an entry, and the index: ${t.bytes()} B", t.bytes() < xs.size * 20L * 1.15 + 4L * 2 * t.keys / 0.75)
        // a reset tape keeps its arrays and fills the same way
        val before = t.bytes()
        t.reset()
        for (i in xs.indices) t.record(i + 1.0, xs[i] / 10.0, ys[i] / 10.0)
        assertEquals(xs.size, t.size); assertEquals(before, t.bytes())
        val u = WorldTape(frame = cam)
        for (i in 0 until 100) u.record(i + 1.0, 5.0, 5.0)
        u.record(1.0, Double.NaN, 1.0)
        assertEquals(1L, u.skipped)
        u.seal()
        assertEquals(WorldTape.MAX_ALTERNATIVES, u.size); assertEquals(100L - WorldTape.MAX_ALTERNATIVES, u.overflow)
        assertEquals(-1, u.find(1e9, 0.0))
    }

    @Test
    fun `the tape holds what a plain fold of its calls holds, repeats near and far`() {
        // a stream with repeats close by (which the memory of recent calls drops) and far apart (which only the fold
        // finds), depths a few millimetres apart, owners, and keys over the alternatives' cap
        val rnd = Random(5)
        val pool = List(20_000) { Triple((rnd.nextInt(4000) - 500) / 10.0, (rnd.nextInt(4000) - 500) / 10.0, 5.0 + rnd.nextInt(2000) / 4.0) }
        val owners = listOf<Any?>(null, "a", "b", "c")
        val calls = ArrayList<DoubleArray>()
        val ownerOf = ArrayList<Any?>()
        for (i in 0 until 300_000) {
            val p = pool[if (rnd.nextInt(4) == 0) rnd.nextInt(pool.size) else (i / 7 + rnd.nextInt(40)) % pool.size]
            val d = when (rnd.nextInt(10)) { 0 -> p.third + 0.003; 1 -> p.third + 0.012; else -> p.third }
            calls.add(doubleArrayOf(d, p.first, p.second)); ownerOf.add(owners[if (rnd.nextInt(8) == 0) rnd.nextInt(4) else 0])
        }
        for (i in 0 until 80) { calls.add(doubleArrayOf(1.0 + i, 7.0, 7.0)); ownerOf.add(null) } // one key, 80 depths
        val t = WorldTape(frame = cam)
        for ((i, c) in calls.withIndex()) { t.setOwner(ownerOf[i]); t.record(c[0], c[1], c[2]) }
        val logged = t.size
        // the plain fold: in call order, a call is dropped when its key holds the same owner within SAME, or holds the cap
        class Ref(val ix: Long, val iy: Long, val d: Float, val owner: Any?)
        val heads = HashMap<Long, MutableList<Int>>(); val ref = ArrayList<Ref>()
        for ((i, c) in calls.withIndex()) {
            val ix = WorldTape.key10(c[1]); val iy = WorldTape.key10(c[2]); val d = c[0].toFloat()
            val list = heads.getOrPut(ix * 1_000_003 + iy) { ArrayList() }
            if (list.any { ref[it].owner === ownerOf[i] && Math.abs(ref[it].d - d) <= WorldTape.SAME }) continue
            if (list.size >= WorldTape.MAX_ALTERNATIVES) continue
            list.add(ref.size); ref.add(Ref(ix, iy, d, ownerOf[i]))
        }
        t.seal()
        assertTrue("the memory of recent calls dropped repeats: $logged logged of ${calls.size}", logged < calls.size)
        assertEquals(ref.size, t.size)
        assertEquals(heads.values.count { it.isNotEmpty() }, t.keys)
        val position = IntArray(ref.size)
        for (list in heads.values) for ((k, e) in list.withIndex()) position[e] = k
        for (e in 0 until t.size) {
            val r = ref[e]
            assertEquals("entry $e x", r.ix, t.screenX10(e)); assertEquals("entry $e y", r.iy, t.screenY10(e))
            assertEquals("entry $e depth", r.d.toDouble(), t.depth(e), 0.0)
            assertSame("entry $e owner", r.owner, t.ownerObject(t.owner(e)))
            val list = heads.getValue(r.ix * 1_000_003 + r.iy)
            assertEquals("entry $e next", list.getOrNull(position[e] + 1) ?: -1, t.next(e))
            if (position[e] == 0) assertEquals("entry $e found", e, t.findKey(r.ix, r.iy))
        }
        println("plain fold: ${calls.size} calls, $logged logged, ${t.size} entries at ${t.keys} keys, ${t.ambiguousKeys} with alternatives, ${t.overflow} over the cap")
    }

    // ---------------------------------------------------------------- renders against the oracle

    /** What the tape found of a render's path vertices. */
    class Coverage(
        val vertices: Int, val found: Int, val ambiguous: Int, val deep: Int, val offFrame: Int, val worst: Double, val within: Int,
        val misses: List<String>,
    ) {
        val share: Double get() = if (vertices == 0) 1.0 else found.toDouble() / vertices
        override fun toString() = ("%d path vertices, %.3f %% on the tape (%d at keys with alternatives, %d of those more than 1 m deep apart), " +
            "%d not (%d of those beyond a frame around the frame); re-projected within 0.01 px: %d of %d, worst %.4f px")
            .format(vertices, share * 100, ambiguous, deep, vertices - found, offFrame, within, found, worst) +
            misses.joinToString("") { "\n    not on the tape: $it" }
    }

    private val pathD = Regex(" d=\"([^\"]*)\"")
    private val pattern = Regex("<pattern\\b.*?</pattern>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Every absolute M/L vertex of every path in [svgs], resolved on [tape] and re-projected with [frame]; pattern tiles
     * are drawn in their own space, not the frame's, so they are left out.
     */
    private fun coverage(tape: WorldTape, frame: City.Camera, vararg svgs: String?): Coverage {
        var vertices = 0; var found = 0; var amb = 0; var deep = 0; var off = 0; var worst = 0.0; var within = 0
        val misses = ArrayList<String>()
        var bx = DoubleArray(4096); var by = DoubleArray(4096); var bo = IntArray(4096)
        val w = DoubleArray(3)
        for (text in svgs) {
            if (text == null) continue
            val svg = text.replace(pattern, "")
            for (m in pathD.findAll(svg)) {
                val d = m.groupValues[1]
                var n = 0; var i = 0; var cmd = 'M'
                val nums = ArrayList<Double>(2)
                while (i < d.length) {
                    val ch = d[i]
                    if (ch.isLetter()) { cmd = ch; i++; nums.clear(); continue }
                    if (ch == ' ' || ch == ',') { i++; continue }
                    var j = i + 1
                    while (j < d.length && (d[j].isDigit() || d[j] == '.')) j++
                    nums.add(d.substring(i, j).toDouble()); i = j
                    if ((cmd == 'M' || cmd == 'L') && nums.size == 2) {
                        if (n == bx.size) { bx = bx.copyOf(n * 2); by = by.copyOf(n * 2); bo = bo.copyOf(n * 2) }
                        bx[n] = nums[0]; by[n] = nums[1]; n++; nums.clear()
                    } else if (cmd != 'M' && cmd != 'L') nums.clear()
                }
                found += tape.resolve(bx, by, n, bo); vertices += n
                for (k in 0 until n) {
                    val e = bo[k]
                    val outside = bx[k] < -frame.W || bx[k] > 2 * frame.W || by[k] < -frame.H || by[k] > 2 * frame.H
                    if (e < 0) {
                        if (outside) off++ else if (misses.size < 3) misses.add("(%.1f, %.1f) in %s".format(bx[k], by[k], svg.substring(maxOf(0, m.range.first - 60), minOf(svg.length, m.range.first + 100))))
                        continue
                    }
                    val head = tape.find(bx[k], by[k])
                    if (tape.next(head) >= 0) {
                        amb++
                        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE; var a = head
                        while (a >= 0) { lo = minOf(lo, tape.depth(a)); hi = maxOf(hi, tape.depth(a)); a = tape.next(a) }
                        if (hi - lo > 1.0) deep++
                    }
                    tape.world(e, w)
                    val p = frame.proj(V3(w[0], w[1], w[2])) ?: continue
                    val err = Math.max(Math.abs(p.x - bx[k]), Math.abs(p.y - by[k]))
                    if (err <= 0.01) within++
                    if (err > worst) worst = err
                }
            }
        }
        return Coverage(vertices, found, amb, deep, off, worst, within, misses)
    }

    private fun report(name: String, tape: WorldTape, c: Coverage) {
        println("$name: tape ${tape.size} entries at ${tape.keys} keys (${tape.ambiguousKeys} with alternatives, ${tape.overflow} over the cap), " +
            "${tape.calls} pc calls (${tape.skipped} out of range, ${tape.late} late), ${tape.foreignCameras} foreign cameras, " +
            "%.1f MB (peak %.1f MB); %s".format(tape.bytes() / 1e6, tape.peakBytes / 1e6, c))
    }

    private fun assertCoverage(name: String, tape: WorldTape, c: Coverage) {
        report(name, tape, c)
        assertTrue("$name: at least 99 % of path vertices resolve: $c", c.share >= 0.99)
        assertTrue("$name: taped points re-project onto their vertex: $c", c.within >= c.found * 0.999)
        assertEquals("$name: no call after the render", 0L, tape.late)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the Westerkerk renders the same with the tape on and off, as Python does`() {
        Oracle.configure()
        val kit = AmsterdamTest.GROUPS.first { it.name == "kit" }
        val jobs = kit.jobs.filter { it["tag"] == "M_ams_kit" || it["tag"] == "kit_objsplit" }
        val dir = File(Oracle.out, "worldtape").also { it.mkdirs() }
        Oracle.run("amsterdam_oracle.py", "render", PyDumps.dumps(linkedMapOf("name" to "worldtape_kit", "env" to kit.env, "jobs" to jobs)), dir.path, timeoutSec = 1800)
        val res = Oracle.py(Json.parseToJsonElement(File(dir, "worldtape_kit.json").readText())) as Map<String, Any?>
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
                    val off = Amsterdam.render(S, m, cfg, null, "split", opts)
                    val tape = WorldTape()
                    val on = WorldTape.recording(tape) { Amsterdam.render(S, m, cfg, null, "split", opts) }
                    val mine = listOf("g" to off.svg to on.svg) + off.objs!!.indices.map { i -> (if (i == 0) "o" else "o2") to off.objs!![i] to on.objs!![i] }
                    for ((lp, onText) in mine) {
                        val (layer, offText) = lp
                        val py = files.getValue("${job["tag"]}__${m}__$layer.svg")["md5"]
                        assertEquals("${job["tag"]} $m $layer tape off", py, RegionsTest.md5(offText))
                        assertEquals("${job["tag"]} $m $layer tape on", py, RegionsTest.md5(onText)); layers++
                    }
                    assertCoverage("Westerkerk ${job["tag"]} $m", tape, coverage(tape, Amsterdam.cameraOf(cfg), on.svg, *on.objs!!.toTypedArray()))
                }
            }
            val cfg = AmsterdamTest.cfgOf(jobs[0])
            val ok = { c: Cost -> c.record <= 0.10 && c.seal <= 0.10 }
            val (cost, text) = overheadWhere(ok, 15) { tape -> if (tape == null) Amsterdam.render(S, "sunny", cfg, null, "split", opts) else WorldTape.recording(tape) { Amsterdam.render(S, "sunny", cfg, null, "split", opts) } }
            println("Westerkerk: $layers layers equal Python with the tape on and off; $text")
            assertTrue("recording adds ≤ 10 % to the Westerkerk's render, and its seal costs ≤ 10 % of one: $text", ok(cost))
        } finally {
            Amsterdam.resetThemes()
        }
    }

    /**
     * The cost of the tape, in this thread's CPU time (which the other processes on the machine disturb less than the
     * wall clock), over [runs] rounds of a render without a tape, with a fresh one (not presized) and its seal, and with
     * one reused through [WorldTape.reset] as a frame loop keeps its tapes, in alternating order (ABC, CBA) so that drift
     * cancels. The shares are medians over the rounds, each against the round's render without a tape: [record] what
     * the render pays with a fresh tape, [seal] the fold that follows it (after the render has returned, so outside the
     * engine's lock), [total] both, [reused] a reused tape's recording.
     */
    class Cost(
        val off: Double, val on: Double, val reusedMs: Double, val sealMs: Double,
        val record: Double, val seal: Double, val total: Double, val reused: Double, val wall: Double, val runs: Int,
    ) {
        override fun toString() = ("CPU, fastest: render %.0f ms without the tape, %.0f ms with a fresh one, %.0f ms with a reused one, %.1f ms to seal; " +
            "median of %d rounds: recording %+.1f %%, seal %+.1f %%, both %+.1f %%, recording into a reused tape %+.1f %% (wall clock, both: %+.1f %%)")
            .format(off, on, reusedMs, sealMs, runs, record * 100, seal * 100, total * 100, reused * 100, wall * 100)
    }

    private val threads = java.lang.management.ManagementFactory.getThreadMXBean()

    private fun cpu(): Long = threads.currentThreadCpuTime

    private fun median(a: DoubleArray): Double { val s = a.sorted(); return (s[(s.size - 1) / 2] + s[s.size / 2]) / 2 }

    /** [runs] rounds of renders without a tape, with a fresh one and with a reused one. */
    private fun overhead(runs: Int, render: (WorldTape?) -> Any): Cost {
        render(null); val kept = WorldTape().also { render(it); it.seal() }
        val off = DoubleArray(runs); val on = DoubleArray(runs); val seal = DoubleArray(runs); val reused = DoubleArray(runs)
        val wallOff = DoubleArray(runs); val wallOn = DoubleArray(runs)
        for (i in 0 until runs) {
            val a = {
                val w = System.nanoTime(); val t = cpu(); render(null); off[i] = (cpu() - t) / 1e6; wallOff[i] = (System.nanoTime() - w) / 1e6
            }
            val b = {
                val w = System.nanoTime(); val tape = WorldTape(); val t = cpu(); render(tape); val t1 = cpu(); tape.seal()
                on[i] = (t1 - t) / 1e6; seal[i] = (cpu() - t1) / 1e6; wallOn[i] = (System.nanoTime() - w) / 1e6
            }
            val c = { kept.reset(); val t = cpu(); render(kept); reused[i] = (cpu() - t) / 1e6; kept.seal() }
            if (i % 2 == 0) { a(); b(); c() } else { c(); b(); a() }
        }
        return Cost(off.min(), on.min(), reused.min(), seal.min(),
            median(DoubleArray(runs) { on[it] / off[it] - 1 }), median(DoubleArray(runs) { seal[it] / off[it] }),
            median(DoubleArray(runs) { (on[it] + seal[it]) / off[it] - 1 }), median(DoubleArray(runs) { reused[it] / off[it] - 1 }),
            median(DoubleArray(runs) { wallOn[it] / wallOff[it] - 1 }), runs)
    }

    /** [overhead], measured again once when the first measure fails [ok] (a shared machine's load comes and goes). */
    private fun overheadWhere(ok: (Cost) -> Boolean, runs: Int, render: (WorldTape?) -> Any): Pair<Cost, String> {
        val first = overhead(runs, render)
        if (ok(first)) return first to "$first"
        val second = overhead(runs, render)
        return (if (ok(second)) second else first) to "$first; measured again: $second"
    }

    @Suppress("UNCHECKED_CAST")
    private fun regionJob(tag: String, check: (String, Dict, Map<String, Any?>, Regions.Layers, WorldTape) -> Unit) {
        val job = RegionsTest.JOBS.firstOrNull { it.tag == tag }
        assumeTrue("the $tag extract is local only", job != null)
        Oracle.configure()
        // the regions' own data, as RegionsTest reads it (the live tests point these at their unpacked packs)
        Regions.root = File(Oracle.repo, "data/regions")
        Regions.parkRoot = File(Oracle.repo, "data/park")
        Regions.streetCfgDir = File(Oracle.repo, "cache/render")
        Regions.resetPlace()
        RegionsTest.runOracle(listOf(job!!))
        val files = (RegionsTest.readJson(File(RegionsTest.dir, "$tag.json"))["files"] as Map<String, Map<String, Any?>>).values
        val opts = RenderOptions(ao = job.ao)
        val S = Regions.load(job.view, opts)
        val cfg = Regions.cfgFor(job.view)
        for (m in job.moments) {
            val off = Regions.render(S, m, cfg, opts)
            val tape = WorldTape()
            val on = WorldTape.recording(tape) { Regions.render(S, m, cfg, opts) }
            for ((layer, pair) in listOf("g" to (off.g to on.g), "o" to (off.o to on.o), "o2" to (off.o2 to on.o2))) {
                val py = files.firstOrNull { it["moment"] == m && it["layer"] == layer }?.get("md5")
                if (py == null) { assertNull("$tag $m $layer only in Kotlin", pair.first); continue }
                assertEquals("$tag $m $layer tape off", py, pair.first?.let { RegionsTest.md5(it) })
                assertEquals("$tag $m $layer tape on", py, pair.second?.let { RegionsTest.md5(it) })
            }
            assertEquals("$tag $m t", off.t?.data?.let { RegionsTest.md5(it) }, on.t?.data?.let { RegionsTest.md5(it) })
            check(m, S, cfg, on, tape)
        }
    }

    @Test
    fun `the Main board grachten_home renders the same with the tape on, within 20 MB and 10 % of render time`() {
        var S0: Dict? = null; var cfg0: Map<String, Any?>? = null
        regionJob("grachten_home") { m, S, cfg, on, tape ->
            val c = coverage(tape, Amsterdam.cameraOf(cfg), on.g, on.o, on.o2)
            assertCoverage("grachten_home $m", tape, c)
            assertTrue("grachten_home $m tape peak ${tape.peakBytes} B ≤ 20 MB", tape.peakBytes <= 20_000_000)
            if (m == "sunny") {
                // a frame loop's tape, reset and refilled by the same render, holds the same entries
                val again = WorldTape(); WorldTape.recording(again) { Regions.render(S, m, cfg) }
                again.reset(); WorldTape.recording(again) { Regions.render(S, m, cfg) }
                again.seal()
                assertEquals(tape.size, again.size); assertEquals(tape.keys, again.keys)
                for (e in 0 until tape.size step 97) {
                    assertEquals(tape.screenX10(e), again.screenX10(e)); assertEquals(tape.screenY10(e), again.screenY10(e))
                    assertEquals(tape.depth(e), again.depth(e), 0.0); assertEquals(tape.next(e), again.next(e))
                }
            }
            S0 = S; cfg0 = cfg
        }
        val S = S0!!; val cfg = cfg0!!
        val ok = { c: Cost -> c.total <= 0.10 }
        val (cost, text) = overheadWhere(ok, 12) { tape -> if (tape == null) Regions.render(S, "sunny", cfg) else WorldTape.recording(tape) { Regions.render(S, "sunny", cfg) } }
        println("grachten_home: $text")
        assertTrue("the tape, recording and seal, adds ≤ 10 % to render: $text", ok(cost))
    }

    @Test
    fun `a terrain region renders the same with the tape on`() {
        regionJob("sangimignano_close") { m, _, cfg, on, tape ->
            assertCoverage("sangimignano_close $m", tape, coverage(tape, Amsterdam.cameraOf(cfg), on.g, on.o, on.o2))
        }
    }
}
