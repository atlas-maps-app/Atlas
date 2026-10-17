package app.atlas.mapcorecheck.style

import app.atlas.mapcore.state.ThemeBlock
import app.atlas.render.Amsterdam
import app.atlas.render.City
import app.atlas.render.Regions
import app.atlas.render.V3
import app.atlas.render.live.LiveMoment
import app.atlas.render.py.Dict
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * light.slang (plan-vector D8, K5) against the port's own light() and tohex (`R/Amsterdam.kt`, `R/City.kt`), over
 * 100,000 random surfaces (colours as hx reads them and as the renders scale them, unit normals, sun bits) lit by 128
 * themes (the port's moments, live hours of the recorded forecast, and themes varied from them; TONE on and off), and
 * over the edges (every grey on walls, roofs and undersides in every theme, at night too, where integer colours meet
 * the port's ties at a half level, and TONE's highlights):
 * - the exact light, run as C++ (the twin) and as a compute entry on the host's Vulkan device (lavapipe), equals the
 *   port bit for bit: every value before rounding and every byte;
 * - light_f32, the binary32 rule for GPUs without shaderFloat64, as C++ and on the device, lies within its bound of
 *   the port's value, and its bytes are the port's wherever that value is farther than the bound from a half level.
 * The themes reach the core as the phone sends them: [ThemeBlock] from the port's dict, read by the core's own reader.
 */
class LightParityTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun place() = Regions.resetPlace()

        @AfterClass
        @JvmStatic
        fun restore() {
            Amsterdam.TONE = false
            Regions.resetPlace()
        }
    }

    /** One theme of the test: the port's dict with its sun vector, the sun's position for the block, and TONE. */
    private class Lit(val th: Dict, val azimuth: Double, val elevation: Double, val tone: Boolean)

    private fun hex(r: Random) = "#%06X".format(r.nextInt(1 shl 24))

    /** The themes: the port's six moments and eight live hours, each with TONE off and on, and 100 varied from them. */
    private fun themes(r: Random): List<Lit> {
        val base = ArrayList<Dict>()
        for (k in listOf("golden", "noon", "night", "grey", "sunny", "wet")) base += LinkedHashMap(Amsterdam.THEMES.getValue(k))
        val zone = ZoneId.of("Europe/Amsterdam")
        val hours = ThemeBlockTest.recordedHours()
        for (h in listOf(3, 8, 11, 14, 17, 19, 21, 30)) {
            LiveMoment.install(LocalDateTime.of(2026, 9, 30, 0, 20).plusHours(h.toLong()).atZone(zone).toInstant(), zone, hours, h % 2 == 0)
            base += LinkedHashMap(Amsterdam.THEMES.getValue(LiveMoment.KEY))
        }
        val out = ArrayList<Lit>()
        for (th in base) {
            val (az, el, l) = Amsterdam.sunOf(th)
            th["_L"] = l
            for (tone in listOf(false, true)) out += Lit(LinkedHashMap(th), az, el, tone)
        }
        while (out.size < 128) {
            val th: Dict = LinkedHashMap(base[r.nextInt(base.size)])
            th["night"] = r.nextDouble() < 0.2
            for (k in listOf("amb", "sun", "haze")) th[k] = hex(r)
            th["amb_k"] = 0.3 + 0.6 * r.nextDouble()
            th["sun_k"] = if (th["night"] == true) 0.0 else 1.2 * r.nextDouble()
            if (r.nextDouble() < 0.4) th["sky_k"] = 0.7 + r.nextDouble() else th.remove("sky_k")
            // a sun above the horizon from any side, or straight down at night (the render's own night vector), with
            // the sun's elevation on the same side of the horizon, as the render's sun_position gives it
            val night = th["night"] == true
            val v = if (night) V3(0.0, -1.0, 0.0) else {
                val x = r.nextGaussian(); val y = abs(r.nextGaussian()) + 1e-3; val z = r.nextGaussian()
                val l = sqrt(x * x + y * y + z * z)
                V3(x / l, y / l, z / l)
            }
            th["_L"] = v
            out += Lit(th, 180.0, if (night) -20.0 else 20.0, r.nextBoolean())
        }
        return out
    }

    /** The surfaces, as StyleHost takes them (eight doubles each), and the port's light for each. */
    private class Surfaces(val themes: List<Lit>) {
        val inputs = ArrayList<Double>()
        val port = ArrayList<V3>()
        var random = 0

        fun add(base: V3, n: V3, lit: Boolean, theme: Int) {
            val t = themes[theme]
            Amsterdam.TONE = t.tone
            port += Amsterdam.light(t.th, base, n, lit)
            inputs += listOf(base.x, base.y, base.z, if (lit) 1.0 else 0.0, n.x, n.y, n.z, theme.toDouble())
        }

        val size get() = port.size
    }

    private fun surfaces(themes: List<Lit>, r: Random): Surfaces {
        val s = Surfaces(themes)
        for (i in 0 until 100_000) {
            // colours as hx reads them, and scaled as the renders pass some (a window's reveal 0.55, a crown's dark 0.6)
            var base = V3(r.nextInt(256).toDouble(), r.nextInt(256).toDouble(), r.nextInt(256).toDouble())
            if (r.nextDouble() < 0.3) base = City.mulc(base, if (r.nextBoolean()) 0.55 else 0.6)
            val n = City.vnorm(V3(r.nextGaussian(), r.nextGaussian(), r.nextGaussian()))
            s.add(base, n, r.nextDouble() < 0.6, r.nextInt(themes.size))
        }
        s.random = s.size
        // every grey on a wall, a roof, an underside and a slope in every theme, lit and unlit
        val normals = listOf(V3(1.0, 0.0, 0.0), V3(0.0, 1.0, 0.0), V3(0.0, -1.0, 0.0), City.vnorm(V3(0.6, 0.48, 0.64)))
        for (t in themes.indices) for (g in 0 until 256 step 5) for (n in normals) {
            val v = g.toDouble()
            s.add(V3(v, v, v), n, true, t)
            s.add(V3(v * 0.55, v * 0.6, v), n, false, t)
        }
        // TONE's highlights: every grey facing each TONE theme's sun
        for ((t, lit) in themes.withIndex()) if (lit.tone && lit.th["night"] != true) {
            val l = lit.th["_L"] as V3
            for (g in 0 until 256 step 3) s.add(V3(g.toDouble(), g.toDouble(), g.toDouble()), l, true, t)
        }
        return s
    }

    private fun blocks(themes: List<Lit>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(themes.size * ThemeBlock.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        for ((i, t) in themes.withIndex()) {
            val l = t.th["_L"] as V3
            ThemeBlock.write(b, t.th, doubleArrayOf(l.x, l.y, l.z), t.azimuth, t.elevation, false, t.tone, at = i * ThemeBlock.SIZE)
        }
        return b
    }

    private fun bits(d: Double) = java.lang.Double.doubleToRawLongBits(d)

    /** tohex's level of one channel, as the port computes it. */
    private fun level(v: Double) = Math.max(0L, Math.min(255L, app.atlas.render.py.PyMath.round(v))).toInt()

    /** How far v lies from the nearest half level. */
    private fun fromHalf(v: Double) = abs(v - (floor(v) + 0.5))

    /** Holds an exact run to the port: every value bit for bit, every byte. */
    private fun exact(name: String, s: Surfaces, bytes: IntArray, values: DoubleArray): String {
        var valuesDiffer = 0
        var bytesDiffer = 0
        var first = -1
        for (i in 0 until s.size) {
            val p = s.port[i]
            val same = (0 until 3).all { k -> bits(values[3 * i + k]) == bits(p[k]) }
            val v = bytes[i]
            val sameBytes = (v and 0xFF) == level(p.x) && ((v shr 8) and 0xFF) == level(p.y) && ((v shr 16) and 0xFF) == level(p.z)
            if (!same) valuesDiffer++
            if (!sameBytes) bytesDiffer++
            if ((!same || !sameBytes) && first < 0) first = i
        }
        val line = "$name: ${s.size} surfaces (${s.random} random), $valuesDiffer values and $bytesDiffer bytes differ from the port"
        assertEquals("$line; first at $first", 0, valuesDiffer + bytesDiffer)
        return line
    }

    /** Holds a binary32 run to the port within the bound: values within it, bytes equal away from a half level. */
    private fun bounded(name: String, s: Surfaces, bytes: IntArray, values: DoubleArray, bound: Double): String {
        var worst = 0.0
        var farDiffer = 0
        var nearDiffer = 0
        var worstLevel = 0
        for (i in 0 until s.size) {
            val p = s.port[i]
            for (k in 0 until 3) {
                worst = max(worst, abs(values[3 * i + k] - p[k]))
                val got = (bytes[i] shr (8 * k)) and 0xFF
                val diff = abs(got - level(p[k]))
                worstLevel = max(worstLevel, diff)
                val near = fromHalf(p[k]) <= bound && p[k] > 0.0 && p[k] < 255.0
                if (diff != 0) if (near) nearDiffer++ else farDiffer++
            }
        }
        val line = "$name: worst %.3g levels from the port (bound %.3g); bytes differ $farDiffer times far from a half level, $nearDiffer times near one".format(worst, bound)
        assertTrue(line, worst <= bound && farDiffer == 0 && worstLevel <= 1)
        return line
    }

    @Test
    fun theExactLightIsThePortsBitForBitAsCppAndOnTheGpu() {
        val r = Random(20261016L)
        val themes = themes(r)
        val s = surfaces(themes, r)
        Amsterdam.TONE = false
        val inputs = s.inputs.toDoubleArray()
        val blocks = blocks(themes)
        val values = DoubleArray(3 * s.size)
        val twin = exact("twin (light.slang as C++)", s, StyleHost.lightTwin(inputs, blocks, values), values)
        val gpuValues = DoubleArray(3 * s.size)
        val gpuBytes = StyleHost.lightGpu(inputs, blocks, gpuValues)
        val gpu = exact("GPU (light.slang on ${StyleHost.gpuDevice()})", s, gpuBytes, gpuValues)
        println("LightParityTest: ${themes.size} themes; $twin; $gpu")
    }

    @Test
    fun theBinary32LightIsWithinItsBoundOfThePortsAsCppAndOnTheGpu() {
        val r = Random(20261017L)
        val themes = themes(r)
        val s = surfaces(themes, r)
        Amsterdam.TONE = false
        val inputs = s.inputs.toDoubleArray()
        val blocks = blocks(themes)
        val bound = StyleHost.lightF32Bound()
        val values = DoubleArray(3 * s.size)
        val twin = bounded("light_f32 as C++", s, StyleHost.lightTwinF32(inputs, blocks, values), values, bound)
        val gpuValues = DoubleArray(3 * s.size)
        val gpuBytes = StyleHost.lightGpuF32(inputs, blocks, gpuValues)
        val gpu = bounded("light_f32 on ${StyleHost.gpuDevice()}", s, gpuBytes, gpuValues, bound)
        println("LightParityTest: ${themes.size} themes; $twin; $gpu")
    }
}
