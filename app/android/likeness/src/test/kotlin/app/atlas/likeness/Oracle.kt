package app.atlas.likeness

import app.atlas.anchor.AnchorCases
import app.atlas.mapview.svg.SvgFonts
import app.atlas.render.Amsterdam
import app.atlas.render.data.PackReader
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveRender
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.WorldTape
import app.atlas.render.live.WxHour
import app.atlas.render.py.PyJson
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.withLock

/** The likeness module's settings, from the test task's system properties. */
object Likeness {
    /** The repository's root. */
    val repo: File get() = File(System.getProperty("atlas.repo") ?: "../..").canonicalFile

    /** Where reference sets are kept: `-Datlas.likeness.refs`, else the repository's git-ignored cache. */
    val refs: File get() = System.getProperty("atlas.likeness.refs")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(repo, "cache/likeness/refs")

    /** The tier to build: `smoke` (every push) or `full` (nightly). */
    val tier: String get() = System.getProperty("atlas.likeness.tier")?.takeIf { it.isNotBlank() } ?: "smoke"

    /** One shard of the tier, `k/n` (CI's nightly full tier runs in two): the cases whose name hashes to k modulo n. */
    val shard: Pair<Int, Int>? get() = System.getProperty("atlas.likeness.shard")?.takeIf { it.isNotBlank() }?.let { s ->
        val (k, n) = s.split('/').map { it.trim().toInt() }
        require(n >= 1 && k in 0 until n) { "a shard is k/n with 0 <= k < n, not '$s'" }
        k to n
    }

    /** Only cases whose names contain one of these comma-separated parts (while working on some). */
    val only: String? get() = System.getProperty("atlas.likeness.only")?.takeIf { it.isNotBlank() }

    /** A phone (`<model>:<w>x<h>[@<density>]`, from its VK facts) whose VK check cases are added at its own size. */
    val phone: FrameSize? get() = System.getProperty("atlas.likeness.phone")?.takeIf { it.isNotBlank() }?.let { FrameSize.phone(it) }

    /** Where the region packs are: `-Datlas.likeness.packs` (CI downloads them there), else the repository's cache. */
    val packs: File get() = System.getProperty("atlas.likeness.packs")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: File(repo, "cache/packs/regions")

    /**
     * The packer's `StyleVersion` (plan-vector D18), which keys references beside `EngineVersion.ID`. The packer does not
     * define it yet (K2), so the build passes it when it exists and references say "unversioned" until then.
     */
    val style: String get() = System.getProperty("atlas.likeness.style")?.takeIf { it.isNotBlank() } ?: "unversioned"

    /** The bundled typefaces the boards' SVGs name, one set per thread (`SvgFonts` keeps an unsynchronised cache). */
    val fonts: SvgFonts get() = fontsHere.get() ?: SvgFonts.fromDirectory(File(repo, "app/android/mapview/src/main/assets/svgfonts")).also { fontsHere.set(it) }

    private val fontsHere = ThreadLocal<SvgFonts>()

    /** The version references are made for: `EngineVersion.ID` (what the port draws) and [style]. */
    val version: String get() = "${EngineVersion.ID}-$style"
}

/** A pack a case needs is not on this machine. */
class MissingPack(val pack: String, val file: File) :
    IllegalStateException("the pack '$pack' is not at ${file.path}: CI downloads it from the release packs-renders (see android.yml); on this Mac it is in cache/packs/regions")

/**
 * The port, run for a case: its region opened once per JVM (the pack checked against the case's SHA-256), the view
 * placed and rendered at the moment under a world tape whose frame camera is the view's own, as the phone's engine
 * renders it. Renders are one at a time: the port's module state is shared (`LiveEngine.lock`).
 */
object Oracle {
    private val regions = HashMap<String, LiveRegion>()

    /** One render: its layers, the relief image, the tape (sealed), the moment's theme and how long it took. */
    class Made(
        val case: Case,
        val region: LiveRegion,
        val view: LiveView,
        val render: LiveRender,
        val tape: WorldTape,
        /** The theme the render drew with (`THEMES[themeKey]`), as it was when the render returned. */
        val theme: Map<String, Any?>,
        val millis: Long,
    ) {
        /** The layers, ground first: g, o and (when the objects were split) o2. */
        val layers: List<String> get() = render.layers
    }

    /** The region of [pack] (`bundled`: the app's Westerkerk; any other: `<packs>/<pack>.atlas`), opened once. */
    @Synchronized
    fun region(pack: String, sha256: String?): LiveRegion {
        regions[pack]?.let { return it }
        AnchorCases.setUp()
        val file = if (pack == "bundled") AnchorCases.bundled else File(Likeness.packs, "$pack.atlas")
        if (!file.isFile) throw MissingPack(pack, file)
        if (sha256 != null) {
            val got = sha256(file)
            check(got == sha256) { "the pack ${file.path} is $got, the case asks for $sha256" }
        }
        val r = PackReader.open(file).use { LiveEngine.open(it, sha256 ?: if (pack == "bundled") "bundled" else "${file.length()}") }
        regions[pack] = r
        return r
    }

    /** The map's view [case] names, in [region]. */
    fun view(case: Case, region: LiveRegion): LiveView {
        val h = case.size.frameH
        val f = region.frame
        return when (val c = case.camera ?: error("case '${case.name}' has no camera")) {
            is CameraSpec.View -> if (c.name == "home") {
                Cameras.home(f::lat, f::lon, h)
            } else {
                val k = region.ladder.knots.firstOrNull { it.name == c.name } ?: error("region ${region.id} has no view ${c.name}")
                EngineVersion.knotView(f, k, case.size.frameW, h)
            }
            is CameraSpec.Sweep -> Cameras.sweep(c.of, region.bbox ?: error("region ${region.id} has no box to sweep"), h, c.seed)[c.k]
            is CameraSpec.Path -> Cameras.path(c.path, c.step, c.steps, Cameras.home(f::lat, f::lon, h))
            is CameraSpec.Explicit -> LiveView(c.lat, c.lon, app.atlas.render.live.LiveCamera.zoom(c.lat, c.distance, h.toDouble()), c.bearing, c.pitch, case.size.frameW, h, c.cyDp ?: 440.0)
        }
    }

    /** The engine's moment for [case] in [region]. */
    fun moment(case: Case, region: LiveRegion): Moment = when (val m = case.moment ?: error("case '${case.name}' has no moment")) {
        is MomentSpec.Named -> Moment.Named(m.name)
        is MomentSpec.Live -> {
            val at = if (m.instant.endsWith("Z") || Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(m.instant)) java.time.OffsetDateTime.parse(m.instant).toInstant()
            else java.time.LocalDateTime.parse(m.instant).atZone(region.zone).toInstant()
            Moment.Live(at, when (m.forecast) {
                null -> null
                "recorded" -> recorded(region.zone)
                else -> throw IllegalArgumentException("case '${case.name}': no forecast '${m.forecast}' (recorded, or none)")
            })
        }
    }

    /** The engine's own record of Open-Meteo's answer for the Westerkerk (its `/wx/ams.json`, local times) as forecast hours. */
    @Suppress("UNCHECKED_CAST")
    fun recorded(zone: java.time.ZoneId): List<WxHour> {
        val text = Amsterdam::class.java.getResourceAsStream("/wx/ams.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val h = (PyJson.loads(text) as Map<String, Any?>)["hourly"] as Map<String, List<Any?>>
        fun d(k: String, i: Int) = (h.getValue(k)[i] as Number).toDouble()
        return h.getValue("time").indices.map { i ->
            val t = java.time.LocalDateTime.parse(h.getValue("time")[i] as String).atZone(zone).toInstant()
            WxHour(t, d("cloud_cover", i), d("precipitation", i), d("wind_speed_10m", i), d("wind_direction_10m", i), d("wind_gusts_10m", i),
                d("temperature_2m", i), d("pressure_msl", i), d("visibility", i), (h.getValue("weather_code")[i] as Number).toInt(),
                d("cloud_cover_low", i), d("relative_humidity_2m", i))
        }
    }

    /** Renders [case] under a world tape (sealed before it is returned). */
    fun render(case: Case): Made {
        val region = region(case.pack ?: error("case '${case.name}' has no pack"), case.packSha256)
        val view = view(case, region)
        val moment = moment(case, region)
        val t0 = System.nanoTime()
        val placed = LiveEngine.place(region, view)
        val tape = WorldTape(Amsterdam.cameraOf(placed.cfg))
        val r = WorldTape.recording(tape) { LiveEngine.render(region, view, moment) } ?: error("the render of '${case.name}' was cancelled")
        val theme = LiveEngine.lock.withLock { LinkedHashMap(Amsterdam.THEMES[r.themeKey] ?: emptyMap<String, Any?>()) }
        tape.seal()
        check(tape.foreignCameras == 0) { "case '${case.name}': the render drew with a camera other than its frame's (${tape.foreignCameras})" }
        return Made(case, region, view, r, tape, theme, (System.nanoTime() - t0) / 1_000_000)
    }

    /** The SHA-256 of [file], hex. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The SHA-256 of [bytes], hex. */
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
