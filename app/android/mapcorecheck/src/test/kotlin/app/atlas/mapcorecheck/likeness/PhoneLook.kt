package app.atlas.mapcorecheck.likeness

import app.atlas.geo.BBox
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.mapview.MapParts
import app.atlas.mapview.vk.CoreMoment
import app.atlas.render.Amsterdam
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.ViewLadder
import app.atlas.render.live.WxHour
import app.atlas.render.py.PyJson
import app.atlas.tiles.PackCatalog
import app.atlas.tiles.WindowTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * The JNI entry points of `CP/jni/TestLook.cpp` in the core's host build: the app's map frame (frame/MapFrame.hpp)
 * through the stand-in's session, as the phone's Bridge drives it, drawn offscreen on lavapipe.
 */
internal object LookHost {
    /** The layout of its calls; the library refuses another. */
    const val ABI = 1

    @JvmStatic external fun abi(): Int
    @JvmStatic external fun lastError(): String

    /** MapCore.Window.version: the version of a window built now (its stage P's and its style's). */
    @JvmStatic external fun windowVersion(): String

    /** A map of [width] × [height] px, its pipeline cache and noise fields under [cache]; 0 with [lastError] set. */
    @JvmStatic external fun open(width: Int, height: Int, cache: String): Long

    /** MapCore.packs: the packs listed (numbers, MapCore.PackRef's kinds, ids, files); a line per pack. */
    @JvmStatic external fun packs(handle: Long, numbers: LongArray, kinds: IntArray, ids: Array<String>, paths: Array<String>): String

    /** MapCore.moment: the theme block from now on. */
    @JvmStatic external fun theme(handle: Long, theme: ByteBuffer): Boolean

    /** MapCore.screenEffects and MapCore.painterOrder. */
    @JvmStatic external fun screen(handle: Long, haze: Boolean, vignette: Boolean, grain: Boolean, painter: Boolean)

    /** Draws at the camera state until the picture is complete (or [seconds] pass); what it drew, or "". */
    @JvmStatic external fun draw(handle: Long, camera: ByteBuffer, seconds: Double): String

    /** MapCore.wantedWindows: nearest first, packed z << 48 | x << 24 | y. */
    @JvmStatic external fun wanted(handle: Long): LongArray

    /** The last frame, R G B A rows. */
    @JvmStatic external fun read(handle: Long, pixels: ByteBuffer): Boolean

    @JvmStatic external fun close(handle: Long)
}

/**
 * The phone look tool's core side (ENGINE.md, "On the user's phone first"): the core's frame of a view and moment as
 * the user's phone draws it, beside today's map's frame of the same view and moment as the phone rasterises it
 * (`:likeness`'s PhoneCanvas.kt draws that one into the same folder). Each step is the app's own code or the same
 * calls with the same inputs:
 *
 * - the map: the app's map frame driven as the Bridge drives it ([LookHost]), the screen's haze, vignette and grain off
 *   and the painter switch off (MapData.wire with the Look settings unset: MapCore.screenEffects, painterOrder);
 * - the moment: the theme block [CoreMoment] writes for the core (the map's live moment, the clock in five-minute steps,
 *   lit by the forecast's hours: the same Moment today's map draws), for the drawn pack's zone;
 * - each frame's camera state as MapCameraSource writes it at rest: the view, the frame, the pack PackList names, and
 *   the parts the map leaves out by default ([MapParts.byDefault]: the clouds' shadows and the water's light);
 * - the packs, on alpha 22's path (`-Datlas.phonelook.path=22`, the default): the windows alone, each taken from its
 *   country's tiles as the phone's Windows takes them ([WindowTiles]: the published list, the index, a range of the
 *   tiles held to its SHA-256), the ones the map's world wants (MapCore.wantedWindows), nearest first, until it wants
 *   none it has not got, listed as PackList lists them (by name, none that holds nothing to draw or is of another
 *   version, each its WINDOW kind and a number for the process) and the camera naming the smallest whose box holds the
 *   target; on alpha 21's path (`21`), the canal ring the APK carried (`-Datlas.phonelook.canal`: `:app:benchTiles`
 *   with the core's stage P tool, the same cut and looks as alpha 21's generateBundledTiles) as a bundled pack beside
 *   the same windows, the camera naming the pack whose views fit the distance best as alpha 21's PackList did.
 *
 * It draws only when asked:
 *
 *     ./gradlew :mapcorecheck:test --tests '*PhoneLook.draw' -Datlas.phonelook.out=/tmp/look \
 *         -Datlas.phonelook.views='edge=52.3735,4.8745,900,60,50'
 *
 * with the views, `-Datlas.phonelook.moment` and `-Datlas.phonelook.size` as PhoneCanvas.kt takes them, and
 * optionally `-Datlas.phonelook.path=22|21`, `-Datlas.phonelook.list=<packs.json>` (the published list the windows
 * are taken by, else packs-latest's) and `-Datlas.phonelook.work=<folder>` (the windows and indexes, kept between runs;
 * `<out>/core-work` by default). It writes `<name>-core<path>.png` and `.txt` (what was drawn and how it settled, the
 * windows taken, the light). Then
 *
 *     ./gradlew :mapcorecheck:test --tests '*PhoneLook.compare' -Datlas.phonelook.compare=/tmp/look
 *
 * holds each `<name>-canvas.png` to its core frames: a difference image (`<name>-diff<path>.png`, each channel's
 * difference three times over a grey middle) and `<name>-compare<path>.txt` (CIELAB means of the frame and of each
 * colour class on both sides, the class of a pixel taken from the canvas's colour, and the probes of `<name>-probes.txt`
 * if there is one: `<label> <x> <y> <w> <h>` a line, boxes in the frame's pixels).
 */
class PhoneLook {
    @Test
    fun draw() {
        val out = System.getProperty("atlas.phonelook.out")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("the phone look tool draws only when asked (-Datlas.phonelook.out=<folder>)", out != null)
        out!!.mkdirs()
        val core = HostCore.load()
        assertTrue("the host core: ${HostCore.unavailable}", core != null)
        assertEquals("the host build's look entry points", LookHost.ABI, LookHost.abi())
        val (w, h, density) = Spec.size(System.getProperty("atlas.phonelook.size") ?: "S1")
        val views = Spec.views(System.getProperty("atlas.phonelook.views") ?: Spec.DEFAULT_VIEWS)
        val moment = System.getProperty("atlas.phonelook.moment")?.takeIf { it.isNotBlank() } ?: Spec.DEFAULT_MOMENT
        val path = System.getProperty("atlas.phonelook.path")?.takeIf { it.isNotBlank() } ?: "22"
        require(path == "22" || path == "21") { "the path is alpha 22's (22) or alpha 21's (21), not '$path'" }
        val work = System.getProperty("atlas.phonelook.work")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(out, "core-work")
        val canal = if (path == "21") {
            File(System.getProperty("atlas.phonelook.canal") ?: error("alpha 21's path needs the canal ring's render pack (-Datlas.phonelook.canal)"))
                .also { require(it.isFile) { "no canal ring at $it" } }
        } else null
        val list = System.getProperty("atlas.phonelook.list")?.takeIf { it.isNotBlank() }?.let { File(it).readText() }
        val phone = PhoneCore(work, list, canal)
        for ((name, spec) in views) {
            val f = phone.draw(spec, moment, w, h, density)
            png(f.argb, w, h, File(out, "$name-core$path.png"))
            File(out, "$name-core$path.txt").writeText(f.notes)
            println("phone look core $name (alpha $path): ${f.notes.lines().first()}")
        }
    }

    @Test
    fun compare() {
        val dir = System.getProperty("atlas.phonelook.compare")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("the phone look tool compares only when asked (-Datlas.phonelook.compare=<folder>)", dir != null)
        val canvases = dir!!.listFiles { f -> CANVAS.matches(f.name) }.orEmpty().sortedBy { it.name }
        assertTrue("no <name>-canvas.png in $dir", canvases.isNotEmpty())
        for (c in canvases) {
            val (name, side) = CANVAS.matchEntire(c.name)!!.destructured
            // today's map as alpha 22 draws it (-canvas), or as this branch does (-canvasnext: <name>-diffnext22.png)
            val next = if (side == "canvasnext") "next" else ""
            val cores = Regex(Regex.escape(name) + """-core(\d+)\.png""")
            for (core in dir.listFiles { f -> cores.matches(f.name) }.orEmpty().sortedBy { it.name }) {
                val path = cores.matchEntire(core.name)!!.groupValues[1]
                val a = ImageIO.read(c)
                val b = ImageIO.read(core)
                require(a.width == b.width && a.height == b.height) { "$c is ${a.width}x${a.height}, $core ${b.width}x${b.height}" }
                val probes = File(dir, "$name-probes.txt").takeIf { it.isFile }?.readLines().orEmpty()
                val report = Compare.of(a, b, probes)
                ImageIO.write(report.diff, "png", File(dir, "$name-diff$next$path.png"))
                File(dir, "$name-compare$next$path.txt").writeText(report.text)
                println("phone look compare $name ($side, alpha $path):\n${report.text}")
            }
        }
    }

    /** The views, moment and size, read as PhoneCanvas.kt's PhoneLookSpec reads them. */
    internal object Spec {
        const val DEFAULT_VIEWS = "home=52.37483,4.88385,430,52,52;trees=52.3736,4.8833,320,15,56;park=52.3662,4.9150,650,40,50;" +
            "edge=52.3770,4.8790,700,0,50;ring=52.3735,4.8745,900,0,50;far=52.3745,4.8840,9200,52,40"
        const val DEFAULT_MOMENT = "2026-10-02T12:00"

        class View(val lat: Double, val lon: Double, val distance: Double, val bearing: Double, val pitch: Double) {
            fun view(widthDp: Int, heightDp: Int) = LiveView(lat, lon, LiveCamera.zoom(lat, distance, heightDp.toDouble()), bearing, pitch, widthDp, heightDp, heightDp * 0.48)
        }

        fun views(text: String): List<Pair<String, View>> = text.split(';').filter { it.isNotBlank() }.map { e ->
            val (name, rest) = e.split('=', limit = 2).map { it.trim() }
            val n = rest.split(',').map { it.trim().toDouble() }
            require(n.size == 5) { "a view is <name>=<lat>,<lon>,<distance>,<bearing>,<pitch>, not '$e'" }
            name to View(n[0], n[1], n[2], n[3], n[4])
        }

        fun size(spec: String): Triple<Int, Int, Double> {
            if (spec == "S1") return Triple(1080, 2400, 1080.0 / 412.0)
            val m = Regex("""(\d+)x(\d+)@([0-9.]+)""").matchEntire(spec) ?: error("a size is S1 or <w>x<h>@<density>, not '$spec'")
            return Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toDouble())
        }

        fun moment(spec: String, zone: ZoneId): Moment {
            if (spec.startsWith("named:")) return Moment.Named(spec.removePrefix("named:"))
            val at = LocalDateTime.parse(spec).atZone(zone).toInstant()
            return Moment.Live(Instant.ofEpochSecond(at.epochSecond - Math.floorMod(at.epochSecond, 300L)), forecast(zone))
        }

        @Suppress("UNCHECKED_CAST")
        private fun forecast(zone: ZoneId): List<WxHour> {
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

    internal companion object {
        /** A canvas frame's file: `<name>-canvas.png` (alpha 22's) or `<name>-canvasnext.png` (this branch's). */
        val CANVAS = Regex("""(.+)-(canvas|canvasnext)\.png""")

        fun png(argb: IntArray, w: Int, h: Int, file: File) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, argb, 0, w)
            file.absoluteFile.parentFile?.mkdirs()
            assertTrue("the PNG could not be written to $file", ImageIO.write(img, "png", file))
        }
    }
}

/**
 * The core on the phone, here: the windows in [work] (taken from their country's tiles as the phone takes them, by the
 * published list [listText], else packs-latest's), and alpha 21's bundled [canal] ring beside them when given.
 */
internal class PhoneCore(private val work: File, listText: String?, private val canal: File?) {
    class Frame(val argb: IntArray, val notes: String)

    /** A pack as PackList lists it: its id, number for the process, MapCore.PackRef's kind, box, profile and views' distances. */
    private class Listed(val id: String, val number: Long, val kind: Int, val box: BBox, val profile: Int, val distances: DoubleArray, val file: File)

    private val windowsDir = File(work, "windows").apply { mkdirs() }
    private val numbers = HashMap<String, Long>()
    private val version = LookHost.windowVersion()
    private val tiles: WindowTiles = run {
        val parsed = listText?.let { PackCatalog.parse(it).packs }
        val catalog = PackCatalog(PackCatalog.DEFAULT_URL, cacheFile = File(work, "index/packs-latest.json"))
        WindowTiles({ parsed ?: kotlinx.coroutines.runBlocking { catalog.packs() } }, File(work, "index"))
    }

    /** [spec] at [moment] in a [w] × [h] px frame at [density], as the phone's core draws it. */
    fun draw(spec: PhoneLook.Spec.View, moment: String, w: Int, h: Int, density: Double): Frame {
        val widthDp = Math.round(w / density).toInt()
        val heightDp = Math.round(h / density).toInt()
        val view = spec.view(widthDp, heightDp)
        val handle = LookHost.open(w, h, File(work, "cache").path)
        check(handle != 0L) { "the host map: ${LookHost.lastError()}" }
        val notes = StringBuilder()
        try {
            // MapData.wire with the Look settings unset: no haze, vignette or grain, true depth
            LookHost.screen(handle, haze = false, vignette = false, grain = false, painter = false)
            val taken = ArrayList<String>()
            var drew = ""
            var listed: List<Listed>
            var rounds = 0
            while (true) {
                listed = list()
                val said = LookHost.packs(handle, listed.map { it.number }.toLongArray(), listed.map { it.kind }.toIntArray(),
                    listed.map { it.id }.toTypedArray(), listed.map { it.file.path }.toTypedArray())
                val pack = choose(listed, view.lat, view.lon, view.distance)
                // MapController.sendMoment: the moment for the drawn pack's zone (every pack here is Amsterdam's)
                val zone = ZoneId.of("Europe/Amsterdam")
                val theme = ThemeBlock.allocate()
                CoreMoment.write(theme, PhoneLook.Spec.moment(moment, zone), zone, daylight = false)
                check(LookHost.theme(handle, theme)) { LookHost.lastError() }
                drew = LookHost.draw(handle, camera(view, w, h, density, pack), DRAW_SECONDS)
                check(drew.isNotEmpty()) { "the host map drew nothing: ${LookHost.lastError()}" }
                if (rounds == 0) notes.appendLine("packs: ${said.lines().filter { it.isNotBlank() }.size} listed")
                // Windows.step: the windows the world wants, nearest first, each taken from its country's tiles
                var added = 0
                for (k in LookHost.wanted(handle)) {
                    val z = (k ushr 48).toInt(); val x = ((k ushr 24) and 0xFFFFFF).toInt(); val y = (k and 0xFFFFFF).toInt()
                    if (fetch(z, x, y, taken)) added++
                }
                rounds++
                if (added == 0 || rounds >= MAX_ROUNDS) break
            }
            val pixels = ByteBuffer.allocateDirect(w * h * 4)
            check(LookHost.read(handle, pixels)) { LookHost.lastError() }
            val argb = IntArray(w * h)
            for (i in 0 until w * h) {
                val o = i * 4
                argb[i] = (0xFF shl 24) or ((pixels.get(o).toInt() and 0xFF) shl 16) or ((pixels.get(o + 1).toInt() and 0xFF) shl 8) or (pixels.get(o + 2).toInt() and 0xFF)
            }
            val pack = choose(listed, view.lat, view.lon, view.distance)
            notes.insert(0, "$drew\n")
            notes.appendLine("camera names ${pack?.id ?: "none"} (#${pack?.number ?: 0}, profile ${pack?.profile ?: 0}); ${listed.size} packs listed after $rounds rounds")
            notes.appendLine("windows taken from the country's tiles: ${taken.size} (${taken.joinToString(" ")})")
            notes.appendLine("parts left out: ${MapParts.byDefault.joinToString(" ") { it.name.lowercase() }}; screen: no haze, vignette or grain; true depth")
            notes.appendLine("window version $version")
            notes.appendLine("light " + light(moment))
            notes.appendLine("view lat ${view.lat} lon ${view.lon} zoom ${view.zoom} bearing ${view.bearing} pitch ${view.pitch} ${view.widthDp}x${view.heightDp}dp cy ${view.cyDp} distance ${view.distance}")
            return Frame(argb, notes.toString())
        } finally {
            LookHost.close(handle)
        }
    }

    /**
     * The light [CoreMoment] writes for [moment], as the port's theme names it (the same keys PhoneCanvas.kt logs for
     * today's map): CoreMoment's steps under the engine's lock, the port's state put back after.
     */
    private fun light(moment: String): String = app.atlas.render.live.LiveEngine.lock.withLock {
        val saved = app.atlas.render.live.EngineState.capture()
        try {
            app.atlas.render.Regions.resetPlace(false)
            val zone = ZoneId.of("Europe/Amsterdam")
            val key = when (val m = PhoneLook.Spec.moment(moment, zone)) {
                is Moment.Named -> m.name
                is Moment.Live -> {
                    app.atlas.render.live.LiveMoment.install(m.at, zone, m.hours, false)
                    app.atlas.render.live.LiveMoment.KEY
                }
            }
            val th = Amsterdam.THEMES.getValue(key)
            val keys = listOf("name", "sky_top", "sky_hor", "sun", "amb", "haze", "haze_max", "ground", "road", "water", "water_top", "water_bot", "shadow", "shadow_op", "sun_k", "amb_k", "sky_k", "cloud")
            keys.filter { it in th }.joinToString(", ") { "$it=${th[it]}" } + "; TONE off (with_pack: the Netherlands)"
        } finally {
            saved.restore()
        }
    }

    /**
     * Window [z]/[x]/[y] from its country's tiles into the windows' folder as the phone's Windows writes it
     * (`14-<x>-<y>.atlas`), or its mark (`.tiles`) where it holds nothing to draw; true where it is new.
     */
    private fun fetch(z: Int, x: Int, y: Int, taken: MutableList<String>): Boolean {
        val file = File(windowsDir, "$z-$x-$y.atlas")
        val mark = File(windowsDir, "$z-$x-$y.tiles")
        if (file.isFile || mark.isFile) return false
        val got = tiles.window(z, x, y, version) ?: run {
            // no country's tiles hold it: the phone would build it from its data, which this tool does not
            mark.writeText("none\n")
            return false
        }
        mark.writeText("${got.date} ${got.pack}\n")
        if (!got.drawn) return false
        val part = File(file.path + ".part")
        part.writeBytes(got.bytes)
        check(part.renameTo(file)) { "could not place $file" }
        taken += "$z/$x/$y"
        return true
    }

    /** PackList.load: the canal ring (alpha 21's bundled pack) and the windows, by name, as the phone lists them. */
    private fun list(): List<Listed> {
        val out = ArrayList<Listed>()
        canal?.let { out += listed("grachten", BUNDLED, it) ?: error("the canal ring at $it does not read") }
        for (f in windowsDir.listFiles { f -> f.name.endsWith(".atlas") }.orEmpty().sortedBy { it.name }) {
            val m = NAME.matchEntire(f.name) ?: continue
            val (z, x, y) = m.destructured
            listed("window/$z/$x/$y", WINDOW, f)?.let { out += it }
        }
        return out
    }

    private fun listed(id: String, kind: Int, file: File): Listed? {
        val head = head(file) ?: return null
        // a window that holds nothing, or of another version than a window built now, is not listed
        if (kind == WINDOW && (head.chunks == 0 || head.version != version)) return null
        val number = numbers.getOrPut(id) { numbers.size + 1L }
        return Listed(id, number, kind, head.box, head.profile, head.distances, file)
    }

    /**
     * The pack the camera names: on alpha 22's path the smallest whose box holds the target (PackList.choose); on
     * alpha 21's, the one whose views fit the distance best, the smaller box on a tie (its PackList.choose at rest).
     */
    private fun choose(packs: List<Listed>, lat: Double, lon: Double, distance: Double): Listed? {
        var best: Listed? = null
        var bestFit = Double.MAX_VALUE
        for (p in packs) {
            val b = p.box
            if (lat < b.south || lat > b.north || lon < b.west || lon > b.east) continue
            val fit = if (canal == null) 0.0 else fit(p.distances, distance)
            val tie = abs(fit - bestFit) <= 1e-9
            if (best == null || (!tie && fit < bestFit) || (tie && area(b) < area(best.box))) {
                best = p
                bestFit = min(fit, bestFit)
            }
        }
        return best
    }

    private class Head(val box: BBox, val profile: Int, val distances: DoubleArray, val chunks: Int?, val version: String?)

    /** What PackList.readHead reads of a render pack's manifest: its box, profile, views' distances, tiles and version. */
    private fun head(file: File): Head? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { d ->
            val head = ByteArray(20)
            d.readFully(head)
            if (String(head, 0, 8, Charsets.US_ASCII) != "ATLASPCK" || head[9].toInt() != 3) throw IOException("not a render pack")
            val length = ((head[12].toInt() and 0xFF) shl 24) or ((head[13].toInt() and 0xFF) shl 16) or ((head[14].toInt() and 0xFF) shl 8) or (head[15].toInt() and 0xFF)
            val bytes = ByteArray(length)
            d.readFully(bytes)
            val m = PyJson.loads(bytes.decodeToString()) as Map<*, *>
            val b = m["bbox"] as Map<*, *>
            fun n(v: Any?) = (v as Number).toDouble()
            val profile = m["profile"] as? String
            val views = (((m["config"] as? Map<*, *>)?.get("views") as? Map<*, *>).orEmpty()).values.mapNotNull { ((it as? Map<*, *>)?.get("dist") as? Number)?.toDouble() }
            val distances = if (profile != "living" && views.isNotEmpty()) views.toDoubleArray() else doubleArrayOf((ViewLadder.livingView()["dist"] as Number).toDouble())
            val engine = m["engine_version"] as? String
            val style = m["style_version"] as? String
            Head(
                BBox(n(b["west"]), n(b["south"]), n(b["east"]), n(b["north"])),
                when (profile) {
                    "living" -> CameraWriter.PROFILE_LIVING
                    "williamsburg" -> CameraWriter.PROFILE_WILLIAMSBURG
                    "world-base" -> CameraWriter.PROFILE_WORLD_BASE
                    else -> CameraWriter.PROFILE_REGION
                },
                distances, (m["chunks"] as? Number)?.toInt(), if (engine != null && style != null) "$engine $style" else null,
            )
        }
    }.getOrNull()

    /** The camera state MapCameraSource writes at rest for [view] in a [w] × [h] frame, naming [pack]. */
    private fun camera(view: LiveView, w: Int, h: Int, density: Double, pack: Listed?): ByteBuffer {
        val b = CameraWriter.allocate()
        CameraWriter.header(b, ++sequence, CameraWriter.REST)
        // MapController.publishCamera: the style's trees and shadows are drawn, the app's default parts left out
        CameraWriter.parts(b, MapParts.bits(MapParts.byDefault))
        CameraWriter.view(b, view.lat, view.lon, view.bearing, view.pitch, view.distance, view.widthDp / 2.0, view.cyDp)
        CameraWriter.frame(b, w, h, view.widthDp, view.heightDp, density)
        CameraWriter.pack(b, pack?.number ?: 0L, pack?.profile ?: CameraWriter.PROFILE_NONE)
        CameraWriter.keepOuts(b, 0)
        CameraWriter.noLocation(b)
        return b
    }

    private var sequence = 0L

    private fun fit(distances: DoubleArray, distance: Double): Double {
        var best = Double.MAX_VALUE
        val d = distance.coerceAtLeast(1.0)
        for (v in distances) best = min(best, abs(ln(d / v)))
        return best
    }

    private fun area(b: BBox): Double = b.width.coerceAtLeast(0.0) * b.height.coerceAtLeast(0.0)

    private companion object {
        /** MapCore.PackRef's kinds. */
        const val BUNDLED = 0
        const val WINDOW = 2

        val NAME = Regex("""(\d+)-(\d+)-(\d+)\.atlas""")

        /** How long a frame may take to settle on lavapipe (the canal ring's densest views take minutes on this Mac). */
        const val DRAW_SECONDS = 600.0

        /** At most this many rounds of windows taken. */
        const val MAX_ROUNDS = 12
    }
}

/** A canvas frame held to a core frame: a difference image and CIELAB means, by colour class and by probe. */
internal object Compare {
    class Report(val diff: BufferedImage, val text: String)

    /** The classes a pixel of the canvas falls in by its colour (hue in degrees, chroma and lightness in CIELAB). */
    private val classes: List<Pair<String, (DoubleArray) -> Boolean>> = listOf(
        "green, dark (L* < 50)" to { c -> isGreen(c) && c[0] < 50 },
        "green, light (L* >= 50)" to { c -> isGreen(c) && c[0] >= 50 },
        "water and blue" to { c -> chroma(c) > 8 && hue(c) in 200.0..290.0 },
        "red and brown (roofs, brick)" to { c -> chroma(c) > 14 && (hue(c) < 70.0 || hue(c) > 340.0) },
        "warm pale (plaster, sand, paper)" to { c -> chroma(c) in 4.0..14.0 && hue(c) in 40.0..110.0 && c[0] > 60 },
        "neutral light (paving, roads, ground)" to { c -> chroma(c) < 6 && c[0] >= 55 },
        "neutral dark (asphalt, shade)" to { c -> chroma(c) < 6 && c[0] < 55 },
    )

    private fun isGreen(c: DoubleArray) = chroma(c) > 8 && hue(c) in 100.0..200.0
    private fun chroma(c: DoubleArray) = hypot(c[1], c[2])
    private fun hue(c: DoubleArray): Double = Math.toDegrees(atan2(c[2], c[1])).let { if (it < 0) it + 360 else it }

    fun of(a: BufferedImage, b: BufferedImage, probes: List<String>): Report {
        val w = a.width; val h = a.height
        val pa = a.getRGB(0, 0, w, h, null, 0, w)
        val pb = b.getRGB(0, 0, w, h, null, 0, w)
        val diff = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val sumA = DoubleArray(3); val sumB = DoubleArray(3)
        val byClassA = Array(classes.size) { DoubleArray(3) }
        val byClassB = Array(classes.size) { DoubleArray(3) }
        val counts = IntArray(classes.size)
        // and by the core's colour: the core's mean there, and the canvas's at the same pixels
        val coreA = Array(classes.size) { DoubleArray(3) }
        val coreB = Array(classes.size) { DoubleArray(3) }
        val ownB = IntArray(classes.size)
        var de = 0.0
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val ca = pa[i]; val cb = pb[i]
                fun ch(c: Int, s: Int) = (c shr s) and 0xFF
                fun d(s: Int) = (128 + 3 * (ch(cb, s) - ch(ca, s))).coerceIn(0, 255)
                row[x] = (d(16) shl 16) or (d(8) shl 8) or d(0)
                val la = lab(ca); val lb = lab(cb)
                for (k in 0 until 3) { sumA[k] += la[k]; sumB[k] += lb[k] }
                de += hypot(hypot(la[0] - lb[0], la[1] - lb[1]), la[2] - lb[2])
                for ((n, cl) in classes.withIndex()) {
                    if (cl.second(la)) {
                        counts[n]++
                        for (k in 0 until 3) { byClassA[n][k] += la[k]; byClassB[n][k] += lb[k] }
                    }
                    if (cl.second(lb)) {
                        ownB[n]++
                        for (k in 0 until 3) { coreA[n][k] += la[k]; coreB[n][k] += lb[k] }
                    }
                }
            }
            diff.setRGB(0, y, w, 1, row, 0, w)
        }
        val n = (w * h).toDouble()
        val text = buildString {
            appendLine("frame: canvas ${fmt(sumA, n)}, core ${fmt(sumB, n)}; Δ ${delta(sumA, sumB, n, n)}; mean ΔE76 ${"%.1f".format(de / n)}")
            appendLine("classes by the canvas's colour (share of the frame; the core's own share of that class): canvas mean, core mean at the same pixels, Δ")
            for ((k, cl) in classes.withIndex()) {
                if (counts[k] == 0) continue
                val c = counts[k].toDouble()
                appendLine("  ${cl.first}: ${pct(counts[k], n)} (core ${pct(ownB[k], n)}): canvas ${fmt(byClassA[k], c)}, core ${fmt(byClassB[k], c)}; Δ ${delta(byClassA[k], byClassB[k], c, c)}")
            }
            appendLine("classes by the core's colour (its share of the frame): core mean, canvas mean at the same pixels, Δ (core - canvas)")
            for ((k, cl) in classes.withIndex()) {
                if (ownB[k] == 0) continue
                val c = ownB[k].toDouble()
                appendLine("  ${cl.first}: ${pct(ownB[k], n)}: core ${fmt(coreB[k], c)}, canvas ${fmt(coreA[k], c)}; Δ ${delta(coreA[k], coreB[k], c, c)}")
            }
            if (probes.isNotEmpty()) appendLine("probes (mean of the box): canvas, core, Δ")
            for (line in probes) {
                val p = line.trim().split(Regex("\\s+"))
                if (p.size < 5 || line.trim().startsWith("#")) continue
                val (px, py, pw, ph) = p.subList(1, 5).map { it.toInt() }
                val sa = DoubleArray(3); val sb = DoubleArray(3)
                var m = 0
                for (yy in py until min(h, py + ph)) for (xx in px until min(w, px + pw)) {
                    val la = lab(pa[yy * w + xx]); val lb = lab(pb[yy * w + xx])
                    for (k in 0 until 3) { sa[k] += la[k]; sb[k] += lb[k] }
                    m++
                }
                if (m == 0) continue
                appendLine("  ${p[0]} ($px,$py ${pw}x$ph): canvas ${fmt(sa, m.toDouble())} ${hex(pa, w, px, py, pw, ph)}, core ${fmt(sb, m.toDouble())} ${hex(pb, w, px, py, pw, ph)}; Δ ${delta(sa, sb, m.toDouble(), m.toDouble())}")
            }
        }
        return Report(diff, text)
    }

    private fun pct(k: Int, n: Double) = "%.1f %%".format(100.0 * k / n)

    private fun fmt(s: DoubleArray, n: Double) = "L* %.1f a* %.1f b* %.1f".format(s[0] / n, s[1] / n, s[2] / n)

    private fun delta(a: DoubleArray, b: DoubleArray, na: Double, nb: Double): String {
        val dl = b[0] / nb - a[0] / na; val da = b[1] / nb - a[1] / na; val db = b[2] / nb - a[2] / na
        return "ΔL* %+.1f Δa* %+.1f Δb* %+.1f (ΔE76 %.1f)".format(dl, da, db, hypot(hypot(dl, da), db))
    }

    /** The mean sRGB colour of a box, as hex. */
    private fun hex(p: IntArray, w: Int, x: Int, y: Int, bw: Int, bh: Int): String {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (yy in y until y + bh) for (xx in x until x + bw) {
            val i = yy * w + xx
            if (i >= p.size) continue
            r += (p[i] shr 16) and 0xFF; g += (p[i] shr 8) and 0xFF; b += p[i] and 0xFF; n++
        }
        if (n == 0) return "-"
        return "#%02X%02X%02X".format((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /** CIELAB (D65) of an sRGB colour. */
    fun lab(argb: Int): DoubleArray {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        val r = lin((argb shr 16) and 0xFF); val g = lin((argb shr 8) and 0xFF); val b = lin(argb and 0xFF)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883
        fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
        val fx = f(x); val fy = f(y); val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }
}
