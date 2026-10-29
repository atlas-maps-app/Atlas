package app.atlas.mapcorecheck.style

import app.atlas.mapcore.state.CameraWriter
import app.atlas.mapcore.state.ThemeBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer

/**
 * The layouts Kotlin shares with the core (plan-vector T9, K5): the theme block and the camera state are defined once,
 * in C++ (`CP/style/Theme.hpp`, `CameraState.hpp`); Kotlin's writers must lay every field where the core's own offset
 * table puts it, and a state written by [CameraWriter] must read back, field by field, as the core reads it. The
 * writer allocates nothing per frame.
 */
class LayoutTest {
    private fun table(name: String): List<Triple<String, Int, Int>> = StyleHost.layout(name).lines().filter { it.isNotBlank() }.map { l ->
        val (n, o, s) = l.split(' ')
        Triple(n, o.toInt(), s.toInt())
    }

    @Test
    fun theThemeBlockIsLaidOutAsTheCoreLaysItOut() {
        assertEquals(table("theme_block"), ThemeBlock.FIELDS)
        assertEquals(ThemeBlock.SIZE, ThemeBlock.FIELDS.last().let { it.second + it.third })
    }

    @Test
    fun theCameraStateIsLaidOutAsTheCoreLaysItOut() {
        assertEquals(table("camera_state"), CameraWriter.FIELDS)
        assertEquals(CameraWriter.LOCATION_AT, CameraWriter.KEEP_OUT_AT + CameraWriter.MAX_KEEP_OUTS * CameraWriter.KEEP_OUT_SIZE)
        assertEquals(CameraWriter.PARTS_AT, CameraWriter.LOCATION_AT + CameraWriter.LOCATION_SIZE)
        assertEquals(CameraWriter.SIZE, CameraWriter.PARTS_AT + 8)
    }

    @Test
    fun theShadersThemeIsEighteenVectors() {
        val t = table("shader_theme")
        assertEquals(18, t.size)
        assertEquals(288, t.last().second + t.last().third)
        assertTrue(t.all { it.third == 16 && it.second % 16 == 0 })
    }

    /** Every number of the layouts Kotlin writes, by its Kotlin name: versions, sizes, flag bits, moments, profiles. */
    private fun kotlinConstants(): Map<String, Int> = buildMap {
        put("ThemeBlock.VERSION", ThemeBlock.VERSION)
        put("ThemeBlock.SIZE", ThemeBlock.SIZE)
        put("ThemeBlock.NIGHT", ThemeBlock.NIGHT)
        put("ThemeBlock.WET", ThemeBlock.WET)
        put("ThemeBlock.WEATHER", ThemeBlock.WEATHER)
        put("ThemeBlock.OVERCAST", ThemeBlock.OVERCAST)
        put("ThemeBlock.SHADOW_BLUR", ThemeBlock.SHADOW_BLUR)
        put("ThemeBlock.CLOUD_SHADOWS", ThemeBlock.CLOUD_SHADOWS)
        put("ThemeBlock.WATER_GRADIENT", ThemeBlock.WATER_GRADIENT)
        put("ThemeBlock.DUSK", ThemeBlock.DUSK)
        put("ThemeBlock.WET_SET", ThemeBlock.WET_SET)
        put("ThemeBlock.GOLDEN", ThemeBlock.GOLDEN)
        put("ThemeBlock.TONE", ThemeBlock.TONE)
        for ((i, name) in ThemeBlock.MOMENTS.withIndex()) put("ThemeBlock.MOMENTS[$name]", i)
        put("CameraWriter.VERSION", CameraWriter.VERSION)
        put("CameraWriter.SIZE", CameraWriter.SIZE)
        put("CameraWriter.MAX_KEEP_OUTS", CameraWriter.MAX_KEEP_OUTS)
        put("CameraWriter.KEEP_OUT_SIZE", CameraWriter.KEEP_OUT_SIZE)
        put("CameraWriter.REST", CameraWriter.REST)
        put("CameraWriter.ENGINE_MODE", CameraWriter.ENGINE_MODE)
        put("CameraWriter.RADIUS_CUT", CameraWriter.RADIUS_CUT)
        put("CameraWriter.FOLLOW", CameraWriter.FOLLOW)
        put("CameraWriter.LOCATION", CameraWriter.LOCATION)
        put("CameraWriter.HEADING", CameraWriter.HEADING)
        put("CameraWriter.PART_GROUND", CameraWriter.PART_GROUND)
        put("CameraWriter.PART_WATER", CameraWriter.PART_WATER)
        put("CameraWriter.PART_CANOPY", CameraWriter.PART_CANOPY)
        put("CameraWriter.PART_GROUND_LINES", CameraWriter.PART_GROUND_LINES)
        put("CameraWriter.PART_SETTS", CameraWriter.PART_SETTS)
        put("CameraWriter.PART_GROUND_TEXT", CameraWriter.PART_GROUND_TEXT)
        put("CameraWriter.PART_QUAYS", CameraWriter.PART_QUAYS)
        put("CameraWriter.PART_SKY", CameraWriter.PART_SKY)
        put("CameraWriter.PART_BUILDING_SHADOWS", CameraWriter.PART_BUILDING_SHADOWS)
        put("CameraWriter.PART_PARK_SHADOWS", CameraWriter.PART_PARK_SHADOWS)
        put("CameraWriter.PART_CLOUD_SHADOWS", CameraWriter.PART_CLOUD_SHADOWS)
        put("CameraWriter.PART_REFLECTIONS", CameraWriter.PART_REFLECTIONS)
        put("CameraWriter.PART_RIPPLE", CameraWriter.PART_RIPPLE)
        put("CameraWriter.PART_GLITTER", CameraWriter.PART_GLITTER)
        put("CameraWriter.PART_RINGS", CameraWriter.PART_RINGS)
        put("CameraWriter.PART_GLINTS", CameraWriter.PART_GLINTS)
        put("CameraWriter.PART_WATER_STREAKS", CameraWriter.PART_WATER_STREAKS)
        put("CameraWriter.PART_LIT_STREETS", CameraWriter.PART_LIT_STREETS)
        put("CameraWriter.PART_WET_CORES", CameraWriter.PART_WET_CORES)
        put("CameraWriter.PART_LAMP_POOLS", CameraWriter.PART_LAMP_POOLS)
        put("CameraWriter.PART_LAMP_HALOS", CameraWriter.PART_LAMP_HALOS)
        put("CameraWriter.PART_LAMP_STREAKS", CameraWriter.PART_LAMP_STREAKS)
        put("CameraWriter.PART_RAIN", CameraWriter.PART_RAIN)
        put("CameraWriter.PART_GOLDEN_WASH", CameraWriter.PART_GOLDEN_WASH)
        put("CameraWriter.PART_WALLS", CameraWriter.PART_WALLS)
        put("CameraWriter.PART_ROOFS", CameraWriter.PART_ROOFS)
        put("CameraWriter.PART_ROOF_EDGES", CameraWriter.PART_ROOF_EDGES)
        put("CameraWriter.PART_WINDOWS", CameraWriter.PART_WINDOWS)
        put("CameraWriter.PART_WINDOW_DEPTH", CameraWriter.PART_WINDOW_DEPTH)
        put("CameraWriter.PART_FLAT_DETAIL", CameraWriter.PART_FLAT_DETAIL)
        put("CameraWriter.PART_CHURCH_WINDOWS", CameraWriter.PART_CHURCH_WINDOWS)
        put("CameraWriter.PART_MATERIAL_FACES", CameraWriter.PART_MATERIAL_FACES)
        put("CameraWriter.PART_HOIST_BEAMS", CameraWriter.PART_HOIST_BEAMS)
        put("CameraWriter.PART_KIT_DETAILS", CameraWriter.PART_KIT_DETAILS)
        put("CameraWriter.PART_DOORS", CameraWriter.PART_DOORS)
        put("CameraWriter.PART_HOUSE_NUMBERS", CameraWriter.PART_HOUSE_NUMBERS)
        put("CameraWriter.PART_TREES", CameraWriter.PART_TREES)
        put("CameraWriter.PART_LEAF_FILTERS", CameraWriter.PART_LEAF_FILTERS)
        put("CameraWriter.PART_FURNITURE", CameraWriter.PART_FURNITURE)
        put("CameraWriter.PART_REGION_ITEMS", CameraWriter.PART_REGION_ITEMS)
        put("CameraWriter.PART_LABELS", CameraWriter.PART_LABELS)
        put("CameraWriter.PART_ROUTES", CameraWriter.PART_ROUTES)
        put("CameraWriter.PART_MARKERS", CameraWriter.PART_MARKERS)
        put("CameraWriter.PART_LOCATION", CameraWriter.PART_LOCATION)
        put("CameraWriter.PART_FROSTED", CameraWriter.PART_FROSTED)
        put("CameraWriter.PART_SHADOW_BLUR", CameraWriter.PART_SHADOW_BLUR)
        put("CameraWriter.PARTS", CameraWriter.PARTS)
        put("CameraWriter.PROFILE_NONE", CameraWriter.PROFILE_NONE)
        put("CameraWriter.PROFILE_LIVING", CameraWriter.PROFILE_LIVING)
        put("CameraWriter.PROFILE_REGION", CameraWriter.PROFILE_REGION)
        put("CameraWriter.PROFILE_WILLIAMSBURG", CameraWriter.PROFILE_WILLIAMSBURG)
        put("CameraWriter.PROFILE_WORLD_BASE", CameraWriter.PROFILE_WORLD_BASE)
    }

    @Test
    fun theFlagBitsMomentsAndProfilesAreTheCoresOwn() {
        val core = StyleHost.layout("constants").lines().filter { it.isNotBlank() }.associate { l ->
            val i = l.lastIndexOf(' ')
            l.substring(0, i) to l.substring(i + 1).toInt()
        }
        // the same names on both sides, so a constant added to one side alone fails, and the same value for each
        assertEquals(core.keys.sorted(), kotlinConstants().keys.sorted())
        for ((name, value) in kotlinConstants()) assertEquals(name, core.getValue(name), value)
    }

    @Test
    fun aCameraStateReadsBackAsTheCoreReadsIt() {
        val b = CameraWriter.allocate()
        CameraWriter.header(b, 9_000_000_001L, CameraWriter.REST or CameraWriter.FOLLOW)
        CameraWriter.view(b, 52.374_6, 4.884, 52.0, 46.0, 2300.0, 206.0, 440.0)
        CameraWriter.frame(b, 1080, 2400, 412, 916, 2.621_359_2)
        CameraWriter.pack(b, -0x5a1f_00c3_e2d4_b6a7L, CameraWriter.PROFILE_REGION)
        CameraWriter.revisions(b, 3, 1, 0)
        CameraWriter.keepOuts(b, 2)
        CameraWriter.keepOut(b, 0, 16f, 52f, 396f, 104f, 26f, 24f, 1.8f, true)
        CameraWriter.keepOut(b, 1, 16f, 840f, 396f, 896f, 28f, 0f, 1f, false)
        val got = parse(StyleHost.cameraDump(b))
        fun bits(d: Double) = "%016x".format(java.lang.Double.doubleToRawLongBits(d))
        val want = linkedMapOf(
            "version" to "3", "flags" to "9", "sequence" to "9000000001", "latitude" to bits(52.374_6), "longitude" to bits(4.884),
            "bearing" to bits(52.0), "pitch" to bits(46.0), "distance" to bits(2300.0), "principal_x" to bits(206.0),
            "principal_y" to bits(440.0), "density" to bits(2.621_359_2), "width_px" to "1080", "height_px" to "2400", "width_dp" to "412",
            "height_dp" to "916", "pack_id" to java.lang.Long.toUnsignedString(-0x5a1f_00c3_e2d4_b6a7L), "profile" to "2",
            "theme_revision" to "3", "labels_revision" to "1", "overlays_revision" to "0", "keep_out_count" to "2",
            "keep_out.0" to "%08x %08x %08x %08x %08x %d %d %d".format(16f.toRawBits(), 52f.toRawBits(), 396f.toRawBits(), 104f.toRawBits(), 26f.toRawBits(), 384, 180, 1),
            "keep_out.1" to "%08x %08x %08x %08x %08x %d %d %d".format(16f.toRawBits(), 840f.toRawBits(), 396f.toRawBits(), 896f.toRawBits(), 28f.toRawBits(), 0, 100, 0),
        )
        assertEquals(want, got)
        // fewer shapes: the slots past them are cleared, so the core accepts the state (a stale shape would be refused)
        CameraWriter.keepOuts(b, 1)
        assertEquals("1", parse(StyleHost.cameraDump(b))["keep_out_count"])
        // a state the core refuses says why: a reserved field, and values out of their ranges
        b.putInt(CameraWriter.RESERVED_AT, 7)
        assertEquals("reserved", parse(StyleHost.cameraDump(b))["error"])
        b.putInt(CameraWriter.RESERVED_AT, 0)
        CameraWriter.view(b, 100.0, 4.884, 52.0, 46.0, 2300.0, 206.0, 440.0)
        assertEquals("target", parse(StyleHost.cameraDump(b))["error"])
        CameraWriter.view(b, 52.374_6, 4.884, 52.0, -10.0, 2300.0, 206.0, 440.0)
        assertEquals("view", parse(StyleHost.cameraDump(b))["error"])
        CameraWriter.view(b, 52.374_6, 4.884, 52.0, 46.0, 2300.0, 206.0, 440.0)
        CameraWriter.frame(b, 1080, 2400, 7, 916, 2.621_359_2)
        assertEquals("frame", parse(StyleHost.cameraDump(b))["error"])
        CameraWriter.frame(b, 1080, 2400, 412, 916, 2.621_359_2)
        assertTrue("error" !in parse(StyleHost.cameraDump(b)))
        // where the user is: read back as written, with its heading; a stale one without the flag is refused
        CameraWriter.header(b, 9_000_000_002L, CameraWriter.REST or CameraWriter.locationFlags(271.5f))
        CameraWriter.location(b, 52.374_66, 4.884_05, 12.5f, 271.5f)
        assertEquals(
            "%s %s %08x %08x".format(bits(52.374_66), bits(4.884_05), 12.5f.toRawBits(), 271.5f.toRawBits()),
            parse(StyleHost.cameraDump(b))["location"],
        )
        CameraWriter.header(b, 9_000_000_003L, CameraWriter.REST)
        assertEquals("reserved", parse(StyleHost.cameraDump(b))["error"])
        CameraWriter.noLocation(b)
        assertTrue("error" !in parse(StyleHost.cameraDump(b)))
        // the parts the map leaves out: read back as written, the last one too; one past them refused
        CameraWriter.parts(b, (1L shl CameraWriter.PART_RAIN) or (1L shl CameraWriter.PART_FROSTED))
        assertEquals("%016x".format((1L shl 22) or (1L shl 44)), parse(StyleHost.cameraDump(b))["parts"])
        b.putLong(CameraWriter.PARTS_AT, 1L shl CameraWriter.PARTS)
        assertEquals("parts", parse(StyleHost.cameraDump(b))["error"])
        CameraWriter.parts(b, 0L)
        assertTrue("parts" !in parse(StyleHost.cameraDump(b)))
    }

    @Test
    fun aKeepOutShapesFadeReadsBackAsTheCoreReadsIt() {
        // bit 0 frosted, bits 1-7 the chrome's fade (0 opaque to 127 gone): the core draws the glass at the chrome's alpha
        val b = CameraWriter.allocate()
        CameraWriter.header(b, 1L, 0)
        CameraWriter.view(b, 52.374_6, 4.884, 0.0, 0.0, 2300.0, 206.0, 440.0)
        CameraWriter.frame(b, 1080, 2400, 412, 916, 2.621_359_2)
        CameraWriter.keepOuts(b, 3)
        CameraWriter.keepOut(b, 0, 16f, 52f, 396f, 104f, 26f, 24f, 1.8f, true, alpha = 0.5f)
        CameraWriter.keepOut(b, 1, 16f, 120f, 396f, 170f, 25f, 24f, 1.8f, true, alpha = 0f)
        CameraWriter.keepOut(b, 2, 16f, 840f, 396f, 896f, 28f, 0f, 1f, false, alpha = 0.25f)
        val got = parse(StyleHost.cameraDump(b))
        assertTrue("error" !in got)
        assertEquals(listOf(1 or (64 shl 1), 1 or (127 shl 1), 95 shl 1), (0 until 3).map { got.getValue("keep_out.$it").split(' ').last().toInt() })
    }

    @Test
    fun writingTheCameraAllocatesNothing() {
        val b = CameraWriter.allocate()
        val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        fun frame(i: Int) {
            CameraWriter.header(b, i.toLong(), if (i % 2 == 0) CameraWriter.REST else 0)
            CameraWriter.view(b, 52.37 + i * 1e-7, 4.88, i * 0.1, 40.0, 430.0, 206.0, 440.0)
            CameraWriter.frame(b, 1080, 2400, 412, 916, 2.6214)
            CameraWriter.revisions(b, i, i, i)
            CameraWriter.keepOuts(b, 2)
            CameraWriter.keepOut(b, 0, 16f, 52f, 396f, 104f, 26f, 24f, 1.8f, true)
            CameraWriter.keepOut(b, 1, 16f, 840f, 396f, 896f, 28f, 24f, 1.8f, true)
        }
        for (i in 0 until 20_000) frame(i) // warm up: the JIT compiles the writer
        val id = Thread.currentThread().id
        val before = mx.getThreadAllocatedBytes(id)
        for (i in 0 until 100_000) frame(i)
        val after = mx.getThreadAllocatedBytes(id)
        // the measurement itself costs nothing either way; anything per frame would show as megabytes
        assertTrue("allocated ${after - before} bytes over 100,000 frames", after - before < 1024)
    }
}

/** `name=value` lines as a map. */
internal fun parse(text: String): LinkedHashMap<String, String> {
    val m = LinkedHashMap<String, String>()
    for (l in text.lines()) {
        if (l.isBlank()) continue
        val i = l.indexOf('=')
        m[l.substring(0, i)] = l.substring(i + 1)
    }
    return m
}

/**
 * The host build's style entry points (`CP/jni/TestStyle.cpp`), from `libatlasmap_host` at the path the system property
 * `atlas.mapcore.host` names (the test task sets it to the host build's library).
 */
internal object StyleHost {
    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
    }

    /** The theme block as the core reads it (`name=value` lines), or `error=<why>`. */
    @JvmStatic external fun themeDump(block: ByteBuffer): String

    /** The camera state as the core reads it (`name=value` lines), or `error=<why>`. */
    @JvmStatic external fun cameraDump(state: ByteBuffer): String

    /**
     * A shared layout's table, a line each: `name offset size` for theme_block, camera_state and shader_theme, `name
     * value` for constants (the layouts' versions, sizes, flag bits, moments and profiles).
     */
    @JvmStatic external fun layout(which: String): String

    /**
     * light.slang's exact light as C++ (the twin) over [inputs] (eight doubles each: base rgb, the sun bit, the normal's
     * xyz, the theme's index) lit by the themes in [blocks] (256 bytes each): per input tohex packed r | g << 8 | b << 16,
     * and into [values] the light before rounding, three doubles each.
     */
    @JvmStatic external fun lightTwin(inputs: DoubleArray, blocks: ByteBuffer, values: DoubleArray): IntArray

    /** The same on the host's Vulkan device (lavapipe); throws with the reason ("unavailable: ...") when it cannot run. */
    @JvmStatic external fun lightGpu(inputs: DoubleArray, blocks: ByteBuffer, values: DoubleArray): IntArray

    /** light_f32 (binary32) as C++ on the inputs rounded to float, as [lightTwin] returns its results. */
    @JvmStatic external fun lightTwinF32(inputs: DoubleArray, blocks: ByteBuffer, values: DoubleArray): IntArray

    /** light_f32 on the host's Vulkan device, compiled as a phone would run it. */
    @JvmStatic external fun lightGpuF32(inputs: DoubleArray, blocks: ByteBuffer, values: DoubleArray): IntArray

    /** The device the last GPU run used. */
    @JvmStatic external fun gpuDevice(): String

    /** light_f32's bound against the port, in levels (`CP/style/Light.hpp`). */
    @JvmStatic external fun lightF32Bound(): Double
}
