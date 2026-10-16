package app.atlas.mapview

import android.graphics.Bitmap
import android.graphics.Color
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.svg.SvgParser
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.PackLibrary
import app.atlas.render.py.PyCompat
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.math.abs

/** The map engine wired to region packs on the test machine: the app's bundled Westerkerk, and the renders' packs where present. */
object EngineTestKit {
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../../..").canonicalFile

    /** The app's bundled pack: the Westerkerk's living map (in git). */
    val bundled: File get() = File(repo, "app/android/app/src/main/assets/packs/amsterdam.atlas")

    /** The canal ring's pack, the Main board's region (the owner's machine only). */
    val grachten: File get() = File(repo, "cache/packs/regions/grachten.atlas")

    val fonts: SvgFonts by lazy { SvgFonts.fromDirectory(File(repo, "app/android/mapview/src/main/assets/svgfonts")) }

    /** Wires [MapEngine] to a fresh library holding [packs] (id to file), offline; returns the regions. */
    fun wire(vararg packs: Pair<String, File>): LiveRegions {
        PyCompat.minor = 9
        val dir = Files.createTempDirectory("engine").toFile()
        LiveEngine.workDir = File(dir, "work")
        LiveEngine.forget()
        val lib = PackLibrary(File(dir, "packs"), emptyList())
        for ((id, f) in packs) lib.install(id) { f.inputStream() }
        // the work runs where it is asked for: the frame thread
        val inline = Executor { it.run() }
        val regions = LiveRegions(lib, inline, online = { false }, onChange = { MapEngine.invalidate() })
        MapEngine.useFonts(fonts)
        MapEngine.regions = regions
        return regions
    }

    fun unwire() {
        MapEngine.regions = null
        LiveEngine.forget()
    }

    /** SVG layers rasterised as the map does, on the map's background, at [scale]. */
    fun raster(layers: List<String>, scale: Float = 2f, background: Int = MapController.DAY_EARTH): Bitmap {
        val docs: List<SvgDocument> = layers.map { SvgParser.parse(it) }
        val img = EngineFrame.rasterise(docs, null, fonts, scale)
        val out = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).apply {
            drawColor(background)
            drawBitmap(img, 0f, 0f, null)
        }
        return out
    }

    /**
     * Mean absolute difference (0 … 255 over red, green and blue) and the share of pixels within [near] levels, from row
     * [top] down, leaving out the pixels where [skip] is [skipColour].
     */
    fun difference(a: Bitmap, b: Bitmap, near: Int = 2, top: Int = 0, skip: Bitmap? = null, skipColour: Int = 0): Pair<Double, Double> {
        val w = minOf(a.width, b.width)
        val h = minOf(a.height, b.height)
        val pa = IntArray(w * h)
        val pb = IntArray(w * h)
        a.getPixels(pa, 0, w, 0, 0, w, h)
        b.getPixels(pb, 0, w, 0, 0, w, h)
        val ps = skip?.let { IntArray(w * h).also { s -> it.getPixels(s, 0, w, 0, 0, w, h) } }
        var sum = 0.0
        var close = 0
        var n = 0
        for (y in top until h) for (x in 0 until w) {
            if (ps != null && ps[y * w + x] == skipColour) continue
            val p = pa[y * w + x]
            val q = pb[y * w + x]
            val dr = abs(Color.red(p) - Color.red(q))
            val dg = abs(Color.green(p) - Color.green(q))
            val db = abs(Color.blue(p) - Color.blue(q))
            sum += (dr + dg + db) / 3.0
            if (maxOf(dr, dg, db) <= near) close++
            n++
        }
        return sum / n to close.toDouble() / n
    }
}
