package app.atlas.mapcorecheck.facade

import app.atlas.mapcore.CoreHandle
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** The host build's façade test entry points (`CP/jni/TestBridge.cpp`). */
object FacadeHost {
    /** Runs the core's ABI check and registration against [target]; "" when it registered, else the refusal. */
    @JvmStatic external fun registerInto(target: Class<*>): String

    @JvmStatic external fun coreAbi(): Int
}

/** A façade class built against another core: the core must refuse it. */
object OtherAbiFacade {
    @Suppress("unused")
    const val ABI = 4
}

/**
 * The façade on the JVM against the core's host build (plan-vector T9, K1's host acceptance): the layouts both sides
 * share are the same field by field (the camera state Kotlin writes, the frame records it reads), a façade of another
 * ABI is refused at load, the host build starts behind the crash guard and reports its facts, and the guard's state
 * shows in them: a crash this build made twice turns Vulkan off, a crash in VK bench's colour stage turns off 10 bits
 * only, and VK facts' reset, refused while a core is alive, lets both try again (D5.10). MapCore's calls and its close
 * exclude each other (CoreHandle, which MapCore calls every native through): a call under way holds close back, none
 * starts once close has begun, and calls on many threads racing a close never reach a freed core.
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
        // The same check JNI_OnLoad runs before RegisterNatives: a class of ABI 4 is refused, with the reason.
        val refused = FacadeHost.registerInto(OtherAbiFacade::class.java)
        assertTrue(refused, refused.contains("ABI 4") && refused.contains("ABI ${Native.ABI}"))
        // And the real façade registers again cleanly.
        assertEquals("", FacadeHost.registerInto(Native::class.java))
    }

    @Test
    fun theHostBuildStartsBehindTheGuardAndReportsItsFacts() {
        val dir = Files.createTempDirectory("atlas-facade").toFile()
        try {
            val guard = dir.resolve("vk").path
            val handle = Native.create(dir.path, guard, "jvm-test", "", 0, "", 0L)
            assertNotEquals("create refused: ${Native.lastError()}", 0L, handle)
            try {
                val facts = Native.facts(handle, dir.path, guard, "jvm-test", 0, "", 0L).lines()
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
                // Android's profiles, read for the facts behind the guard's profiles stage (never at the start).
                assertTrue(facts.joinToString("\n"), facts.any { it.startsWith("profiles: VP_ANDROID_") })
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
            // Without a core: a guarded start of its own, its profiles read too, closed behind the close stage.
            val alone = Native.facts(0L, dir.path, guard, "jvm-test", 0, "", 0L).lines()
            assertTrue(alone.any { it.startsWith("gpu: ") })
            assertTrue(alone.any { it.startsWith("profiles: VP_ANDROID_") })
            assertFalse(dir.resolve("vk/guard").exists())
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
            val facts = Native.facts(0L, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
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
            assertEquals(0L, Native.create(dir.path, guard.path, "jvm-test", "", 0, "", 0L))
            val why = Native.lastError()
            assertTrue(why, why.startsWith("vk guard pipelines $driver $version attempt 2 pipeline $pipeline build $build: "))
            assertEquals(0L, Native.create(dir.path, guard.path, "jvm-test", "", 0, "", 0L))
            val off = Native.facts(0L, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
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
            val handle = Native.create(dir.path, guard.path, "jvm-test", "", 0, "", 0L)
            assertNotEquals("create refused after the reset: ${Native.lastError()}", 0L, handle)
            try {
                val after = Native.facts(handle, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
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
            val facts = Native.facts(0L, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
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
            val handle = Native.create(dir.path, guard.path, "jvm-test", "", 0, "", 0L)
            assertNotEquals("the start after a crash in the colour stage refused Vulkan: ${Native.lastError()}", 0L, handle)
            try {
                val after = Native.facts(handle, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
                val text = after.joinToString("\n")
                assertTrue(text, "guard: $line" in after)
                assertTrue(text, "colour.stage: off (10 bits are off for this driver and build: $line)" in after)
                assertTrue(text, after.any { it.startsWith("colour: rgba8 (") && it.endsWith("; 10 bits: off, 10 bits are off for this driver and build: $line)") })
                assertTrue(text, "guard.off: $driver $version at colour, pipeline $pipeline build $build (this driver and build: 10 bits are off)" in after)
                // None written yet; a crash in the colour stage keeps the cache (below), which never holds a 10-bit pipeline.
                assertTrue(text, after.any { it.startsWith("pipeline.cache: miss: no file") })
                // The reset while this core is alive: refused, nothing forgotten (the core would write its cache back).
                val refused = Native.resetGuard(dir.path, guard.path)
                assertEquals("vk guard reset: refused, 1 core is alive (it would write its pipeline cache back): close it first", refused)
                assertTrue(guard.resolve("off").isFile)
            } finally {
                Native.close(handle) // which writes the pipeline cache back
            }
            assertTrue(guard.resolve("pipelines.bin").isFile)
            // A later start of this build and driver: still 8 bits, still on.
            val later = Native.facts(0L, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
            assertTrue(later.joinToString("\n"), "guard: clear" in later)
            assertTrue("colour.stage: off (10 bits are off for this driver and build: $line)" in later)
            // Another crash in the colour stage keeps the pipeline cache written back: the stage's pipelines go through a
            // cache of its own, so the one on disk holds no 10-bit pipeline.
            guard.resolve("guard").writeText(
                "stage colour\npipeline $pipeline\ndriver $driver\nversion $version\nbuild $build\nattempt 1\n",
            )
            val kept = Native.create(dir.path, guard.path, "jvm-test", "", 0, "", 0L)
            assertNotEquals("the start after a second crash in the colour stage refused Vulkan: ${Native.lastError()}", 0L, kept)
            try {
                val again = Native.facts(kept, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
                assertTrue(again.joinToString("\n"), "pipeline.cache: hit" in again)
                assertTrue("guard: $line" in again)
            } finally {
                Native.close(kept)
            }

            // With no core alive, the reset forgets the verdict (once: the second crash found it on file) and the cache
            // written back, and 10 bits are back.
            assertEquals("vk guard reset: forgot 10 bits turned off for 1 driver and the pipeline cache", Native.resetGuard(dir.path, guard.path))
            assertFalse(guard.resolve("pipelines.bin").exists())
            val back = Native.facts(0L, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
            assertTrue(back.joinToString("\n"), "guard.off: none" in back)
            assertTrue(back.any { it.startsWith("colour.stage: ") && !it.startsWith("colour.stage: off") })
        } finally {
            dir.deleteRecursively()
        }
    }
    @Test
    fun aMarkerOfAProcessThatWasStoppedNotCrashedCountsForNothing() {
        // Kills are not crashes (D5.10): a marker survives a swipe-away, a low-memory kill or a force-stop as it survives
        // a crash, so the façade passes the app's process exits as Android records them (MK/ProcessExits.kt's text) and
        // the guard looks the marker's process up among them: by its id, the first exit after its start.
        val dir = Files.createTempDirectory("atlas-exits").toFile()
        try {
            val guard = dir.resolve("vk")
            val facts = Native.facts(0L, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
            val build = facts.first { it.startsWith("guard.build: ") }.removePrefix("guard.build: ")
            val (driver, version) = facts.first { it.startsWith("guard.driver: ") }.removePrefix("guard.driver: ").split(' ')
            // A marker of the 120-frame stage, left by process 9001, which started at 1000 ms.
            fun leave(attempt: Int, pid: Long = 9001) = guard.resolve("guard").writeText(
                "stage frames\ndriver $driver\nversion $version\nbuild $build\nattempt $attempt\npid $pid\nstart 1000\n",
            )
            fun started(exits: String, processStart: Long = 0L): Long {
                val handle = Native.create(dir.path, guard.path, "jvm-test", "", 0, exits, processStart)
                assertNotEquals("refused: ${Native.lastError()}", 0L, handle)
                return handle
            }
            fun factsOf(handle: Long) = Native.facts(handle, dir.path, guard.path, "jvm-test", 0, "", 0L).lines()
            // Swiped away three times running (an older process of the same id crashed before this one started): never
            // counted, nothing turned off, VK facts saying so.
            repeat(3) {
                leave(1)
                val handle = started("9001 500 1 crash-native\n9001 5000 0 user-requested")
                try {
                    val after = factsOf(handle)
                    val text = after.joinToString("\n")
                    assertTrue(text, "guard: clear" in after)
                    assertTrue(text, "vk guard: frames marker after user-requested, not counted" in after)
                    assertTrue(text, "guard.off: none" in after)
                } finally {
                    Native.close(handle)
                }
                assertFalse(guard.resolve("guard").exists())
            }
            // A low-memory kill of a retry: not counted, and the start goes on as the retry, without the cache.
            leave(2)
            started("9001 5000 0 low-memory").let { handle ->
                try {
                    val after = factsOf(handle)
                    assertTrue(after.joinToString("\n"), "vk guard: frames marker after low-memory, not counted" in after)
                    assertTrue("guard: clear" in after)
                    assertTrue(after.any { it.startsWith("pipeline.cache: miss") })
                } finally {
                    Native.close(handle)
                }
            }
            // A marker of this very process (its id, and its start as the façade gives it): an earlier core in it left
            // it, and it runs. Not counted whatever Android records, API 29's nothing too.
            for (exits in listOf("none", "")) {
                leave(1, ProcessHandle.current().pid())
                started(exits, processStart = 1000L).let { handle ->
                    try {
                        assertTrue("vk guard: frames marker of this running process, not counted" in factsOf(handle))
                        assertTrue("guard: clear" in factsOf(handle))
                    } finally {
                        Native.close(handle)
                    }
                }
            }
            // This process's id with another start is a process whose id this one reused: it counts as any other with
            // no record (the retry), never as this one's.
            leave(1, ProcessHandle.current().pid())
            started("none", processStart = 3_600_000L).let { handle ->
                try {
                    val after = factsOf(handle)
                    assertTrue(after.joinToString("\n"), "guard: vk guard frames $driver $version attempt 1 build $build" in after)
                    assertTrue(after.any { it.startsWith("guard.exit: no record") })
                } finally {
                    Native.close(handle)
                }
            }
            // The system's routine trim of a cached process (REASON_OTHER) and a signal while cached: not counted, nothing
            // turned off (MK/ProcessExits.kt passes both as not counted).
            for (reason in listOf("other", "signaled (cached)")) {
                leave(1)
                started("9001 5000 0 $reason").let { handle ->
                    try {
                        val after = factsOf(handle)
                        assertTrue(after.joinToString("\n"), "vk guard: frames marker after $reason, not counted" in after)
                        assertTrue("guard: clear" in after)
                    } finally {
                        Native.close(handle)
                    }
                }
            }
            // A native crash counts: the retry, and VK facts says how the process ended.
            leave(1)
            started("9001 5000 1 crash-native").let { handle ->
                try {
                    val after = factsOf(handle)
                    val text = after.joinToString("\n")
                    assertTrue(text, "guard: vk guard frames $driver $version attempt 1 build $build" in after)
                    assertTrue(text, after.any { it.startsWith("guard.exit: crash-native") })
                } finally {
                    Native.close(handle)
                }
            }
            // No record of the process at all counts too: the retry's crash turns Vulkan off.
            leave(2)
            assertEquals(0L, Native.create(dir.path, guard.path, "jvm-test", "", 0, "none", 0L))
            assertTrue(Native.lastError(), Native.lastError().startsWith("vk guard frames $driver $version attempt 2 build $build: "))
            // (the earlier cores wrote their pipeline cache back at their close).
            assertEquals("vk guard reset: forgot 1 driver turned off and the pipeline cache", Native.resetGuard(dir.path, guard.path))
            // Exits not known (API 29): every marker counts, as before.
            leave(1)
            started("").let { handle ->
                try {
                    assertTrue("guard: vk guard frames $driver $version attempt 1 build $build" in factsOf(handle))
                } finally {
                    Native.close(handle)
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A host core in [dir] (its cache and guard there), behind a [CoreHandle] as MapCore holds it. */
    private fun hostCore(dir: File): CoreHandle {
        val handle = Native.create(dir.path, dir.resolve("vk").path, "jvm-test", "", 0, "", 0L)
        assertNotEquals("create refused: ${Native.lastError()}", 0L, handle)
        return CoreHandle(handle)
    }

    @Test
    fun aCallUnderWayHoldsCloseBackAndNoCallStartsOnceCloseHasBegun() {
        val dir = Files.createTempDirectory("atlas-close").toFile()
        try {
            val core = hostCore(dir)
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val freed = AtomicBoolean(false)
            val freedDuringCall = AtomicBoolean(true)
            val caller = thread(name = "atlas-test-call") {
                core.call(Unit) {
                    inside.countDown()
                    release.await()
                    freedDuringCall.set(freed.get())
                    assertEquals("vk no frames", Native.stats(it)) // the core is whole: nothing freed it yet
                }
            }
            assertTrue(inside.await(30, TimeUnit.SECONDS))
            val closer = thread(name = "atlas-test-close") {
                assertTrue(core.close {
                    freed.set(true)
                    Native.close(it)
                })
            }
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!core.closed && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue(core.closed)
            // Close has begun: a new call does nothing, and the one under way holds close back.
            assertEquals("closed", core.call("closed") { "ran" })
            Thread.sleep(200)
            assertFalse("close freed the core while a call was under way", freed.get())
            release.countDown()
            caller.join()
            closer.join()
            assertFalse(freedDuringCall.get())
            assertTrue(freed.get())
            // Closed once: a second close frees nothing.
            assertFalse(core.close { throw AssertionError("freed twice") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun callsOnManyThreadsRacingCloseNeverReachAFreedCore() {
        // Each round a new host core, closed while six threads call it as fast as they can (its facts, stats, records
        // and the pipeline cache's write-back, as VK facts, the panel, VK bench and onTrimMemory call them). A call
        // that slipped past close into a freed core would read freed memory (MapCore's check before each call, without
        // this, did: the JVM crashed); here close frees it only once no call is under way, and every call after it has
        // begun returns at once.
        val dir = Files.createTempDirectory("atlas-close-race").toFile()
        try {
            val guard = dir.resolve("vk").path
            var total = 0L
            repeat(12) { round ->
                val core = hostCore(dir)
                val inCall = AtomicInteger(0)
                val calls = AtomicLong(0)
                val failures = ConcurrentLinkedQueue<Throwable>()
                val go = CountDownLatch(1)
                val threads = (0 until 6).map { kind ->
                    thread(name = "atlas-test-race-$kind") {
                        val records = FrameRecords.allocate(16)
                        try {
                            go.await()
                            while (!core.closed) {
                                core.call(Unit) { handle ->
                                    inCall.incrementAndGet()
                                    try {
                                        when (kind % 4) {
                                            0 -> check(Native.facts(handle, dir.path, guard, "jvm-test", 0, "", 0L).contains("gpu: "))
                                            1 -> check(Native.stats(handle) == "vk no frames")
                                            2 -> check(Native.records(handle, records) == 0)
                                            else -> Native.trimMemory(handle, 0)
                                        }
                                        calls.incrementAndGet()
                                    } finally {
                                        inCall.decrementAndGet()
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                }
                go.countDown()
                Thread.sleep(15L + 10L * (round % 4))
                val underWayAtFree = AtomicInteger(-1)
                assertTrue(core.close {
                    underWayAtFree.set(inCall.get())
                    Native.close(it)
                })
                threads.forEach { it.join() }
                assertEquals("calls under way as the core was freed (round $round)", 0, underWayAtFree.get())
                assertTrue("round $round: ${failures.firstOrNull()}", failures.isEmpty())
                assertEquals(-1, core.call(-1) { throw AssertionError("a call ran after close") })
                assertTrue("round $round made no calls before its close", calls.get() > 0)
                total += calls.get()
            }
            println("FacadeTest: $total calls raced 12 closes")
        } finally {
            dir.deleteRecursively()
        }
    }
}
