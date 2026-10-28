package app.atlas.mapcorecheck.sun

import app.atlas.anchor.AnchorCases
import app.atlas.geo.BBox
import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.Living
import app.atlas.render.Real
import app.atlas.render.V2
import app.atlas.render.V3
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.py.Dict
import app.atlas.render.py.PyMath
import app.atlas.render.py.PyRandom
import app.atlas.render.py.truthy
import app.atlas.rendertiles.model.Keep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Random
import kotlin.concurrent.withLock

/**
 * The sun worker's entry points in the core's host build (`CP/jni/TestSun.cpp`, plan-vector K8), loaded from
 * `-Datlas.mapcore.host`.
 */
internal object SunHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's sun entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The pack's sun scene at ladder [level]: `tiles … solids … samples … windows … maxh … ms …`, then `kind` lines. */
    @JvmStatic external fun scene(path: String, level: Int): String

    /**
     * The core's answers for the port's samples (7 longs each: the point's bits, the building's id or Long.MIN_VALUE,
     * its first vertex's bits, the port's answer): per sample -1 (no such sample), else kind + 32 · the march's answer +
     * 64 · (the core's sample faces the sun) + 128 · (the core holds such a sample more than once).
     */
    @JvmStatic external fun match(path: String, level: Int, sx: Double, sy: Double, sz: Double, port: LongArray): IntArray?

    /** Every bit of the scene for the sun: `bits <sha256> lit <n> of <n>`, then `kind <name> <lit> <of>` lines. */
    @JvmStatic external fun bits(path: String, level: Int, sx: Double, sy: Double, sz: Double, threads: Int): String

    /** Every solid's and tree's ground shadow: (id, x0, z0 | x, z, 0), kind, n, n pairs of bits. */
    @JvmStatic external fun hulls(path: String, level: Int, sx: Double, sy: Double, sz: Double): LongArray?

    /** One bucket's time over the tiles within [radius] of (tx, tz), nearest (cx, cz) first: `bucket <ms> threads …`. */
    @JvmStatic external fun bucket(
        path: String, level: Int, sx: Double, sy: Double, sz: Double, tx: Double, tz: Double, radius: Double, cx: Double,
        cz: Double, windowReach: Double, threads: Int, repeats: Int,
    ): String

    /** The sun's check: `sun <hash> ok`, or `sun <hash> FAIL expected <hash>`. */
    @JvmStatic external fun check(): String

    /** The bundled check over the pack at [path], as the app's VK facts runs it: its line, `hash <hex>`, `ms <time>`. */
    @JvmStatic external fun bundled(path: String): String
}

/**
 * The sun worker held to the port (plan-vector D8, K8): every sample the port's renderer tests with `Shadows.lit` at
 * the boards' views (the Westerkerk at its four moments, the Main board, the smoke tier's eight sweep cameras) is one
 * of the core's samples, the same point bit for bit and the same building left out, and the core's answer is the
 * port's; the ground shadows equal `hull2`'s; the sun's check over a drawn scene equals the port's own `Shadows`; one
 * bucket of the Main board takes at most 80 ms on four cores (gated with ATLAS_TIMED=1, printed otherwise).
 *
 * The port's samples come from its own render, through the test-only tap on `Amsterdam.Shade.lit`; the core's from the
 * bundled render packs (`-Datlas.tiles`) at the level the render loaded.
 */
class SunParityTest {
    private val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))
    private val westerkerkPack = File(tiles, "amsterdam.atlas")
    private val grachtenPack = File(tiles, "grachten.atlas")

    /** One render of the port: the region, the view, the moment. */
    private class Case(val name: String, val pack: File, val region: () -> LiveRegion?, val view: (LiveRegion) -> LiveView, val moment: String)

    /** One sample the port tested: its point's bits, the building left out (or none) and the answer. */
    private class PortSample(val x: Long, val y: Long, val z: Long, val id: Long, val x0: Long, val z0: Long, val lit: Boolean)

    /** The port's render of [case] with every sample it tested, the sun it drew with and the level it loaded at. */
    private class Rendered(val samples: List<PortSample>, val sun: V3, val level: Int, val millis: Long)

    private fun render(case: Case, region: LiveRegion): Rendered {
        val view = case.view(region)
        val out = ArrayList<PortSample>()
        return LiveEngine.lock.withLock {
            val t0 = System.nanoTime()
            Amsterdam.Shade.tap = Amsterdam.Shade.Tap { p, skip, lit ->
                if (skip == null) {
                    out += PortSample(p.x.toRawBits(), p.y.toRawBits(), p.z.toRawBits(), Long.MIN_VALUE, 0, 0, lit)
                } else {
                    val first = firstVertex(skip)
                    out += PortSample(p.x.toRawBits(), p.y.toRawBits(), p.z.toRawBits(), (skip["id"] as Number).toLong(), first.x.toRawBits(), first.y.toRawBits(), lit)
                }
            }
            val r = try {
                LiveEngine.render(region, view, Moment.Named(case.moment)) ?: error("${case.name}: the render was cancelled")
            } finally {
                Amsterdam.Shade.tap = null
            }
            val sun = Amsterdam.sunOf(Amsterdam.THEMES.getValue(r.themeKey)).third
            val level = Keep.indexOf(r.tolerance)
            check(level >= 0) { "${case.name}: the render loaded at ${r.tolerance} m, which is not on the keep ladder" }
            Rendered(out, sun, level, (System.nanoTime() - t0) / 1_000_000)
        }
    }

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val w = { AnchorCases.westerkerk() }
        val g = { AnchorCases.grachten() }
        // EngineVersion's fixed renders: the Westerkerk at its four moments and the Main board (San Gimignano's close view
        // stands on terrain, which render tiles do not carry yet)
        for (f in EngineVersion.FIXED) {
            if (f.region == "sangimignano") continue
            val pack = if (f.region == "grachten") grachtenPack else westerkerkPack
            out += Case(f.name, pack, if (f.region == "grachten") g else w, { r -> f.liveView(r) }, f.moment)
        }
        // the smoke tier's home and board cameras (CASES/cases/smoke.json at S1, 412 × 916) at their sunny moment
        out += Case("home sunny S1", westerkerkPack, w, { r -> AnchorCases.homeView(r, 916) }, "sunny")
        out += Case("home grey S1", westerkerkPack, w, { r -> AnchorCases.homeView(r, 916) }, "grey")
        out += Case("board sunny S1", grachtenPack, g, { r -> knot(r, "grachten_home", 916) }, "sunny")
        out += Case("board grey S1", grachtenPack, g, { r -> knot(r, "grachten_home", 916) }, "grey")
        // the smoke tier's eight sweep cameras, each at its own moment and at the sunny one
        val bundledSweep = { k: Int, m: String -> Case("sweep $k $m S1", westerkerkPack, w, { r -> sweep(24, r.bbox!!, 916, 9)[k] }, m) }
        val canalSweep = { k: Int, m: String -> Case("canal sweep $k $m S1", grachtenPack, g, { r -> sweep(12, r.bbox!!, 916, 9)[k] }, m) }
        for (k in 0 until 4) {
            val own = if (k % 2 == 0) "sunny" else "night"
            out += bundledSweep(k, own)
            out += canalSweep(k, own)
            if (own != "sunny") {
                out += bundledSweep(k, "sunny")
                out += canalSweep(k, "sunny")
            }
        }
        return out
    }

    @Test
    fun theCoreAnswersEverySampleOfTheBoardsViewsAsThePortDoes() {
        assumeTrue("the canal ring's pack is not on this machine", AnchorCases.grachtenPack.isFile)
        val lines = ArrayList<String>()
        val kinds = java.util.TreeMap<String, IntArray>()
        var total = 0
        val seenScenes = HashSet<String>()
        for (case in cases()) {
            val region = case.region() ?: error("${case.name}: no region")
            val r = render(case, region)
            val path = case.pack.path
            if (seenScenes.add("$path ${r.level}")) println("sun scene ${case.pack.name} level ${r.level}: ${SunHost.scene(path, r.level).lines().first()}")
            val port = LongArray(r.samples.size * 7)
            for ((i, s) in r.samples.withIndex()) {
                port[7 * i] = s.x; port[7 * i + 1] = s.y; port[7 * i + 2] = s.z
                port[7 * i + 3] = s.id; port[7 * i + 4] = s.x0; port[7 * i + 5] = s.z0; port[7 * i + 6] = if (s.lit) 1 else 0
            }
            val core = SunHost.match(path, r.level, r.sun.x, r.sun.y, r.sun.z, port) ?: error("${case.name}: the core has no scene of ${case.pack}")
            var missing = 0; var differ = 0; var repeated = 0; var lit = 0; var turned = 0
            val portHash = MessageDigest.getInstance("SHA-256"); val coreHash = MessageDigest.getInstance("SHA-256")
            val firstMissing = ArrayList<String>()
            val differing = java.util.TreeMap<String, Int>()
            for ((i, s) in r.samples.withIndex()) {
                val c = core[i]
                if (c < 0) {
                    missing++
                    if (firstMissing.size < 5) firstMissing += "(${Double.fromBits(s.x)}, ${Double.fromBits(s.y)}, ${Double.fromBits(s.z)}) of ${if (s.id == Long.MIN_VALUE) "none" else s.id.toString()}"
                    continue
                }
                val kind = KINDS[c and 31]
                // the core's bit is its march's answer where its sample faces the sun; the port marched, so its face did
                val coreLit = (c and 32) != 0 && (c and 64) != 0
                if ((c and 64) == 0) turned++
                if ((c and 128) != 0) repeated++
                if (coreLit != s.lit) { differ++; differing.merge(kind, 1, Int::plus) }
                if (s.lit) lit++
                kinds.getOrPut(kind) { IntArray(2) }.let { it[0]++; if (s.lit) it[1]++ }
                for (h in listOf(portHash to s.lit, coreHash to coreLit)) {
                    h.first.update(longBytes(s.x, s.y, s.z, s.id, s.x0, s.z0)); h.first.update(if (h.second) 1 else 0)
                }
            }
            total += r.samples.size
            val ph = hex(portHash.digest()); val ch = hex(coreHash.digest())
            val line = "sun ${case.name}: ${r.samples.size} samples (${lit} lit) at level ${r.level}, port ${ph.take(16)}, core ${ch.take(16)}, " +
                "missing $missing, differing $differ $differing, turned from the sun $turned, repeated $repeated (render ${r.millis} ms)"
            println(line)
            lines += line
            assertEquals("${case.name}: port samples the core does not have, the first: $firstMissing", 0, missing)
            assertEquals("${case.name}: samples whose answer differs from the port's", 0, differ)
            assertEquals("${case.name}: samples the port marched from whose face the core finds turned from the sun", 0, turned)
            assertEquals("${case.name}: the core's hash over the port's samples", ph, ch)
        }
        for ((k, v) in kinds) println("sun kind $k: ${v[0]} samples tested by the port, ${v[1]} lit")
        println("sun: $total samples of ${cases().size} renders, every one the port's bit")
        assertTrue("the boards' views test samples", total > 50_000)
    }

    @Test
    fun theGroundShadowsAreHull2s() {
        assumeTrue("the canal ring's pack is not on this machine", AnchorCases.grachtenPack.isFile)
        var compared = 0
        for ((case, pack, regionOf, viewName) in listOf(
            Quad("westerkerk-sunny", westerkerkPack, { AnchorCases.westerkerk() }, null),
            Quad("grachten_home-sunny", grachtenPack, { AnchorCases.grachten()!! }, "grachten_home"),
        )) {
            val region = regionOf()
            val scene = LiveEngine.loadScene(region, viewName)
            val level = Keep.indexOf(scene.tolerance)
            val L = LiveEngine.lock.withLock {
                scene.state.restore()
                Amsterdam.sunOf(Amsterdam.THEMES.getValue("sunny")).third
            }
            assertTrue("$case: the sun is up", L.y > 0)
            @Suppress("UNCHECKED_CAST")
            val S = scene.S
            val want = HashMap<List<Long>, List<Long>>()
            fun o(y: Double) = V2(-L[0] / L[1] * y, -L[2] / L[1] * y)
            @Suppress("UNCHECKED_CAST")
            val solids = (S["buildings"] as List<Dict>).filter { !truthy(it["hidden"]) && truthy(it["h"]) } + (S["parts"] as List<Dict>).filter { truthy(it["h"]) }
            for (b in solids) {
                val poly = polyOf(b)
                val top = (b["h"] as Number).toDouble(); val bot = (b["min"] as Number).toDouble()
                val off1 = o(top)
                val moved = poly.map { V2(it.x + off1[0], it.y + off1[1]) }
                val shadow = if (bot > 0) moved else City.hull2(poly + moved)
                val key = listOf((b["id"] as Number).toLong(), poly[0].x.toRawBits(), poly[0].y.toRawBits(), if (bot > 0) 1L else 0L)
                want[key] = shadow.flatMap { listOf(it.x.toRawBits(), it.y.toRawBits()) }
            }
            @Suppress("UNCHECKED_CAST")
            for (n in (S["trees"] as List<Dict>)) {
                val nx = (n["x"] as Number).toDouble(); val nz = (n["z"] as Number).toDouble()
                val off = o((n["h"] as Number).toDouble() * 0.7); val r = (n["crown"] as Number).toDouble() / 2
                val pts = (0 until 16).map { k -> val a = k * Math.PI / 8; V2(nx + off[0] + r * PyMath.cos(a), nz + off[1] + r * PyMath.sin(a)) }
                want[listOf(nx.toRawBits(), nz.toRawBits(), 0L, 2L)] = pts.flatMap { listOf(it.x.toRawBits(), it.y.toRawBits()) }
            }
            val core = SunHost.hulls(pack.path, level, L.x, L.y, L.z) ?: error("$case: no scene")
            var at = 0; var n = 0; var differ = 0
            val got = HashMap<List<Long>, List<Long>>()
            while (at < core.size) {
                val key = listOf(core[at], core[at + 1], core[at + 2], core[at + 3]); val m = core[at + 4].toInt()
                got[key] = core.copyOfRange(at + 5, at + 5 + 2 * m).toList()
                at += 5 + 2 * m; n++
            }
            for ((k, v) in want) if (got[k] != v) differ++
            println("sun hulls $case: ${want.size} shadows of the port (${solids.size} solids, ${(S["trees"] as List<*>).size} trees), the core's $n, differing $differ")
            assertEquals("$case: the core's shadows", want.size, got.size)
            assertEquals("$case: shadows that differ from hull2's", 0, differ)
            compared += want.size
        }
        assertTrue(compared > 10_000)
    }

    private data class Quad(val name: String, val pack: File, val region: () -> LiveRegion, val view: String?)

    @Test
    fun theSunsCheckIsThePortsShadowsAndHull2() {
        val port = portCheck()
        val line = SunHost.check()
        println("sun check: the port ${"%016x".format(port)}, the core's line '$line'")
        assertEquals("the core's check of the drawn scene", "sun ${"%016x".format(port)}", line.substringBefore(" ok").substringBefore(" FAIL"))
        val source = File(System.getProperty("atlas.repo"), "app/android/mapcore/cpp/sun/Buckets.hpp").readText()
        val expected = Regex("march_check_expected = 0x([0-9A-Fa-f']+)ULL").find(source)?.groupValues?.get(1)?.replace("'", "")?.lowercase()
        assertEquals("CP/sun/Buckets.hpp's march_check_expected must be the port's hash, 0x%016XULL".format(port), "%016x".format(port), expected)
        assertTrue(line.endsWith(" ok"))
    }

    @Test
    fun theBundledCheckIsTheHostBuildsAndItsSunThePorts() {
        // the Westerkerk's sunny sun, as the port draws it: the bundled check's sun (CP/sun/Buckets.hpp's bundled_sun)
        val region = AnchorCases.westerkerk()
        val placed = LiveEngine.place(region, AnchorCases.homeView(region, 916))
        val L = LiveEngine.lock.withLock {
            LiveEngine.render(region, AnchorCases.homeView(region, 916), Moment.Named("sunny"))
            Amsterdam.sunOf(Amsterdam.THEMES.getValue("sunny")).third
        }
        assertNotNull(placed)
        val want = listOf(L.x, L.y, L.z).map { "%016x".format(it.toRawBits()) }
        val source = File(System.getProperty("atlas.repo"), "app/android/mapcore/cpp/sun/Buckets.hpp").readText()
        val sun = Regex("bundled_sun\\{([^;]*)\\};").find(source)?.groupValues?.get(1) ?: error("Buckets.hpp has no bundled_sun")
        val got = Regex("0x([0-9A-Fa-f']+)ULL").findAll(sun).map { it.groupValues[1].replace("'", "").lowercase() }.toList()
        println("sun bundled: the port's sunny sun ${want.joinToString(" ")}")
        assertEquals("CP/sun/Buckets.hpp's bundled_sun must be the Westerkerk's sunny sun as the port computes it", want, got)
        // the host build's bundled check: the hash ci/hashes.env pins for the emulator, ATLAS_SUN_HASH
        val out = SunHost.bundled(westerkerkPack.path).lines()
        println("sun bundled: ${out.joinToString("; ")}")
        val hash = out[1].removePrefix("hash ")
        assertEquals("the host build's bundled line (bundled_check_expected must be its hash, 0x%sULL)".format(hash.uppercase()), "sun $hash ok", out[0])
        val hashes = File(System.getProperty("atlas.repo"), "app/android/ci/hashes.env").readText()
        val pinned = Regex("^ATLAS_SUN_HASH=([0-9a-f]{16})$", RegexOption.MULTILINE).find(hashes)?.groupValues?.get(1)
        assertEquals("ci/hashes.env's ATLAS_SUN_HASH must be the host build's bundled hash", hash, pinned)
    }

    @Test
    fun oneBucketOfTheMainBoardIsQuick() {
        assumeTrue("the canal ring's pack is not on this machine", AnchorCases.grachtenPack.isFile)
        val region = AnchorCases.grachten()!!
        val case = Case("board", grachtenPack, { region }, { r -> knot(r, "grachten_home", 916) }, "sunny")
        val placed = LiveEngine.place(region, case.view(region))
        val r = render(case, region)
        val target = placed.cfg["target"] as List<*>
        val cam = Amsterdam.cameraOf(placed.cfg)
        val radius = (placed.cfg["radius"] as Number?)?.toDouble() ?: 1e9
        val winD = (placed.cfg["win_d"] as Number?)?.toDouble() ?: 390.0
        val tx = (target[0] as Number).toDouble(); val tz = (target[2] as Number).toDouble()
        val one = SunHost.bucket(grachtenPack.path, r.level, r.sun.x, r.sun.y, r.sun.z, tx, tz, radius, cam.C.x, cam.C.z, winD * 1.35, 1, 3)
        val four = SunHost.bucket(grachtenPack.path, r.level, r.sun.x, r.sun.y, r.sun.z, tx, tz, radius, cam.C.x, cam.C.z, winD * 1.35, 4, 5)
        println("sun Main board, one core: $one")
        println("sun Main board, four cores: $four")
        val ms = four.split(' ')[1].toDouble()
        // a time on a shared machine is no measurement: gated only on a quiet one, asked for with ATLAS_TIMED=1
        if (System.getenv("ATLAS_TIMED") == "1") assertTrue("one bucket of the Main board takes $ms ms on four cores, over 80", ms <= 80.0)
    }

    @Test
    fun everyBitsHashIsWrittenForTheOtherHosts() {
        assumeTrue("the canal ring's pack is not on this machine", AnchorCases.grachtenPack.isFile)
        // the boards' own suns and levels: the C++ test (ATLAS_SUN_EXPECT) holds every other host to these hashes
        val lines = ArrayList<String>()
        for (case in cases().filter { it.name in setOf("westerkerk-sunny", "westerkerk-grey", "grachten_home-sunny") }) {
            val region = case.region()!!
            val r = render(case, region)
            val one = SunHost.bits(case.pack.path, r.level, r.sun.x, r.sun.y, r.sun.z, 1).lines()
            val four = SunHost.bits(case.pack.path, r.level, r.sun.x, r.sun.y, r.sun.z, 4).lines()
            assertEquals("${case.name}: the bits on four threads", one, four)
            val head = one.first().split(' ')
            lines += "sun bits ${case.pack.nameWithoutExtension} ${r.level} %016x %016x %016x ${head[1]}".format(r.sun.x.toRawBits(), r.sun.y.toRawBits(), r.sun.z.toRawBits())
            println("sun bits ${case.name}: ${one.joinToString("; ")}")
        }
        System.getProperty("atlas.l0.out")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "sun-bits.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }

    // ------------------------------------------------------------------ the port's side of the sun's check

    /** CP/sun/Buckets.cpp's march_check, line for line, with the port's Shadows, hull2 and sunVector. */
    private fun portCheck(): Long {
        val rnd = PyRandom(20261018L)
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64's offset basis
        fun feed(v0: Long, bytes: Int) {
            var v = v0
            repeat(bytes) {
                hash = (hash xor (v and 0xFF)) * 0x100000001B3L
                v = v ushr 8
            }
        }
        class P(override val poly: List<V2>, override val h: Double) : Real.Prism {
            override var bb: DoubleArray? = null
            override var rf: DoubleArray? = null
        }
        val prisms = ArrayList<P>()
        repeat(600) {
            val cx = rnd.uniform(-300.0, 300.0); val cz = rnd.uniform(-300.0, 300.0)
            val nv = 3 + (rnd.random() * 6).toInt()
            val r = rnd.uniform(3.0, 22.0)
            val h = if (rnd.random() < 0.05) rnd.uniform(40.0, 90.0) else rnd.uniform(3.0, 30.0)
            val poly = (0 until nv).map { i ->
                val a = 2 * Math.PI * i / nv + rnd.uniform(-0.25, 0.25)
                val rr = r * rnd.uniform(0.55, 1.0)
                V2(cx + rr * PyMath.cos(a), cz + rr * PyMath.sin(a))
            }
            prisms += P(poly, h)
        }
        val index = Real.Shadows.Index(prisms)
        class Pt(val x: Double, val y: Double, val z: Double, val skip: Int)
        val points = (0 until 5000).map {
            val x = rnd.uniform(-320.0, 320.0); val y = rnd.uniform(0.0, 45.0); val z = rnd.uniform(-320.0, 320.0)
            Pt(x, y, z, if (rnd.random() < 0.4) (rnd.random() * 600).toInt() else -1)
        }
        for ((az, el) in listOf(135.0 to 35.0, 250.0 to 12.0, 190.0 to 55.0, 80.0 to 3.0, 300.0 to 70.0)) {
            val L = Living.sunVector(az, el)
            feed(L.x.toRawBits(), 8); feed(L.y.toRawBits(), 8); feed(L.z.toRawBits(), 8)
            val sh = Real.Shadows(index, L)
            for (p in points) feed(if (sh.lit(V3(p.x, p.y, p.z), if (p.skip >= 0) prisms[p.skip] else null)) 1 else 0, 1)
        }
        repeat(300) {
            val n = 3 + (rnd.random() * 30).toInt()
            val pts = ArrayList<V2>()
            for (i in 0 until n) {
                if (i > 0 && rnd.random() < 0.2) {
                    pts += pts[(rnd.random() * i).toInt()]
                    continue
                }
                var x = rnd.uniform(-50.0, 50.0)
                val z = rnd.uniform(-50.0, 50.0)
                if (rnd.random() < 0.05) x = -0.0
                pts += V2(x, z)
            }
            val hull = City.hull2(pts)
            feed(hull.size.toLong(), 4)
            for (q in hull) { feed(q.x.toRawBits(), 8); feed(q.y.toRawBits(), 8) }
        }
        return hash
    }

    private companion object {
        /** CP/sun/Samples.hpp's kinds, by number. */
        val KINDS = listOf(
            "wall", "cell", "skillion_wall", "window", "pyramid", "hip", "gable", "slope", "skillion", "top", "mansard", "dormer",
            "mansard_top", "hull", "cabin", "reflection", "quay", "quay_band", "crown", "band",
        )

        /** The footprint of a scene's building dict (its `poly`, of V2s or of pairs). */
        @Suppress("UNCHECKED_CAST")
        fun polyOf(b: Map<String, Any?>): List<V2> = (b["poly"] as List<Any?>).map { p ->
            when (p) {
                is V2 -> p
                is List<*> -> V2((p[0] as Number).toDouble(), (p[1] as Number).toDouble())
                else -> error("a vertex $p")
            }
        }

        fun firstVertex(b: Map<String, Any?>): V2 = polyOf(b)[0]

        /** A region view's camera on a 412 × [height] frame: the ladder's knot [name], which the camera snaps onto. */
        fun knot(r: LiveRegion, name: String, height: Int): LiveView {
            val k = r.ladder.knots.firstOrNull { it.name == name } ?: error("region ${r.id} has no view $name")
            return EngineVersion.knotView(r.frame, k, 412, height)
        }

        /** D16's sweep as :likeness's Cameras.sweep draws it (StrictMath and java.util.Random, the same on every machine). */
        fun sweep(n: Int, box: BBox, height: Int, seed: Long): List<LiveView> {
            val r = Random(seed)
            return List(n) {
                val lat = box.south + r.nextDouble() * box.height
                val lon = box.west + r.nextDouble() * box.width
                val dist = 91.0 * StrictMath.pow(4000.0 / 91.0, r.nextDouble())
                val bearing = r.nextDouble() * 360.0
                val pitch = r.nextDouble() * 70.0
                val cy = if (r.nextBoolean()) 440.0 else 457.5
                LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), bearing, pitch, 412, height, cy)
            }
        }

        fun longBytes(vararg v: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(8 * v.size)
            for (x in v) b.putLong(x)
            return b.array()
        }

        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
