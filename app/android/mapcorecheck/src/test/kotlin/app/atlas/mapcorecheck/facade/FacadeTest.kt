package app.atlas.mapcorecheck.facade

import app.atlas.mapcore.FrameRecords
import app.atlas.mapcore.FrameSource
import app.atlas.mapcore.Native
import app.atlas.mapcore.state.CameraWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    const val ABI = 3
}

/**
 * The façade on the JVM against the core's host build (plan-vector T9, K1's host acceptance): the layouts both sides
 * share are the same field by field (the camera state Kotlin writes, the frame records it reads), a façade of another
 * ABI is refused at load, the host build starts behind the crash guard and reports its facts, and the guard's state
 * shows in them: a crash this build made twice turns Vulkan off, a crash in VK bench's colour stage turns off 10 bits
 * only, and VK facts' reset, refused while a core is alive, lets both try again (D5.10).
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
        // The same check JNI_OnLoad runs before RegisterNatives: a class of ABI 3 is refused, with the reason.
        val refused = FacadeHost.registerInto(OtherAbiFacade::class.java)
        assertTrue(refused, refused.contains("ABI 3") && refused.contains("ABI ${Native.ABI}"))
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
                // The frame's colour (8 bits a channel in this alpha; offscreen here, hosts having no surfaces), what VK
                // bench's colour stage would draw (10 bits where the device draws the frame in them), and K5's
                // shaderFloat64.
                assertTrue(facts.joinToString("\n"), facts.any { Regex("colour: (rgba8|bgra8) \\(.*; 10 bits: .*\\)").matches(it) })
                assertTrue(facts.any { Regex("colour\\.stage: (rgb10a2|bgr10a2|none) \\(.*\\)").matches(it) })
                assertTrue(facts.any { it.startsWith("colour.formats: rgb10a2 ") })
                assertTrue(facts.any { Regex("feature\\.shaderFloat64: (yes|no) \\(K5's light: .*\\)").matches(it) })
                assertTrue(facts.any { it == "feature.shaderInt64: yes" || it == "feature.shaderInt64: no" })
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

    @Test
    fun theGuardsStateShowsInTheFactsAndAResetLetsVulkanTryAgain() {
        val dir = Files.createTempDirectory("atlas-guard").toFile()
        try {
            val guard = dir.resolve("vk")
            // A clean start: the guard found nothing; the facts name this build and the driver the guard keys by.
            val facts = Native.facts(0L, dir.path, guard.path, "jvm-test", 0).lines()
            assertTrue(facts.joinToString("\n"), "guard: clear" in facts)
            assertTrue("guard.off: none" in facts)
            val build = facts.first { it.startsWith("guard.build: ") }.removePrefix("guard.build: ")
            assertTrue(build, Regex("[0-9a-f]{16}").matches(build))
            // The linker's identity of the library, which the build ID hashes (on macOS its LC_UUID; "none" without).
            assertTrue(facts.joinToString("\n"), facts.any { Regex("build\\.linker: ([0-9a-f]+|none)").matches(it) })
            val (driver, version) = facts.first { it.startsWith("guard.driver: ") }.removePrefix("guard.driver: ").split(' ')
            assertNotEquals("unknown", driver)

            // What a start of this build leaves when its retry crashed compiling the composite: the next start turns
            // Vulkan off and says which pipeline crashed, and every later start of this build and driver stays off.
            val pipeline = "composite (module standin_composite, configuration C renderpass composite direct)"
            guard.resolve("guard").writeText(
                "stage pipelines\npipeline $pipeline\ndriver $driver\nversion $version\nbuild $build\nattempt 2\n",
            )
            assertEquals(0L, Native.create(dir.path, guard.path, "jvm-test", "", 0))
            val why = Native.lastError()
            assertTrue(why, why.startsWith("vk guard pipelines $driver $version attempt 2 pipeline $pipeline build $build: "))
            assertEquals(0L, Native.create(dir.path, guard.path, "jvm-test", "", 0))
            val off = Native.facts(0L, dir.path, guard.path, "jvm-test", 0).lines()
            assertTrue(off.joinToString("\n"), off.any { it.startsWith("vulkan: off (") })
            assertTrue(off.any { it.startsWith("guard: vk guard pipelines ") })
            assertTrue(
                off.joinToString("\n"),
                "guard.off: $driver $version at pipelines, pipeline $pipeline build $build (this driver and build: Vulkan is off)" in off,
            )
            assertTrue(off.any { it.startsWith("gpu: ") }) // the facts an earlier start kept

            // VK facts' "Reset guard": the crash and the pipeline cache are forgotten, and the next start opens Vulkan.
            guard.resolve("pipelines.bin").writeText("a cache")
            assertEquals("vk guard reset: forgot 1 driver turned off and the pipeline cache", Native.resetGuard(dir.path, guard.path))
            assertFalse(guard.resolve("pipelines.bin").exists())
            val handle = Native.create(dir.path, guard.path, "jvm-test", "", 0)
            assertNotEquals("create refused after the reset: ${Native.lastError()}", 0L, handle)
            try {
                val after = Native.facts(handle, dir.path, guard.path, "jvm-test", 0).lines()
                assertTrue(after.joinToString("\n"), "guard: clear" in after)
                assertTrue("guard.off: none" in after)
            } finally {
                Native.close(handle) // which writes the pipeline cache back
            }
            assertEquals("vk guard reset: forgot the pipeline cache", Native.resetGuard(dir.path, guard.path))
            assertEquals("vk guard reset: nothing to forget", Native.resetGuard(dir.path, guard.path))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aCrashInTheColourStageTurnsOffTenBitsOnlyAndTheResetWaitsForNoCore() {
        val dir = Files.createTempDirectory("atlas-colour").toFile()
        try {
            val guard = dir.resolve("vk")
            val facts = Native.facts(0L, dir.path, guard.path, "jvm-test", 0).lines()
            val build = facts.first { it.startsWith("guard.build: ") }.removePrefix("guard.build: ")
            val (driver, version) = facts.first { it.startsWith("guard.driver: ") }.removePrefix("guard.driver: ").split(' ')

            // What VK bench's colour stage leaves when the driver crashes compiling a pipeline of the 10-bit frame: the
            // next start opens Vulkan, at 8 bits, with 10 bits off for this driver and build, and says so.
            val pipeline = "composite (module standin_composite, configuration C renderpass composite direct rgb10a2)"
            guard.mkdirs()
            guard.resolve("guard").writeText(
                "stage colour\npipeline $pipeline\ndriver $driver\nversion $version\nbuild $build\nattempt 1\n",
            )
            val line = "vk guard colour $driver $version attempt 1 pipeline $pipeline build $build"
            val handle = Native.create(dir.path, guard.path, "jvm-test", "", 0)
            assertNotEquals("the start after a crash in the colour stage refused Vulkan: ${Native.lastError()}", 0L, handle)
            try {
                val after = Native.facts(handle, dir.path, guard.path, "jvm-test", 0).lines()
                val text = after.joinToString("\n")
                assertTrue(text, "guard: $line" in after)
                assertTrue(text, "colour.stage: off (10 bits are off for this driver and build: $line)" in after)
                assertTrue(text, after.any { it.startsWith("colour: rgba8 (") && it.endsWith("; 10 bits: off, 10 bits are off for this driver and build: $line)") })
                assertTrue(text, "guard.off: $driver $version at colour, pipeline $pipeline build $build (this driver and build: 10 bits are off)" in after)
                assertTrue(text, after.any { it.startsWith("pipeline.cache: miss") }) // started without it
                // The reset while this core is alive: refused, nothing forgotten (the core would write its cache back).
                val refused = Native.resetGuard(dir.path, guard.path)
                assertEquals("vk guard reset: refused, 1 core is alive (it would write its pipeline cache back): close it first", refused)
                assertTrue(guard.resolve("off").isFile)
            } finally {
                Native.close(handle) // which writes the pipeline cache back
            }
            assertTrue(guard.resolve("pipelines.bin").isFile)
            // A later start of this build and driver: still 8 bits, still on.
            val later = Native.facts(0L, dir.path, guard.path, "jvm-test", 0).lines()
            assertTrue(later.joinToString("\n"), "guard: clear" in later)
            assertTrue("colour.stage: off (10 bits are off for this driver and build: $line)" in later)

            // With no core alive, the reset forgets the verdict and the cache written back, and 10 bits are back.
            assertEquals("vk guard reset: forgot 10 bits turned off for 1 driver and the pipeline cache", Native.resetGuard(dir.path, guard.path))
            assertFalse(guard.resolve("pipelines.bin").exists())
            val back = Native.facts(0L, dir.path, guard.path, "jvm-test", 0).lines()
            assertTrue(back.joinToString("\n"), "guard.off: none" in back)
            assertTrue(back.any { it.startsWith("colour.stage: ") && !it.startsWith("colour.stage: off") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
