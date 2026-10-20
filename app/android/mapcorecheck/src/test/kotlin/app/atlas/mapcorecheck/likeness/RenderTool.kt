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
        val core = HostCore.load()
        assertTrue("the host core: ${HostCore.unavailable}", core != null)
        val pack = System.getProperty("atlas.render.pack")?.takeIf { it.isNotBlank() } ?: "bundled"
        val moment = System.getProperty("atlas.render.moment")?.takeIf { it.isNotBlank() } ?: "sunny"
        val mode = System.getProperty("atlas.render.mode")?.takeIf { it.isNotBlank() } ?: "structural"
        val (w, h, density) = size(System.getProperty("atlas.render.size") ?: "S1")
        val widthDp = Math.round(w / density).toInt()
        val heightDp = Math.round(h / density).toInt()
        val view = view(System.getProperty("atlas.render.view") ?: "home", pack, widthDp, heightDp)

        val camera = CameraWriter.allocate()
        CameraWriter.header(camera, 1L, CameraWriter.REST)
        CameraWriter.view(camera, view.lat, view.lon, view.bearing, view.pitch, LiveCamera.distance(view.lat, view.zoom, heightDp.toDouble()), widthDp / 2.0, view.cyDp)
        CameraWriter.frame(camera, w, h, widthDp, heightDp, density)
        CameraWriter.pack(camera, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.revisions(camera, 0, 0, 0)
        CameraWriter.keepOuts(camera, 0)
        val theme = CaseState.theme(pack, JsonObject(mapOf("named" to JsonPrimitive(moment))))
        val case = TestCases.caseBuffer(w, h, density, mode, false, view, moment, false, pack)

        val pixels = ByteBuffer.allocateDirect(w * h * 4)
        val owners = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.LITTLE_ENDIAN)
        val t0 = System.nanoTime()
        val r = TestCases.render(case, camera, theme, pixels, owners)
        assertTrue("the core failed: ${HostCore.error(r)}: ${TestCases.lastError()}", r >= 0)
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until w * h) {
            val o = i * 4
            image.setRGB(i % w, i / w, ((pixels.get(o).toInt() and 0xFF) shl 16) or ((pixels.get(o + 1).toInt() and 0xFF) shl 8) or (pixels.get(o + 2).toInt() and 0xFF))
        }
        val file = File(out!!)
        file.absoluteFile.parentFile?.mkdirs()
        assertTrue("the PNG could not be written to $file", ImageIO.write(image, "png", file))
        println(
            "render tool: $file, ${w}x$h at $density, $pack, view ${"%.6f".format(view.lat)} ${"%.6f".format(view.lon)} zoom " +
                "${"%.2f".format(view.zoom)} bearing ${view.bearing} pitch ${view.pitch}, $moment, $mode, " +
                "${(System.nanoTime() - t0) / 1_000_000} ms on ${TestCases.device()}",
        )
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
