package app.atlas.anchor

import app.atlas.geo.BBox
import app.atlas.render.Amsterdam
import app.atlas.render.data.PackReader
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveRender
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.WorldTape
import app.atlas.render.py.PyCompat
import java.io.File
import java.nio.file.Files
import java.util.Random
import kotlin.math.ceil

/**
 * The renders the anchor gates are made of (plan-gpu D8): the app's bundled Westerkerk and, where present, the canal
 * ring's pack (the Main board); the real panel sizes with their `BoardScale` densities; the home views; and the seeded
 * camera sweep. Every render is made under a world tape whose frame camera is the view's own, as the phone makes it.
 */
object AnchorCases {
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile

    /** The app's bundled pack: the Westerkerk's living map (in git). */
    val bundled: File get() = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")

    /** The canal ring's pack, the Main board's region (the owner's machine only). */
    val grachtenPack: File get() = File(repo, "cache/packs/regions/grachten.atlas")

    private var work: File? = null
    private var westerkerk: LiveRegion? = null
    private var grachten: LiveRegion? = null

    /** Points the engine at a fresh work directory (once per JVM). */
    @Synchronized
    fun setUp() {
        if (work != null) return
        PyCompat.minor = 9
        val dir = Files.createTempDirectory("anchors").toFile()
        work = dir
        LiveEngine.workDir = dir
        Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
    }

    /** The bundled Westerkerk region. */
    @Synchronized
    fun westerkerk(): LiveRegion {
        setUp()
        return westerkerk ?: PackReader.open(bundled).use { LiveEngine.open(it, "bundled") }.also { westerkerk = it }
    }

    /** The canal ring's region, or null when its pack is not on this machine. */
    @Synchronized
    fun grachten(): LiveRegion? {
        setUp()
        if (!grachtenPack.isFile) return null
        return grachten ?: PackReader.open(grachtenPack).use { LiveEngine.open(it, "${grachtenPack.length()}") }.also { grachten = it }
    }

    /** A real panel: [wPx] × [hPx] device pixels at the map's `BoardScale` density (412 dp wide). */
    enum class Size(val wPx: Int, val hPx: Int) {
        S1(1080, 2400), S2(1080, 2340), S3(1440, 3120), S4(1344, 2992);

        val density: Double get() = wPx / 412.0

        /** The engine frame's height, CSS px: ⌈hPx / d⌉, with a 1e-6 tolerance. */
        val frameHeight: Int get() = ceil(hPx / density - 1e-6).toInt()
    }

    /** amsterdam.py's main camera (the Westerkerk home) on a 412 × [height] frame: 10 m west and 30 m north, yaw 52°, 38° down, 430 m. */
    fun homeView(region: LiveRegion, height: Int = 916, dist: Double = 430.0): LiveView {
        val f = region.frame
        val lat = f.lat(-10.0, -30.0); val lon = f.lon(-10.0, -30.0)
        return LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), 52.0, 52.0, 412, height, 440.0)
    }

    /** The Main board's camera (grachten home) on a 412 × [height] frame. */
    fun boardView(region: LiveRegion, height: Int = 915): LiveView {
        val f = region.frame
        val lat = f.lat(-177.0, -521.0); val lon = f.lon(-177.0, -521.0)
        return LiveView(lat, lon, LiveCamera.zoom(lat, 2300.0, height.toDouble()), 52.0, 46.0, 412, height, 440.0)
    }

    /**
     * D8's sweep: [n] cameras, seeded ([seed], 9 by default), targets within [box], distance 150–1500 m, bearing 0–360°,
     * map pitch 0–70°, the principal point at 440 dp or the frame's middle.
     */
    fun sweep(n: Int, box: BBox, height: Int = 916, seed: Long = 9): List<LiveView> {
        val r = Random(seed)
        return List(n) {
            val lat = box.south + r.nextDouble() * box.height
            val lon = box.west + r.nextDouble() * box.width
            val dist = 150.0 + r.nextDouble() * 1350.0
            val bearing = r.nextDouble() * 360.0
            val pitch = r.nextDouble() * 70.0
            val cy = if (r.nextBoolean()) 440.0 else height / 2.0
            LiveView(lat, lon, LiveCamera.zoom(lat, dist, height.toDouble()), bearing, pitch, 412, height, cy)
        }
    }

    /** One render under a world tape, and its anchor source. */
    class Made(val render: LiveRender, val tape: WorldTape, val source: AnchorSource) {
        val layers: List<String> get() = render.layers
    }

    /** Renders [view] of [region] at [moment] under a tape whose frame camera is the view's own. */
    fun render(region: LiveRegion, view: LiveView, moment: Moment, kind: AnchorKind = AnchorKind.REST): Made {
        val placed = LiveEngine.place(region, view)
        val tape = WorldTape(Amsterdam.cameraOf(placed.cfg))
        val r = WorldTape.recording(tape) { LiveEngine.render(region, view, moment) } ?: error("the render was cancelled")
        val src = AnchorSource.of(r, tape, AnchorMeta(regionId = region.id, kind = kind))
        return Made(r, tape, src)
    }

    /** The anchor of [made]; [report] gets its counts. */
    fun anchor(made: Made, report: CompileReport? = null): Anchor = AnchorCompiler.compile(made.source, report)

    /**
     * An anchor of a screen-space SVG [svg] (its own layer, no tape): every point fixed to the screen. For RefRaster's
     * gates on drawn shapes.
     */
    fun synthetic(svg: String): Anchor {
        val doc = app.atlas.mapview.svg.SvgParser.parse(svg)
        val cfg = linkedMapOf<String, Any?>(
            "W" to doc.width.toDouble(), "H" to doc.height.toDouble(), "target" to listOf(0.0, 0.0, 0.0), "yaw" to 0.0, "pitch" to 45.0,
            "dist" to 500.0, "fov" to 36.0,
        )
        val tape = WorldTape()
        tape.seal()
        return AnchorCompiler.compile(AnchorSource(Amsterdam.cameraOf(cfg), cfg, doc, emptyList(), tape, null))
    }

    /** The named moments the gates use. */
    val MOMENTS = listOf("sunny", "grey", "wet", "night")
}
