package app.atlas.mapcorecheck.fx

import app.atlas.mapview.svg.Turbulence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The noise fields' entry points in the core's host build (`CP/jni/TestFields.cpp`, plan-vector K17a), loaded from
 * `-Datlas.mapcore.host`.
 */
internal object FieldHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's field entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The core's noise over a block, by its lanes or its scalar reference: packed `a r g b`, rows from the top. */
    @JvmStatic external fun noise(
        seed: Float, baseX: Float, baseY: Float, octaves: Int, fractal: Boolean, scaleX: Float, scaleY: Float,
        left: Int, top: Int, width: Int, height: Int, need: Int, lanes: Boolean,
    ): IntArray?

    /** Field [field]'s kept bytes over a frame, as fields.inc hashes them (its bytes a pixel, rows from the top). */
    @JvmStatic external fun kept(width: Int, height: Int, scale: Float, threads: Int, lanes: Boolean, field: Int): ByteArray?

    /** VK facts' `fields …` line. */
    @JvmStatic external fun line(width: Int, height: Int, scale: Float, threads: Int): String
}

/**
 * The core's screen-wide noise fields (CP/fx, plan-vector K17a) against the port's [Turbulence.colorWebKit], byte for
 * byte, by both of the core's paths (its NEON lanes and its scalar reference), at D16's sizes and a phone's
 * (`-Datlas.likeness.phone=<model>:<w>x<h>[@<density>]`, as the likeness harness takes it; the owner's phone's family
 * when none is given). And the bytes the core keeps of each field against fields.inc, whose hashes mapview's
 * ByteTablesExportTest took with the port's own SvgFilterRaster: here the noise column of that file is recomputed
 * with the port's Turbulence, so the file the C++ tests read (on CI's arm64 runner too) is the port's.
 */
class FieldParityTest {
    /** A screen-wide field as amsterdam.py writes it (the core's fx/Fields.hpp, in its order). */
    private class Field(val name: String, val seed: Float, val baseX: Float, val baseY: Float, val octaves: Int, val need: Int)

    private class Size(val name: String, val w: Int, val h: Int, val density: Double) {
        val scale: Float get() = density.toFloat()
    }

    private val fields = listOf(
        Field("mat", 13f, 0.16f, 0.75f, 2, 15),
        Field("grain", 8f, 0.9f, 0.9f, 1, 15),
        Field("ripple", 11f, 0.012f, 0.26f, 2, 3),
        Field("glints", 5f, 0.02f, 0.3f, 2, 15),
        Field("cloud_shadows", 23f, 0.0026f, 0.0058f, 3, 15),
    )

    private val sizes: List<Size> = run {
        val named = listOf(Size("S1", 1080, 2400, 1080 / 412.0), Size("S2", 1080, 2340, 1080 / 412.0),
            Size("S3", 1440, 3120, 1440 / 412.0), Size("S4", 1344, 2992, 1344 / 412.0))
        val phone = System.getProperty("atlas.likeness.phone")?.takeIf { it.isNotBlank() }?.let { spec ->
            val colon = spec.lastIndexOf(':')
            val rest = spec.substring(colon + 1)
            val at = rest.indexOf('@')
            val dims = (if (at >= 0) rest.substring(0, at) else rest).split('x', 'X', '×').map { it.trim().toInt() }
            Size(spec.substring(0, colon), dims[0], dims[1], if (at >= 0) rest.substring(at + 1).toDouble() else dims[0] / 412.0)
        }
        named + (phone?.let { listOf(it) } ?: listOf(Size("pixel10", 1080, 2424, 1080 / 412.0), Size("pixel10proxl-high", 1008, 2244, 1008 / 412.0)))
    }

    /** The port's noise of [f] over a frame of [size], on [threads] threads. */
    private fun port(f: Field, size: Size, pool: java.util.concurrent.ExecutorService): IntArray {
        val out = IntArray(size.w * size.h)
        val t = Turbulence.of(f.seed)
        val inv = 1f / size.scale
        val bands = 32
        pool.invokeAll((0 until bands).map { k ->
            Callable {
                val y0 = size.h * k / bands
                val y1 = size.h * (k + 1) / bands
                t.fillWebKit(0, y0, size.w, y1 - y0, inv, inv, f.baseX, f.baseY, f.octaves, true, f.need, out, y0 * size.w, size.w)
            }
        }).forEach { it.get() }
        return out
    }

    @Test
    fun `every screen-wide field is the port's noise byte for byte, by both of the core's paths`() {
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
        try {
            var pixels = 0L
            for (size in sizes) for (f in fields) {
                val port = port(f, size, pool)
                for (lanes in listOf(true, false)) {
                    // the scalar reference over S1 and the phone; the lanes everywhere
                    if (!lanes && size.name != "S1" && size.name != sizes.last().name) continue
                    val core = FieldHost.noise(f.seed, f.baseX, f.baseY, f.octaves, true, size.scale, size.scale, 0, 0, size.w, size.h, f.need, lanes)
                        ?: error("the core refused ${size.name}")
                    val first = port.indices.firstOrNull { port[it] != core[it] }
                    assertTrue(
                        "${f.name} at ${size.name} (${size.w} × ${size.h} at ${size.scale}) by the ${if (lanes) "lanes" else "scalar reference"}: " +
                            "${port.indices.count { port[it] != core[it] }} pixels differ, the first at " +
                            "(${first?.rem(size.w)}, ${first?.div(size.w)}): port %08x, core %08x".format(first?.let { port[it] } ?: 0, first?.let { core[it] } ?: 0),
                        first == null,
                    )
                    pixels += port.size
                }
            }
            println("fields: $pixels pixels of ${fields.size} fields at ${sizes.joinToString { "${it.name} ${it.w}x${it.h}" }}, every one the port's")
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `fields inc's noise hashes are the port's, and the core keeps what its kept hashes say`() {
        val inc = File(System.getProperty("atlas.repo"), "app/android/mapcore/cpp/fx/tables/fields.inc")
        val row = Regex("""ATLAS_FIELD\("([^"]+)", (\d+), (\d+), 0x([0-9a-f]{8}), "([a-z_]+)", 0x([0-9a-f]{16})ULL, 0x([0-9a-f]{16})ULL\)""")
        val rows = inc.readLines().mapNotNull { row.matchEntire(it.trim()) }
        assertTrue("fields.inc has its rows", rows.size >= 20)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
        try {
            for (m in rows) {
                val (name, w, h, scaleBits, field, noise, kept) = m.destructured
                val f = fields.single { it.name == field }
                val scale = java.lang.Float.intBitsToFloat(scaleBits.toLong(16).toInt())
                val size = Size(name, w.toInt(), h.toInt(), scale.toDouble())
                assertEquals("$field at $name: the scale is the size's BoardScale density", (size.w / 412.0).toFloat(), scale)
                val port = port(f, size, pool)
                val bytes = ByteBuffer.allocate(4 * port.size).order(ByteOrder.LITTLE_ENDIAN).also { b -> port.forEach { b.putInt(it) } }.array()
                assertEquals("$field at $name: fields.inc's noise hash", noise, prefix(bytes))
                val core = FieldHost.kept(size.w, size.h, scale, 4, true, fields.indexOf(f)) ?: error("the core refused $name")
                assertEquals("$field at $name: the core's kept bytes", kept, prefix(core))
            }
            println("fields.inc: ${rows.size} fields and sizes, the noise the port's, the kept bytes the core's")
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun `VK facts' line checks a reference size and names any other unchecked`() {
        val s1 = FieldHost.line(1080, 2400, (1080 / 412.0).toFloat(), 4)
        println(s1)
        assertTrue(s1, s1.startsWith("fields 1080x2400 cpu ") && s1.endsWith(" ok"))
        val odd = FieldHost.line(500, 700, 1.5f, 2)
        assertTrue(odd, odd.endsWith(" unchecked"))
    }

    private fun prefix(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
