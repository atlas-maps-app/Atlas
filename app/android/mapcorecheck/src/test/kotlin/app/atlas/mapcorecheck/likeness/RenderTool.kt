package app.atlas.mapcorecheck.likeness

import app.atlas.mapcore.state.CameraWriter
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The desktop render tool (ENGINE.md, step 1): the core's host build draws any view and moment to a PNG on this machine,
 * through the passes the app draws with, to judge the look without a phone and to make the boards. It runs only when
 * asked, through the likeness harness's own host core (the camera state and the port's moment as the app writes them):
 *
 *     ./gradlew :mapcorecheck:test --tests '*RenderTool*' -Datlas.render.out=/tmp/home.png
 *
 * with, all optional: `-Datlas.render.pack=bundled|grachten` (the Westerkerk's living map, or the canal ring's pack),
 * `-Datlas.render.view=home` (the Westerkerk home; a ladder knot of the pack's region, such as grachten_home; or
 * `lat,lon,distance,bearing,pitch`, the map's bearing and pitch in degrees), `-Datlas.render.moment=sunny` (a named
 * moment), `-Datlas.render.size=S1` (1080 × 2400 px at 1080 / 412, or `<w>x<h>@<density>`) and
 * `-Datlas.render.mode=structural|full`.
 */
class RenderTool {
    @Test
    fun render() {
        val out = System.getProperty("atlas.render.out")
        assumeTrue("the render tool draws only when asked (-Datlas.render.out=<png>)", !out.isNullOrBlank())
        val frame = HostFrames.draw(
            pack = System.getProperty("atlas.render.pack")?.takeIf { it.isNotBlank() } ?: "bundled",
            view = System.getProperty("atlas.render.view")?.takeIf { it.isNotBlank() } ?: "home",
            moment = System.getProperty("atlas.render.moment")?.takeIf { it.isNotBlank() } ?: "sunny",
            size = System.getProperty("atlas.render.size")?.takeIf { it.isNotBlank() } ?: "S1",
            mode = System.getProperty("atlas.render.mode")?.takeIf { it.isNotBlank() } ?: "structural",
        )
        val file = File(out!!)
        HostFrames.png(frame, file)
        println("render tool: $file, ${frame.describe()}")
    }
}

/** A frame the host core drew for the tools: its size, its R G B A bytes, its view, and how long it took. */
internal class HostFrame(val w: Int, val h: Int, val density: Double, val rgba: ByteArray, val view: LiveView, val ms: Long) {
    fun describe(): String =
        "${w}x$h at $density, view ${"%.6f".format(view.lat)} ${"%.6f".format(view.lon)} zoom ${"%.2f".format(view.zoom)} " +
            "bearing ${view.bearing} pitch ${view.pitch}, $ms ms on ${TestCases.device()}"
}

/** The tools' frames: the likeness harness's host core drawing a view and moment of a pack (RenderTool, EngineRefs). */
internal object HostFrames {
    /** [pack]'s [view] at [moment], [size], in [mode] (see [RenderTool] for the forms each takes). */
    fun draw(pack: String, view: String, moment: String, size: String, mode: String): HostFrame {
        val core = HostCore.load()
        assertTrue("the host core: ${HostCore.unavailable}", core != null)
        val (w, h, density) = size(size)
        val widthDp = Math.round(w / density).toInt()
        val heightDp = Math.round(h / density).toInt()
        val v = view(view, pack, widthDp, heightDp)
        val camera = CameraWriter.allocate()
        CameraWriter.header(camera, 1L, CameraWriter.REST)
        CameraWriter.view(camera, v.lat, v.lon, v.bearing, v.pitch, LiveCamera.distance(v.lat, v.zoom, heightDp.toDouble()), widthDp / 2.0, v.cyDp)
        CameraWriter.frame(camera, w, h, widthDp, heightDp, density)
        CameraWriter.pack(camera, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.revisions(camera, 0, 0, 0)
        CameraWriter.keepOuts(camera, 0)
        val theme = CaseState.theme(pack, JsonObject(mapOf("named" to JsonPrimitive(moment))))
        val case = TestCases.caseBuffer(w, h, density, mode, false, v, moment, false, pack)
        val pixels = ByteBuffer.allocateDirect(w * h * 4)
        val owners = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.LITTLE_ENDIAN)
        val t0 = System.nanoTime()
        val r = TestCases.render(case, camera, theme, pixels, owners)
        assertTrue("the core failed: ${HostCore.error(r)}: ${TestCases.lastError()}", r >= 0)
        val rgba = ByteArray(w * h * 4)
        pixels.get(0, rgba)
        return HostFrame(w, h, density, rgba, v, (System.nanoTime() - t0) / 1_000_000)
    }

    /** Writes [frame] to [file] as a PNG. */
    fun png(frame: HostFrame, file: File) {
        val image = BufferedImage(frame.w, frame.h, BufferedImage.TYPE_INT_RGB)
        val px = frame.rgba
        for (i in 0 until frame.w * frame.h) {
            val o = i * 4
            image.setRGB(i % frame.w, i / frame.w, ((px[o].toInt() and 0xFF) shl 16) or ((px[o + 1].toInt() and 0xFF) shl 8) or (px[o + 2].toInt() and 0xFF))
        }
        file.absoluteFile.parentFile?.mkdirs()
        assertTrue("the PNG could not be written to $file", ImageIO.write(image, "png", file))
    }

    private fun size(spec: String): Triple<Int, Int, Double> {
        if (spec == "S1") return Triple(1080, 2400, 1080.0 / 412.0)
        val m = Regex("""(\d+)x(\d+)@([0-9.]+)""").matchEntire(spec) ?: error("a size is S1 or <w>x<h>@<density>, not '$spec'")
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toDouble())
    }

    private fun view(spec: String, pack: String, widthDp: Int, heightDp: Int): LiveView {
        val region = CaseState.region(pack)
        val f = region.frame
        if (spec == "home") {
            // the Westerkerk home: 10 m west and 30 m north of the Westerkerk, bearing 52°, map pitch 52°, 430 m
            val lat = f.lat(-10.0, -30.0)
            val lon = f.lon(-10.0, -30.0)
            return LiveView(lat, lon, LiveCamera.zoom(lat, 430.0, heightDp.toDouble()), 52.0, 52.0, widthDp, heightDp, 440.0)
        }
        val numbers = spec.split(',').map { it.trim().toDoubleOrNull() }
        if (numbers.size == 5 && numbers.all { it != null }) {
            val (lat, lon, dist, bearing, pitch) = numbers.map { it!! }
            return LiveView(lat, lon, LiveCamera.zoom(lat, dist, heightDp.toDouble()), bearing, pitch, widthDp, heightDp, heightDp * 0.48)
        }
        val k = region.ladder.knots.firstOrNull { it.name == spec }
            ?: error("the region of '$pack' has no view '$spec' (its views: ${region.ladder.knots.joinToString { it.name }})")
        return EngineVersion.knotView(f, k, widthDp, heightDp)
    }
}
