package app.atlas.mapcorecheck.likeness

import app.atlas.anchor.AnchorCases
import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.RenderOptions
import app.atlas.render.live.LiveEngine
import app.atlas.render.live.LiveMoment
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.withLock

/**
 * The JNI entry points of `CP/jni/TestCases.cpp` in the core's host build (`libatlasmap_host`, K0): one case drawn
 * offscreen on lavapipe through the map's own frame (K7: every registered pass over the world of the case's render
 * pack), at a camera state and the theme block [CaseState] writes for it as the app writes them.
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
     *
     * The case buffer of a frame of [w] × [h] px at [density], in [mode], of [view] at [moment] in [pack]. */
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
class HostCore private constructor(val library: File, val passes: String) {
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
    }
}

/** A case the core cannot draw on this machine, and why. */
class CaseUnavailable(why: String) : Exception(why)

/**
 * A case's region and its theme block, as the app writes it for the core (plan-vector T9): the moment's theme through
 * ThemeBlock from the port's own THEMES after the case's region is loaded, resolved as LiveEngine.render resolves it (a
 * named moment, or the live one installed for its instant). A pack missing here is a [CaseUnavailable].
 */
internal object CaseState {
    /** The region of a case's pack. */
    fun region(pack: String): LiveRegion = when (pack) {
        "bundled" -> AnchorCases.westerkerk()
        "grachten" -> AnchorCases.grachten() ?: throw CaseUnavailable("the canal ring's pack is not on this machine (${AnchorCases.grachtenPack})")
        else -> throw CaseUnavailable("no region for the pack '$pack'")
    }

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
        ThemeBlock.write(b, th, doubleArrayOf(sun.x, sun.y, sun.z), az, el, key == "golden", Amsterdam.TONE, daylight = RenderOptions().daylight)
        return b
    }
}

class CaseBufferTest {
    @Test
    fun `the case buffer carries the size, density, camera and moment where TestCases cpp reads them`() {
        val view = LiveView(52.37, 4.88, 17.0, 52.0, 52.0, 412, 916, 440.0)
        val b = TestCases.caseBuffer(1080, 2400, 1080 / 412.0, "structural", true, view, "sunny", false, "bundled")
        assertEquals(TestCases.CASE_BYTES, b.capacity())
        assertEquals(TestCases.MAGIC, b.getInt(0)); assertEquals(TestCases.ABI, b.getInt(4))
        assertEquals(1080, b.getInt(8)); assertEquals(2400, b.getInt(12)); assertEquals(1080 / 412.0, b.getDouble(16), 0.0)
        assertEquals(0, b.getInt(28)); assertEquals(1, b.getInt(32)); assertEquals(0, b.getInt(36))
        assertEquals(52.37, b.getDouble(40), 0.0); assertEquals(17.0, b.getDouble(56), 0.0); assertEquals(440.0, b.getDouble(80), 0.0)
        assertEquals(412, b.getInt(88)); assertEquals(916, b.getInt(92))
        assertEquals('s'.code.toByte(), b.get(96)); assertEquals(0.toByte(), b.get(96 + "sunny".length))
        assertEquals("bundled", String(ByteArray(7) { b.get(160 + it) }))
    }

    @Test
    fun `the host core says why it cannot draw when it is not built here`() {
        if (HostCore.load() == null) assertTrue(HostCore.unavailable, HostCore.unavailable.isNotEmpty())
        else assertTrue(HostCore.load()!!.passes, HostCore.load()!!.passes.isNotEmpty())
    }
}
