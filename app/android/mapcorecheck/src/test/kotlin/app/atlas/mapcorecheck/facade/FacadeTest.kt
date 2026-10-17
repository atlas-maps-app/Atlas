package app.atlas.mapcorecheck.facade

import app.atlas.mapcore.FrameRecords
import app.atlas.mapcore.FrameSource
import app.atlas.mapcore.Native
import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Files

/** The host build's façade test entry points (`CP/jni/TestBridge.cpp`). */
object FacadeHost {
    /** Runs the core's ABI check and registration against [target]; "" when it registered, else the refusal. */
    @JvmStatic external fun registerInto(target: Class<*>): String

    @JvmStatic external fun coreAbi(): Int
}

/** A façade class built against another core: the core must refuse it. */
object OtherAbiFacade {
    @Suppress("unused")
    const val ABI = 2
}

/**
 * The façade on the JVM against the core's host build (plan-vector T9, K1's host acceptance): the layouts both sides
 * share are the same field by field (the camera state Kotlin writes, the frame records it reads), a façade of another
 * ABI is refused at load, and the host build starts behind the crash guard and reports its facts.
 */
class FacadeTest {
    companion object {
        private var loaded = false

        @BeforeClass
        @JvmStatic
        fun load() {
            val path = System.getProperty("atlas.mapcore.host")
            assumeTrue("no host build (-Datlas.mapcore.host)", path != null)
            assertNull(Native.load(path))
            loaded = true
        }
    }

    private fun table(name: String): Pair<Pair<Int, Int>, Map<String, Pair<Int, Int>>> {
        val lines = Native.layouts().lines().filter { it.startsWith(name) }
        val head = lines.first { it.startsWith("$name version ") }.split(' ')
        val fields = lines.filter { it.startsWith("$name.") }.associate { l ->
            val (field, offset, size) = l.removePrefix("$name.").split(' ')
            field to (offset.toInt() to size.toInt())
        }
        return (head[2].toInt() to head[4].toInt()) to fields
    }

    @Test
    fun theCameraStateIsLaidOutTheSameOnBothSides() {
        val (head, fields) = table("camera_state")
        assertEquals(CameraWriter.VERSION, head.first)
        assertEquals(CameraWriter.SIZE, head.second)
        assertEquals(CameraWriter.FIELDS.map { it.first }, fields.keys.toList())
        for ((name, offset, size) in CameraWriter.FIELDS) assertEquals("camera_state.$name", offset to size, fields[name])
    }

    @Test
    fun theFrameRecordsAreLaidOutTheSameOnBothSides() {
        val (head, fields) = table("frame_record")
        assertEquals(FrameRecords.VERSION, head.first)
        assertEquals(FrameRecords.SIZE, head.second)
        assertEquals(FrameRecords.FIELDS.map { it.first }, fields.keys.toList())
        for ((name, offset, size) in FrameRecords.FIELDS) assertEquals("frame_record.$name", offset to size, fields[name])
        // The fields tile the record.
        var at = 0
        for ((_, offset, size) in FrameRecords.FIELDS) {
            assertEquals(at, offset)
            at += size
        }
        assertEquals(FrameRecords.SIZE, at)
    }

    @Test
    fun theFacadesAbiIsTheCoresAndAnotherIsRefusedAtLoad() {
        assertEquals(Native.ABI, Native.abi())
        assertEquals(Native.ABI, FacadeHost.coreAbi())
        // The same check JNI_OnLoad runs before RegisterNatives: a class of ABI 2 is refused, with the reason.
        val refused = FacadeHost.registerInto(OtherAbiFacade::class.java)
        assertTrue(refused, refused.contains("ABI 2") && refused.contains("ABI ${Native.ABI}"))
        // And the real façade registers again cleanly.
        assertEquals("", FacadeHost.registerInto(Native::class.java))
    }

    @Test
    fun theHostBuildStartsBehindTheGuardAndReportsItsFacts() {
        val dir = Files.createTempDirectory("atlas-facade").toFile()
        try {
            val guard = dir.resolve("vk").path
            val handle = Native.create(dir.path, guard, "jvm-test", "", 0)
            assertNotEquals("create refused: ${Native.lastError()}", 0L, handle)
            try {
                val facts = Native.facts(handle, dir.path, guard, "jvm-test", 0).lines()
                assertTrue(facts.joinToString("\n"), facts.any { it.startsWith("gpu: ") })
                assertTrue(facts.any { it.startsWith("config: ") })
                assertTrue(facts.any { it == "guard: clear" })
                assertTrue(Native.buildInfo().startsWith("atlasmap "))
                // K3b's numerics line, as VK facts logs it for smoke.sh.
                val pymath = Native.numericsLine()
                assertTrue(pymath, Regex("pymath [0-9a-f]{16} (ok|FAIL expected [0-9a-f]{16})").matches(pymath))
                // Hosts have no surfaces: attach says so instead of drawing.
                val source = object : FrameSource {
                    override fun camera(vsyncNanos: Long, out: ByteBuffer) = false
                    override fun presented(frame: Long, nanos: Long, complete: Boolean) = Unit
                }
                assertEquals(false, Native.attach(handle, Any(), 64, 64, source, CameraWriter.allocate()))
                assertEquals("hosts have no surfaces", Native.lastError())
                assertEquals("vk no frames", Native.stats(handle))
                assertEquals("vkbench pipelines: hosts have no surfaces", Native.pipelines(handle))
            } finally {
                Native.close(handle)
            }
            // The core kept its facts for the honest state, and its pipeline cache folder exists.
            assertTrue(dir.resolve("vk/facts").isFile)
            // Without a core: a guarded start of its own.
            val alone = Native.facts(0L, dir.path, guard, "jvm-test", 0).lines()
            assertTrue(alone.any { it.startsWith("gpu: ") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
