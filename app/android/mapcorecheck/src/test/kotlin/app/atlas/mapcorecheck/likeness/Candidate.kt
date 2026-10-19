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
        val b = ByteBuffer.allocateDirect(CASE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, MAGIC); b.putInt(4, ABI); b.putInt(8, entry.w); b.putInt(12, entry.h); b.putDouble(16, entry.density)
        b.putInt(24, clear)
        b.putInt(28, when (entry.mode) { "structural" -> 0; "full" -> 1; else -> 2 })
        b.putInt(32, if (entry.o["engine"]?.jsonPrimitive?.content == "false") 0 else 1)
        val v = entry.view
        val m = entry.moment
        b.putInt(36, if (m?.get("live") != null) 1 else 0)
        if (v != null) {
            b.putDouble(40, v.dbl("lat")); b.putDouble(48, v.dbl("lon")); b.putDouble(56, v.dbl("zoom")); b.putDouble(64, v.dbl("bearing"))
            b.putDouble(72, v.dbl("pitch")); b.putDouble(80, v.dbl("cyDp")); b.putInt(88, v.int("widthDp")); b.putInt(92, v.int("heightDp"))
        }
        put(b, 96, (m?.get("named") ?: m?.get("live"))?.let { (it as JsonPrimitive).content } ?: "")
        put(b, 160, entry.o.strOrNull("pack") ?: "")
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
        val pixels = ByteBuffer.allocateDirect(w * h * 4)
        val owners = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.LITTLE_ENDIAN)
        val r = TestCases.render(TestCases.caseBuffer(entry), CaseState.camera(entry), CaseState.theme(entry), pixels, owners)
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

/**
 * The camera state and the theme block of a case, as the app writes them for the core (plan-vector T9): the camera
 * through CameraWriter (at rest; engine mode unless the case says otherwise), the moment's theme through ThemeBlock from
 * the port's own THEMES after the case's region is loaded, resolved as LiveEngine.render resolves it (a named moment,
 * or the live one installed for its instant).
 */
internal object CaseState {
    /** The case's camera state. */
    fun camera(entry: RefEntry): ByteBuffer {
        val v = entry.view ?: error("case '${entry.name}' has no view")
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
    private fun region(entry: RefEntry): LiveRegion = when (val pack = entry.o.strOrNull("pack") ?: "bundled") {
        "bundled" -> AnchorCases.westerkerk()
        "grachten" -> AnchorCases.grachten() ?: error("the canal ring's pack is not on this machine")
        else -> error("no region for the pack '$pack'")
    }

    /** The case's theme block. */
    @Suppress("UNCHECKED_CAST")
    fun theme(entry: RefEntry): ByteBuffer {
        val region = region(entry)
        val m = entry.moment ?: error("case '${entry.name}' has no moment")
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
                val th = Amsterdam.THEMES[named] ?: error("the engine has no moment '$named'")
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
