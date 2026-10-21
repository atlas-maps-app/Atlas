package app.atlas.mapcorecheck.text

import app.atlas.anchor.AnchorCases
import app.atlas.geo.BBox
import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.Living
import app.atlas.render.Regions
import app.atlas.render.V2
import app.atlas.render.V3
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.py.truthy
import app.atlas.render.v3
import app.atlas.rendertiles.compile.EngineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.concurrent.withLock

/**
 * The text module's entry points in the core's host build (`CP/jni/TestText.cpp`, plan-vector K13), loaded from
 * `-Datlas.mapcore.host`. A camera is the port's cfg as 11 doubles: target x, y, z, yaw, pitch, dist, fov, W, H, cx and
 * cy (NaN where the cfg names none).
 */
internal object TextHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's text entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /**
     * The canal names of the pack at [path] as the renders' rule draws them: [camera] then the radius and the water's
     * level. `candidates <n> ms <time>`, then `run <source index> <turned 0|1> <face> <weight> <six float bits> <element>`
     * a name, in the rule's order.
     */
    @JvmStatic external fun canalRule(path: String, camera: DoubleArray, night: Boolean, weather: Boolean): String

    /** One ground_text: [geometry] is p0, u and v; [anchor] 0 start, 1 middle, 2 end. The element, or "" for none. */
    @JvmStatic external fun groundText(
        camera: DoubleArray, text: String, geometry: DoubleArray, size: Double, fill: Int, opacity: Double, weight: Int,
        spacing: Double, anchor: Int,
    ): String

    /** A runway's designators ([points] as x, z pairs) in the marking colour [mark] (0xRRGGBB): the elements, a line each. */
    @JvmStatic external fun runways(camera: DoubleArray, points: DoubleArray, ref: String, mark: Int): String

    /** str.upper() of each code point alone: per code point, its capital form's length, then its code points. */
    @JvmStatic external fun upper(codePoints: IntArray): IntArray
}

/**
 * The core's text runs held to the port (plan-vector D13, K13's L1): at the Westerkerk home at its four moments, the
 * Main board, the S1 home and the smoke tier's eight sweep cameras, the canal names the core's rule draws from the
 * bundled tiles (`-Datlas.tiles`) are the port's `<text>` elements in its own SVG, element for element and in its
 * order: the strings, the matrices as printed (the snapping at rest) and read back as SvgParser reads them, and the
 * fonts. The names the core turns upside down for reading (Q3) are counted, an intended deviation that L1 compares
 * unturned; the house numbers and shop signs in the SVG are K14's, counted. Then ground_text, the runway designators
 * and str.upper() against the port on generated input.
 */
class TextParityTest {
    private val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))
    private val westerkerkPack = File(tiles, "amsterdam.atlas")
    private val grachtenPack = File(tiles, "grachten.atlas")

    /** One render of the port: its region, its view, its moment. */
    private class Case(val name: String, val pack: File, val region: () -> LiveRegion?, val view: (LiveRegion) -> LiveView, val moment: String)

    /** The port's render: its camera and radius cut and the water's level, the theme's night and weather, its `<text>` elements. */
    private class Rendered(val camera: DoubleArray, val night: Boolean, val weather: Boolean, val texts: List<String>, val millis: Long)

    /** A canal name as the core draws it. */
    private class Run(val index: Int, val turned: Boolean, val face: String, val weight: Int, val matrix: IntArray, val element: String)

    private fun render(case: Case, region: LiveRegion): Rendered = LiveEngine.lock.withLock {
        val t0 = System.nanoTime()
        val r = LiveEngine.render(region, case.view(region), Moment.Named(case.moment)) ?: error("${case.name}: the render was cancelled")
        val th = Amsterdam.THEMES.getValue(r.themeKey)
        val cfg = r.cfg
        fun num(k: String): Double = (cfg[k] as Number).toDouble()
        fun opt(k: String): Double = (cfg[k] as Number?)?.toDouble() ?: Double.NaN
        val t = v3(cfg["target"])
        val camera = doubleArrayOf(
            t.x, t.y, t.z, num("yaw"), num("pitch"), num("dist"), num("fov"), num("W"), num("H"), opt("cx"), opt("cy"),
            (cfg["radius"] as Number?)?.toDouble() ?: 1e9, Amsterdam.WATER_Y,
        )
        val texts = r.layers.flatMap { layer -> TEXT.findAll(layer).map { it.value }.toList() }
        Rendered(camera, th["night"] == true, truthy(th["wx"]), texts, (System.nanoTime() - t0) / 1_000_000)
    }

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val w = { AnchorCases.westerkerk() }
        val g = { AnchorCases.grachten() }
        // the Westerkerk home at its four moments and the Main board, as EngineVersion fixes them
        for (f in EngineVersion.FIXED) {
            if (f.region == "sangimignano") continue
            val grachten = f.region == "grachten"
            out += Case(f.name, if (grachten) grachtenPack else westerkerkPack, if (grachten) g else w, { r -> f.liveView(r) }, f.moment)
        }
        // the app's home camera at S1 and the smoke tier's eight sweep cameras (CASES/cases/smoke.json), sunny
        out += Case("home S1", westerkerkPack, w, { r -> AnchorCases.homeView(r, 916) }, "sunny")
        for (k in 0 until 4) {
            out += Case("sweep $k S1", westerkerkPack, w, { r -> sweep(24, r.bbox!!, 916, 9)[k] }, "sunny")
            out += Case("canal sweep $k S1", grachtenPack, g, { r -> sweep(12, r.bbox!!, 916, 9)[k] }, "sunny")
        }
        return out
    }

    @Test
    fun theCanalNamesAreThePortsTextRunsAtTheHomeViewsAndTheSweep() {
        var names = 0; var renders = 0; var turned = 0; var skipped = 0
        val boards = HashSet<String>()
        for (case in cases()) {
            val region = case.region()
            if (region == null || !case.pack.isFile) {
                println("text L1 ${case.name}: skipped, the canal ring's pack is not on this machine")
                skipped++
                continue
            }
            val r = render(case, region)
            val port = r.texts.filter { it.startsWith(GROUND_TEXT) }
            val others = r.texts.filterNot { it.startsWith(GROUND_TEXT) }
            val out = TextHost.canalRule(case.pack.path, r.camera, r.night, r.weather)
            check(!out.startsWith("error=")) { "${case.name}: $out" }
            val head = out.lineSequence().first()
            val runs = out.lineSequence().filter { it.startsWith("run ") }.map(::parseRun).toList()
            val core = runs.map { it.element }
            val first = (0 until maxOf(port.size, core.size)).firstOrNull { port.getOrNull(it) != core.getOrNull(it) }
            assertEquals(
                "${case.name}: the canal names differ from the port's at element $first:\n port ${first?.let { port.getOrNull(it) }}\n core ${first?.let { core.getOrNull(it) }}",
                port, core,
            )
            for ((i, run) in runs.withIndex()) {
                // the matrix as SvgParser reads the port's print back (SvgNumbers: a double, then a float)
                val printed = MATRIX.find(port[i])!!.groupValues[1].trim().split(' ').map { it.toDouble().toFloat().toRawBits() }
                assertEquals("${case.name}: ${run.element}: the snapped matrix", printed, run.matrix.toList())
                val weight = WEIGHT.find(port[i])!!.groupValues[1].toInt()
                assertEquals("${case.name}: ${run.element}: the face", "geist", run.face)
                assertEquals("${case.name}: ${run.element}: the weight", weight, run.weight)
            }
            val houseNumbers = others.count { "Georgia" in it }
            val up = runs.count { it.turned }
            println(
                "text L1 ${case.name}: ${core.size} canal names equal the port's ($up drawn upside down by the core), " +
                    "$houseNumbers house numbers and ${others.size - houseNumbers} shop signs (K14's); ${head.substringBefore(" ms")}, " +
                    "render ${r.millis} ms",
            )
            names += core.size; turned += up; renders++
            if (case.name == "westerkerk-sunny" || case.name == "grachten_home-sunny") {
                assertTrue("${case.name}: the port draws canal names", core.isNotEmpty())
                boards += case.name
            }
            if (r.night) assertTrue("${case.name}: no canal names at night", core.isEmpty())
        }
        println("text L1: $names canal names over $renders renders, every one the port's element; $turned drawn upside down; $skipped skipped")
        assertTrue("the Westerkerk home is checked", "westerkerk-sunny" in boards)
    }

    @Test
    fun groundTextPrintsThePortsElement() {
        val random = Random(13)
        var drawn = 0; var none = 0
        val texts = listOf("PRINSENGRACHT", "Keizersgracht", "18C", "Ü-Bahn & Co", "ĲSSELMEER", "·", "Ⅻ", "ﬁ", "東京", "😀", "")
        for (i in 0 until 20_000) {
            val camera = randomCamera(random)
            val cam = cameraOf(camera)
            val p0 = V3(random.nextDouble() * 1200 - 600, random.nextDouble() * 20 - 5, random.nextDouble() * 1200 - 600)
            val u = unit(random)
            // v mostly across u, sometimes nearly along it, where the map degenerates
            val v = if (random.nextInt(8) == 0) V3(u.x + random.nextGaussian() * 1e-3, u.y, u.z + random.nextGaussian() * 1e-3) else unit(random)
            val size = listOf(3.2, 18.0, 0.62, 2.4, random.nextDouble() * 20)[random.nextInt(5)]
            val fill = random.nextInt(0x1000000)
            val opacity = random.nextDouble()
            val weight = listOf(100, 400, 700, 800, 900)[random.nextInt(5)]
            val spacing = random.nextDouble() * 0.4
            val anchor = random.nextInt(3)
            val text = texts[random.nextInt(texts.size)]
            val want = Living.groundText(
                cam, text, p0, u, v, size, "#%06X".format(fill), opacity, weight, spacing, ANCHORS[anchor],
            )
            val got = TextHost.groundText(
                camera, text, doubleArrayOf(p0.x, p0.y, p0.z, u.x, u.y, u.z, v.x, v.y, v.z), size, fill, opacity, weight,
                spacing, anchor,
            )
            assertEquals("case $i", want, got)
            if (want.isEmpty()) none++ else drawn++
        }
        println("text ground_text: 20000 runs equal the port's elements ($drawn drawn, $none refused)")
        assertTrue("most runs are drawn", drawn > 10_000)
        assertTrue("some are refused", none > 500)
    }

    /** The style's runway marks, by day and at night. */
    @Suppress("UNCHECKED_CAST")
    private val runwayMarks = EngineStyle.tables["runways"] as Map<String, String>

    @Test
    fun runwayDesignatorsAreThePorts() {
        val random = Random(18)
        val refs = listOf(
            "18C/36C", "09/27", "04/22", "06/24", "18R/36L", " 18L / 36R ", "36C/18C", "١٨/٣٦", "１８/３６L", "𝟏𝟖/𝟑𝟔", "18",
            "", "/", "0/0", "000000000000000000018/36", "9/27/45", "x18/36", "18x/36x", "\u00A018\u3000/\u200936", "35/17", "1/19",
            "123456789012345678901234567890/36", "99999999999999999999/18", "18446744073709551617/0018", "3" + "0".repeat(400) + "/x",
        )
        var designators = 0; var runways = 0
        for (i in 0 until 4_000) {
            val camera = randomCamera(random)
            val cam = cameraOf(camera)
            val n = 2 + random.nextInt(4)
            val heading = random.nextDouble() * 2 * Math.PI
            val length = 500 + random.nextDouble() * 3000
            val x0 = random.nextDouble() * 1000 - 500; val z0 = random.nextDouble() * 1000 - 500
            val points = (0 until n).map { k ->
                val t = length * k / (n - 1) - length / 2
                V2(x0 + t * Math.sin(heading) + random.nextGaussian(), z0 - t * Math.cos(heading) + random.nextGaussian())
            }
            val ref = refs[random.nextInt(refs.size)]
            val night = random.nextBoolean()
            // the marking colour as draw_over picks it, the style's (EngineStyleTest holds it to the port's)
            val mk = runwayMarks.getValue(if (!night) "marks_day" else "marks_night")
            val want = TEXT.findAll(Regions.runwayEnds(cam, points, 45.0, ref, mk, night)).map { it.value }.toList()
            val got = TextHost.runways(camera, points.flatMap { listOf(it.x, it.y) }.toDoubleArray(), ref, mk.removePrefix("#").toInt(16))
                .lines().filter { it.isNotEmpty() }
            assertEquals("case $i: ref '$ref'", want, got)
            designators += want.size; runways++
        }
        println("text runways: $runways runways' designators equal the port's ($designators drawn)")
        assertTrue("designators are drawn", designators > 1_000)
    }

    @Test
    fun upperIsTheJvmsOnEveryCodePoint() {
        val all = (0..0x10FFFF).filter { it !in 0xD800..0xDFFF }.toIntArray()
        var changed = 0; var longer = 0
        val differ = ArrayList<String>()
        var at = 0
        while (at < all.size) {
            val chunk = all.copyOfRange(at, minOf(all.size, at + 65_536))
            val core = TextHost.upper(chunk)
            var k = 0
            for (c in chunk) {
                val n = core[k]
                val got = core.copyOfRange(k + 1, k + 1 + n)
                k += 1 + n
                val want = String(Character.toChars(c)).uppercase().codePoints().toArray()
                if (!want.contentEquals(got)) {
                    if (differ.size < 10) differ += "U+%04X: JVM %s, core %s".format(c, want.joinToString { "%04X".format(it) }, got.joinToString { "%04X".format(it) })
                    continue
                }
                if (want.size != 1 || want[0] != c) changed++
                if (want.size > 1) longer++
            }
            at += chunk.size
        }
        println("text upper: ${all.size} code points equal the JVM's uppercase() (Java ${System.getProperty("java.version")}); $changed change, $longer to more than one")
        assertTrue("code points differ: $differ", differ.isEmpty())
    }

    private companion object {
        val TEXT = Regex("<text[^>]*>[^<]*</text>")
        const val GROUND_TEXT = "<text transform=\"matrix("
        val MATRIX = Regex("""matrix\(([^)]*)\)""")
        val WEIGHT = Regex("""font-weight="(\d+)"""")
        val ANCHORS = listOf("start", "middle", "end")

        fun parseRun(line: String): Run {
            val f = line.split(' ', limit = 12)
            return Run(f[1].toInt(), f[2] == "1", f[3], f[4].toInt(), IntArray(6) { f[5 + it].toLong(16).toInt() }, f[11])
        }

        /** A camera over the region's middle: a target within 600 m, any yaw, pitch 2-89°, 40 m to 4 km away, 412 × 915. */
        fun randomCamera(random: Random): DoubleArray {
            val cy = if (random.nextBoolean()) 440.0 else Double.NaN
            return doubleArrayOf(
                random.nextDouble() * 1200 - 600, 0.0, random.nextDouble() * 1200 - 600, random.nextDouble() * 360,
                2 + random.nextDouble() * 87, 40 * Math.pow(100.0, random.nextDouble()), 36.0, 412.0, 915.0, Double.NaN, cy,
            )
        }

        fun cameraOf(c: DoubleArray): City.Camera = City.Camera(
            V3(c[0], c[1], c[2]), c[3], c[4], c[5], c[6], c[7], c[8], cx = c[9].takeIf { !it.isNaN() }, cy = c[10].takeIf { !it.isNaN() },
        )

        fun unit(random: Random): V3 {
            val a = random.nextDouble() * 2 * Math.PI
            val y = if (random.nextInt(4) == 0) random.nextDouble() * 2 - 1 else 0.0
            val h = Math.sqrt(1 - y * y)
            return V3(h * Math.cos(a), y, h * Math.sin(a))
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
    }
}
