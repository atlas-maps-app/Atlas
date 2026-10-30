package app.atlas.likeness

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import app.atlas.mapview.CountryTiles
import app.atlas.mapview.EngineFrame
import app.atlas.mapview.MapEngine
import app.atlas.mapview.MapParts
import app.atlas.mapview.svg.SvgCanvas
import app.atlas.mapview.svg.SvgDocument
import app.atlas.mapview.svg.SvgFonts
import app.atlas.mapview.svg.SvgParser
import app.atlas.render.Amsterdam
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegions
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.PackLibrary
import app.atlas.render.live.WxHour
import app.atlas.render.py.PyJson
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.awt.image.BufferedImage
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.concurrent.withLock
import kotlin.math.min

/**
 * The phone look tool's canvas side (ENGINE.md, "On the user's phone first"): today's map as the phone rasterises it
 * where the core has no tiles ("rasterising" on the map's panel), for a side by side with the core's frame of the same
 * view and moment (MT/likeness/PhoneLook.kt in :mapcorecheck draws that one). Each step is the app's own code with the
 * app's own inputs:
 *
 * - the packs: a [PackLibrary] as MapData makes it (the bundled Westerkerk installed from the app's assets, the renders'
 *   element packs the phone downloads on a first visit to their boxes placed beside it, `renders` off), and
 *   [LiveRegions] over it as MapData makes it (no Overpass builds), which picks the region a view draws;
 * - the options: [MapEngine.options] after the calls MapData.wire makes with the settings unset
 *   ([MapEngine.screenEffects] with the haze, vignette and grain off, [MapEngine.leaveOut] with [MapParts.byDefault]);
 * - the frame: [LiveEngine.render] with them, each layer through [SvgParser], rasterised by [EngineFrame.rasterise] with
 *   the CPU filters at the screen's density (at most [MapEngine.maxRasterScale]), as EngineFrames.make does, on
 *   Robolectric's Android Skia; then drawn as MapController.draw draws a frame at rest: the day's earth under it.
 *
 * It draws only when asked:
 *
 *     ./gradlew :likeness:testDebugUnitTest --tests '*PhoneCanvas*' -Datlas.phonelook.out=/tmp/look \
 *         -Datlas.phonelook.views='edge=52.3735,4.8745,900,60,50;park=52.3862,4.8790,700,250,48'
 *
 * with, optional: `-Datlas.phonelook.moment=2026-10-02T12:00` (the map's live moment at that local time, lit by the
 * Westerkerk's recorded forecast as the phone's MapWeather hands its hours over, without the low cloud and humidity its
 * forecast lacks; or `named:sunny`), `-Datlas.phonelook.size=S1` (1080 × 2400 px at 1080 / 412, or
 * `<w>x<h>@<density>`) and `-Datlas.phonelook.packs=amsterdam,grachten,artis` (the renders' element packs on the
 * phone, from -Datlas.likeness.packs, the repository's cache/packs/regions by default), `-Datlas.phonelook.heap=512`
 * (the phone's heap in MB, which decides the regions it may load) and `-Datlas.phonelook.canvas=22` (alpha 22's frames,
 * with the map's options as they are; `next`, this branch's, with the region's own: MapEngine.optionsFor, written as
 * `<name>-canvasnext.png`). Each view is
 * `<name>=<lat>,<lon>,<distance m>,<bearing>,<map pitch>`, the map's principal point at 48 % of its height, as the
 * desktop render tool's. It writes `<name>-canvas.png` and `<name>-canvas.txt` (the region drawn and why, the moment's
 * light, the options, the timings).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PhoneCanvasTest {
    @Test
    fun draw() {
        val out = System.getProperty("atlas.phonelook.out")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("the phone look tool draws only when asked (-Datlas.phonelook.out=<folder>)", out != null)
        out!!.mkdirs()
        val (w, h, density) = PhoneLookSpec.size(System.getProperty("atlas.phonelook.size") ?: "S1")
        val views = PhoneLookSpec.views(System.getProperty("atlas.phonelook.views") ?: PhoneLookSpec.DEFAULT_VIEWS)
        val moment = System.getProperty("atlas.phonelook.moment")?.takeIf { it.isNotBlank() } ?: PhoneLookSpec.DEFAULT_MOMENT
        val packs = (System.getProperty("atlas.phonelook.packs") ?: "amsterdam,grachten,artis").split(',').map { it.trim() }
        val heapMb = System.getProperty("atlas.phonelook.heap")?.takeIf { it.isNotBlank() }?.toLong() ?: PhoneCanvas.PHONE_HEAP_MB
        val next = when (val c = System.getProperty("atlas.phonelook.canvas")?.takeIf { it.isNotBlank() } ?: "22") {
            "22" -> false
            "next" -> true
            else -> error("the canvas is alpha 22's (22) or this branch's (next), not '$c'")
        }
        val canvas = PhoneCanvas(File(out, "canvas-work"), packs, heapMb, next)
        val suffix = if (next) "canvasnext" else "canvas"
        for ((name, v) in views) {
            val f = canvas.draw(v, moment, w, h, density)
            PhoneCanvas.png(f.argb, w, h, File(out, "$name-$suffix.png"))
            File(out, "$name-$suffix.txt").writeText(f.notes)
            println("phone look canvas $name: ${f.notes.lines().first()}")
        }
    }
}

/** The views, moment and size the phone look tool's two sides take (the same text on both). */
object PhoneLookSpec {
    /**
     * The side by sides: the Westerkerk home (10 m west and 30 m north of it, as the desktop render tool's), the
     * Prinsengracht's trees, Artis's greens, the edge of the Westerkerk's pack at its north-west corner, the canal ring's
     * western edge, and the city from beyond the core's reach (9 km).
     */
    const val DEFAULT_VIEWS = "home=52.37483,4.88385,430,52,52;trees=52.3736,4.8833,320,15,56;park=52.3662,4.9150,650,40,50;" +
        "edge=52.3770,4.8790,700,0,50;ring=52.3735,4.8745,900,0,50;far=52.3745,4.8840,9200,52,40"

    /** A clear midday of the Westerkerk's recorded forecast. */
    const val DEFAULT_MOMENT = "2026-10-02T12:00"

    /** A view as the map's camera has it: the target, the eye's distance, the bearing and the map's pitch. */
    class Spec(val lat: Double, val lon: Double, val distance: Double, val bearing: Double, val pitch: Double) {
        /** The map's LiveView of it in a frame of [widthDp] × [heightDp] (MapController.liveView, the principal point at 48 %). */
        fun view(widthDp: Int, heightDp: Int) = LiveView(lat, lon, LiveCamera.zoom(lat, distance, heightDp.toDouble()), bearing, pitch, widthDp, heightDp, heightDp * 0.48)
    }

    fun views(text: String): List<Pair<String, Spec>> = text.split(';').filter { it.isNotBlank() }.map { e ->
        val (name, rest) = e.split('=', limit = 2).map { it.trim() }
        val n = rest.split(',').map { it.trim().toDouble() }
        require(n.size == 5) { "a view is <name>=<lat>,<lon>,<distance>,<bearing>,<pitch>, not '$e'" }
        name to Spec(n[0], n[1], n[2], n[3], n[4])
    }

    fun size(spec: String): Triple<Int, Int, Double> {
        if (spec == "S1") return Triple(1080, 2400, 1080.0 / 412.0)
        val m = Regex("""(\d+)x(\d+)@([0-9.]+)""").matchEntire(spec) ?: error("a size is S1 or <w>x<h>@<density>, not '$spec'")
        return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toDouble())
    }

    /**
     * The map's moment of [spec] in [zone]: `named:<moment>` (a style that fixes one), else a local time, the live
     * moment (the clock in five-minute steps, MapController.quantise) lit by the recorded forecast's hours as
     * MapData's MapWeather hands them over (no low cloud or humidity: the phone's forecast lacks them).
     */
    fun moment(spec: String, zone: ZoneId): Moment {
        if (spec.startsWith("named:")) return Moment.Named(spec.removePrefix("named:"))
        val at = LocalDateTime.parse(spec).atZone(zone).toInstant()
        val quantised = Instant.ofEpochSecond(at.epochSecond - Math.floorMod(at.epochSecond, 300L))
        return Moment.Live(quantised, forecast(zone))
    }

    /** The Westerkerk's recorded forecast (the engine's `/wx/ams.json`, local times) as the phone's forecast hours. */
    @Suppress("UNCHECKED_CAST")
    fun forecast(zone: ZoneId): List<WxHour> {
        val text = Amsterdam::class.java.getResourceAsStream("/wx/ams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val h = (PyJson.loads(text) as Map<String, Any?>)["hourly"] as Map<String, List<Any?>>
        fun d(k: String, i: Int) = (h.getValue(k)[i] as Number).toDouble()
        return h.getValue("time").indices.map { i ->
            WxHour(
                LocalDateTime.parse(h.getValue("time")[i] as String).atZone(zone).toInstant(), d("cloud_cover", i), d("precipitation", i),
                d("wind_speed_10m", i), d("wind_direction_10m", i), d("wind_gusts_10m", i), d("temperature_2m", i), d("pressure_msl", i),
                d("visibility", i), (h.getValue("weather_code")[i] as Number).toInt(),
            )
        }
    }
}

/**
 * Today's map on the phone, drawn here: the phone's packs and regions in [work], the renders' [packs] among them, a
 * scene allowed what [heapMb] of heap allows on the phone (LiveRegions' heap rule: a region whose scene would not fit
 * [LiveRegions.SCENE_SHARE] of it is neither downloaded nor loaded, and the map says so).
 */
class PhoneCanvas(work: File, packs: List<String>, private val heapMb: Long = PHONE_HEAP_MB, private val next: Boolean = false) {
    /** A frame: its opaque ARGB pixels and what drew it. */
    class Frame(val argb: IntArray, val notes: String)

    private val regions: LiveRegions
    private val fonts: SvgFonts = SvgFonts.fromDirectory(File(Likeness.repo, "app/android/mapview/src/main/assets/svgfonts"))

    init {
        app.atlas.anchor.AnchorCases.setUp()
        work.deleteRecursively()
        // MapData.packs: files/maps, the renders' manifest, no render packs; the bundled Westerkerk installed from the
        // app's assets (MapData.BUNDLED_ASSET), the renders' element packs as a first visit downloads them
        val library = PackLibrary(File(work, "maps"), listOf(PackLibrary.RENDERS), renders = false)
        for (id in packs) {
            val file = if (id == "amsterdam") File(Likeness.repo, "app/android/app/src/main/assets/packs/amsterdam.atlas") else File(Likeness.packs, "$id.atlas")
            if (!file.isFile) throw MissingPack(id, file)
            library.install(id) { FileInputStream(file) }
        }
        // MapData.regions: no Overpass builds; offline here, as every pack the views need is on the "phone"
        regions = LiveRegions(library, { it.run() }, online = { false }, overpass = null, heapBudget = { ((heapMb shl 20) * LiveRegions.SCENE_SHARE).toLong() })
        LiveEngine.workDir = File(work, "engine")
        // MapData.wire with the settings unset: the screen's effects off, the parts left out by default; and on this
        // branch's canvas the countries whose tiles the core draws, as MapData reads them from the published list
        // (packs-20261029's Netherlands)
        MapEngine.screenEffects(haze = false, vignette = false, grain = false)
        MapEngine.leaveOut(MapParts.byDefault)
        MapEngine.countries = if (next) listOf(NETHERLANDS) else emptyList()
    }

    /** [spec] at [moment] (PhoneLookSpec.moment's text) in a [w] × [h] px frame at [density], as the phone draws it. */
    fun draw(spec: PhoneLookSpec.Spec, moment: String, w: Int, h: Int, density: Double): Frame {
        val widthDp = Math.round(w / density).toInt()
        val heightDp = Math.round(h / density).toInt()
        val view = spec.view(widthDp, heightDp)
        // EngineFrames.make: the region the view draws (opened on the first ask, ready on the next)
        var state = regions.stateFor(view)
        if (state is LiveRegions.State.Pending) state = regions.stateFor(view)
        val st = state as? LiveRegions.State.Ready ?: return nothing(state, w, h, view)
        val region = st.region
        // alpha 22's frames are drawn with the map's options as they are; this branch's with the region's (EngineFrames.make)
        val opts = if (next) MapEngine.optionsFor(region) else MapEngine.options
        val m = PhoneLookSpec.moment(moment, region.zone)
        val t0 = System.nanoTime()
        val res = LiveEngine.render(region, view, m, opts) ?: error("the render was cancelled")
        val theme = LiveEngine.lock.withLock { LinkedHashMap(Amsterdam.THEMES[res.themeKey] ?: emptyMap<String, Any?>()) }
        val t1 = System.nanoTime()
        val docs = ArrayList<SvgDocument>(res.layers.size)
        for (svg in res.layers) docs += SvgParser.parse(svg)
        val scale = min(density.toFloat(), MapEngine.maxRasterScale)
        val night = m == Moment.Named("night")
        val image = EngineFrame.rasterise(docs, res.raster, fonts, scale, SvgCanvas.FilterMode.CPU, background = if (night) NIGHT else Color.TRANSPARENT)
        val t2 = System.nanoTime()
        // MapController.draw at rest: the earth (DAY_EARTH, NIGHT_EARTH), then the frame scaled from its dp to the screen
        val screen = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(screen)
        c.drawColor(if (night) NIGHT else DAY_EARTH)
        c.save()
        c.scale(w / widthDp.toFloat(), h / heightDp.toFloat())
        c.scale(1 / scale, 1 / scale)
        c.drawBitmap(image, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
        c.restore()
        val argb = IntArray(w * h)
        screen.getPixels(argb, 0, w, 0, 0, w, h)
        image.recycle()
        screen.recycle()
        val keys = listOf("name", "sky_top", "sky_hor", "sun", "amb", "haze", "haze_max", "ground", "road", "water", "water_top", "water_bot", "shadow", "shadow_op", "sun_k", "amb_k", "sky_k", "cloud")
        val notes = buildString {
            appendLine("region ${region.id} (${region.kind}), view ${res.view ?: "-"}, band ${res.tolerance}, moment ${res.themeKey} (${res.moment}); ${w}x$h at $density, raster scale $scale")
            appendLine("why this region: ${st.pending?.let { "a better one pending: $it" } ?: "the best fit among the phone's packs holding the target"}")
            appendLine("options $opts; a phone heap of $heapMb MB")
            appendLine("light " + keys.filter { it in theme }.joinToString(", ") { "$it=${theme[it]}" })
            appendLine("TONE ${Amsterdam.TONE}, AIR_HAZE ${Amsterdam.AIR_HAZE}")
            appendLine("view lat ${view.lat} lon ${view.lon} zoom ${view.zoom} bearing ${view.bearing} pitch ${view.pitch} ${view.widthDp}x${view.heightDp}dp cy ${view.cyDp}")
            appendLine("timings: load ${res.loadMillis} ms, render ${res.renderMillis} ms, parse and raster ${(t2 - t1) / 1_000_000} ms, all ${(t2 - t0) / 1_000_000} ms")
        }
        return Frame(argb, notes)
    }

    /**
     * What the map shows where today's map draws nothing (EngineFrames: MapStatus.NoData, its reason): the day's earth
     * alone, and "No map data here" over it where no frame was shown before (MapController.drawStatus).
     */
    private fun nothing(state: LiveRegions.State, w: Int, h: Int, view: LiveView): Frame {
        val argb = IntArray(w * h) { DAY_EARTH }
        val why = (state as? LiveRegions.State.Empty)?.reason ?: state.toString()
        return Frame(argb, "nothing drawn: $why\nview lat ${view.lat} lon ${view.lon} zoom ${view.zoom} bearing ${view.bearing} pitch ${view.pitch}\n")
    }

    companion object {
        /**
         * The heap a Pixel gives an app with a large heap (dalvik.vm.heapsize, 512 MB): its scene budget is 205 MB, which
         * the canal ring's (an estimated 332 MB) does not fit.
         */
        const val PHONE_HEAP_MB = 512L

        /** The Netherlands as packs-latest lists its tiles (packs-20261029): its pack's box. */
        val NETHERLANDS = CountryTiles("netherlands", "Netherlands", app.atlas.geo.BBox(2.9361, 50.7475, 7.2305, 54.0179))

        /** MapController's DAY_EARTH and NIGHT_EARTH (EngineFrames' NIGHT): what the map paints under a frame. */
        const val DAY_EARTH = 0xFFE4E0D8.toInt()
        const val NIGHT = 0xFF0B0F1A.toInt()

        fun png(argb: IntArray, w: Int, h: Int, file: File) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, argb, 0, w)
            file.absoluteFile.parentFile?.mkdirs()
            assertTrue("the PNG could not be written to $file", ImageIO.write(img, "png", file))
        }
    }
}
