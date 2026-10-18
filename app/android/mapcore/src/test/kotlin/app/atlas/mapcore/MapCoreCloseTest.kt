package app.atlas.mapcore

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * One core at a time in a process (plan-vector D5.10, [MapCore]): the one maker of cores ([MapCore.create], which
 * [MapCore.shared] calls) records each core it makes as the process's and refuses while that core is alive, so no
 * second core runs beside it (the crash guard's marker is one file a process's cores would share); a core's close
 * holds the lock its start, the facts without a core and the guard's reset hold, and forgets the process's core only
 * once its free has returned, so no other core starts while the close's stage runs. The tests' own make and free take
 * the native create's and close's place: no native core is made.
 */
class MapCoreCloseTest {
    private val processField = MapCore::class.java.getDeclaredField("process").apply { isAccessible = true }
    private val loadState = Native::class.java.getDeclaredField("state").apply { isAccessible = true }
    private var loadedBefore: Any? = null
    private val config = CoreConfig(cacheDir = "", guardDir = "", system = "jvm-test")

    @Before
    fun libraryLoaded() {
        // The library counted as loaded, so that create reaches its own checks and the test's make.
        loadedBefore = loadState.get(null)
        loadState.set(null, "")
    }

    @After
    fun forget() {
        processField.set(null, null)
        MapCore::class.java.getDeclaredField("refusal").apply { isAccessible = true }.set(null, "")
        loadState.set(null, loadedBefore)
    }

    /** The process's core, as [MapCore.shared] makes it, over a handle no native call sees. */
    private fun core(handle: Long = 42L): MapCore = MapCore.create(config) { handle } ?: error(MapCore.lastError())

    @Test
    fun theOneMakerRecordsEachCoreAsTheProcesssAndRefusesASecond() {
        val first = core()
        assertSame("create did not record its core as the process's", first, MapCore.sharedOrNull())
        // A second core beside it: refused before anything is made, saying why.
        assertNull(MapCore.create(config) { throw AssertionError("a second core was made beside the process's") })
        assertTrue(MapCore.lastError(), MapCore.lastError().contains("one core runs in a process at a time"))
        // The process's core is what shared gives; it makes none.
        assertSame(first, MapCore.shared(config))
        // Once it is closed, the next core is the process's.
        first.close { check(it == 42L) }
        assertNull(MapCore.sharedOrNull())
        val next = core(43L)
        assertSame(next, MapCore.sharedOrNull())
        assertSame(next, MapCore.shared(config))
    }

    @Test
    fun closeHoldsTheCompanionsLockAndForgetsTheProcesssCoreOnlyOnceItIsFreed() {
        val core = core()
        val seenWhileFreeing = AtomicReference<MapCore?>()
        val freed = CountDownLatch(1)
        val closer: Thread
        synchronized(MapCore.Companion) {
            // The lock held here, as a start, VK facts without a core or a reset holds it: the close waits.
            closer = thread(name = "atlas-test-close") {
                core.close { handle ->
                    check(handle == 42L)
                    seenWhileFreeing.set(MapCore.sharedOrNull())
                    freed.countDown()
                }
            }
            assertFalse("the close freed the core under another's lock", freed.await(300, TimeUnit.MILLISECONDS))
            assertSame(core, MapCore.sharedOrNull())
        }
        assertTrue(freed.await(30, TimeUnit.SECONDS))
        closer.join()
        assertSame("the process's core was forgotten before it was freed", core, seenWhileFreeing.get())
        assertNull(MapCore.sharedOrNull())
        // Closed once: a second close frees nothing, and leaves the next process core alone.
        val other = core(43L)
        core.close { throw AssertionError("freed twice") }
        assertSame(other, MapCore.sharedOrNull())
    }

    @Test
    fun aCloseWhoseFreeFailsStillForgetsTheProcesssCore() {
        val core = core()
        val failed = runCatching { core.close { throw IllegalStateException("the native free failed") } }
        assertTrue(failed.isFailure)
        assertNull(MapCore.sharedOrNull())
    }
}
