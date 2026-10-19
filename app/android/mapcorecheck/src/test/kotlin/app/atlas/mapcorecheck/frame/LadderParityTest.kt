package app.atlas.mapcorecheck.frame

import app.atlas.anchor.AnchorCases
import app.atlas.geo.BBox
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.svg.SvgCamera
import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import app.atlas.render.live.ScreenLayers
import app.atlas.render.live.ViewLadder
import app.atlas.render.py.PyFmt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.Random

/**
 * The frame core's entry points in the core's host build (`CP/jni/TestTwin.cpp`, plan-vector K7), loaded from
 * `-Datlas.mapcore.host`.
 */
internal object FrameHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's frame entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The pack's ladder: a `frame` line, then a `knot` line per knot in the ladder's order (doubles as their bits). */
    @JvmStatic external fun ladder(path: String): String

    /**
     * The frame's view of a camera state over the pack at [path] with a theme block (or none): the view it snapped onto,
     * its distance, ViewLadder.place's settings, the engine camera's values and ScreenLayers.of's, in [Canon]'s text.
     */
    @JvmStatic external fun place(path: String, camera: ByteBuffer, theme: ByteBuffer?): String

    /** Device px of world points (x, y, z triples): per point the double camera's x, y, then the vertex stage's x, y. */
    @JvmStatic external fun project(path: String, camera: ByteBuffer, points: DoubleArray): DoubleArray?

    /** The snap at rest of each value: the double's, then the float's (widened). */
    @JvmStatic external fun snap(values: DoubleArray): DoubleArray

    /** The SVG's print precision of each value at [places] decimals (view::decimals). */
    @JvmStatic external fun decimals(values: DoubleArray, places: Int): DoubleArray

    /** The twin's stream of the frame over render packs (`id=path`) at a camera state and a theme block; null on a failure. */
    @JvmStatic external fun twin(packs: Array<String>, active: String?, camera: ByteBuffer, theme: ByteBuffer, full: Boolean): ByteArray?

    /**
     * The frame's counts over render packs (`id=path`) at a camera state and a theme block: `pass`, `kind` and `total`
     * lines, then `fse opaque <x> translucent <x>` from the overdraw mode; null on a failure.
     */
    @JvmStatic external fun stats(packs: Array<String>, active: String?, camera: ByteBuffer, theme: ByteBuffer, full: Boolean): String?

    /** Why the last frame drawn on this thread failed, and what it noted. */
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun lastNotes(): String
}

/** The canonical text both sides write: every double as its bits. */
internal object Canon {
    fun hex(d: Double): String {
        val s = java.lang.Long.toHexString(d.toRawBits())
        return "0".repeat(16 - s.length) + s
    }

    private fun b(v: Boolean) = if (v) "1" else "0"

    /** A cfg value as CP/view/Ladder.cpp's Placed::dump writes it. */
    fun value(v: Any?): String = when (v) {
        null -> "n"
        is Boolean -> "b $v"
        is Int, is Long, is Short, is Byte -> "i $v"
        is Double -> "d ${hex(v)}"
        is Float -> "d ${hex(v.toDouble())}"
        is String -> "s $v"
        is List<*> -> "l [" + v.joinToString(", ") { value(it) } + "]"
        is Map<*, *> -> "m {" + v.entries.joinToString(", ") { "${it.key}: ${value(it.value)}" } + "}"
        else -> "? ${v::class.simpleName}"
    }

    /** The port's text for a placed camera, its engine camera and (with a theme) its screen layers. */
    fun port(placed: ViewLadder.Placed, th: Map<String, Any?>?, key: String?): String {
        val sb = StringBuilder()
        sb.append("view ").append(placed.view ?: "-").append('\n')
        sb.append("dist ").append(hex(placed.dist)).append('\n')
        sb.append("cfg\n")
        for ((k, v) in placed.cfg) sb.append(k).append('=').append(value(v)).append('\n')
        val cam = Amsterdam.cameraOf(placed.cfg)
        sb.append(camera(cam))
        if (th != null) sb.append(layers(ScreenLayers.of(cam, th, placed.cfg, key!!)))
        return sb.toString()
    }

    fun camera(c: City.Camera): String {
        fun v3(v: app.atlas.render.V3) = "${hex(v.x)} ${hex(v.y)} ${hex(v.z)}"
        return "camera eye ${v3(c.C)} f ${v3(c.f)} r ${v3(c.r)} u ${v3(c.u)} focal ${hex(c.focal)} cx ${hex(c.cx)} cy ${hex(c.cy)} " +
            "horizon ${hex(c.horizonY())}\n"
    }

    fun layers(f: ScreenLayers.Frame): String {
        val sb = StringBuilder()
        sb.append("layers W ${hex(f.width)} H ${hex(f.height)} horizon ${hex(f.horizon)} weather ${b(f.weather)} night ${b(f.night)} " +
            "overcast ${b(f.overcast)} eye ${b(f.eye)}\n")
        val c = f.clouds
        sb.append(if (c != null) "clouds ${hex(c.k)} ${hex(c.t)} ${hex(c.red)} ${hex(c.green)} ${hex(c.blue)}\n" else "clouds -\n")
        sb.append("sky_bottom ${hex(f.skyBottom)}\n")
        val s = f.sun
        sb.append(if (s != null) "sun ${hex(s.x)} ${hex(s.y)}\n" else "sun -\n")
        val h = f.haze
        sb.append("haze band ${b(h.band)} y0 ${hex(h.y0)} y1 ${hex(h.y1)} n ${h.offsets.size}\n")
        sb.append("offsets"); for (o in h.offsets) sb.append(' ').append(hex(o)); sb.append('\n')
        sb.append("alphas"); for (a in h.alphas) sb.append(' ').append(hex(a)); sb.append('\n')
        sb.append("wash ${b(f.wash)} cut ${if (f.cloudShadows) hex(f.cloudShadowCut) else "-"} rain ${f.rainAngle ?: "-"} fin ${b(f.drawsFin)}\n")
        return sb.toString()
    }
}

/**
 * The frame's camera, ladder and screen layers held to the port's (plan-vector D4, D18, K7): ViewLadder.place and
 * ScreenLayers.of computed by the core (`CP/view/{Ladder,ScreenLayers,Camera}.cpp`) from the camera state and the theme
 * block as the app writes them equal the port's, bit for bit, over the boards' views, the sweeps at S1–S4 and 10^5
 * random cameras; the device pixels of world points equal SvgCamera's projection times the density within 10^-4 px over
 * the sweep at S1–S4; and the snap at rest prints as the port's own `%.1f` (PyFmt) on a planted set.
 *
 * The core reads the bundled render packs (`-Datlas.tiles`), the port the same regions (AnchorCases).
 */
class LadderParityTest {
    private val tiles = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))
    private val westerkerkPack = File(tiles, "amsterdam.atlas")
    private val grachtenPack = File(tiles, "grachten.atlas")

    /** A region, its pack, and its name in logs. */
    private class Packed(val name: String, val region: LiveRegion, val pack: File)

    private fun regions(): List<Packed> {
        val out = arrayListOf(Packed("westerkerk", AnchorCases.westerkerk(), westerkerkPack))
        if (grachtenPack.isFile) AnchorCases.grachten()?.let { out += Packed("grachten", it, grachtenPack) }
        return out
    }

    /** The camera state the map writes for [v] at [density] (px per dp; the px sizes the dp sizes times it, rounded). */
    private fun state(v: LiveView, density: Double, rest: Boolean = false, out: ByteBuffer = CameraWriter.allocate()): ByteBuffer {
        CameraWriter.header(out, 1L, if (rest) CameraWriter.REST else 0)
        CameraWriter.view(out, v.lat, v.lon, v.bearing, v.pitch, v.distance, v.widthDp / 2.0, v.cyDp)
        CameraWriter.frame(out, Math.round(v.widthDp * density).toInt(), Math.round(v.heightDp * density).toInt(), v.widthDp, v.heightDp, density)
        CameraWriter.pack(out, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.revisions(out, 0, 0, 0)
        CameraWriter.keepOuts(out, 0)
        return out
    }

    /** A moment's theme dict with its sun vector (`_L`), and its block as the app writes it. */
    private class Moment(val key: String, val th: Map<String, Any?>, val block: ByteBuffer)

    private fun moments(): List<Moment> = Amsterdam.THEMES.keys.toList().map { key ->
        val base = Amsterdam.THEMES.getValue(key)
        val (az, el, sun) = Amsterdam.sunOf(base)
        val th = LinkedHashMap(base).also { it["_L"] = sun }
        val b = ThemeBlock.allocate()
        ThemeBlock.write(b, base, doubleArrayOf(sun.x, sun.y, sun.z), az, el, key == "golden", false)
        Moment(key, th, b)
    }

    /** The core's and the port's text for one camera and moment; null when they agree. */
    private fun differs(p: Packed, v: LiveView, density: Double, m: Moment?): String? {
        val placed = p.region.ladder.place(p.region.frame, v)
        val want = Canon.port(placed, m?.th, m?.key)
        val got = FrameHost.place(p.pack.path, state(v, density), m?.block)
        if (got == want) return null
        val w = want.lines(); val g = got.lines()
        val i = w.indices.firstOrNull { it >= g.size || w[it] != g[it] } ?: w.size
        return "${p.name} ${v} ${m?.key ?: "no theme"}: line ${i + 1}: port '${w.getOrNull(i)}', core '${g.getOrNull(i)}'"
    }

    @Test
    fun theLadderIsThePortsKnotsAndFrame() {
        for (p in regions()) {
            val lines = FrameHost.ladder(p.pack.path).lines().filter { it.isNotBlank() }
            assertTrue("${p.name}: ${lines.firstOrNull()}", lines.isNotEmpty() && !lines[0].startsWith("error"))
            val f = p.region.frame
            val living = if (p.region.kind == LiveRegion.Kind.LIVING) 1 else 0
            assertEquals("${p.name}: the frame", "frame ${Canon.hex(f.lat0)} ${Canon.hex(f.lon0)} ${Canon.hex(f.kx)} ${Canon.hex(f.kz)} living $living", lines[0])
            val knots = p.region.ladder.knots.map { k ->
                "knot ${k.name} dist ${Canon.hex(k.dist)} target ${k.target.joinToString(" ") { Canon.hex(it) }} yaw ${Canon.hex(k.yaw)} " +
                    "pitch ${Canon.hex(k.pitch)} cy ${if (k.cy.isNaN()) "-" else Canon.hex(k.cy)}"
            }
            assertEquals("${p.name}: the knots", knots, lines.drop(1))
            println("ladder ${p.name}: ${knots.size} knots, every one the port's")
        }
    }

    @Test
    fun theBoardsViewsPlaceAndLayerAsThePortDoes() {
        val ms = moments()
        var n = 0
        for (p in regions()) {
            val views = ArrayList<Pair<LiveView, Double>>()
            // every region view, exact, on S1–S4's frames (and at its own density)
            for (size in AnchorCases.Size.entries) {
                for (k in p.region.ladder.knots) views += EngineVersion.knotView(p.region.frame, k, 412, size.frameHeight) to size.density
            }
            // EngineVersion's fixed renders and the smoke tier's cameras
            for (f in EngineVersion.FIXED) {
                if ((f.region == "grachten") == (p.name == "grachten") && f.region != "sangimignano") views += f.liveView(p.region) to (1080 / 412.0)
            }
            if (p.name == "westerkerk") views += AnchorCases.homeView(p.region, 916) to (1080 / 412.0)
            for ((v, d) in views) {
                for (m in ms) {
                    differs(p, v, d, m)?.let { throw AssertionError(it) }
                    n++
                }
                differs(p, v, d, null)?.let { throw AssertionError(it) }
            }
        }
        println("boards: $n views × moments placed and layered as the port does, bit for bit")
        assertTrue(n > 50)
    }

    /** D16's sweep as :likeness's Cameras.sweep draws it (as MT/sun/SunParityTest's). */
    private fun sweep(n: Int, box: BBox, height: Int, seed: Long): List<LiveView> {
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

    @Test
    fun theSweepsPlaceAndLayerAsThePortDoes() {
        val ms = moments()
        var n = 0
        for (p in regions()) {
            val count = if (p.name == "westerkerk") 24 else 12
            for (size in AnchorCases.Size.entries) {
                for (v in sweep(count, p.region.bbox!!, size.frameHeight, 9)) {
                    for (m in ms) {
                        differs(p, v, size.density, m)?.let { throw AssertionError(it) }
                        n++
                    }
                }
            }
        }
        println("sweeps: $n cameras × moments at S1–S4 placed and layered as the port does, bit for bit")
        assertTrue(n > 500)
    }

    @Test
    fun aHundredThousandRandomCamerasPlaceAndLayerAsThePortDoes() {
        val ms = moments()
        val ps = regions()
        val r = Random(20261019)
        var snapped = 0
        val n = 100_000
        val camera = CameraWriter.allocate()
        val densities = doubleArrayOf(1.0, 2.0, 2.625, 1080 / 412.0, 1440 / 412.0, 1344 / 412.0, 3.0)
        for (i in 0 until n) {
            val p = ps[i % ps.size]
            val box = p.region.bbox!!
            val widthDp = 320 + r.nextInt(161)
            val heightDp = 560 + r.nextInt(441)
            val v = if (r.nextInt(10) == 0) {
                // near one of the region's views: each tolerance crossed or kept, one at a time
                val k = p.region.ladder.knots[r.nextInt(p.region.ladder.knots.size)]
                val exact = EngineVersion.knotView(p.region.frame, k, widthDp, heightDp)
                val which = r.nextInt(5)
                fun near(limit: Double) = (r.nextDouble() * 2 - 1) * limit * 2
                val f = p.region.frame
                val tx = f.x(exact.lat, exact.lon) + if (which == 0) near(ViewLadder.SNAP_M) else 0.0
                val tz = f.z(exact.lat, exact.lon)
                val lat = f.lat(tx, tz); val lon = f.lon(tx, tz)
                val dist = k.dist * (1 + if (which == 1) near(ViewLadder.SNAP_DIST) else 0.0)
                LiveView(lat, lon, LiveCamera.zoom(lat, dist, heightDp.toDouble()), exact.bearing + if (which == 2) near(ViewLadder.SNAP_DEG) else 0.0,
                    exact.pitch + if (which == 3) near(ViewLadder.SNAP_DEG) else 0.0, widthDp, heightDp, exact.cyDp + if (which == 4) near(ViewLadder.SNAP_CY) else 0.0)
            } else {
                val lat = box.south + r.nextDouble() * box.height
                val lon = box.west + r.nextDouble() * box.width
                val dist = 30.0 * StrictMath.pow(20_000.0 / 30.0, r.nextDouble())
                val bearing = -400.0 + r.nextDouble() * 1160.0
                val pitch = r.nextDouble() * 89.99
                val cy = when (r.nextInt(4)) { 0 -> 440.0; 1 -> 457.5; 2 -> heightDp / 2.0; else -> r.nextDouble() * heightDp }
                LiveView(lat, lon, LiveCamera.zoom(lat, dist, heightDp.toDouble()), bearing, pitch, widthDp, heightDp, cy)
            }
            if (v.pitch !in 0.0..90.0) continue
            val m = ms[r.nextInt(ms.size)]
            val placed = p.region.ladder.place(p.region.frame, v)
            if (placed.view != null) snapped++
            val want = Canon.port(placed, m.th, m.key)
            val got = FrameHost.place(p.pack.path, state(v, densities[r.nextInt(densities.size)], out = camera), m.block)
            if (got != want) throw AssertionError(differs(p, v, 2.0, m) ?: "camera $i: the texts differ")
        }
        println("random: $n cameras placed and layered as the port does, bit for bit ($snapped snapped onto a region view)")
        assertTrue(snapped > 100)
    }

    @Test
    fun theDevicePixelsAreSvgCamerasTimesTheDensity() {
        var worst = 0.0
        var worstFloat = 0.0
        var points = 0
        for (p in regions()) {
            val count = if (p.name == "westerkerk") 24 else 12
            for (size in AnchorCases.Size.entries) {
                for (v in sweep(count, p.region.bbox!!, size.frameHeight, 9)) {
                    val placed = LiveEngine.place(p.region, v)
                    val cfg = placed.cfg
                    val t = cfg["target"] as List<*>
                    fun n(x: Any?) = (x as Number).toDouble()
                    val svg = SvgCamera(n(t[0]), n(t[1]), n(t[2]), n(cfg["yaw"]), n(cfg["pitch"]), n(cfg["dist"]), n(cfg["fov"]), n(cfg["W"]), n(cfg["H"]),
                        cx = (cfg["cx"] as Number?)?.toDouble(), cy = (cfg["cy"] as Number?)?.toDouble())
                    // A grid over the frame onto the ground and 15 and 45 m up, where those rows meet them ahead.
                    val pts = ArrayList<Double>()
                    for (gy in doubleArrayOf(0.0, 15.0, 45.0)) {
                        for (i in 0..8) for (j in 0..16) {
                            val g = svg.groundAt(svg.width * i / 8, svg.height * j / 16, gy) ?: continue
                            pts += g[0]; pts += g[1]; pts += g[2]
                        }
                    }
                    val got = FrameHost.project(p.pack.path, state(v, size.density), pts.toDoubleArray()) ?: error("no projection")
                    for (k in 0 until pts.size / 3) {
                        val s = svg.proj(pts[3 * k], pts[3 * k + 1], pts[3 * k + 2]) ?: continue
                        val dx = s[0] * size.density; val dy = s[1] * size.density
                        if (got[4 * k].isNaN()) continue
                        worst = maxOf(worst, Math.abs(got[4 * k] - dx), Math.abs(got[4 * k + 1] - dy))
                        // the vertex stage's float camera, inside the frame and a frame around it
                        if (s[0] > -svg.width && s[0] < 2 * svg.width && s[1] > -svg.height && s[1] < 2 * svg.height && !got[4 * k + 2].isNaN()) {
                            worstFloat = maxOf(worstFloat, Math.abs(got[4 * k + 2] - dx) / size.density, Math.abs(got[4 * k + 3] - dy) / size.density)
                        }
                        points++
                    }
                }
            }
        }
        println("device px: $points points over the sweeps at S1–S4, the core within ${"%.3g".format(worst)} px of SvgCamera × density; " +
            "the vertex stage's float camera within ${"%.3g".format(worstFloat)} CSS px")
        assertTrue(points > 10_000)
        assertTrue("the core's device px differ from SvgCamera's by $worst", worst <= 1e-4)
        // The vertex stage's float camera is held to the twin's, which computes the same float maths (L2's vertex
        // check), not to the double camera: near the eye a float tile-local position's last bit is a visible fraction of
        // a pixel. Reported here, not gated.
    }

    @Test
    fun theSnapPrintsAsThePortsPercentPointOneF() {
        // Ties on exact binary halves, both neighbours of every print step, signed zeros, large and tiny values.
        val planted = ArrayList<Double>()
        planted += listOf(0.0, -0.0, 0.25, 0.75, 1.25, -0.25, -0.75, 2.25, 0.05, -0.05, 0.04, -0.04, 0.049999, -0.049999, 0.35, 0.45, 2.675,
            1e7 + 0.05, 123456.75, 1e-30, -1e-30, java.lang.Double.MIN_VALUE, 0.1, 0.2, 0.3)
        for (k in -30..30) for (base in doubleArrayOf(0.0, 1e5, -2048.0)) {
            for (v in doubleArrayOf(base + k / 10.0, base + (k + 0.5) / 10.0)) {
                planted += v; planted += Math.nextUp(v); planted += Math.nextDown(v)
            }
        }
        val r = Random(5)
        repeat(100_000) { planted += (r.nextDouble() * 2 - 1) * (if (it % 5 == 0) 2e6 else 3000.0) }
        val values = planted.toDoubleArray()
        val snapped = FrameHost.snap(values)
        var floats = 0
        for ((i, v) in values.withIndex()) {
            assertEquals("the snap of $v", PyFmt.pf("%.1f", v), PyFmt.pf("%.1f", snapped[2 * i]))
            assertEquals("the snap of $v is the double nearest its print", PyFmt.pf("%.1f", v).toDouble().toRawBits(), snapped[2 * i].toRawBits())
            val f = v.toFloat().toDouble()
            assertEquals("the float snap of $f", PyFmt.pf("%.1f", f), PyFmt.pf("%.1f", snapped[2 * i + 1]))
            floats++
        }
        for (places in 0..3) {
            val d = FrameHost.decimals(values, places)
            for ((i, v) in values.withIndex()) assertEquals("$v at $places decimals", PyFmt.pf("%.${places}f", v), PyFmt.pf("%.${places}f", d[i]))
        }
        println("snap: ${values.size} values (planted and random) print as the port's %.1f, in double and in float ($floats), and at 0–3 decimals")
    }
}
