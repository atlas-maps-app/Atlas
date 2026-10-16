package app.atlas.packer

import app.atlas.anchor.AnchorCodec
import app.atlas.anchor.AnchorCompiler
import app.atlas.anchor.AnchorHead
import app.atlas.anchor.AnchorKind
import app.atlas.anchor.AnchorMeta
import app.atlas.anchor.AnchorSource
import app.atlas.render.Amsterdam
import app.atlas.render.data.PackReader
import app.atlas.render.live.EngineVersion
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveRender
import app.atlas.render.live.LiveView
import app.atlas.render.live.Moment
import app.atlas.render.live.WorldTape
import app.atlas.render.py.PyCompat
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * `packer anchors`: GPU anchors (plan-gpu D2) made on the JVM through the same engine port and anchor compiler the
 * phone runs, so an anchor made here is the one the phone would make for the same camera, data, moment and frame.
 *
 * `--bundled` makes the app's bundled set (G4): the Westerkerk home (`amsterdam.py`'s main camera) at its four
 * moments, and its backdrop from 1.3 km at sunny and night, each on the phones' two common map frames (412 × 916 and
 * 412 × 893), from the app's bundled pack, with the index `bundled.json`. Gradle builds them into the APK's assets
 * (`:app:generateBundledAnchors`); they are not committed.
 *
 * Each anchor's render is made twice, without and with the world tape, and the anchor is refused unless the two are
 * md5-identical: recording must never change what the engine draws.
 */
object Anchors {
    const val USAGE = """packer anchors --bundled <pack.atlas> <out-dir> [--work DIR] [--quiet]
      The app's bundled anchors from the Westerkerk's pack: westerkerk-{sunny,grey,wet,night}-<H>.anchor and
      westerkerk-far-{sunny,night}-<H>.anchor for H = 916 and 893, and bundled.json, their index. --work is where the
      engine unpacks the pack (default: a temporary folder, deleted after). --quiet prints warnings only.
  packer anchors --renders …
      Published anchors of the renders' named views: plan-gpu G13, not built yet."""

    /** The map frame's width, CSS px (`BoardScale`: the app's map is always 412 dp wide). */
    const val WIDTH = 412

    /** The bundled frame heights, CSS px: phones of 1080 × 2400 and of 1080 × 2340 (and 1440 × 3120) pixels. */
    val HEIGHTS = listOf(916, 893)

    /** The backdrop's camera distance, metres (plan-gpu D9: the bundled far anchor at 1.3 km). */
    const val FAR_DIST = 1300.0

    /** The living map's view the bundled set stands on: `amsterdam.py`'s main camera. */
    const val HOME_VIEW = "amsterdam"

    /** The moments of the home anchors, and of the backdrop. */
    val HOME_MOMENTS = listOf("sunny", "grey", "wet", "night")
    val FAR_MOMENTS = listOf("sunny", "night")

    /**
     * One anchor of a set: its [name] (the file is `<name>-<height>.anchor`), its [role] ([HOME] or [BACKDROP]), its
     * moment, the frame's height, and the camera's distance (null: the view's own).
     */
    class Spec(val name: String, val role: String, val moment: String, val height: Int, val dist: Double?) {
        val file: String get() = "$name-$height.anchor"
        val kind: AnchorKind get() = if (role == BACKDROP) AnchorKind.BACKDROP else AnchorKind.BUNDLED

        companion object {
            const val HOME = "home"
            const val BACKDROP = "backdrop"
        }
    }

    /** The bundled set, in the order it is made and listed. */
    val BUNDLED: List<Spec> = HEIGHTS.flatMap { h ->
        HOME_MOMENTS.map { Spec("westerkerk-$it", Spec.HOME, it, h, null) } + FAR_MOMENTS.map { Spec("westerkerk-far-$it", Spec.BACKDROP, it, h, FAR_DIST) }
    }

    /**
     * One anchor made: its [spec] and [file], the file's size and SHA-256, the md5 of its render ([EngineVersion.md5]),
     * the header as the file reads back, its point and primitive counts, the region view its camera snapped to (null
     * between views) and the theme it was drawn in.
     */
    class Made(
        val spec: Spec, val file: File, val bytes: Long, val sha256: String, val renderMd5: String,
        val head: AnchorHead, val points: Int, val primitives: Int, val view: String?, val themeKey: String,
    )

    /** What a set's build made, and what it found wrong without refusing (the engine version being stale). */
    class Result(val made: List<Made>, val warnings: List<String>, val index: File, val millis: Long)

    /** `packer anchors …` as its own program (Gradle runs it so): [args] without the command word. */
    @JvmStatic
    fun main(args: Array<String>) {
        val code = try {
            run(listOf("anchors") + args, ::println)
        } catch (e: UsageException) {
            System.err.println(e.message)
            System.err.println()
            System.err.println(USAGE)
            2
        } catch (e: Exception) {
            System.err.println("packer anchors: ${e.message ?: e}")
            1
        }
        exitProcess(code)
    }

    /** Runs `anchors …` ([args] start with the command word); [out] gets the report lines. Returns the exit code. */
    fun run(args: List<String>, out: (String) -> Unit): Int {
        if (args.firstOrNull() != "anchors") throw UsageException("usage: $USAGE")
        val positional = ArrayList<String>()
        val options = LinkedHashMap<String, String?>()
        var i = 1
        while (i < args.size) {
            val s = args[i]
            if (s.startsWith("--")) {
                if (s == "--work") {
                    if (i + 1 >= args.size) throw UsageException("$s needs a value")
                    options[s] = args[++i]
                } else options[s] = null
            } else positional += s
            i++
        }
        val unknown = options.keys - setOf("--bundled", "--renders", "--work", "--quiet")
        if (unknown.isNotEmpty()) throw UsageException("unknown option ${unknown.joinToString()}; usage: $USAGE")
        if ("--renders" in options) throw UsageException("anchors --renders (published anchors of the renders' named views) comes with plan-gpu G13")
        if ("--bundled" !in options) throw UsageException("usage: $USAGE")
        if (positional.size != 2) throw UsageException("anchors --bundled takes the pack and the output folder; usage: $USAGE")
        val pack = File(positional[0])
        if (!pack.isFile) throw UsageException("no pack $pack")
        val quiet = "--quiet" in options
        val log: (String) -> Unit = if (quiet) { _ -> } else out
        val r = bundled(pack, File(positional[1]), options["--work"]?.let(::File), log)
        for (w in r.warnings) System.err.println("packer anchors: warning: $w")
        log("${r.made.size} anchors, %.2f MB, in %.1f s; engine ${EngineVersion.ID}; ${r.index.path}".format(r.made.sumOf { it.bytes } / 1e6, r.millis / 1e3))
        return 0
    }

    /**
     * Makes the bundled set from [pack] into [out] (its earlier `.anchor` files and index are removed first), with the
     * engine's files unpacked under [work] (a temporary folder, deleted after, when null). [log] gets one line per anchor.
     */
    fun bundled(pack: File, out: File, work: File? = null, log: (String) -> Unit = {}): Result {
        val t0 = System.nanoTime()
        out.mkdirs()
        out.listFiles { f -> f.name.endsWith(".anchor") || f.name == INDEX }?.forEach { it.delete() }
        val packSha = sha256(pack.readBytes())
        val dir = work ?: Files.createTempDirectory("atlas-anchors").toFile()
        val workBefore = LiveEngine.workDir
        val minorBefore = PyCompat.minor
        // the port as the phone runs it: CPython 3.9's last bits (the app never changes it)
        PyCompat.minor = 9
        LiveEngine.workDir = dir
        val made = ArrayList<Made>()
        val warnings = ArrayList<String>()
        var region: LiveRegion? = null
        try {
            val r = PackReader.open(pack).use { LiveEngine.open(it, hex(packSha)) }
            region = r
            require(r.kind == LiveRegion.Kind.LIVING) { "${pack.name} is not the living map's pack (region ${r.id}, ${r.kind})" }
            for (spec in BUNDLED) made += make(r, spec, packSha, out, warnings, log)
        } finally {
            region?.let { LiveEngine.forget(it) }
            LiveEngine.workDir = workBefore
            PyCompat.minor = minorBefore
            if (work == null) dir.deleteRecursively()
        }
        val index = File(out, INDEX)
        index.writeText(Manifest.pretty(index(pack, packSha, made)) + "\n")
        return Result(made, warnings, index, (System.nanoTime() - t0) / 1_000_000)
    }

    /** The map's view of [spec] over the living [region]: its home camera, or that camera standing back to [Spec.dist]. */
    fun view(region: LiveRegion, spec: Spec): LiveView {
        val k = region.ladder.knots.firstOrNull { it.name == HOME_VIEW } ?: error("region ${region.id} has no view $HOME_VIEW")
        return EngineVersion.knotView(region.frame, k, WIDTH, spec.height, spec.dist ?: k.dist)
    }

    private fun make(region: LiveRegion, spec: Spec, packSha: ByteArray, out: File, warnings: MutableList<String>, log: (String) -> Unit): Made {
        val view = view(region, spec)
        val moment = Moment.Named(spec.moment)
        val t0 = System.nanoTime()
        val plain = LiveEngine.render(region, view, moment) ?: error("${spec.file}: the render was cancelled")
        val t1 = System.nanoTime()
        val placed = LiveEngine.place(region, view)
        val tape = WorldTape(Amsterdam.cameraOf(placed.cfg))
        val taped = WorldTape.recording(tape) { LiveEngine.render(region, view, moment) } ?: error("${spec.file}: the render was cancelled")
        val t2 = System.nanoTime()
        val md5 = checkTape(plain, taped, spec.file)
        stale(region.id, spec, plain.view, md5)?.let { warnings += it }
        val meta = AnchorMeta(regionId = region.id, kind = spec.kind, engineVersion = EngineVersion.ID, sourceHash = packSha)
        val src = AnchorSource.of(taped, tape, meta)
        val t3 = System.nanoTime()
        val anchor = AnchorCompiler.compile(src)
        val t4 = System.nanoTime()
        val bytes = AnchorCodec.encode(anchor)
        val t5 = System.nanoTime()
        // read back as the phone will: the file must decode to this anchor's own header
        val back = AnchorCodec.decode(bytes)
        val t6 = System.nanoTime()
        check(back.engineVersion == EngineVersion.ID && back.head.kind == spec.kind && back.head.camera.height == spec.height.toDouble() && back.head.moment == taped.moment) {
            "${spec.file} does not read back as written"
        }
        val file = File(out, spec.file)
        file.writeBytes(bytes)
        fun ms(a: Long, b: Long) = (b - a) / 1_000_000
        log(
            "%-27s render %4d ms, taped %4d ms, parse %3d ms, compile %3d ms, encode %3d ms, decode %3d ms; %6.2f MB on disk, %5.2f MB in memory; %d points, %d primitives".format(
                spec.file, ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t3, t4), ms(t4, t5), ms(t5, t6), bytes.size / 1e6, anchor.bytes() / 1e6, anchor.points.size, anchor.prims.size,
            ),
        )
        return Made(spec, file, bytes.size.toLong(), hex(sha256(bytes)), md5, back.head, back.points.size, back.prims.size, taped.view, taped.themeKey)
    }

    /**
     * The md5 of [plain]'s layers ([EngineVersion.md5]), after checking that [taped], the same render made under the
     * world tape, is identical to it, layers and relief raster; an anchor of a render the tape changed is refused.
     */
    fun checkTape(plain: LiveRender, taped: LiveRender, what: String): String {
        val a = EngineVersion.md5(plain)
        val b = EngineVersion.md5(taped)
        require(a == b) { "$what: the render under the world tape differs from the one without (md5 $b, not $a); the anchor is refused" }
        val ra = plain.raster; val rb = taped.raster
        require((ra == null) == (rb == null) && (ra == null || ra.data.contentEquals(rb!!.data) && ra.clip == rb.clip)) {
            "$what: the relief raster under the world tape differs from the one without; the anchor is refused"
        }
        return a
    }

    /**
     * A warning when [spec]'s render (of region [regionId], snapped to [view], with layers of md5 [md5]) is one of
     * [EngineVersion]'s fixed renders and its md5 is not the declared one: anchors made now would carry a stale version.
     */
    internal fun stale(regionId: String, spec: Spec, view: String?, md5: String): String? {
        if (spec.dist != null || view != HOME_VIEW) return null
        val f = EngineVersion.FIXED.firstOrNull {
            it.region == regionId && it.view == HOME_VIEW && it.moment == spec.moment && it.width == WIDTH && it.height == spec.height
        } ?: return null
        val declared = EngineVersion.OUTPUTS[f.name]
        if (declared == md5) return null
        return "${spec.file}: the engine draws ${f.name} with md5 $md5, but EngineVersion declares $declared; the version is stale " +
            "(run render's EngineVersionTest, and bump EngineVersion.OUTPUTS)"
    }

    private fun index(pack: File, packSha: ByteArray, made: List<Made>) = buildJsonObject {
        put("engine", EngineVersion.ID)
        put("format", AnchorCodec.VERSION)
        putJsonObject("pack") {
            put("file", pack.name)
            put("sha256", hex(packSha))
        }
        putJsonArray("anchors") {
            for (m in made) addJsonObject {
                val s = m.spec
                val cam = m.head.camera
                put("file", s.file)
                put("role", s.role)
                put("kind", s.kind.name.lowercase())
                put("moment", s.moment)
                put("theme", m.themeKey)
                put("width", WIDTH)
                put("height", s.height)
                put("view", m.view?.let(::JsonPrimitive) ?: JsonNull)
                putJsonObject("camera") {
                    putJsonArray("target") { add(cam.targetX); add(cam.targetY); add(cam.targetZ) }
                    put("yaw", cam.yaw)
                    put("pitch", cam.pitch)
                    put("dist", cam.dist)
                    put("fov", cam.fov)
                    put("cx", cam.cx)
                    put("cy", cam.cy)
                }
                put("radius", m.head.radius)
                put("ground_r", m.head.groundR)
                put("points", m.points)
                put("primitives", m.primitives)
                put("bytes", m.bytes)
                put("sha256", m.sha256)
                put("render_md5", m.renderMd5)
            }
        }
    }

    /** The index's file name, beside the anchors. */
    const val INDEX = "bundled.json"

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
