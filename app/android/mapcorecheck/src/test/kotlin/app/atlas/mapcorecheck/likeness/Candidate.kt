package app.atlas.mapcorecheck.likeness

import app.atlas.anchor.AnchorCases
import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.RenderOptions
import app.atlas.render.live.LiveCamera
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveMoment
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.withLock

/** A candidate's frame of a case: opaque ARGB pixels (null when it drew nothing), and its owner map and labels when it gives them. */
class CandidateFrame(
    val source: String,
    val w: Int,
    val h: Int,
    val px: IntArray?,
    val owners: OwnerFrame?,
    val labels: List<Label>?,
    /** What drew it, or why nothing did. */
    val note: String,
    val millis: Long = 0,
)

/** Something that draws the cases: the core's host build, or the stub. */
interface Candidate {
    val name: String
    fun draw(entry: RefEntry): CandidateFrame
}

/**
 * The JNI entry points of `CP/jni/TestCases.cpp` in the core's host build (`libatlasmap_host`, K0): one case drawn
 * offscreen on lavapipe through the map's own frame (K7: every registered pass over the world of the case's render
 * pack), at the camera state and theme block [CaseState] writes for it as the app writes them, with its owner map.
 */
internal object TestCases {
    /** The layout version of the case buffer; the library refuses another (2: the camera state and theme block beside it). */
    const val ABI = 2

    /** Bytes of a case. */
    const val CASE_BYTES = 256

    /** Little-endian "LKC1". */
    const val MAGIC = 0x31434B4C

    /** What [render] filled, as bits of its result: the pixels (RGBA8 rows), the owners (64-bit keys per pixel). */
    const val FILLED_PIXELS = 1
    const val FILLED_OWNERS = 2

    @JvmStatic external fun abi(): Int

    /** The passes the core draws a case with, comma-separated: the frame's registry. */
    @JvmStatic external fun passes(): String

    /** The folder the cases' render packs are in (`-Datlas.tiles`: the packer's bundled tiles). */
    @JvmStatic external fun tiles(dir: String)

    /** Why the last [render] on this thread failed. */
    @JvmStatic external fun lastError(): String

    /** The device the last case on this thread was drawn on: its name, Vulkan version, driver, and the validation layer. */
    @JvmStatic external fun device(): String

    /**
     * Draws [case] (a [CASE_BYTES] direct buffer, [caseBuffer]'s layout) at [camera] and [theme] (a camera state and a
     * theme block, [CaseState]'s) into [pixels] (w·h·4 bytes, R, G, B, A rows) and, when given, [owners] (w·h
     * little-endian u64: the owner's type in the top byte, its OSM id below; 0 for none). Returns the FILLED bits, or a
     * negative error: −1 a bad case, −2 no Vulkan device, −3 the device failed, −4 out of memory.
     */
    @JvmStatic external fun render(case: ByteBuffer, camera: ByteBuffer, theme: ByteBuffer, pixels: ByteBuffer, owners: ByteBuffer?): Int

    /**
     * The case buffer the core reads (little-endian): 0 magic, 4 version, 8 width px, 12 height px, 16 density (f64),
     * 24 clear colour (ARGB), 28 mode (0 structural, 1 full, 2 effect), 32 flags (bit 0: engine mode, the radius cut),
     * 36 moment kind (0 named, 1 live), 40 lat, 48 lon, 56 zoom, 64 bearing, 72 map pitch, 80 principal point (dp)
     * (f64 each), 88 width dp, 92 height dp, 96 the moment's name or instant (64 bytes, UTF-8, NUL-padded), 160 the pack
     * (64 bytes), 224 reserved.
     */
    fun caseBuffer(entry: RefEntry, clear: Int = 0xFFFFFFFF.toInt()): ByteBuffer {
        val v = entry.view
        val m = entry.moment
        return caseBuffer(
            entry.w, entry.h, entry.density, entry.mode, entry.o["engine"]?.jsonPrimitive?.content != "false",
            v?.let { LiveView(it.dbl("lat"), it.dbl("lon"), it.dbl("zoom"), it.dbl("bearing"), it.dbl("pitch"), it.int("widthDp"), it.int("heightDp"), it.dbl("cyDp")) },
            (m?.get("named") ?: m?.get("live"))?.let { (it as JsonPrimitive).content } ?: "", m?.get("live") != null,
            entry.o.strOrNull("pack") ?: "", clear,
        )
    }

    /** The case buffer of a frame of [w] × [h] px at [density], in [mode], of [view] at [moment] in [pack]. */
    fun caseBuffer(
        w: Int, h: Int, density: Double, mode: String, engine: Boolean, view: LiveView?, moment: String, live: Boolean,
        pack: String, clear: Int = 0xFFFFFFFF.toInt(),
    ): ByteBuffer {
        val b = ByteBuffer.allocateDirect(CASE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, MAGIC); b.putInt(4, ABI); b.putInt(8, w); b.putInt(12, h); b.putDouble(16, density)
        b.putInt(24, clear)
        b.putInt(28, when (mode) { "structural" -> 0; "full" -> 1; else -> 2 })
        b.putInt(32, if (engine) 1 else 0)
        b.putInt(36, if (live) 1 else 0)
        if (view != null) {
            b.putDouble(40, view.lat); b.putDouble(48, view.lon); b.putDouble(56, view.zoom); b.putDouble(64, view.bearing)
            b.putDouble(72, view.pitch); b.putDouble(80, view.cyDp); b.putInt(88, view.widthDp); b.putInt(92, view.heightDp)
        }
        put(b, 96, moment)
        put(b, 160, pack)
        return b
    }

    private fun put(b: ByteBuffer, at: Int, s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        require(bytes.size < 64) { "'$s' does not fit the case buffer" }
        for (i in 0 until 64) b.put(at + i, if (i < bytes.size) bytes[i] else 0)
    }
}

/**
 * The core's host build on lavapipe (T10): `libatlasmap_host` from `-Datlas.mapcore.host` (the path K3's build gives the
 * test task), the Vulkan driver from `VK_ICD_FILENAMES`. [load] says why it cannot be used when it cannot.
 */
class HostCore private constructor(val library: File, val passes: String) : Candidate {
    override val name: String get() = "core (host build, passes: $passes)"

    override fun draw(entry: RefEntry): CandidateFrame {
        val t0 = System.nanoTime()
        val w = entry.w; val h = entry.h
        // A case this machine cannot draw is reported unavailable, with why, never thrown out of the run: whether it
        // may fail the run is the gate's question.
        val state = try {
            CaseState.check(entry)?.let { throw CaseUnavailable(it) }
            CaseState.camera(entry) to CaseState.theme(entry)
        } catch (e: CaseUnavailable) {
            return CandidateFrame(name, w, h, null, null, null, "unavailable here: ${e.message}", (System.nanoTime() - t0) / 1_000_000)
        }
        val pixels = ByteBuffer.allocateDirect(w * h * 4)
        val owners = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.LITTLE_ENDIAN)
        val r = TestCases.render(TestCases.caseBuffer(entry), state.first, state.second, pixels, owners)
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (r < 0) return CandidateFrame(name, w, h, null, null, null, "the core failed: ${error(r)}: ${TestCases.lastError()}", ms)
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val o = i * 4
            px[i] = (0xFF shl 24) or ((pixels.get(o).toInt() and 0xFF) shl 16) or ((pixels.get(o + 1).toInt() and 0xFF) shl 8) or (pixels.get(o + 2).toInt() and 0xFF)
        }
        val own = if (r and TestCases.FILLED_OWNERS != 0) ownersOf(owners, w, h) else null
        return CandidateFrame(name, w, h, px, own, null, "drawn by the core's passes ($passes) on ${TestCases.device()}", ms)
    }

    companion object {
        /** The reason the host core is not there, when [load] returned null. */
        var unavailable: String = ""
            private set

        private var loaded: HostCore? = null

        /** The host build, loaded once; null when it is not built here or refuses (see [unavailable]). */
        @Synchronized
        fun load(): HostCore? {
            loaded?.let { return it }
            val path = System.getProperty("atlas.mapcore.host")?.takeIf { it.isNotBlank() }
            if (path == null) { unavailable = "no host build given (-Datlas.mapcore.host): the core's CMake host preset (K0) builds libatlasmap_host"; return null }
            val f = File(path)
            if (!f.isFile) { unavailable = "the host build is not at $path"; return null }
            return try {
                System.load(f.absolutePath)
                val abi = TestCases.abi()
                val tiles = System.getProperty("atlas.tiles")
                when {
                    abi != TestCases.ABI -> { unavailable = "the host build's case layout is $abi, this harness's ${TestCases.ABI}"; null }
                    tiles == null -> { unavailable = "no folder of render packs (-Datlas.tiles)"; null }
                    else -> { TestCases.tiles(tiles); HostCore(f, TestCases.passes()).also { loaded = it } }
                }
            } catch (e: UnsatisfiedLinkError) {
                unavailable = "the host build has no likeness entry points (CP/jni/TestCases.cpp): ${e.message}"
                null
            }
        }

        fun error(code: Int): String = when (code) {
            -1 -> "a bad case"
            -2 -> "no Vulkan device (lavapipe through VK_ICD_FILENAMES?)"
            -3 -> "the device failed"
            -4 -> "out of memory"
            else -> "error $code"
        }

        /** An owner map of 64-bit keys (type in the top byte, OSM id below), indexed as the reference sets index theirs. */
        fun ownersOf(b: ByteBuffer, w: Int, h: Int): OwnerFrame {
            val idx = IntArray(w * h)
            val table = ArrayList<Owner>()
            val byKey = HashMap<Long, Int>()
            for (i in 0 until w * h) {
                val k = b.getLong(i * 8)
                if (k == 0L) continue
                idx[i] = byKey.getOrPut(k) {
                    val type = when ((k ushr 56).toInt()) { 1 -> "n"; 2 -> "w"; 3 -> "r"; 4 -> "a"; else -> "o" }
                    table.add(Owner(type, k and 0x00FFFFFFFFFFFFFFL, "", "", 0)); table.size
                }
            }
            return OwnerFrame(Frame(w, h, idx), table)
        }
    }
}

/** A case the candidate cannot draw on this machine, and why. */
class CaseUnavailable(why: String) : Exception(why)

/**
 * The camera state and the theme block of a case, as the app writes them for the core (plan-vector T9): the camera
 * through CameraWriter (at rest; engine mode unless the case says otherwise), the moment's theme through ThemeBlock from
 * the port's own THEMES after the case's region is loaded, resolved as LiveEngine.render resolves it (a named moment,
 * or the live one installed for its instant). What a case lacks for them, or its pack missing here, is a
 * [CaseUnavailable].
 */
internal object CaseState {
    /**
     * Why [entry] cannot be drawn on this machine, or null: an effect probe (its scene is no camera's view: the frame
     * draws the probes once their effects are its own, D16's EFFECTS gate), a case with no view or no moment, a pack
     * this harness has no region for, or the pack's region or render pack not here.
     */
    fun check(entry: RefEntry): String? {
        if (entry.isProbe || entry.mode == "effect") return "'${entry.name}' is an effect probe, which the frame does not draw yet"
        if (entry.view == null) return "'${entry.name}' has no view"
        if (entry.moment == null) return "'${entry.name}' has no moment"
        val pack = entry.o.strOrNull("pack") ?: "bundled"
        when (pack) {
            "bundled" -> {}
            "grachten" -> if (!AnchorCases.grachtenPack.isFile) return "the canal ring's pack is not on this machine (${AnchorCases.grachtenPack})"
            else -> return "no region for the pack '$pack'"
        }
        val tiles = System.getProperty("atlas.tiles") ?: return "no folder of render packs (-Datlas.tiles)"
        val render = File(tiles, "${if (pack == "bundled") "amsterdam" else pack}.atlas")
        if (!render.isFile) return "the render pack of '$pack' is not on this machine ($render)"
        return null
    }

    /** The case's camera state. */
    fun camera(entry: RefEntry): ByteBuffer {
        val v = entry.view ?: throw CaseUnavailable("'${entry.name}' has no view")
        val lat = v.dbl("lat"); val lon = v.dbl("lon"); val heightDp = v.int("heightDp"); val widthDp = v.int("widthDp")
        val engine = entry.o["engine"]?.jsonPrimitive?.content != "false"
        val b = CameraWriter.allocate()
        CameraWriter.header(b, 1L, CameraWriter.REST or (if (engine) CameraWriter.ENGINE_MODE else 0))
        CameraWriter.view(b, lat, lon, v.dbl("bearing"), v.dbl("pitch"), LiveCamera.distance(lat, v.dbl("zoom"), heightDp.toDouble()), widthDp / 2.0, v.dbl("cyDp"))
        CameraWriter.frame(b, entry.w, entry.h, widthDp, heightDp, entry.density)
        CameraWriter.pack(b, 0L, CameraWriter.PROFILE_NONE)
        CameraWriter.revisions(b, 0, 0, 0)
        CameraWriter.keepOuts(b, 0)
        return b
    }

    /** The region of a case's pack. */
    fun region(pack: String): LiveRegion = when (pack) {
        "bundled" -> AnchorCases.westerkerk()
        "grachten" -> AnchorCases.grachten() ?: throw CaseUnavailable("the canal ring's pack is not on this machine (${AnchorCases.grachtenPack})")
        else -> throw CaseUnavailable("no region for the pack '$pack'")
    }

    /** The case's theme block. */
    fun theme(entry: RefEntry): ByteBuffer =
        theme(entry.o.strOrNull("pack") ?: "bundled", entry.moment ?: throw CaseUnavailable("'${entry.name}' has no moment"))

    /** The theme block of moment [m] (`{"named": …}` or `{"live": …}`) in [pack]'s region. */
    @Suppress("UNCHECKED_CAST")
    fun theme(pack: String, m: JsonObject): ByteBuffer {
        val region = region(pack)
        val (th, key) = LiveEngine.lock.withLock {
            // the region's own module state (its themes), as its render restores it
            LiveEngine.loadScene(region, if (region.kind == LiveRegion.Kind.LIVING) null else region.ladder.knots.first().name).state.restore()
            val daylight = RenderOptions().daylight
            fun live(at: java.time.Instant, hours: List<app.atlas.render.live.WxHour>?): String {
                LiveMoment.install(at, region.zone, hours, daylight)
                return LiveMoment.KEY
            }
            val named = (m["named"] as? JsonPrimitive)?.content
            val key = if (named != null) {
                val th = Amsterdam.THEMES[named] ?: throw CaseUnavailable("the engine has no moment '$named'")
                if (region.kind == LiveRegion.Kind.REGION && th["wx"] == null) {
                    val d = (th["date"] as List<Number>).map { it.toInt() }
                    val hm = (th["hhmm"] as List<Number>).map { it.toInt() }
                    live(java.time.LocalDateTime.of(d[0], d[1], d[2], hm[0], hm[1]).atZone(region.zone).toInstant(), null)
                } else named
            } else {
                val instant = (m["live"] as JsonPrimitive).content
                val at = if (instant.endsWith("Z") || Regex("[+-]\\d\\d:\\d\\d$").containsMatchIn(instant)) java.time.OffsetDateTime.parse(instant).toInstant()
                else java.time.LocalDateTime.parse(instant).atZone(region.zone).toInstant()
                live(at, null)
            }
            LinkedHashMap(Amsterdam.THEMES.getValue(key)) to key
        }
        val (az, el, sun) = Amsterdam.sunOf(th)
        val b = ThemeBlock.allocate()
        ThemeBlock.write(b, th, doubleArrayOf(sun.x, sun.y, sun.z), az, el, key == "golden", Amsterdam.TONE)
        return b
    }
}

/**
 * The stub: the oracle's own frames stand in for an engine (D16's planted failures "on the stub's frames"). Unplanted,
 * it gives each case's reference with its owner map and labels; with a plant, the oracle's planted frame of the base case.
 */
class Stub(val plant: PlantEntry? = null) : Candidate {
    override val name: String get() = if (plant == null) "stub" else "stub, planted: ${plant.name}"

    override fun draw(entry: RefEntry): CandidateFrame {
        val p = plant
        return if (p == null) CandidateFrame(name, entry.w, entry.h, entry.ref.px, entry.owners, entry.labels, "the oracle's own frame")
        else CandidateFrame(name, entry.w, entry.h, p.frame.px, p.owners, p.labels, p.detail)
    }
}

class CandidateTest {
    @Test
    fun `the case buffer carries the size, density, camera and moment where TestCases cpp reads them`() {
        val set = RefSetReader.open()
        val e = set.entries.getValue("home sunny S1")
        val b = TestCases.caseBuffer(e)
        assertEquals(TestCases.CASE_BYTES, b.capacity())
        assertEquals(TestCases.MAGIC, b.getInt(0)); assertEquals(TestCases.ABI, b.getInt(4))
        assertEquals(1080, b.getInt(8)); assertEquals(2400, b.getInt(12)); assertEquals(1080 / 412.0, b.getDouble(16), 0.0)
        assertEquals(0, b.getInt(28)); assertEquals(1, b.getInt(32))
        assertEquals(e.view!!.dbl("zoom"), b.getDouble(56), 0.0)
        assertEquals(412, b.getInt(88)); assertEquals(916, b.getInt(92))
        assertEquals('s'.code.toByte(), b.get(96)); assertEquals(0.toByte(), b.get(96 + "sunny".length))
        assertEquals("bundled", String(ByteArray(7) { b.get(160 + it) }))
    }

    @Test
    fun `owner keys from the core become an indexed map`() {
        val b = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.LITTLE_ENDIAN)
        b.putLong(0, (2L shl 56) or 123L); b.putLong(8, 0L); b.putLong(16, (1L shl 56) or 9L); b.putLong(24, (2L shl 56) or 123L)
        val m = HostCore.ownersOf(b, 2, 2)
        assertEquals(listOf(1, 0, 2, 1), m.frame.px.toList())
        assertEquals(listOf("w123", "n9"), m.owners.map { it.tag })
    }

    @Test
    fun `a case this machine cannot draw is unavailable, with why, never thrown`() {
        val dir = java.nio.file.Files.createTempDirectory("likeness-set").toFile()
        try {
            val size = "\"size\": {\"w\": 1080, \"h\": 2400, \"density\": 2.6213592233009706}"
            val view = "\"view\": {\"lat\": 52.37, \"lon\": 4.88, \"zoom\": 17.0, \"bearing\": 0.0, \"pitch\": 0.0, \"cyDp\": 458.0, \"widthDp\": 412, \"heightDp\": 916}"
            File(dir, "manifest.json").writeText(
                "{\"key\": \"k\", \"entries\": {" +
                    "\"probe mat S1\": {\"mode\": \"effect\", \"probe\": {\"effect\": \"mat\"}, $size}, " +
                    "\"elsewhere S1\": {\"mode\": \"structural\", \"pack\": \"nowhere\", $size, $view, \"moment\": {\"named\": \"sunny\"}}, " +
                    "\"no moment S1\": {\"mode\": \"structural\", $size, $view}}}",
            )
            val set = RefSetReader(dir)
            val probe = set.entries.getValue("probe mat S1")
            assertTrue(CaseState.check(probe)!!.contains("effect probe"))
            assertTrue(CaseState.check(set.entries.getValue("elsewhere S1"))!!.contains("no region for the pack 'nowhere'"))
            assertTrue(CaseState.check(set.entries.getValue("no moment S1"))!!.contains("no moment"))
            // Drawn by the host core when it is built here: reported, not thrown.
            val core = HostCore.load() ?: return
            val f = core.draw(probe)
            assertEquals(null, f.px)
            assertTrue(f.note, f.note.startsWith("unavailable here: "))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the host core draws a case when it is built here, and says why not otherwise`() {
        val core = HostCore.load()
        if (core == null) {
            println("no host core: ${HostCore.unavailable}")
            return
        }
        val set = RefSetReader.open()
        val e = set.entries.getValue("home sunny S1")
        val f = core.draw(e)
        println("host core: ${f.note}, ${f.millis} ms")
        assertTrue("it drew: ${f.note}", f.px != null)
        assertEquals(e.w * e.h, f.px!!.size)
    }
}
